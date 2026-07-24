import Foundation

// Streams the server's SSE endpoint and delivers decoded StreamEvents on the main
// thread. Reconnects with backoff; the server re-sends a full `snapshot` on every
// (re)connect, so no client-side resume logic is needed. There is no server heartbeat,
// so we use a long request timeout and rely on reconnect.
final class SSEClient {
    private let urlProvider: () -> URL?
    private let onEvent: (StreamEvent) -> Void
    private let onConnected: (Bool) -> Void
    private var task: Task<Void, Never>?

    init(url: @escaping () -> URL?,
         onEvent: @escaping (StreamEvent) -> Void,
         onConnected: @escaping (Bool) -> Void) {
        self.urlProvider = url
        self.onEvent = onEvent
        self.onConnected = onConnected
    }

    func start() {
        stop()
        task = Task { [weak self] in
            guard let self else { return }
            await self.runLoop()
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    private func runLoop() async {
        var backoff: UInt64 = 2
        while !Task.isCancelled {
            guard let base = urlProvider() else {
                try? await Task.sleep(nanoseconds: 2 * 1_000_000_000)
                continue
            }
            do {
                try await connect(base)
                backoff = 2               // clean EOF → reconnect promptly
            } catch is CancellationError {
                break
            } catch {
                // network/decoding error → fall through to backoff
            }
            await MainActor.run { self.onConnected(false) }
            if Task.isCancelled { break }
            try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
            backoff = min(backoff * 2, 30)
        }
    }

    private func connect(_ base: URL) async throws {
        // Fresh ephemeral session per attempt: no shared connection pool, so a socket
        // that died during sleep is never reused (which otherwise hangs the reconnect
        // until the request timeout). Closed via defer when the stream ends/throws.
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 60          // backstop: a hung socket fails in ~60s, not an hour
        cfg.timeoutIntervalForResource = 86_400
        cfg.waitsForConnectivity = true
        let session = URLSession(configuration: cfg)
        defer { session.invalidateAndCancel() }

        var req = URLRequest(url: base.appendingPathComponent("api/stream"))
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")

        Diag.log("connecting: \(req.url?.absoluteString ?? "?")")
        let (bytes, response) = try await session.bytes(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            Diag.log("bad response: \(response)")
            throw URLError(.badServerResponse)
        }
        Diag.log("connected HTTP \(http.statusCode)")
        await MainActor.run { self.onConnected(true) }

        var dataBuffer = ""
        for try await rawLine in bytes.lines {
            if Task.isCancelled { throw CancellationError() }
            // AsyncLineSequence usually strips terminators, but strip a stray CR defensively
            let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
            Diag.log("line[\(line.count)]: \(line.prefix(80))")
            if line.hasPrefix("data:") {
                var value = String(line.dropFirst("data:".count))
                if value.hasPrefix(" ") { value.removeFirst() }
                dataBuffer += value
                // The server emits one complete JSON object per `data:` line, and
                // URLSession's AsyncLineSequence does NOT surface the blank separator
                // lines — so decode eagerly here instead of waiting for an empty line.
                if let raw = dataBuffer.data(using: .utf8),
                   let event = try? JSONDecoder().decode(StreamEvent.self, from: raw) {
                    Diag.log("frame ok: \(dataBuffer.count) bytes dispatched")
                    dataBuffer = ""
                    await MainActor.run { self.onEvent(event) }
                }
            } else if line.isEmpty {
                // backstop for spec-compliant blank-line framing (rarely reached here)
                let json = dataBuffer
                dataBuffer = ""
                guard !json.isEmpty, let raw = json.data(using: .utf8) else { continue }
                do {
                    let event = try JSONDecoder().decode(StreamEvent.self, from: raw)
                    await MainActor.run { self.onEvent(event) }
                } catch {
                    Diag.log("DECODE FAIL: \(error)")
                    Diag.log("json head: \(json.prefix(200))")
                }
            }
            // other SSE fields (event:, id:, retry:, ":" comments) are ignored
        }
    }
}

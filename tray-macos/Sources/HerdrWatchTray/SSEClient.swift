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

    private lazy var session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 3600
        cfg.timeoutIntervalForResource = 86_400
        cfg.waitsForConnectivity = true
        return URLSession(configuration: cfg)
    }()

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
        var req = URLRequest(url: base.appendingPathComponent("api/stream"))
        req.setValue("text/event-stream", forHTTPHeaderField: "Accept")

        let (bytes, response) = try await session.bytes(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        await MainActor.run { self.onConnected(true) }

        var dataBuffer = ""
        for try await line in bytes.lines {
            if Task.isCancelled { throw CancellationError() }
            if line.isEmpty {
                // end of one SSE frame
                let json = dataBuffer
                dataBuffer = ""
                if !json.isEmpty, let raw = json.data(using: .utf8),
                   let event = try? JSONDecoder().decode(StreamEvent.self, from: raw) {
                    await MainActor.run { self.onEvent(event) }
                }
            } else if line.hasPrefix("data:") {
                var value = String(line.dropFirst("data:".count))
                if value.hasPrefix(" ") { value.removeFirst() }
                dataBuffer += value
            }
            // other SSE fields (event:, id:, retry:, ":" comments) are ignored
        }
    }
}

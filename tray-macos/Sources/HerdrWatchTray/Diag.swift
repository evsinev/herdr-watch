import Foundation

// Lightweight stderr logger, enabled only when HERDR_DEBUG is set in the environment.
// Run the app as `HERDR_DEBUG=1 ./HerdrWatchTray` to see SSE diagnostics.
enum Diag {
    static let enabled = ProcessInfo.processInfo.environment["HERDR_DEBUG"] != nil

    static func log(_ message: @autoclosure () -> String) {
        guard enabled else { return }
        FileHandle.standardError.write(Data(("[herdr] " + message() + "\n").utf8))
    }
}

import AppKit
import UserNotifications

// Native notification delivery via UserNotifications. Works only when running as a
// bundled, (ad-hoc) signed .app — a bare `swift run` executable has no bundle identifier,
// so we no-op there instead of crashing UNUserNotificationCenter.current().
final class Notifier {
    static let shared = Notifier()
    private var available = false

    func requestAuthorization() {
        guard Bundle.main.bundleIdentifier != nil else {
            Diag.log("notifications disabled: no bundle id (run the .app, not the bare binary)")
            return
        }
        available = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error = error {
                Diag.log("notif auth error: \(error)")
            } else {
                Diag.log("notif auth granted=\(granted)")
            }
        }
    }

    func post(title: String, body: String, sound: Bool) {
        guard available else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if sound { content.sound = .default }
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error { Diag.log("notif post error: \(error)") }
        }
    }
}

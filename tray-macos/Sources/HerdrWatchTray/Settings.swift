import AppKit
import ServiceManagement

enum Settings {
    private static let urlKey = "baseURL"
    static let defaultURL = "http://localhost:8080"

    static var baseURLString: String {
        get { UserDefaults.standard.string(forKey: urlKey) ?? defaultURL }
        set { UserDefaults.standard.set(newValue, forKey: urlKey) }
    }
    static var baseURL: URL? { URL(string: baseURLString) }
}

// Small settings window: server URL + "Launch at Login".
// Note: Launch-at-Login (SMAppService) only works when running the packaged .app,
// not a bare `swift run` binary.
final class SettingsWindowController: NSWindowController {
    private let onChange: () -> Void
    private let urlField = NSTextField(string: Settings.baseURLString)
    private let loginToggle = NSButton(checkboxWithTitle: "Launch at Login", target: nil, action: nil)

    init(onChange: @escaping () -> Void) {
        self.onChange = onChange
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 160),
            styleMask: [.titled, .closable],
            backing: .buffered, defer: false)
        window.title = "herdr-watch Settings"
        window.isReleasedWhenClosed = false
        window.center()
        super.init(window: window)
        buildUI()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private func buildUI() {
        guard let content = window?.contentView else { return }

        let label = NSTextField(labelWithString: "Server URL")
        label.frame = NSRect(x: 20, y: 116, width: 360, height: 18)

        urlField.frame = NSRect(x: 20, y: 88, width: 360, height: 24)
        urlField.placeholderString = Settings.defaultURL
        urlField.stringValue = Settings.baseURLString

        loginToggle.frame = NSRect(x: 20, y: 52, width: 360, height: 20)
        loginToggle.target = self
        loginToggle.action = #selector(toggleLogin)
        loginToggle.state = (SMAppService.mainApp.status == .enabled) ? .on : .off

        let save = NSButton(title: "Save", target: self, action: #selector(save))
        save.frame = NSRect(x: 300, y: 12, width: 80, height: 30)
        save.bezelStyle = .rounded
        save.keyEquivalent = "\r"

        content.addSubview(label)
        content.addSubview(urlField)
        content.addSubview(loginToggle)
        content.addSubview(save)
    }

    @objc private func save() {
        let value = urlField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if !value.isEmpty, URL(string: value) != nil {
            Settings.baseURLString = value
        }
        onChange()
        window?.close()
    }

    @objc private func toggleLogin() {
        do {
            if loginToggle.state == .on {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            NSSound.beep()
            loginToggle.state = (SMAppService.mainApp.status == .enabled) ? .on : .off
        }
    }
}

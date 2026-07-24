import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private let store = FleetStore()
    private var sse: SSEClient!
    private var settingsWindow: SettingsWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        render()

        sse = SSEClient(
            url: { Settings.baseURL },
            onEvent: { [weak self] event in self?.handle(event) },
            onConnected: { [weak self] connected in
                self?.store.connected = connected
                self?.render()
            })
        sse.start()
    }

    // MARK: - SSE

    private func handle(_ event: StreamEvent) {
        switch event.payload {
        case .snapshot(let list): store.applySnapshot(list)
        case .update(let host):   store.applyUpdate(host)
        case .remove(let id):     store.applyRemove(id)
        }
        render()
    }

    private func reconnect() {
        store.connected = false
        render()
        sse.start()
    }

    // MARK: - UI (main thread)

    private func render() {
        updateIcon()
        statusItem.menu = buildMenu()
    }

    private func updateIcon() {
        guard let button = statusItem.button else { return }
        if !store.connected {
            button.image = AgentStatus.statusItemImage(tint: nil)
            button.appearsDisabled = true
            button.toolTip = "herdr-watch — reconnecting…"
            return
        }
        button.appearsDisabled = false
        button.image = AgentStatus.statusItemImage(tint: store.attentionTint())
        let c = store.counts()
        button.toolTip = "herdr-watch — \(c.blocked) blocked · \(c.done) done · \(c.working) working"
    }

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()
        menu.autoenablesItems = false

        if !store.connected {
            addInfo(menu, "Reconnecting…")
        } else {
            let rows = store.rows()
            if rows.isEmpty {
                addInfo(menu, "No agents")
            } else {
                for row in rows {
                    let text = "\(row.host)   \(row.title)   [\(row.status)]"
                    let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
                    item.isEnabled = false
                    item.image = AgentStatus.dot(row.color)
                    if row.dim {
                        item.attributedTitle = NSAttributedString(
                            string: text,
                            attributes: [.foregroundColor: NSColor.disabledControlTextColor])
                    }
                    menu.addItem(item)
                }
            }
        }

        menu.addItem(.separator())

        let open = menu.addItem(withTitle: "Open dashboard…", action: #selector(openDashboard), keyEquivalent: "o")
        open.target = self
        let settings = menu.addItem(withTitle: "Settings…", action: #selector(openSettings), keyEquivalent: ",")
        settings.target = self

        menu.addItem(.separator())

        let quit = menu.addItem(withTitle: "Quit herdr-watch", action: #selector(quit), keyEquivalent: "q")
        quit.target = self

        return menu
    }

    private func addInfo(_ menu: NSMenu, _ text: String) {
        let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        item.isEnabled = false
        menu.addItem(item)
    }

    // MARK: - Actions

    @objc private func openDashboard() {
        if let url = Settings.baseURL { NSWorkspace.shared.open(url) }
    }

    @objc private func openSettings() {
        if settingsWindow == nil {
            settingsWindow = SettingsWindowController(onChange: { [weak self] in self?.reconnect() })
        }
        settingsWindow?.showWindow(nil)
        settingsWindow?.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}

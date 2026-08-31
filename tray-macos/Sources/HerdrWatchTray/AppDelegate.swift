import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private let store = FleetStore()
    private var sse: SSEClient!
    private var settingsWindow: SettingsWindowController?

    func applicationDidFinishLaunching(_ notification: Notification) {
        Notifier.shared.requestAuthorization()
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        render()

        sse = SSEClient(
            url: { Settings.baseURL },
            onEvent: { [weak self] event in self?.handle(event) },
            onConnected: { [weak self] connected in
                self?.store.connected = connected
                self?.render()
                if connected { self?.fetchUsage() }
            })
        sse.start()

        // After waking from sleep the old SSE socket is dead; force a fresh reconnect
        // instead of waiting for the stale connection to time out.
        NSWorkspace.shared.notificationCenter.addObserver(
            self, selector: #selector(onWake), name: NSWorkspace.didWakeNotification, object: nil)

        // Полосы квоты — ЦВЕТНОЕ изображение (isTemplate = false), автоинверсии под тему
        // меню-бара у него нет: цвет дорожек мы считаем сами из effectiveAppearance.
        // Значит и перерисовать иконку нужно при смене темы.
        DistributedNotificationCenter.default.addObserver(
            self, selector: #selector(onThemeChange),
            name: NSNotification.Name("AppleInterfaceThemeChangedNotification"), object: nil)
    }

    @objc private func onThemeChange() {
        DispatchQueue.main.async { [weak self] in self?.render() }
    }

    @objc private func onWake() {
        sse.start()
    }

    // MARK: - SSE

    private func handle(_ event: StreamEvent) {
        switch event.payload {
        case .ping:
            return   // heartbeat — keeps the socket warm; no state change, no re-render
        case .snapshot(let list):
            store.applySnapshot(list)
        case .update(let host):
            let transitions = store.applyUpdate(host)
            if Settings.notificationsEnabled {
                // Don't notify on transitions into `working` — too noisy (agents flap idle↔working).
                for t in transitions where t.to.lowercased() != "working" { notify(t) }
            }
        case .remove(let id):
            store.applyRemove(id)
        case .usage(let usage):
            store.usage = usage
        case .unknown(let type):
            Diag.log("ignoring unknown event type: \(type)")
            return   // ничего не изменилось — и рендерить нечего
        }
        render()
    }

    private func notify(_ t: FleetStore.Transition) {
        let title = "\(t.host) · \(t.project)"
        let body: String
        let sound: Bool
        switch t.to.lowercased() {
        case "blocked": body = "⛔ needs input"; sound = true
        case "done":    body = "✅ finished";     sound = false
        default:        body = "\(t.from ?? "?") → \(t.to)"; sound = false
        }
        Notifier.shared.post(title: title, body: body, sound: sound)
    }

    /// Событие `claude_usage` сервер шлёт ТОЛЬКО при изменении цифр — в отличие от
    /// `snapshot`, на подключение оно не приходит. Поэтому квоту на каждом
    /// (пере)подключении добираем разово через REST: иначе после старта трея полос нет
    /// до первого движения квоты, а оно бывает раз в десятки минут.
    private func fetchUsage() {
        guard let url = Settings.baseURL?.appendingPathComponent("api/claude-usage") else { return }
        Task { [weak self] in
            let cfg = URLSessionConfiguration.ephemeral
            cfg.timeoutIntervalForRequest = 10
            cfg.waitsForConnectivity = false
            let session = URLSession(configuration: cfg)
            defer { session.invalidateAndCancel() }
            do {
                let (data, response) = try await session.data(from: url)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    Diag.log("usage fetch: bad response \(response)")
                    return
                }
                let usage = try JSONDecoder().decode(ClaudeUsage.self, from: data)
                await MainActor.run { [weak self] in
                    guard let self else { return }
                    self.store.usage = usage
                    self.render()
                }
            } catch {
                Diag.log("usage fetch failed: \(error)")   // не критично: SSE догонит
            }
        }
    }

    private func reconnect() {
        store.connected = false
        render()
        sse.start()
    }

    // MARK: - UI (main thread)

    // Строки-подписи (агенты, квота, «No agents») намеренно disabled: клик по ним ничего
    // не значит. Но disabled-строку AppKit рисует СВОИМ тусклым серым, и на тёмном меню
    // его плохо видно. Явный цвет в attributedTitle это перебивает — строка остаётся
    // инертной (не подсвечивается под курсором), но читается нормально.
    private static let infoFont = NSFont.menuFont(ofSize: 0)
    private static let infoColor = NSColor.labelColor
    /// Приглушённый — только там, где приглушение НЕСЁТ СМЫСЛ: недоступный хост,
    /// устаревшие показания квоты.
    private static let mutedColor = NSColor.secondaryLabelColor

    private func render() {
        updateIcon()
        statusItem.menu = buildMenu()
    }

    private func updateIcon() {
        guard let button = statusItem.button else { return }
        button.imagePosition = .imageLeading   // icon on the left, counter text on the right
        if !store.connected {
            button.image = AgentStatus.statusItemImage(tint: nil)
            button.title = ""
            button.appearsDisabled = true
            button.toolTip = "herdr-watch — reconnecting…"
            return
        }
        button.appearsDisabled = false
        // Полосы квоты ЗАМЕНЯЮТ иконку-символ. В меню-баре места мало: 5h + 7d + не
        // больше двух помодельных окон; полный список — в выпадающем меню.
        let gauges = store.usageGauges(maxModels: 2)
        button.image = UsageRender.statusItemImage(
            tint: store.attentionTint(), gauges: gauges,
            stale: store.usageIsStale, appearance: button.effectiveAppearance)
        let c = store.counts()
        button.title = Settings.showCounter ? counterText(c) : ""
        var tip = "herdr-watch — \(c.blocked) blocked · \(c.done) done · \(c.working) working"
        if !gauges.isEmpty {
            tip += "\nclaude quota — " + gauges.map { "\($0.label) \($0.clamped)%" }.joined(separator: " · ")
        }
        button.toolTip = tip
    }

    private func counterText(_ c: FleetStore.Counts) -> String {
        var parts: [String] = []
        if c.blocked > 0 { parts.append("⛔\(c.blocked)") }
        if c.done > 0 { parts.append("✅\(c.done)") }
        return parts.joined(separator: " ")
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
                    let text = "\(row.host)   \(row.project)   [\(row.status)]"
                    let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
                    item.isEnabled = false
                    item.image = AgentStatus.dot(row.color)
                    item.attributedTitle = NSAttributedString(string: text, attributes: [
                        .font: Self.infoFont,
                        .foregroundColor: row.dim ? Self.mutedColor : Self.infoColor,
                    ])
                    menu.addItem(item)
                }
            }
        }

        addUsage(menu)

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

    /// Блок квоты: заголовок с источником и возрастом показаний + строка на окно.
    /// Не рисуется вовсе, когда показывать нечего (хук не установлен / ни одно окно не
    /// отчиталось) — ровно как `hasUsage()` во фронтенде.
    private func addUsage(_ menu: NSMenu) {
        let gauges = store.usageGauges()
        guard let usage = store.usage, !gauges.isEmpty else { return }
        let stale = store.usageIsStale

        menu.addItem(.separator())

        var meta: [String] = []
        if stale { meta.append("stale") }
        if let source = UsageText.source(usage.source) { meta.append(source) }
        meta.append(usage.capturedAt.map { UsageText.ago($0) } ?? "no reading")
        // «claude · account»: цифры относятся к аккаунту Claude, а не к машине, и спутать
        // эти вещи нельзя.
        addInfo(menu, "claude · account   —   " + meta.joined(separator: " · "),
                color: stale ? Self.mutedColor : Self.infoColor)

        let width = gauges.map { $0.label.count }.max() ?? 2
        let font = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        for gauge in gauges {
            let label = gauge.label.padding(toLength: width, withPad: " ", startingAt: 0)
            var text = String(format: "%@  %3d%%", label, gauge.clamped)
            if let resetsAt = gauge.resetsAt { text += "   " + UsageText.untilReset(resetsAt) }
            let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
            item.isEnabled = false
            item.image = UsageRender.menuRowImage(percent: gauge.clamped, stale: stale)
            item.attributedTitle = NSAttributedString(string: text, attributes: [
                .font: font,
                .foregroundColor: stale ? Self.mutedColor : Self.infoColor,
            ])
            if stale, let error = usage.error { item.toolTip = error }
            menu.addItem(item)
        }
    }

    private func addInfo(_ menu: NSMenu, _ text: String, color: NSColor = AppDelegate.infoColor) {
        let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        item.isEnabled = false
        item.attributedTitle = NSAttributedString(
            string: text, attributes: [.font: Self.infoFont, .foregroundColor: color])
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

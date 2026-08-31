import AppKit

/// Полосы утилизации квоты Claude. ЗЕРКАЛО backend `usage/UsageSeverity.java` и
/// frontend `lib/theme.ts` (`USAGE_BANDS`) — значения обязаны совпадать, правим тройкой.
/// Цвета берём из токенов статусов (`AgentStatus`), новых hex'ов здесь нет.
enum UsageBands {
    static let warnAt = 70
    static let criticalAt = 90

    static func color(_ usedPercent: Int) -> NSColor {
        if usedPercent >= criticalAt { return AgentStatus.color("blocked") }
        if usedPercent >= warnAt { return AgentStatus.color("working") }
        return AgentStatus.color("idle")
    }
}

/// Одно окно квоты, приведённое к тому, что рисует трей.
/// `resetsAt == nil` — время сброса не показываем: у помодельных окон оно то же
/// недельное, что и у `7d` (так же поступает UsageGauge.tsx).
struct UsageGauge {
    let label: String
    let percent: Int
    let resetsAt: Double?

    /// 0…100 — рисовать чем-то за этими границами нельзя.
    var clamped: Int { min(100, max(0, percent)) }
}

/// Тексты рядом с цифрами — зеркало хелперов из `frontend/src/components/UsageGauge.tsx`.
enum UsageText {
    /// «38m» / «2h 10m» / «3d» — сколько осталось до сброса окна.
    static func untilReset(_ resetsAt: Double, now: Date = Date()) -> String {
        let secs = Int((resetsAt - now.timeIntervalSince1970).rounded())
        if secs <= 0 { return "resetting" }
        let mins = Int((Double(secs) / 60).rounded())
        if mins < 60 { return "\(mins)m" }
        let hours = mins / 60
        if hours < 24 { return "\(hours)h \(mins % 60)m" }
        return "\(Int((Double(hours) / 24).rounded()))d"
    }

    /// «3m ago» — возраст показаний: цифра без возраста здесь вводит в заблуждение
    /// (квота двигается только пока открыта сессия Claude Code).
    static func ago(_ capturedAt: Double, now: Date = Date()) -> String {
        let secs = max(0, Int((now.timeIntervalSince1970 - capturedAt).rounded()))
        if secs < 60 { return "\(secs)s ago" }
        let mins = Int((Double(secs) / 60).rounded())
        if mins < 60 { return "\(mins)m ago" }
        let hours = Int((Double(mins) / 60).rounded())
        if hours < 48 { return "\(hours)h ago" }
        return "\(Int((Double(hours) / 24).rounded()))d ago"
    }

    /// Кто наблюдал показания. Незнакомый источник показываем как есть, а не прячем.
    static func source(_ raw: String?) -> String? {
        guard let raw = raw, !raw.isEmpty else { return nil }
        switch raw.uppercased() {
        case "STATUSLINE":  return "statusline"
        case "ACCOUNT_API": return "account api"
        case "NONE":        return nil
        default:            return raw.lowercased().replacingOccurrences(of: "_", with: " ")
        }
    }
}

/// Рисование полос квоты: мини-полосы в меню-баре и горизонтальная полоска в строке меню.
enum UsageRender {
    private static let barWidth: CGFloat = 3
    private static let barGap: CGFloat = 2
    private static let barHeight: CGFloat = 12
    private static let symbolGap: CGFloat = 5

    /// Иконка меню-бара: символ статуса + вертикальные полосы квоты справа от него.
    ///
    /// Без окон возвращаем РОВНО прежнюю иконку (template — macOS сам инвертирует её
    /// под тему меню-бара). С полосами композит обязан быть цветным, автоинверсии у
    /// него больше нет, поэтому цвет символа берём из `effectiveAppearance` кнопки
    /// статус-айтема: это единственное, что знает, тёмный сейчас меню-бар или светлый.
    static func statusItemImage(tint: NSColor?, gauges: [UsageGauge], stale: Bool,
                               appearance: NSAppearance) -> NSImage {
        guard !gauges.isEmpty else { return AgentStatus.statusItemImage(tint: tint) }

        let label = resolvedLabelColor(appearance)
        let symbol = AgentStatus.statusItemImage(tint: tint ?? label)
        let symbolSize = symbol.size
        let barsWidth = CGFloat(gauges.count) * barWidth + CGFloat(gauges.count - 1) * barGap
        let size = NSSize(width: symbolSize.width + symbolGap + barsWidth,
                          height: max(symbolSize.height, barHeight))

        let image = NSImage(size: size)
        image.lockFocus()
        symbol.draw(in: NSRect(x: 0, y: (size.height - symbolSize.height) / 2,
                              width: symbolSize.width, height: symbolSize.height))
        // STALE не занижает полосу (цифры остаются лучшим, что у нас есть), но глазом
        // отличаться обязан — гасим прозрачностью, как приглушённая плитка в UI.
        let alpha: CGFloat = stale ? 0.45 : 1
        var x = symbolSize.width + symbolGap
        let y = (size.height - barHeight) / 2
        for gauge in gauges {
            label.withAlphaComponent(0.16 * alpha).setFill()
            pill(NSRect(x: x, y: y, width: barWidth, height: barHeight)).fill()
            let percent = gauge.clamped
            if percent > 0 {
                // Минимум 2pt: 1% иначе даёт 0.13pt и «есть расход» выглядит как ноль.
                let height = max(2, barHeight * CGFloat(percent) / 100)
                UsageBands.color(percent).withAlphaComponent(alpha).setFill()
                pill(NSRect(x: x, y: y, width: barWidth, height: height)).fill()
            }
            x += barWidth + barGap
        }
        image.unlockFocus()
        image.isTemplate = false
        return image
    }

    /// Горизонтальная полоска для строки меню (идёт как `NSMenuItem.image`).
    static func menuRowImage(percent: Int, stale: Bool) -> NSImage {
        let width: CGFloat = 42
        let height: CGFloat = 6
        let value = min(100, max(0, percent))
        let color = UsageBands.color(value)
        let alpha: CGFloat = stale ? 0.45 : 1

        let image = NSImage(size: NSSize(width: width, height: height))
        image.lockFocus()
        color.withAlphaComponent(0.18 * alpha).setFill()
        pill(NSRect(x: 0, y: 0, width: width, height: height)).fill()
        if value > 0 {
            let filled = max(1.5, width * CGFloat(value) / 100)
            color.withAlphaComponent(alpha).setFill()
            NSBezierPath(roundedRect: NSRect(x: 0, y: 0, width: filled, height: height),
                         xRadius: min(height / 2, filled / 2), yRadius: height / 2).fill()
        }
        image.unlockFocus()
        image.isTemplate = false
        return image
    }

    private static func pill(_ rect: NSRect) -> NSBezierPath {
        NSBezierPath(roundedRect: rect,
                     xRadius: min(rect.width, rect.height) / 2,
                     yRadius: min(rect.width, rect.height) / 2)
    }

    /// `labelColor` — динамический цвет; его значение зависит от текущего appearance,
    /// поэтому разрешаем его в контексте меню-бара и фиксируем в sRGB до отрисовки.
    private static func resolvedLabelColor(_ appearance: NSAppearance) -> NSColor {
        var out = NSColor.white
        appearance.performAsCurrentDrawingAppearance {
            out = NSColor.labelColor.usingColorSpace(.sRGB) ?? NSColor.white
        }
        return out
    }
}

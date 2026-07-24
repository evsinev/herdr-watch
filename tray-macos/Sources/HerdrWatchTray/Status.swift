import AppKit

// Single source of truth for status → color / priority, mirrored 1:1 from
// frontend/src/lib/theme.ts. Also renders the menu-bar icon and the small
// colored dots used in menu rows.
enum AgentStatus {
    static let colors: [String: NSColor] = [
        "blocked": NSColor(hex: 0xE24B4A),
        "working": NSColor(hex: 0xEF9F27),
        "done":    NSColor(hex: 0x378ADD),
        "idle":    NSColor(hex: 0x639922),
        "unknown": NSColor(hex: 0x888780),
    ]
    static let priority: [String: Int] = [
        "blocked": 5, "working": 4, "done": 3, "idle": 2, "unknown": 1,
    ]

    static func color(_ status: String?) -> NSColor {
        colors[(status ?? "unknown").lowercased()] ?? colors["unknown"]!
    }
    static func prio(_ status: String?) -> Int {
        priority[(status ?? "unknown").lowercased()] ?? 1
    }

    private static let symbolName = "dot.radiowaves.up.forward"

    /// tint == nil → template image (macOS auto-inverts white/black per menu-bar theme).
    /// tint != nil → colored image (isTemplate = false), the "attention" state.
    static func statusItemImage(tint: NSColor?) -> NSImage {
        let base = NSImage(systemSymbolName: symbolName, accessibilityDescription: "herdr-watch")
            ?? NSImage(size: NSSize(width: 18, height: 18))
        let sizeCfg = NSImage.SymbolConfiguration(pointSize: 15, weight: .regular)
        if let tint = tint {
            let cfg = sizeCfg.applying(NSImage.SymbolConfiguration(paletteColors: [tint]))
            let img = base.withSymbolConfiguration(cfg) ?? base
            img.isTemplate = false
            return img
        } else {
            let img = base.withSymbolConfiguration(sizeCfg) ?? base
            img.isTemplate = true
            return img
        }
    }

    static func dot(_ color: NSColor, diameter: CGFloat = 10) -> NSImage {
        let img = NSImage(size: NSSize(width: diameter, height: diameter))
        img.lockFocus()
        color.setFill()
        NSBezierPath(ovalIn: NSRect(x: 0, y: 0, width: diameter, height: diameter)).fill()
        img.unlockFocus()
        img.isTemplate = false
        return img
    }
}

extension NSColor {
    convenience init(hex: Int) {
        self.init(
            srgbRed: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green:   CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue:    CGFloat(hex & 0xFF) / 255.0,
            alpha: 1.0)
    }
}

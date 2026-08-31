import AppKit

/// Строка-подпись в меню (агент, окно квоты, «No agents»): картинка + текст,
/// БЕЗ подсветки под курсором.
///
/// Почему своя вьюха, а не обычный `NSMenuItem`. У стандартной строки «на меня нельзя
/// нажать» достигается только через `isEnabled = false`, но такую строку AppKit гасит
/// своим тусклым серым ПОВЕРХ любого `foregroundColor` — проверено на живом меню, цветом
/// это не лечится. Включённая строка читается, но подсвечивается под курсором и врёт, что
/// по ней есть куда кликнуть. Своя вьюха развязывает две вещи: цвет наш, подсветки нет.
///
/// Метрики сняты пиксельно с реального меню: текст стандартных пунктов («Open dashboard…»)
/// начинается на 20.5 pt от левого края, высота строки — 22 pt. Картинка занимает начало
/// той же колонки, текст идёт за ней.
final class MenuRowView: NSView {
    private static let leadingInset: CGFloat = 20.5
    private static let imageGap: CGFloat = 7
    private static let trailingInset: CGFloat = 18
    private static let rowHeight: CGFloat = 22

    private let image: NSImage?
    private let text: NSAttributedString

    init(image: NSImage?, text: NSAttributedString) {
        self.image = image
        self.text = text
        let imageWidth = image.map { $0.size.width + Self.imageGap } ?? 0
        let width = Self.leadingInset + imageWidth + ceil(text.size().width) + Self.trailingInset
        super.init(frame: NSRect(x: 0, y: 0, width: width, height: Self.rowHeight))
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func draw(_ dirtyRect: NSRect) {
        var x = Self.leadingInset
        if let image = image {
            let size = image.size
            image.draw(in: NSRect(x: x, y: ((bounds.height - size.height) / 2).rounded(),
                                  width: size.width, height: size.height))
            x += size.width + Self.imageGap
        }
        let size = text.size()
        text.draw(at: NSPoint(x: x, y: ((bounds.height - size.height) / 2).rounded()))
    }
}

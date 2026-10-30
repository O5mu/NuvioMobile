import Foundation
import UIKit
import CoreText

/// Draws text subtitles with UIKit above the video, so they stay still and sharp while the
/// picture is scaled. Sizes use mpv's units (scaled pixels of a 720 high screen) so the same
/// style values work for both this view and mpv's own subtitle renderer.
final class NativeSubtitleView: UIView {

    struct Style {
        var textColor: UIColor = .white
        var backgroundColor: UIColor = .clear
        var outlineColor: UIColor = .black
        var outlineSize: CGFloat = 0
        var bold = false
        var fontSize: CGFloat = 54
        var bottomPercent: CGFloat = 90
        var fontFamily = ""
        var shadowColor: UIColor = .black
        var shadowOffset: CGFloat = 0
        var shadowBlur: CGFloat = 0
    }

    /// libass fits a font's whole line height into the font size, while UIKit sizes the em box.
    private static let fontSizeFactor: CGFloat = 0.75
    private static let horizontalMargin: CGFloat = 24
    private static let backgroundPadding: CGFloat = 6

    private let backgroundView = UIView()
    private let textContainer = UIView()
    private let outlineLabel = UILabel()
    private let textLabel = UILabel()
    private var importedFontDescriptors: [String: CTFontDescriptor] = [:]

    var text = "" {
        didSet {
            guard text != oldValue else { return }
            setNeedsLayout()
        }
    }

    var style = Style() {
        didSet { setNeedsLayout() }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        addSubview(backgroundView)
        addSubview(textContainer)
        [outlineLabel, textLabel].forEach { label in
            label.numberOfLines = 0
            label.backgroundColor = .clear
            textContainer.addSubview(label)
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        defer { CATransaction.commit() }

        guard !text.isEmpty, bounds.height > 1 else {
            textContainer.isHidden = true
            backgroundView.isHidden = true
            return
        }

        let unit = bounds.height / 720
        let font = subtitleFont(size: max(style.fontSize * unit * Self.fontSizeFactor, 1))
        let outline = style.outlineSize * unit
        let hasBackground = style.backgroundColor.cgColor.alpha > 0

        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        paragraph.lineBreakMode = .byWordWrapping
        paragraph.baseWritingDirection = .natural

        textLabel.attributedText = NSAttributedString(string: text, attributes: [
            .font: font,
            .foregroundColor: style.textColor,
            .paragraphStyle: paragraph,
        ])
        // The outline sits under the text, so overlapping joined letters show no seams.
        outlineLabel.isHidden = outline <= 0
        if outline > 0 {
            outlineLabel.attributedText = NSAttributedString(string: text, attributes: [
                .font: font,
                .foregroundColor: UIColor.clear,
                .strokeColor: style.outlineColor,
                .strokeWidth: outline * 2 / font.pointSize * 100,
                .paragraphStyle: paragraph,
            ])
        }

        let insets = safeAreaInsets
        let maxWidth = max(bounds.width - insets.left - insets.right - Self.horizontalMargin * 2, 1)
        let textSize = textLabel.sizeThatFits(CGSize(width: maxWidth, height: .greatestFiniteMagnitude))

        // Labels clip to their bounds, so leave room for the stroke around the text.
        let padding = ceil(outline) + 2
        let containerSize = CGSize(width: ceil(textSize.width) + padding * 2, height: ceil(textSize.height) + padding * 2)
        let bottom = min(bounds.height * style.bottomPercent / 100, bounds.height - insets.bottom)
        let centerX = insets.left + (bounds.width - insets.left - insets.right) / 2
        let containerFrame = CGRect(
            x: centerX - containerSize.width / 2,
            y: bottom - containerSize.height + padding,
            width: containerSize.width,
            height: containerSize.height
        )

        textContainer.isHidden = false
        textContainer.frame = containerFrame
        textLabel.frame = textContainer.bounds
        outlineLabel.frame = textContainer.bounds

        backgroundView.isHidden = !hasBackground
        if hasBackground {
            backgroundView.backgroundColor = style.backgroundColor
            backgroundView.frame = containerFrame.insetBy(
                dx: padding - Self.backgroundPadding,
                dy: padding - Self.backgroundPadding
            )
        }

        // A background box replaces the shadow, the same as mpv's opaque-box style.
        let hasShadow = !hasBackground && (style.shadowOffset > 0 || style.shadowBlur > 0)
        let layer = textContainer.layer
        layer.shadowOpacity = hasShadow ? 1 : 0
        if hasShadow {
            layer.shadowColor = style.shadowColor.cgColor
            layer.shadowOffset = CGSize(width: style.shadowOffset * unit, height: style.shadowOffset * unit)
            layer.shadowRadius = style.shadowBlur * unit
        }
    }

    private func subtitleFont(size: CGFloat) -> UIFont {
        if let descriptor = importedFontDescriptor() {
            let font = CTFontCreateWithFontDescriptor(descriptor, size, nil)
            if style.bold, let bold = CTFontCreateCopyWithSymbolicTraits(font, size, nil, .traitBold, .traitBold) {
                return bold as UIFont
            }
            return font as UIFont
        }
        // The system font and its fallbacks cover Arabic (SF Arabic) and CJK text.
        return UIFont.systemFont(ofSize: size, weight: style.bold ? .bold : .regular)
    }

    private func importedFontDescriptor() -> CTFontDescriptor? {
        let prefix = "file:"
        guard style.fontFamily.hasPrefix(prefix) else { return nil }
        let fileName = String(style.fontFamily.dropFirst(prefix.count))
        if let cached = importedFontDescriptors[fileName] { return cached }
        guard !fileName.isEmpty, !fileName.contains("/"),
              let supportURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return nil }
        let fontURL = supportURL.appendingPathComponent("SubtitleFonts/\(fileName)")
        let descriptors = CTFontManagerCreateFontDescriptorsFromURL(fontURL as CFURL) as? [CTFontDescriptor]
        let descriptor = descriptors?.first
        importedFontDescriptors[fileName] = descriptor
        return descriptor
    }
}

extension UIColor {
    /// Parses mpv's "#AARRGGBB" colour strings.
    convenience init?(mpvColor: String) {
        guard mpvColor.hasPrefix("#"), mpvColor.count == 9,
              let value = UInt32(mpvColor.dropFirst(), radix: 16)
        else { return nil }
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
    }
}

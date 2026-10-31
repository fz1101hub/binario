import SwiftUI

/// Binario 2.0 colour tokens (design/BINARIO_2_DESIGN_SYSTEM.md). Each value is defined once, in
/// Resources/Assets.xcassets, with a light and a dark appearance. AccentColor is also the app-wide
/// tint through ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME in project.yml, so buttons, tabs,
/// toggles and pickers need no per-view colour. Status is always spelled out in text as well.
enum BinarioPalette {
    /// Reference green: identity and decorative accents only, never small text on white.
    static var brandGreen: Color { Color("BrandGreen") }
    /// Text placed on an AccentColor fill: white on the light-mode green, dark on the dark-mode one.
    static var onAccent: Color { Color("OnAccentColor") }
    /// Error and validation messages (AA on the grouped backgrounds, unlike the system red).
    static var statusError: Color { Color("StatusError") }
}

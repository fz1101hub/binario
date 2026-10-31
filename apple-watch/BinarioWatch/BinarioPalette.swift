import SwiftUI

/// Binario 2.0 colour tokens for the watch (design/BINARIO_2_DESIGN_SYSTEM.md, dark column: the
/// watch is always dark). Each value is defined once, in Assets.xcassets; AccentColor is also the
/// app-wide tint through ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME in project.yml. The widget
/// extension has no asset catalogue and keeps the system styling of the watch face.
enum BinarioPalette {
    static var brandGreen: Color { Color("BrandGreen") }
    /// Data-quality warnings; never the only signal, the status text is always shown.
    static var statusWarning: Color { Color("StatusWarning") }
    static var statusError: Color { Color("StatusError") }
}

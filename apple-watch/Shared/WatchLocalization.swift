import Foundation

enum WatchLocalization {
    static func text(_ key: String.LocalizationValue) -> String {
        String(localized: key, table: "Localizable", bundle: .main)
    }
}

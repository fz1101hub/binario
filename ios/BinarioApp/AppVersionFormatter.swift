import Foundation

protocol AppVersionFormatting: Sendable {
    func displayVersion(bundle: Bundle) -> String
}

struct BundleAppVersionFormatter: AppVersionFormatting {
    func displayVersion(bundle: Bundle) -> String {
        let shortVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let buildVersion = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String

        switch (shortVersion?.trimmingCharacters(in: .whitespacesAndNewlines),
                buildVersion?.trimmingCharacters(in: .whitespacesAndNewlines)) {
        case let (.some(short), .some(build)) where !short.isEmpty && !build.isEmpty:
            return "\(short) (\(build))"
        case let (.some(short), _) where !short.isEmpty:
            return short
        case let (_, .some(build)) where !build.isEmpty:
            return build
        default:
            return String(localized: "settings.about.version_unavailable")
        }
    }
}

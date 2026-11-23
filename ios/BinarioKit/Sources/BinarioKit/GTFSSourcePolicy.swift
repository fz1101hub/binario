import Foundation

public enum GTFSSourcePolicyError: Error, Equatable, Sendable {
    case unsupportedSchema
    case invalidSource
}

/// The reviewed list of bundled GTFS sources (`binario_gtfs_source_policy_v1.json`): the
/// source id of each pack, its HTTPS download URL, the access mode, the licence and the public
/// page of the feed (Android's `RailSourceCatalog.landingPage`) recorded for the in-app source list. It is an input of the artifact fingerprint, so it cannot
/// drift from the index it ships with.
public struct GTFSSourcePolicy: Decodable, Equatable, Sendable {
    public static let supportedSchemaVersion = 1
    public static let maximumSources = 64
    public static let openDirectAccessMode = "OPEN_DIRECT"
    /// A pack whose origin is plain HTTP (Genova–Casella): imported offline by CI and accepted by the
    /// index builder only when the manifest records an HTTPS copy of the same bytes. The app never
    /// downloads it and links its public page, which must be HTTPS.
    public static let gatewayOnlyAccessMode = "GATEWAY_ONLY"

    public struct Source: Decodable, Equatable, Sendable {
        public let sourceID: String
        public let sourceURL: URL
        public let accessMode: String
        /// Nil until the owner records the licence; the app then shows only attribution and URL.
        public let licence: String?
        /// The feed's public page, which the source list links to rather than the archive.
        public let landingPage: URL?

        enum CodingKeys: String, CodingKey {
            case sourceID = "sourceId"
            case sourceURL = "sourceUrl"
            case accessMode
            case licence
            case landingPage
        }
    }

    public let schemaVersion: Int
    public let sources: [Source]

    public init(data: Data, decoder: JSONDecoder = JSONDecoder()) throws {
        let decoded = try decoder.decode(Self.self, from: data)
        try decoded.validate()
        self = decoded
    }

    public func source(id: String) -> Source? {
        sources.first { $0.sourceID == id }
    }

    public func validate() throws {
        guard schemaVersion == Self.supportedSchemaVersion else { throw GTFSSourcePolicyError.unsupportedSchema }
        guard !sources.isEmpty, sources.count <= Self.maximumSources,
              Set(sources.map(\.sourceID)).count == sources.count,
              sources.allSatisfy({ source in
                  !source.sourceID.isEmpty
                      && Self.reviewedOrigin(source)
                      && source.landingPage.map { $0.scheme == "https" } ?? true
                      && source.licence.map { !$0.isEmpty } ?? true
              })
        else { throw GTFSSourcePolicyError.invalidSource }
    }

    /// An HTTPS direct download, or a gateway source (HTTP or HTTPS) with an HTTPS public page.
    private static func reviewedOrigin(_ source: Source) -> Bool {
        switch source.accessMode {
        case openDirectAccessMode:
            return source.sourceURL.scheme == "https"
        case gatewayOnlyAccessMode:
            return ["https", "http"].contains(source.sourceURL.scheme ?? "") && source.landingPage?.scheme == "https"
        default:
            return false
        }
    }
}

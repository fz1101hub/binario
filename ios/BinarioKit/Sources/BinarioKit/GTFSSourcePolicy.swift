import Foundation

public enum GTFSSourcePolicyError: Error, Equatable, Sendable {
    case unsupportedSchema
    case invalidSource
}

/// The reviewed list of bundled GTFS sources (`binario_gtfs_source_policy_v1.json`): the
/// source id of each pack, its HTTPS download URL, the access mode and the licence recorded
/// for the in-app source list. It is an input of the artifact fingerprint, so it cannot
/// drift from the index it ships with.
public struct GTFSSourcePolicy: Decodable, Equatable, Sendable {
    public static let supportedSchemaVersion = 1
    public static let maximumSources = 64
    public static let openDirectAccessMode = "OPEN_DIRECT"

    public struct Source: Decodable, Equatable, Sendable {
        public let sourceID: String
        public let sourceURL: URL
        public let accessMode: String
        /// Nil until the owner records the licence; the app then shows only attribution and URL.
        public let licence: String?

        enum CodingKeys: String, CodingKey {
            case sourceID = "sourceId"
            case sourceURL = "sourceUrl"
            case accessMode
            case licence
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
                      && source.accessMode == Self.openDirectAccessMode
                      && source.sourceURL.scheme == "https"
                      && source.licence.map { !$0.isEmpty } ?? true
              })
        else { throw GTFSSourcePolicyError.invalidSource }
    }
}

import CZlib
import Foundation

public enum BundledGTFSScheduleError: Error, Equatable, Sendable {
    case invalidMetadata
    case invalidCompressedArtifact
    case decompressionFailed
    case invalidDatabase
    case fileSystemFailure
}

/// Installs the signed, generated static-GTFS resource into an app-owned read-only cache.
/// Every boundary is size-bounded and verified before the resulting database is returned.
public struct BundledGTFSScheduleInstaller: Sendable {
    public static let maximumMetadataBytes = 4 * 1_024 * 1_024
    public static let maximumCompressedBytes = 32 * 1_024 * 1_024
    public static let maximumDatabaseBytes = 256 * 1_024 * 1_024
    private static let installationLock = NSLock()

    public init() {}

    public func install(
        compressedURL: URL,
        metadataURL: URL,
        cacheDirectory: URL
    ) throws -> URL {
        Self.installationLock.lock()
        defer { Self.installationLock.unlock() }
        let metadata = try Self.loadMetadata(from: metadataURL)
        let destination = cacheDirectory
            .appendingPathComponent("binario-gtfs-\(metadata.database.sha256.prefix(16)).sqlite", isDirectory: false)

        // Fast path: the cache name carries the digest prefix, the size must match the
        // metadata and the database must still validate (schema, quick_check, fingerprint).
        // Re-hashing the whole database with pure-Swift SHA-256 on every launch kept the
        // planner disabled for seconds on older iPhones; the full digest is verified below
        // whenever the cache is written.
        if let cachedSize = try? destination.resourceValues(forKeys: [.fileSizeKey]).fileSize,
           cachedSize == metadata.database.sizeBytes,
           SQLiteGTFSJourneyRepository.validatesDatabase(
               at: destination,
               inputFingerprint: metadata.inputs.fingerprintSha256
           ) {
            try Self.excludeFromBackup(destination)
            // A launch killed between a staged write and its removal leaves a hidden copy of the
            // whole index behind; under the lock no staged write of this process is in progress.
            // Listed only after the backup flag is written: see `removeStagedDatabases`.
            Self.removeStagedDatabases(in: cacheDirectory)
            return Self.withoutCachedResourceValues(destination)
        }

        Self.removeStagedDatabases(in: cacheDirectory)

        do {
            try FileManager.default.createDirectory(
                at: cacheDirectory,
                withIntermediateDirectories: true,
                attributes: nil
            )
            let compressed = try Self.readBounded(
                compressedURL,
                maximumBytes: Self.maximumCompressedBytes,
                expectedBytes: metadata.compression.sizeBytes
            )
            guard Self.sha256Hex(compressed) == metadata.compression.sha256 else {
                throw BundledGTFSScheduleError.invalidCompressedArtifact
            }
            let database = try Self.decompress(
                compressed,
                expectedBytes: metadata.database.sizeBytes
            )
            guard Self.sha256Hex(database) == metadata.database.sha256 else {
                throw BundledGTFSScheduleError.invalidDatabase
            }
            let installed = try Self.publishVerified(
                database: database,
                inputFingerprint: metadata.inputs.fingerprintSha256,
                onlySourceID: nil,
                stagedPrefix: ".binario-gtfs-",
                destination: destination
            )
            Self.removeSupersededDatabases(in: cacheDirectory, keeping: destination)
            return installed
        } catch let error as BundledGTFSScheduleError {
            throw error
        } catch {
            throw BundledGTFSScheduleError.fileSystemFailure
        }
    }

    /// Installs a downloaded per-country index (`ForeignScheduleStore`) at `destination`, with the
    /// checks of the bundled one: the compressed file of the pinned size and SHA-256, a bounded
    /// inflate to the pinned database size and SHA-256, the schema, `quick_check` and input
    /// fingerprint of `validatesDatabase`, and exactly one pack, `sourceID`. The database is staged
    /// beside `destination` (`.binario-foreign-<UUID>.sqlite`, removed on every path), excluded from
    /// backup, then moved into place. Nothing is written when a check fails.
    public func installVerified(
        compressed: Data,
        expected: ForeignSchedulePins.Artifact,
        sourceID: String,
        into destination: URL
    ) throws -> URL {
        Self.installationLock.lock()
        defer { Self.installationLock.unlock() }
        guard destination.isFileURL,
              compressed.count == expected.sizeBytes,
              compressed.count <= Self.maximumCompressedBytes,
              Self.sha256Hex(compressed) == expected.sha256
        else { throw BundledGTFSScheduleError.invalidCompressedArtifact }
        do {
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true,
                attributes: nil
            )
            let database = try Self.decompress(compressed, expectedBytes: expected.databaseSizeBytes)
            guard Self.sha256Hex(database) == expected.databaseSHA256 else {
                throw BundledGTFSScheduleError.invalidDatabase
            }
            return try Self.publishVerified(
                database: database,
                inputFingerprint: expected.inputFingerprintSHA256,
                onlySourceID: sourceID,
                stagedPrefix: Self.foreignStagedPrefix,
                destination: destination
            )
        } catch let error as BundledGTFSScheduleError {
            throw error
        } catch {
            throw BundledGTFSScheduleError.fileSystemFailure
        }
    }

    /// The hidden staged copy of a downloaded index, before it is moved into place.
    static let foreignStagedPrefix = ".binario-foreign-"

    /// Writes a database whose digest was checked to a staged copy excluded from backup, checks its
    /// schema and fingerprint (and its single pack when `onlySourceID` is given), and moves it to
    /// `destination`. Called under `installationLock`.
    private static func publishVerified(
        database: Data,
        inputFingerprint: String,
        onlySourceID: String?,
        stagedPrefix: String,
        destination: URL
    ) throws -> URL {
        let staged = destination.deletingLastPathComponent()
            .appendingPathComponent("\(stagedPrefix)\(UUID().uuidString).sqlite", isDirectory: false)
        defer { try? FileManager.default.removeItem(at: staged) }
        try database.write(to: staged, options: .atomic)
        try excludeFromBackup(staged)
        let valid: Bool
        if let onlySourceID {
            valid = SQLiteGTFSJourneyRepository.validatesDatabase(
                at: staged,
                inputFingerprint: inputFingerprint,
                onlySourceID: onlySourceID
            )
        } else {
            valid = SQLiteGTFSJourneyRepository.validatesDatabase(at: staged, inputFingerprint: inputFingerprint)
        }
        guard valid else { throw BundledGTFSScheduleError.invalidDatabase }

        if FileManager.default.fileExists(atPath: destination.path) {
            _ = try FileManager.default.replaceItemAt(
                destination,
                withItemAt: staged,
                backupItemName: nil,
                options: []
            )
        } else {
            try FileManager.default.moveItem(at: staged, to: destination)
        }
        try excludeFromBackup(destination)
        return withoutCachedResourceValues(destination)
    }

    private static func loadMetadata(from url: URL) throws -> Metadata {
        do {
            let data = try readBounded(url, maximumBytes: maximumMetadataBytes, expectedBytes: nil)
            let value = try JSONDecoder().decode(Metadata.self, from: data)
            guard value.schemaVersion == 1,
                  value.database.applicationId == Int(SQLiteGTFSJourneyRepository.applicationID),
                  value.database.userVersion == Int(SQLiteGTFSJourneyRepository.userVersion),
                  (1...maximumDatabaseBytes).contains(value.database.sizeBytes),
                  (1...maximumCompressedBytes).contains(value.compression.sizeBytes),
                  value.compression.format == "zlib",
                  value.compression.level == 9,
                  value.maximumCompressedSizeBytes == maximumCompressedBytes,
                  isSHA256(value.database.sha256),
                  isSHA256(value.compression.sha256),
                  isSHA256(value.inputs.fingerprintSha256)
            else { throw BundledGTFSScheduleError.invalidMetadata }
            return value
        } catch let error as BundledGTFSScheduleError {
            throw error
        } catch {
            throw BundledGTFSScheduleError.invalidMetadata
        }
    }

    private static func readBounded(_ url: URL, maximumBytes: Int, expectedBytes: Int?) throws -> Data {
        guard url.isFileURL,
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size >= 0,
              size <= maximumBytes,
              expectedBytes == nil || size == expectedBytes
        else { throw BundledGTFSScheduleError.invalidMetadata }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard data.count == size else { throw BundledGTFSScheduleError.invalidMetadata }
            return data
        } catch let error as BundledGTFSScheduleError {
            throw error
        } catch {
            throw BundledGTFSScheduleError.fileSystemFailure
        }
    }

    private static func decompress(_ compressed: Data, expectedBytes: Int) throws -> Data {
        guard expectedBytes > 0, expectedBytes <= maximumDatabaseBytes else {
            throw BundledGTFSScheduleError.invalidMetadata
        }
        var output = Data(count: expectedBytes)
        var outputLength = uLongf(expectedBytes)
        let status = output.withUnsafeMutableBytes { destinationBuffer in
            compressed.withUnsafeBytes { sourceBuffer in
                uncompress(
                    destinationBuffer.bindMemory(to: Bytef.self).baseAddress,
                    &outputLength,
                    sourceBuffer.bindMemory(to: Bytef.self).baseAddress,
                    uLong(compressed.count)
                )
            }
        }
        guard status == Z_OK, Int(outputLength) == expectedBytes else {
            throw BundledGTFSScheduleError.decompressionFailed
        }
        return output
    }

    private static func isSHA256(_ value: String) -> Bool {
        SHA256Digest.isHex(value)
    }

    /// The hidden staged copies (`.binario-gtfs-<UUID>.sqlite`) that `removeSupersededDatabases`
    /// never sees: it skips hidden files and matches the visible prefix only.
    /// No resource value is prefetched (`[]`, not `nil`): listing the cache with the default keys
    /// right before the fast path rewrites the backup flag of the cached index made that flag
    /// read back as cleared on CI (`testBundledInstallerVerifiesAndReusesAValidDatabase`).
    private static func removeStagedDatabases(in directory: URL) {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [],
            options: []
        )) ?? []
        for url in urls where url.lastPathComponent.hasPrefix(".binario-gtfs-") && url.pathExtension == "sqlite" {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private static func removeSupersededDatabases(in directory: URL, keeping: URL) {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        // Enumerated Foundation URLs may compare unequal to a constructed URL
        // for the same path on Darwin; never delete the database just published.
        for url in urls where url.standardizedFileURL.path != keeping.standardizedFileURL.path
            && url.lastPathComponent.hasPrefix("binario-gtfs-")
            && url.pathExtension == "sqlite" {
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// `destination` caches the resource values read while checking the size of an existing
    /// cache, including the backup flag as it was before `excludeFromBackup`. Callers get a copy
    /// without that cache, so they read the flag that is now on disk.
    static func withoutCachedResourceValues(_ url: URL) -> URL {
        // A new URL instance for the same path: copies of `url` share its underlying
        // reference and therefore its resource value cache.
        var fresh = URL(fileURLWithPath: url.path, isDirectory: false)
        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        fresh.removeAllCachedResourceValues()
        #endif
        return fresh
    }

    static func excludeFromBackup(_ url: URL) throws {
        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        // Write through a fresh URL with an empty cache: a cached "already excluded" value
        // from an earlier read must not let the flag be skipped after someone cleared it.
        // The flag is then read back from disk through another fresh URL and written again
        // if it did not stick (seen on CI right after the flag had been cleared), waiting a little
        // longer each time: at most about 1.5 s in all, and only when the flag does not stick.
        let pauses: [TimeInterval] = [0.05, 0.1, 0.2, 0.4, 0.8]
        for attempt in 0...pauses.count {
            var mutableURL = URL(fileURLWithPath: url.path, isDirectory: false)
            mutableURL.removeAllCachedResourceValues()
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try mutableURL.setResourceValues(values)
            if isExcludedFromBackupOnDisk(url) { return }
            if attempt < pauses.count { Thread.sleep(forTimeInterval: pauses[attempt]) }
        }
        #endif
    }

    /// The backup flag as stored now, read through a new URL instance with no cached values.
    static func isExcludedFromBackupOnDisk(_ url: URL) -> Bool {
        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        var fresh = URL(fileURLWithPath: url.path, isDirectory: false)
        fresh.removeAllCachedResourceValues()
        return (try? fresh.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) == true
        #else
        return false
        #endif
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256Digest.hex(data)
    }

    private struct Metadata: Decodable {
        let schemaVersion: Int
        let database: Database
        let compression: Compression
        let inputs: Inputs
        let maximumCompressedSizeBytes: Int

        struct Database: Decodable {
            let applicationId: Int
            let userVersion: Int
            let sha256: String
            let sizeBytes: Int
        }

        struct Compression: Decodable {
            let format: String
            let level: Int
            let sha256: String
            let sizeBytes: Int
        }

        struct Inputs: Decodable {
            let fingerprintSha256: String
        }
    }
}

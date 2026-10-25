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
            return destination
        }

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

            let staged = cacheDirectory.appendingPathComponent(".binario-gtfs-\(UUID().uuidString).sqlite")
            defer { try? FileManager.default.removeItem(at: staged) }
            try database.write(to: staged, options: .atomic)
            try Self.excludeFromBackup(staged)
            guard SQLiteGTFSJourneyRepository.validatesDatabase(
                at: staged,
                inputFingerprint: metadata.inputs.fingerprintSha256
            ) else {
                throw BundledGTFSScheduleError.invalidDatabase
            }

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
            try Self.excludeFromBackup(destination)
            Self.removeSupersededDatabases(in: cacheDirectory, keeping: destination)
            return destination
        } catch let error as BundledGTFSScheduleError {
            throw error
        } catch {
            throw BundledGTFSScheduleError.fileSystemFailure
        }
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
        value.count == 64 && value.utf8.allSatisfy { byte in
            (48...57).contains(byte) || (97...102).contains(byte)
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

    private static func excludeFromBackup(_ url: URL) throws {
        #if os(iOS) || os(macOS) || os(watchOS) || os(tvOS) || os(visionOS)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try mutableURL.setResourceValues(values)
        #endif
    }

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(Array(data)).map { String(format: "%02x", $0) }.joined()
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

private enum SHA256 {
    private static let initial: [UInt32] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ]
    private static let constants: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func hash(_ input: [UInt8]) -> [UInt8] {
        var message = input
        let bitLength = UInt64(message.count) * 8
        message.append(0x80)
        while message.count % 64 != 56 { message.append(0) }
        message.append(contentsOf: withUnsafeBytes(of: bitLength.bigEndian, Array.init))

        var state = initial
        var schedule = Array(repeating: UInt32(0), count: 64)
        for chunkStart in stride(from: 0, to: message.count, by: 64) {
            for index in 0..<16 {
                let offset = chunkStart + index * 4
                schedule[index] = UInt32(message[offset]) << 24
                    | UInt32(message[offset + 1]) << 16
                    | UInt32(message[offset + 2]) << 8
                    | UInt32(message[offset + 3])
            }
            for index in 16..<64 {
                let s0 = rotateRight(schedule[index - 15], 7)
                    ^ rotateRight(schedule[index - 15], 18)
                    ^ (schedule[index - 15] >> 3)
                let s1 = rotateRight(schedule[index - 2], 17)
                    ^ rotateRight(schedule[index - 2], 19)
                    ^ (schedule[index - 2] >> 10)
                schedule[index] = schedule[index - 16] &+ s0 &+ schedule[index - 7] &+ s1
            }

            var a = state[0], b = state[1], c = state[2], d = state[3]
            var e = state[4], f = state[5], g = state[6], h = state[7]
            for index in 0..<64 {
                let s1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
                let choice = (e & f) ^ ((~e) & g)
                let temporary1 = h &+ s1 &+ choice &+ constants[index] &+ schedule[index]
                let s0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
                let majority = (a & b) ^ (a & c) ^ (b & c)
                let temporary2 = s0 &+ majority
                h = g; g = f; f = e; e = d &+ temporary1
                d = c; c = b; b = a; a = temporary1 &+ temporary2
            }
            state[0] &+= a; state[1] &+= b; state[2] &+= c; state[3] &+= d
            state[4] &+= e; state[5] &+= f; state[6] &+= g; state[7] &+= h
        }
        return state.flatMap { word in
            let value = word.bigEndian
            return withUnsafeBytes(of: value, Array.init)
        }
    }

    private static func rotateRight(_ value: UInt32, _ count: UInt32) -> UInt32 {
        (value >> count) | (value << (32 - count))
    }
}

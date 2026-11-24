import CZlib
import Foundation
#if canImport(Compression)
import Compression
#endif

// A ZIP reader for the GTFS archives downloaded at runtime (European Sleeper, remote-gtfs-v1.json), written here with
// no third-party code. It reads the central directory, never the local headers' own sizes, and refuses an archive
// before inflating anything when it breaks a rule of the shared table (`SharedData.RemoteGtfs`): its size, the number
// of entries, a name that is not a bare file name or is repeated, a declared size over the entry or total limit, or a
// compression ratio no timetable has. The path rules are those of the Python builder's archives
// (tools/build_ios_gtfs_schedule.py, `BoundedGtfsArchive`): directories are skipped, every file is a bare name.
// DEFLATE is inflated with Apple's Compression framework (`COMPRESSION_ZLIB` is raw DEFLATE, RFC 1951), and every
// entry is checked against its declared size and CRC-32. The cases are contracts/shared-rules/v1/remote-gtfs-v1.json.

/// Why an archive or one of its entries is refused. Nothing of a refused archive is used.
public enum GTFSArchiveFailure: String, Error, Equatable, Sendable {
    /// The archive is larger than the table allows.
    case tooLarge
    /// Not a ZIP file: no end of central directory, or offsets and signatures that do not hold.
    case malformed
    case tooManyEntries
    /// A name with a directory part, a backslash, «.» or «..», control characters, or not UTF-8.
    case unsafeName
    case duplicateName
    /// One entry declares more bytes than an entry may have.
    case entryTooLarge
    /// The entries together declare more bytes than the table allows.
    case totalTooLarge
    /// An entry declares more bytes per compressed byte than the table allows.
    case ratioTooHigh
    /// Encryption, ZIP64, a split archive or a method other than stored and deflate.
    case unsupported
    /// The data does not inflate to the declared size and CRC-32.
    case corrupt
    /// A file every GTFS feed has is absent.
    case missingFiles
}

/// The limits an archive is read with: the shared table's (`SharedData.RemoteGtfs`).
public struct GTFSArchiveLimits: Equatable, Sendable {
    public var maximumArchiveBytes: Int
    public var maximumEntries: Int
    public var maximumEntryBytes: Int
    public var maximumTotalUncompressedBytes: Int
    public var maximumCompressionRatio: Int

    public init(
        maximumArchiveBytes: Int, maximumEntries: Int, maximumEntryBytes: Int,
        maximumTotalUncompressedBytes: Int, maximumCompressionRatio: Int
    ) {
        self.maximumArchiveBytes = maximumArchiveBytes
        self.maximumEntries = maximumEntries
        self.maximumEntryBytes = maximumEntryBytes
        self.maximumTotalUncompressedBytes = maximumTotalUncompressedBytes
        self.maximumCompressionRatio = maximumCompressionRatio
    }

    /// The limits of remote-gtfs-v1.json.
    public static let remote = GTFSArchiveLimits(
        maximumArchiveBytes: SharedData.RemoteGtfs.maximumArchiveBytes,
        maximumEntries: SharedData.RemoteGtfs.archive.maximumEntries,
        maximumEntryBytes: SharedData.RemoteGtfs.archive.maximumEntryBytes,
        maximumTotalUncompressedBytes: SharedData.RemoteGtfs.archive.maximumTotalUncompressedBytes,
        maximumCompressionRatio: SharedData.RemoteGtfs.archive.maximumCompressionRatio
    )
}

/// A ZIP archive held in memory, its central directory read and checked. `data(of:)` inflates one entry at a time.
public struct GTFSZipArchive: Sendable {
    /// The files GTFS requires, as the Python builder (`REQUIRED_FILES`), and one of the two calendars.
    public static let requiredFiles: [String] = ["agency.txt", "routes.txt", "trips.txt", "stops.txt", "stop_times.txt"]
    public static let calendarFiles: [String] = ["calendar.txt", "calendar_dates.txt"]

    struct Entry: Equatable, Sendable {
        let name: String
        let method: UInt16
        let crc: UInt32
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int
    }

    private let bytes: Data
    private let entries: [String: Entry]
    /// The file names, in the order of the central directory (directories left out).
    public let names: [String]

    /// Reads the central directory of `data` and checks every rule before anything is inflated.
    public init(_ data: Data, limits: GTFSArchiveLimits = .remote) throws {
        guard data.count <= limits.maximumArchiveBytes else { throw GTFSArchiveFailure.tooLarge }
        // A copy whose indices start at zero: a slice of a larger Data keeps its own.
        let bytes = Data(data)
        let directory = try Self.centralDirectory(bytes)
        guard directory.count <= limits.maximumEntries else { throw GTFSArchiveFailure.tooManyEntries }
        var cursor = directory.offset
        var entries: [String: Entry] = [:]
        var names: [String] = []
        var total = 0
        for _ in 0..<directory.count {
            guard cursor + 46 <= directory.end, Self.uint32(bytes, cursor) == 0x0201_4B50 else { throw GTFSArchiveFailure.malformed }
            let flags = Self.uint16(bytes, cursor + 8)
            let method = Self.uint16(bytes, cursor + 10)
            let crc = Self.uint32(bytes, cursor + 16)
            let compressed = Self.uint32(bytes, cursor + 20)
            let uncompressed = Self.uint32(bytes, cursor + 24)
            let nameLength = Int(Self.uint16(bytes, cursor + 28))
            let extraLength = Int(Self.uint16(bytes, cursor + 30))
            let commentLength = Int(Self.uint16(bytes, cursor + 32))
            let disk = Self.uint16(bytes, cursor + 34)
            let localOffset = Self.uint32(bytes, cursor + 42)
            let next = cursor + 46 + nameLength + extraLength + commentLength
            guard next <= directory.end else { throw GTFSArchiveFailure.malformed }
            let nameBytes = bytes.subdata(in: (cursor + 46)..<(cursor + 46 + nameLength))
            cursor = next
            // ZIP64 sizes or offsets, a split archive: nothing a timetable needs.
            guard compressed != 0xFFFF_FFFF, uncompressed != 0xFFFF_FFFF, localOffset != 0xFFFF_FFFF, disk == 0 else {
                throw GTFSArchiveFailure.unsupported
            }
            guard let name = String(data: nameBytes, encoding: .utf8) else { throw GTFSArchiveFailure.unsafeName }
            // A directory is skipped, as by the builder (`ZipInfo.is_dir`).
            if name.hasSuffix("/") {
                guard Self.isSafeDirectory(name) else { throw GTFSArchiveFailure.unsafeName }
                continue
            }
            guard Self.isBareFileName(name) else { throw GTFSArchiveFailure.unsafeName }
            guard entries[name] == nil else { throw GTFSArchiveFailure.duplicateName }
            guard flags & 0x0001 == 0, method == 0 || method == 8 else { throw GTFSArchiveFailure.unsupported }
            let size = Int(uncompressed)
            guard size <= limits.maximumEntryBytes else { throw GTFSArchiveFailure.entryTooLarge }
            total += size
            guard total <= limits.maximumTotalUncompressedBytes else { throw GTFSArchiveFailure.totalTooLarge }
            guard size <= max(Int(compressed), 1) * limits.maximumCompressionRatio else { throw GTFSArchiveFailure.ratioTooHigh }
            if method == 0, compressed != uncompressed { throw GTFSArchiveFailure.corrupt }
            entries[name] = Entry(
                name: name, method: method, crc: crc, compressedSize: Int(compressed), uncompressedSize: size,
                localHeaderOffset: Int(localOffset)
            )
            names.append(name)
        }
        self.bytes = bytes
        self.entries = entries
        self.names = names
    }

    /// True when the archive holds `name`.
    public func contains(_ name: String) -> Bool {
        entries[name] != nil
    }

    /// The declared size of `name`, nil when the archive does not hold it.
    public func size(of name: String) -> Int? {
        entries[name]?.uncompressedSize
    }

    /// Throws `missingFiles` unless every required file and one calendar are present (the builder's rule).
    public func requireGTFSFiles() throws {
        guard Self.requiredFiles.allSatisfy({ contains($0) }), Self.calendarFiles.contains(where: { contains($0) }) else {
            throw GTFSArchiveFailure.missingFiles
        }
    }

    /// The bytes of `name`, inflated and checked against its declared size and CRC-32; nil when there is no such file.
    public func data(of name: String) throws -> Data? {
        guard let entry = entries[name] else { return nil }
        let start = try dataStart(of: entry)
        guard start + entry.compressedSize <= bytes.count else { throw GTFSArchiveFailure.malformed }
        let compressed = bytes.subdata(in: start..<(start + entry.compressedSize))
        let output: Data
        if entry.method == 0 {
            output = compressed
        } else {
            output = try Self.inflated(compressed, expectedBytes: entry.uncompressedSize)
        }
        guard output.count == entry.uncompressedSize, Self.checksum(output) == entry.crc else { throw GTFSArchiveFailure.corrupt }
        return output
    }

    /// Where the data of `entry` begins: after its local header, whose own name and extra lengths count.
    private func dataStart(of entry: Entry) throws -> Int {
        let offset = entry.localHeaderOffset
        guard offset + 30 <= bytes.count, Self.uint32(bytes, offset) == 0x0403_4B50 else { throw GTFSArchiveFailure.malformed }
        let flags = Self.uint16(bytes, offset + 6)
        guard flags & 0x0001 == 0 else { throw GTFSArchiveFailure.unsupported }
        let nameLength = Int(Self.uint16(bytes, offset + 26))
        let extraLength = Int(Self.uint16(bytes, offset + 28))
        let start = offset + 30 + nameLength + extraLength
        guard start <= bytes.count else { throw GTFSArchiveFailure.malformed }
        return start
    }

    // MARK: - Central directory

    private struct Directory {
        let offset: Int
        let end: Int
        let count: Int
    }

    /// The end of central directory record, searched backwards over the last 22 + 65 535 bytes (its comment).
    private static func centralDirectory(_ bytes: Data) throws -> Directory {
        guard bytes.count >= 22 else { throw GTFSArchiveFailure.malformed }
        let lowest = max(0, bytes.count - 22 - 0xFFFF)
        var position = bytes.count - 22
        var found = false
        while position >= lowest {
            if uint32(bytes, position) == 0x0605_4B50, position + 22 + Int(uint16(bytes, position + 20)) <= bytes.count {
                found = true
                break
            }
            position -= 1
        }
        guard found else { throw GTFSArchiveFailure.malformed }
        let disk = uint16(bytes, position + 4)
        let directoryDisk = uint16(bytes, position + 6)
        let onDisk = uint16(bytes, position + 8)
        let total = uint16(bytes, position + 10)
        let size = uint32(bytes, position + 12)
        let offset = uint32(bytes, position + 16)
        // ZIP64 or a split archive.
        guard disk == 0, directoryDisk == 0, onDisk == total, total != 0xFFFF, size != 0xFFFF_FFFF, offset != 0xFFFF_FFFF else {
            throw GTFSArchiveFailure.unsupported
        }
        let start = Int(offset)
        let end = start + Int(size)
        guard end <= position else { throw GTFSArchiveFailure.malformed }
        return Directory(offset: start, end: end, count: Int(total))
    }

    /// A bare file name: no directory part, no backslash, not «.» or «..», no control character.
    static func isBareFileName(_ name: String) -> Bool {
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/"), !name.contains("\\") else { return false }
        return !name.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F }
    }

    /// A directory entry («feed/»): skipped, but never one that climbs («../») or starts at the root.
    private static func isSafeDirectory(_ name: String) -> Bool {
        let parts = name.dropLast().split(separator: "/", omittingEmptySubsequences: false)
        guard !name.hasPrefix("/"), !name.contains("\\"), !parts.isEmpty else { return false }
        return parts.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
            && !name.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F }
    }

    // MARK: - Inflating

    /// Raw DEFLATE to exactly `expectedBytes`: more or fewer is a corrupt entry.
    static func inflated(_ compressed: Data, expectedBytes: Int) throws -> Data {
        if expectedBytes == 0 { return Data() }
        guard !compressed.isEmpty else { throw GTFSArchiveFailure.corrupt }
        // One byte more than declared: a stream that fills it is longer than its entry says.
        let capacity = expectedBytes + 1
        var output = Data(count: capacity)
        let written: Int = output.withUnsafeMutableBytes { (destination: UnsafeMutableRawBufferPointer) -> Int in
            compressed.withUnsafeBytes { (source: UnsafeRawBufferPointer) -> Int in
                guard let target = destination.bindMemory(to: UInt8.self).baseAddress,
                      let input = source.bindMemory(to: UInt8.self).baseAddress else { return -1 }
                return decodeDeflate(target, capacity: capacity, input, count: compressed.count)
            }
        }
        guard written == expectedBytes else { throw GTFSArchiveFailure.corrupt }
        output.count = written
        return output
    }

    #if canImport(Compression)
    /// Apple's Compression: `COMPRESSION_ZLIB` decodes raw DEFLATE (no zlib header). 0 on an error.
    private static func decodeDeflate(
        _ target: UnsafeMutablePointer<UInt8>, capacity: Int, _ input: UnsafePointer<UInt8>, count: Int
    ) -> Int {
        compression_decode_buffer(target, capacity, input, count, nil, COMPRESSION_ZLIB)
    }
    #else
    /// Where the Compression framework does not exist (the Linux build of the package): zlib's raw inflate.
    private static func decodeDeflate(
        _ target: UnsafeMutablePointer<UInt8>, capacity: Int, _ input: UnsafePointer<UInt8>, count: Int
    ) -> Int {
        var stream = z_stream()
        guard inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return -1 }
        defer { inflateEnd(&stream) }
        stream.next_in = UnsafeMutablePointer(mutating: input)
        stream.avail_in = uInt(count)
        stream.next_out = target
        stream.avail_out = uInt(capacity)
        let status = inflate(&stream, Z_FINISH)
        guard status == Z_STREAM_END || status == Z_BUF_ERROR || status == Z_OK else { return -1 }
        return capacity - Int(stream.avail_out)
    }
    #endif

    /// The CRC-32 of ZIP (zlib's `crc32`), in chunks that fit its 32-bit length.
    static func checksum(_ data: Data) -> UInt32 {
        var crc: uLong = crc32(0, nil, 0)
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            guard let base = buffer.bindMemory(to: Bytef.self).baseAddress else { return }
            var offset = 0
            while offset < buffer.count {
                let length = min(buffer.count - offset, 1 << 30)
                crc = crc32(crc, base + offset, uInt(length))
                offset += length
            }
        }
        return UInt32(truncatingIfNeeded: crc)
    }

    // MARK: - Little-endian fields

    static func uint16(_ bytes: Data, _ offset: Int) -> UInt16 {
        guard offset >= 0, offset + 2 <= bytes.count else { return 0 }
        return UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8
    }

    static func uint32(_ bytes: Data, _ offset: Int) -> UInt32 {
        guard offset >= 0, offset + 4 <= bytes.count else { return 0 }
        return UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16
            | UInt32(bytes[offset + 3]) << 24
    }
}

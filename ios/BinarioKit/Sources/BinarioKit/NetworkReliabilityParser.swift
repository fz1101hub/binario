import CZlib
import Foundation

/// Reader of the bundled `binario_punctuality_stats_v1.json.gz`. Tolerant: an absent, corrupt,
/// oversized or other-schema file gives nil (the feature is hidden), a train whose fields do not
/// add up is left out, and an optional stop value that is not plausible is ignored without losing
/// the stop. Bounded: compressed and decompressed size, nesting, trains, stops and buckets.
/// Stop arrays of 5, 7 and 9 elements are valid; elements 5 to 8 may be `null`.
public enum NetworkReliabilityParser {
    public static let schema = "binario-punctuality-v1"
    public static let resourceName = "binario_punctuality_stats_v1.json"
    public static let resourceExtension = "gz"

    public struct Limits: Sendable {
        public var maximumCompressedBytes: Int
        public var maximumJSONBytes: Int
        /// The pipeline's own bounds (tools/build_punctuality_stats.py): 50,000 trains, 400 stops per train.
        public var maximumTrains: Int
        public var maximumStopsPerTrain: Int
        public var maximumBuckets: Int
        /// Samples and days per entry: far above any window, and within an Int16.
        public var maximumCount: Int

        public init(
            maximumCompressedBytes: Int = 8 * 1_024 * 1_024,
            maximumJSONBytes: Int = 64 * 1_024 * 1_024,
            maximumTrains: Int = 50_000,
            maximumStopsPerTrain: Int = 400,
            maximumBuckets: Int = 16,
            maximumCount: Int = 10_000
        ) {
            self.maximumCompressedBytes = maximumCompressedBytes
            self.maximumJSONBytes = maximumJSONBytes
            self.maximumTrains = maximumTrains
            self.maximumStopsPerTrain = maximumStopsPerTrain
            self.maximumBuckets = maximumBuckets
            self.maximumCount = maximumCount
        }
    }

    static let maximumText = 16
    static let maximumDelayMinutes = 24 * 60
    static let maximumWindowDays = 366
    /// A run that reaches a stop up to two days after its service day's midnight still names its minute.
    static let maximumScheduledMinute = 3 * 24 * 60
    static let maximumDepth = 16

    /// The statistics of the gzip file at `url`, or nil.
    public static func parseGzipFile(at url: URL, limits: Limits = Limits()) -> NetworkReliabilityStats? {
        guard url.isFileURL,
              let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size > 0, size <= limits.maximumCompressedBytes,
              let data = try? Data(contentsOf: url, options: .mappedIfSafe),
              data.count == size
        else { return nil }
        return parseGzip(data, limits: limits)
    }

    /// The statistics of a gzip document, or nil.
    public static func parseGzip(_ data: Data, limits: Limits = Limits()) -> NetworkReliabilityStats? {
        guard data.count <= limits.maximumCompressedBytes,
              let json = gunzip(data, maximumBytes: limits.maximumJSONBytes)
        else { return nil }
        return parse(json: json, limits: limits)
    }

    /// The statistics of an uncompressed JSON document, or nil.
    public static func parse(json: [UInt8], limits: Limits = Limits()) -> NetworkReliabilityStats? {
        guard json.count <= limits.maximumJSONBytes else { return nil }
        do {
            return try read(json, limits: limits)
        } catch {
            return nil
        }
    }

    // MARK: - gzip

    /// The content of a single-member gzip stream, or nil when it is corrupt, truncated, followed
    /// by other data or larger than `maximumBytes`.
    static func gunzip(_ data: Data, maximumBytes: Int) -> [UInt8]? {
        guard !data.isEmpty else { return nil }
        var stream = z_stream()
        // 15 window bits plus 16: a gzip wrapper (RFC 1952) only, not raw deflate or zlib.
        guard inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
            return nil
        }
        defer { _ = inflateEnd(&stream) }
        var output: [UInt8] = []
        var chunk = [UInt8](repeating: 0, count: 64 * 1_024)
        let status: Int32 = data.withUnsafeBytes { (input: UnsafeRawBufferPointer) -> Int32 in
            guard let base = input.bindMemory(to: Bytef.self).baseAddress else { return Z_DATA_ERROR }
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: base)
            stream.avail_in = uInt(input.count)
            var result: Int32 = Z_OK
            while result != Z_STREAM_END {
                let produced: Int = chunk.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) -> Int in
                    stream.next_out = buffer.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(buffer.count)
                    result = inflate(&stream, Z_NO_FLUSH)
                    return buffer.count - Int(stream.avail_out)
                }
                guard result == Z_OK || result == Z_STREAM_END else { return result }
                guard output.count + produced <= maximumBytes else { return Z_BUF_ERROR }
                output.append(contentsOf: chunk[0..<produced])
                // No progress with all the input read: the stream is truncated.
                if result == Z_OK, produced == 0, stream.avail_in == 0 { return Z_DATA_ERROR }
            }
            stream.next_in = nil
            stream.next_out = nil
            return stream.avail_in == 0 ? Z_STREAM_END : Z_DATA_ERROR
        }
        guard status == Z_STREAM_END else { return nil }
        return output
    }

    // MARK: - Document

    private static func read(_ bytes: [UInt8], limits: Limits) throws -> NetworkReliabilityStats? {
        var reader = ReliabilityJSONReader(bytes: bytes, maximumDepth: maximumDepth)
        var schema: String?
        var generatedDay: NetworkReliabilityDay?
        var windowStart: NetworkReliabilityDay?
        var windowEnd: NetworkReliabilityDay?
        var windowDays: Int?
        var buckets: [Int]?
        // Trains read after the buckets (the pipeline's order) are made compact at once; earlier ones wait for them.
        var built: [NetworkReliabilityTrain] = []
        var pending: [RawTrain] = []
        var builtWith: [Int]?
        var entries = 0
        var codes: [String: String] = [:]

        try reader.beginObject()
        while let key = try reader.nextKey() {
            switch key {
            case "schema": schema = try reader.readValue(depth: 2).string
            case "generatedAt":
                generatedDay = try reader.readValue(depth: 2).string.flatMap(dayOfInstant)
            case "windowStart": windowStart = try reader.readValue(depth: 2).string.flatMap { NetworkReliabilityDay(iso: $0) }
            case "windowEnd": windowEnd = try reader.readValue(depth: 2).string.flatMap { NetworkReliabilityDay(iso: $0) }
            case "windowDays": windowDays = try reader.readValue(depth: 2).integer
            case "buckets": buckets = try integers(reader.readValue(depth: 2), maximum: limits.maximumBuckets)
            case "trains":
                guard try reader.beginArrayIfPresent() else {
                    _ = try reader.readValue(depth: 2)
                    continue
                }
                while try reader.hasNextElement() {
                    entries += 1
                    guard entries <= limits.maximumTrains else { throw ReliabilityJSONReader.Failure.limit }
                    guard let raw = try rawTrain(reader.readValue(depth: 3), limits: limits, codes: &codes) else { continue }
                    if let known = buckets.flatMap(validBuckets) {
                        if let train = raw.build(buckets: known) { built.append(train) }
                        builtWith = known
                    } else {
                        pending.append(raw)
                    }
                }
            default:
                _ = try reader.readValue(depth: 2)
            }
        }
        try reader.endDocument()

        guard schema == Self.schema, let limitsInMinutes = buckets.flatMap(validBuckets),
              let end = windowEnd ?? generatedDay else { return nil }
        let days: Int
        if let windowDays {
            days = windowDays
        } else if let windowStart, let span = windowStart.days(until: end) {
            days = span + 1
        } else {
            return nil
        }
        guard (1...maximumWindowDays).contains(days) else { return nil }
        // Buckets given twice, differently: the trains already read do not match them.
        if let builtWith, builtWith != limitsInMinutes { return nil }
        let trains = built + pending.compactMap { $0.build(buckets: limitsInMinutes) }
        return NetworkReliabilityStats(windowStart: windowStart, windowEnd: end, windowDays: days, buckets: limitsInMinutes, trains: trains)
    }

    private static func dayOfInstant(_ text: String) -> NetworkReliabilityDay? {
        let formatter = ISO8601DateFormatter()
        guard let instant = formatter.date(from: text) else { return nil }
        return NetworkReliabilityDay(instant: instant)
    }

    private static func validBuckets(_ buckets: [Int]) -> [Int]? {
        guard let first = buckets.first, first >= 0 else { return nil }
        for index in buckets.indices.dropFirst() where buckets[index] <= buckets[index - 1] { return nil }
        return buckets
    }

    /// An array of integers, or nil when the value is not one or holds more than `maximum`.
    private static func integers(_ value: ReliabilityJSONValue, maximum: Int) -> [Int]? {
        guard case let .array(items) = value, items.count <= maximum else { return nil }
        var values: [Int] = []
        values.reserveCapacity(items.count)
        for item in items {
            guard let integer = item.integer else { return nil }
            values.append(integer)
        }
        return values
    }

    private static func text(_ value: ReliabilityJSONValue?) -> String? {
        value?.string?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func rawTrain(_ value: ReliabilityJSONValue, limits: Limits, codes: inout [String: String]) -> RawTrain? {
        guard case let .object(fields) = value else { return nil }
        guard let number = text(fields["n"]), !number.isEmpty, number.utf16.count <= maximumText,
              let origin = text(fields["o"]), !origin.isEmpty, origin.utf16.count <= maximumText,
              let observed = fields["d"]?.integer, (0...limits.maximumCount).contains(observed)
        else { return nil }
        let category = (text(fields["c"]) ?? "").uppercased()
        let cancelled = fields["x"].map { $0.integer ?? -1 } ?? 0
        guard category.utf16.count <= maximumText, (0...observed).contains(cancelled),
              let stopsValue = fields["s"], let stops = self.stops(stopsValue, limits: limits, codes: &codes)
        else { return nil }
        // A train cancelled on every day observed has no stops; any other train needs at least one.
        guard !stops.isEmpty || (observed > 0 && cancelled == observed) else { return nil }
        let originCode = codes[origin] ?? origin
        codes[origin] = originCode
        return RawTrain(number: number, origin: originCode, category: category, days: observed, cancelled: cancelled, stops: stops)
    }

    /// The stops of a train, or nil when one of them is malformed (the whole train is then left out).
    private static func stops(_ value: ReliabilityJSONValue, limits: Limits, codes: inout [String: String]) -> [RawStop]? {
        guard case let .array(items) = value, items.count <= limits.maximumStopsPerTrain else { return nil }
        var stops: [RawStop] = []
        stops.reserveCapacity(items.count)
        for item in items {
            guard let stop = self.stop(item, limits: limits, codes: &codes) else { return nil }
            stops.append(stop)
        }
        return stops
    }

    private static func stop(_ value: ReliabilityJSONValue, limits: Limits, codes: inout [String: String]) -> RawStop? {
        guard case let .array(items) = value, items.count >= 4 else { return nil }
        guard let code = text(items[0]), !code.isEmpty, code.utf16.count <= maximumText,
              let samples = items[1].integer, (0...limits.maximumCount).contains(samples),
              let mean = items[2].number, mean.isFinite,
              mean >= -Double(maximumDelayMinutes), mean <= Double(maximumDelayMinutes),
              let cumulative = integers(items[3], maximum: limits.maximumBuckets)
        else { return nil }
        let suppressed = items.count > 4 ? items[4].integer : 0
        guard let suppressed, (0...limits.maximumCount).contains(suppressed),
              cumulative.allSatisfy({ (0...samples).contains($0) }),
              zip(cumulative, cumulative.dropFirst()).allSatisfy({ $0 <= $1 })
        else { return nil }
        // Optional since the 4 Nov 2026 extensions: a value that is not plausible is left out, not the stop.
        let maxDelay = items.count > 5 ? items[5].integer.flatMap { (0...maximumDelayMinutes).contains($0) ? $0 : nil } : nil
        let scheduled = items.count > 6 ? items[6].integer.flatMap { (0..<maximumScheduledMinute).contains($0) ? $0 : nil } : nil
        let platform = items.count > 7 ? self.platform(items[7], limits: limits) : nil
        let weekdays = items.count > 8 ? self.weekdays(items[8], limits: limits) : nil
        // Later elements of a newer pipeline are ignored.
        let stationCode = codes[code] ?? code
        codes[code] = stationCode
        return RawStop(
            code: stationCode,
            samples: samples,
            meanTenths: NetworkReliability.roundedToInt(mean * 10),
            cumulative: cumulative,
            suppressed: suppressed,
            maxDelay: maxDelay,
            scheduledMinute: scheduled,
            platform: platform,
            weekdays: weekdays
        )
    }

    /// `[name, count, observedWithPlatform]`, or nil for `null` or an implausible value.
    private static func platform(_ value: ReliabilityJSONValue, limits: Limits) -> RawPlatform? {
        guard case let .array(items) = value, items.count == 3,
              let name = text(items[0]), !name.isEmpty, name.utf16.count <= maximumText,
              let count = items[1].integer, let observed = items[2].integer,
              count >= 0, count <= observed, observed <= limits.maximumCount
        else { return nil }
        return RawPlatform(name: name, count: count, observed: observed)
    }

    /// Seven `[samples, le5, le15]` triples for Monday…Sunday, or nil for `null` or an implausible value.
    private static func weekdays(_ value: ReliabilityJSONValue, limits: Limits) -> [Int]? {
        guard case let .array(days) = value, days.count == 7 else { return nil }
        var counts: [Int] = []
        counts.reserveCapacity(21)
        for day in days {
            guard let triple = integers(day, maximum: 3), triple.count == 3,
                  triple[1] >= 0, triple[1] <= triple[2], triple[2] <= triple[0], triple[0] <= limits.maximumCount
            else { return nil }
            counts.append(contentsOf: triple)
        }
        return counts
    }

    private struct RawPlatform {
        let name: String
        let count: Int
        let observed: Int
    }

    private struct RawStop {
        let code: String
        let samples: Int
        let meanTenths: Int
        let cumulative: [Int]
        let suppressed: Int
        let maxDelay: Int?
        let scheduledMinute: Int?
        let platform: RawPlatform?
        let weekdays: [Int]?
    }

    private struct RawTrain {
        let number: String
        let origin: String
        let category: String
        let days: Int
        let cancelled: Int
        let stops: [RawStop]

        /// The compact train, or nil when a stop does not have one count per bucket limit.
        func build(buckets: [Int]) -> NetworkReliabilityTrain? {
            guard stops.allSatisfy({ $0.cumulative.count == buckets.count }) else { return nil }
            let hasWeekdays = stops.contains { $0.weekdays != nil }
            var cumulative: [Int16] = []
            cumulative.reserveCapacity(stops.count * buckets.count)
            var platformCounts: [Int16] = []
            platformCounts.reserveCapacity(stops.count * 2)
            var weekdayCounts: [Int16] = []
            if hasWeekdays { weekdayCounts.reserveCapacity(stops.count * 21) }
            for stop in stops {
                cumulative.append(contentsOf: stop.cumulative.map { Int16(clamping: $0) })
                platformCounts.append(Int16(clamping: stop.platform?.count ?? 0))
                platformCounts.append(Int16(clamping: stop.platform?.observed ?? 0))
                if hasWeekdays {
                    if let weekdays = stop.weekdays {
                        weekdayCounts.append(contentsOf: weekdays.map { Int16(clamping: $0) })
                    } else {
                        weekdayCounts.append(contentsOf: repeatElement(Int16(-1), count: 21))
                    }
                }
            }
            return NetworkReliabilityTrain(
                number: number,
                originCode: origin,
                category: category,
                daysObserved: days,
                daysCancelled: cancelled,
                codes: stops.map(\.code),
                sampleCounts: stops.map { Int16(clamping: $0.samples) },
                meanTenths: stops.map { Int32(clamping: $0.meanTenths) },
                cumulativeCounts: cumulative,
                suppressedCounts: stops.map { Int16(clamping: $0.suppressed) },
                maxDelays: stops.map { Int16(clamping: $0.maxDelay ?? -1) },
                scheduledMinutes: stops.map { Int16(clamping: $0.scheduledMinute ?? -1) },
                platformNames: stops.map { $0.platform?.name },
                platformCounts: platformCounts,
                weekdayCounts: weekdayCounts,
                buckets: buckets
            )
        }
    }
}

/// A JSON value of one train or one top-level field: the trains array itself is streamed.
enum ReliabilityJSONValue {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([ReliabilityJSONValue])
    case object([String: ReliabilityJSONValue])

    var string: String? {
        if case let .string(value) = self { return value }
        return nil
    }

    var number: Double? {
        if case let .number(value) = self { return value }
        return nil
    }

    /// The value when it is a whole number within ±2^31, else nil (a fraction or another type).
    var integer: Int? {
        guard case let .number(value) = self, value.isFinite, value == value.rounded(),
              value >= -2_147_483_648, value <= 2_147_483_647 else { return nil }
        return Int(value)
    }
}

/// A strict, bounded JSON reader over UTF-8 bytes: the top-level object and the trains array are
/// read key by key and element by element, every other value as a whole.
struct ReliabilityJSONReader {
    enum Failure: Error {
        case malformed
        case tooDeep
        case limit
    }

    private let bytes: [UInt8]
    private let maximumDepth: Int
    private var position = 0
    private var objectStarted = false
    private var objectHasMembers = false
    private var arrayHasElements = false

    init(bytes: [UInt8], maximumDepth: Int) {
        self.bytes = bytes
        self.maximumDepth = maximumDepth
    }

    mutating func beginObject() throws {
        try expect(UInt8(ascii: "{"))
        objectStarted = true
        objectHasMembers = false
    }

    /// The next key of the top-level object, positioned on its value, or nil after its `}`.
    mutating func nextKey() throws -> String? {
        guard objectStarted else { throw Failure.malformed }
        guard let byte = peekByte() else { throw Failure.malformed }
        if byte == UInt8(ascii: "}") {
            position += 1
            objectStarted = false
            return nil
        }
        if objectHasMembers {
            try expect(UInt8(ascii: ","))
        }
        objectHasMembers = true
        let key = try readString()
        try expect(UInt8(ascii: ":"))
        return key
    }

    /// Opens an array value when the next value is one; false leaves a value of another type unread.
    mutating func beginArrayIfPresent() throws -> Bool {
        guard peekByte() == UInt8(ascii: "[") else { return false }
        position += 1
        arrayHasElements = false
        return true
    }

    /// True when the open top-level array has another element, positioned on it; false after its `]`.
    mutating func hasNextElement() throws -> Bool {
        guard let byte = peekByte() else { throw Failure.malformed }
        if byte == UInt8(ascii: "]") {
            position += 1
            return false
        }
        if arrayHasElements {
            try expect(UInt8(ascii: ","))
        }
        arrayHasElements = true
        return true
    }

    /// Nothing but whitespace may follow the document.
    mutating func endDocument() throws {
        guard peekByte() == nil else { throw Failure.malformed }
    }

    mutating func readValue(depth: Int) throws -> ReliabilityJSONValue {
        guard depth <= maximumDepth else { throw Failure.tooDeep }
        guard let byte = peekByte() else { throw Failure.malformed }
        switch byte {
        case UInt8(ascii: "{"):
            position += 1
            var fields: [String: ReliabilityJSONValue] = [:]
            if peekByte() == UInt8(ascii: "}") {
                position += 1
                return .object(fields)
            }
            while true {
                let key = try readString()
                try expect(UInt8(ascii: ":"))
                fields[key] = try readValue(depth: depth + 1)
                guard let next = peekByte() else { throw Failure.malformed }
                position += 1
                if next == UInt8(ascii: "}") { return .object(fields) }
                guard next == UInt8(ascii: ",") else { throw Failure.malformed }
            }
        case UInt8(ascii: "["):
            position += 1
            var items: [ReliabilityJSONValue] = []
            if peekByte() == UInt8(ascii: "]") {
                position += 1
                return .array(items)
            }
            while true {
                try items.append(readValue(depth: depth + 1))
                guard let next = peekByte() else { throw Failure.malformed }
                position += 1
                if next == UInt8(ascii: "]") { return .array(items) }
                guard next == UInt8(ascii: ",") else { throw Failure.malformed }
            }
        case UInt8(ascii: "\""):
            return try .string(readString())
        case UInt8(ascii: "t"):
            try readLiteral("true")
            return .bool(true)
        case UInt8(ascii: "f"):
            try readLiteral("false")
            return .bool(false)
        case UInt8(ascii: "n"):
            try readLiteral("null")
            return .null
        default:
            return try .number(readNumber())
        }
    }

    /// The next byte after whitespace, without consuming it; nil at the end.
    private mutating func peekByte() -> UInt8? {
        while position < bytes.count {
            switch bytes[position] {
            case 0x20, 0x09, 0x0A, 0x0D: position += 1
            default: return bytes[position]
            }
        }
        return nil
    }

    private mutating func expect(_ byte: UInt8) throws {
        guard peekByte() == byte else { throw Failure.malformed }
        position += 1
    }

    private mutating func readLiteral(_ literal: StaticString) throws {
        let expected = literal.withUTF8Buffer { Array($0) }
        guard position + expected.count <= bytes.count, Array(bytes[position..<(position + expected.count)]) == expected else {
            throw Failure.malformed
        }
        position += expected.count
    }

    private mutating func readNumber() throws -> Double {
        let start = position
        if position < bytes.count, bytes[position] == UInt8(ascii: "-") { position += 1 }
        let integerStart = position
        while position < bytes.count, (48...57).contains(bytes[position]) { position += 1 }
        guard position > integerStart else { throw Failure.malformed }
        // No leading zeros: "01" is not JSON.
        if position - integerStart > 1, bytes[integerStart] == UInt8(ascii: "0") { throw Failure.malformed }
        if position < bytes.count, bytes[position] == UInt8(ascii: ".") {
            position += 1
            let fractionStart = position
            while position < bytes.count, (48...57).contains(bytes[position]) { position += 1 }
            guard position > fractionStart else { throw Failure.malformed }
        }
        if position < bytes.count, bytes[position] == UInt8(ascii: "e") || bytes[position] == UInt8(ascii: "E") {
            position += 1
            if position < bytes.count, bytes[position] == UInt8(ascii: "+") || bytes[position] == UInt8(ascii: "-") { position += 1 }
            let exponentStart = position
            while position < bytes.count, (48...57).contains(bytes[position]) { position += 1 }
            guard position > exponentStart else { throw Failure.malformed }
        }
        guard let value = Double(String(decoding: bytes[start..<position], as: UTF8.self)), value.isFinite else {
            throw Failure.malformed
        }
        return value
    }

    private mutating func readString() throws -> String {
        guard peekByte() == UInt8(ascii: "\"") else { throw Failure.malformed }
        position += 1
        var buffer: [UInt8] = []
        while position < bytes.count {
            let byte = bytes[position]
            position += 1
            switch byte {
            case UInt8(ascii: "\""):
                return String(decoding: buffer, as: UTF8.self)
            case UInt8(ascii: "\\"):
                guard position < bytes.count else { throw Failure.malformed }
                let escaped = bytes[position]
                position += 1
                switch escaped {
                case UInt8(ascii: "\""), UInt8(ascii: "\\"), UInt8(ascii: "/"): buffer.append(escaped)
                case UInt8(ascii: "b"): buffer.append(0x08)
                case UInt8(ascii: "f"): buffer.append(0x0C)
                case UInt8(ascii: "n"): buffer.append(0x0A)
                case UInt8(ascii: "r"): buffer.append(0x0D)
                case UInt8(ascii: "t"): buffer.append(0x09)
                case UInt8(ascii: "u"):
                    var scalarValue = try UInt32(readHex4())
                    if (0xD800...0xDBFF).contains(scalarValue) {
                        // A high surrogate must be followed by its low surrogate.
                        guard position + 1 < bytes.count, bytes[position] == UInt8(ascii: "\\"), bytes[position + 1] == UInt8(ascii: "u") else {
                            throw Failure.malformed
                        }
                        position += 2
                        let low = try UInt32(readHex4())
                        guard (0xDC00...0xDFFF).contains(low) else { throw Failure.malformed }
                        scalarValue = 0x10000 + ((scalarValue - 0xD800) << 10) + (low - 0xDC00)
                    }
                    guard let scalar = Unicode.Scalar(scalarValue) else { throw Failure.malformed }
                    UTF8.encode(scalar) { buffer.append($0) }
                default:
                    throw Failure.malformed
                }
            default:
                guard byte >= 0x20 else { throw Failure.malformed }
                buffer.append(byte)
            }
        }
        throw Failure.malformed
    }

    private mutating func readHex4() throws -> UInt16 {
        guard position + 4 <= bytes.count else { throw Failure.malformed }
        var value: UInt16 = 0
        for _ in 0..<4 {
            let byte = bytes[position]
            position += 1
            let digit: UInt8
            switch byte {
            case 48...57: digit = byte - 48
            case 65...70: digit = byte - 55
            case 97...102: digit = byte - 87
            default: throw Failure.malformed
            }
            value = value << 4 | UInt16(digit)
        }
        return value
    }
}

/// Reads the bundled statistics once, off the calling actor.
public enum NetworkReliabilityLoader {
    public static func load(url: URL?) async -> NetworkReliabilityStats? {
        guard let url else { return nil }
        return await Task.detached(priority: .utility) {
            NetworkReliabilityParser.parseGzipFile(at: url)
        }.value
    }
}

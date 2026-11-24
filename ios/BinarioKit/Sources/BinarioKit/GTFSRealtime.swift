import Foundation

// GTFS-Realtime Trip Updates (gtfs-realtime.proto, proto2) as Android reads them through the official bindings
// (`com.google.transit.realtime.GtfsRealtime`): only the fields Binario reads, decoded by hand from the protobuf wire
// format. The cases both platforms read are contracts/shared-rules/v1/gtfs-realtime-v1.json.

/// FeedMessage: the header and the entities. `timestamp` is FeedHeader.timestamp as Java holds a uint64 (a value past
/// `Int64.max` is negative), nil when the header has none.
public struct GTFSRealtimeFeed: Equatable, Sendable {
    public let version: String
    public let timestamp: Int64?
    public let entities: [GTFSRealtimeEntity]
}

public struct GTFSRealtimeEntity: Equatable, Sendable {
    public let id: String
    public let isDeleted: Bool
    public let tripUpdate: GTFSRealtimeTripUpdate?
}

public struct GTFSRealtimeTripUpdate: Equatable, Sendable {
    public internal(set) var trip: GTFSRealtimeTripDescriptor
    /// TripUpdate.timestamp, nil when absent.
    public internal(set) var timestamp: Int64?
    /// Empty for a trip the caller did not keep (`GTFSRealtimeDecoder.decode(_:keepTrip:)`).
    public internal(set) var stopTimeUpdates: [GTFSRealtimeStopTimeUpdate]
}

/// TripDescriptor. Absent strings read as empty, as the bindings' getters return them.
public struct GTFSRealtimeTripDescriptor: Equatable, Sendable {
    public internal(set) var tripID = ""
    public internal(set) var routeID = ""
    public internal(set) var startDate = ""
    public internal(set) var scheduleRelationship = GTFSRealtimeTripRelationship.scheduled
}

/// TripDescriptor.ScheduleRelationship. A number the schema does not know leaves the field as it was (unset reads
/// as SCHEDULED), as proto2 keeps it among the unknown fields.
public enum GTFSRealtimeTripRelationship: Int32, Sendable {
    case scheduled = 0
    case added = 1
    case unscheduled = 2
    case canceled = 3
    case replacement = 5
    case duplicated = 6
    case deleted = 7
    case new = 8
}

/// TripUpdate.StopTimeUpdate.ScheduleRelationship, with the same rule for unknown numbers.
public enum GTFSRealtimeStopRelationship: Int32, Sendable {
    case scheduled = 0
    case skipped = 1
    case noData = 2
    case unscheduled = 3
}

public struct GTFSRealtimeStopTimeUpdate: Equatable, Sendable {
    /// stop_sequence as Java holds a uint32 (an int), nil when absent.
    public internal(set) var stopSequence: Int32?
    public internal(set) var stopID = ""
    public internal(set) var arrival: GTFSRealtimeStopTimeEvent?
    public internal(set) var departure: GTFSRealtimeStopTimeEvent?
    public internal(set) var scheduleRelationship = GTFSRealtimeStopRelationship.scheduled
}

/// StopTimeEvent: `delay` in seconds and the absolute `time` in POSIX seconds, each nil when absent.
public struct GTFSRealtimeStopTimeEvent: Equatable, Sendable {
    public internal(set) var delay: Int32?
    public internal(set) var time: Int64?
}

/// The protobuf decoder of a Trip Updates feed. Malformed input is no data (nil), as the bindings' `parseFrom` throws
/// on it: truncated or invalid wire data, a varint over ten bytes, a negative or overlong length, field number zero,
/// wire types 6 and 7, an unmatched end of group, a missing required field (the header and its version, an entity's
/// id, a trip update's trip), and here also a feed past `Limits`. Unknown fields and known fields with another wire
/// type are skipped; a message field given twice is merged, a scalar field given twice keeps the last value. The
/// messages Binario does not read (vehicles, alerts, shapes, trip and stop properties) are skipped unread, so their
/// own required fields are not checked.
public enum GTFSRealtimeDecoder {
    public struct Limits: Equatable, Sendable {
        public var maximumBytes: Int
        public var maximumEntities: Int
        public var maximumStopTimeUpdatesPerTrip: Int
        public var maximumTextBytes: Int

        public init(
            maximumBytes: Int = 32 * 1_048_576, maximumEntities: Int = 250_000,
            maximumStopTimeUpdatesPerTrip: Int = 5_000, maximumTextBytes: Int = 4_096
        ) {
            self.maximumBytes = maximumBytes
            self.maximumEntities = maximumEntities
            self.maximumStopTimeUpdatesPerTrip = maximumStopTimeUpdatesPerTrip
            self.maximumTextBytes = maximumTextBytes
        }
    }

    /// The feed in `data`, nil when it is malformed or past `limits`. Every entity is read and checked; the stop time
    /// updates are kept only for the trips `keepTrip` keeps, so a national feed costs only the trips asked about.
    public static func decode(
        _ data: Data, limits: Limits = Limits(), keepTrip: (String) -> Bool = { _ in true }
    ) -> GTFSRealtimeFeed? {
        guard data.count <= limits.maximumBytes else { return nil }
        let bytes = [UInt8](data)
        let parser = Parser(bytes: bytes, limits: limits)
        return try? parser.feed(keepTrip: keepTrip)
    }

    private enum Malformed: Error {
        case invalid
    }

    private struct Parser {
        let bytes: [UInt8]
        let limits: Limits

        // MARK: Messages

        func feed(keepTrip: (String) -> Bool) throws -> GTFSRealtimeFeed {
            var reader = Reader(bytes: bytes, range: 0..<bytes.count)
            var header: (version: String?, timestamp: Int64?)?
            var entities: [GTFSRealtimeEntity] = []
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 2):
                    let range = try reader.lengthDelimited()
                    header = try self.header(range, merging: header ?? (nil, nil))
                case (2, 2):
                    guard entities.count < limits.maximumEntities else { throw Malformed.invalid }
                    entities.append(try entity(try reader.lengthDelimited(), keepTrip: keepTrip))
                default:
                    try reader.skip(field: next.field, wire: next.wire)
                }
            }
            // Required: FeedMessage.header and FeedHeader.gtfs_realtime_version (an empty version is set).
            guard let header, let version = header.version else { throw Malformed.invalid }
            return GTFSRealtimeFeed(version: version, timestamp: header.timestamp, entities: entities)
        }

        func header(_ range: Range<Int>, merging start: (version: String?, timestamp: Int64?)) throws -> (version: String?, timestamp: Int64?) {
            var reader = Reader(bytes: bytes, range: range)
            var result = start
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 2): result.version = try text(&reader)
                case (3, 0): result.timestamp = Int64(bitPattern: try reader.varint())
                default: try reader.skip(field: next.field, wire: next.wire)
                }
            }
            return result
        }

        func entity(_ range: Range<Int>, keepTrip: (String) -> Bool) throws -> GTFSRealtimeEntity {
            var reader = Reader(bytes: bytes, range: range)
            var id: String?
            var isDeleted = false
            var update: GTFSRealtimeTripUpdate?
            var hasTrip = false
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 2): id = try text(&reader)
                case (2, 0): isDeleted = try reader.varint() != 0
                case (3, 2):
                    let range = try reader.lengthDelimited()
                    var merged = update ?? GTFSRealtimeTripUpdate(trip: GTFSRealtimeTripDescriptor(), timestamp: nil, stopTimeUpdates: [])
                    try tripUpdate(range, into: &merged, hasTrip: &hasTrip)
                    update = merged
                default: try reader.skip(field: next.field, wire: next.wire)
                }
            }
            // Required: FeedEntity.id and, in a trip update, its TripDescriptor.
            guard let id, update == nil || hasTrip else { throw Malformed.invalid }
            if var kept = update, !keepTrip(kept.trip.tripID) {
                kept.stopTimeUpdates = []
                update = kept
            }
            return GTFSRealtimeEntity(id: id, isDeleted: isDeleted, tripUpdate: update)
        }

        func tripUpdate(_ range: Range<Int>, into update: inout GTFSRealtimeTripUpdate, hasTrip: inout Bool) throws {
            var reader = Reader(bytes: bytes, range: range)
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 2):
                    try tripDescriptor(try reader.lengthDelimited(), into: &update.trip)
                    hasTrip = true
                case (2, 2):
                    guard update.stopTimeUpdates.count < limits.maximumStopTimeUpdatesPerTrip else { throw Malformed.invalid }
                    var stop = GTFSRealtimeStopTimeUpdate()
                    try stopTimeUpdate(try reader.lengthDelimited(), into: &stop)
                    update.stopTimeUpdates.append(stop)
                case (4, 0): update.timestamp = Int64(bitPattern: try reader.varint())
                default: try reader.skip(field: next.field, wire: next.wire)
                }
            }
        }

        func tripDescriptor(_ range: Range<Int>, into trip: inout GTFSRealtimeTripDescriptor) throws {
            var reader = Reader(bytes: bytes, range: range)
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 2): trip.tripID = try text(&reader)
                case (3, 2): trip.startDate = try text(&reader)
                case (4, 0):
                    if let known = GTFSRealtimeTripRelationship(rawValue: Int32(truncatingIfNeeded: try reader.varint())) {
                        trip.scheduleRelationship = known
                    }
                case (5, 2): trip.routeID = try text(&reader)
                default: try reader.skip(field: next.field, wire: next.wire)
                }
            }
        }

        func stopTimeUpdate(_ range: Range<Int>, into stop: inout GTFSRealtimeStopTimeUpdate) throws {
            var reader = Reader(bytes: bytes, range: range)
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 0): stop.stopSequence = Int32(truncatingIfNeeded: try reader.varint())
                case (2, 2):
                    var event = stop.arrival ?? GTFSRealtimeStopTimeEvent()
                    try stopTimeEvent(try reader.lengthDelimited(), into: &event)
                    stop.arrival = event
                case (3, 2):
                    var event = stop.departure ?? GTFSRealtimeStopTimeEvent()
                    try stopTimeEvent(try reader.lengthDelimited(), into: &event)
                    stop.departure = event
                case (4, 2): stop.stopID = try text(&reader)
                case (5, 0):
                    if let known = GTFSRealtimeStopRelationship(rawValue: Int32(truncatingIfNeeded: try reader.varint())) {
                        stop.scheduleRelationship = known
                    }
                default: try reader.skip(field: next.field, wire: next.wire)
                }
            }
        }

        func stopTimeEvent(_ range: Range<Int>, into event: inout GTFSRealtimeStopTimeEvent) throws {
            var reader = Reader(bytes: bytes, range: range)
            while let next = try reader.tag() {
                switch (next.field, next.wire) {
                case (1, 0): event.delay = Int32(truncatingIfNeeded: try reader.varint())
                case (2, 0): event.time = Int64(bitPattern: try reader.varint())
                default: try reader.skip(field: next.field, wire: next.wire)
                }
            }
        }

        /// A string field: UTF-8, invalid sequences replaced, as the bindings read proto2 strings.
        func text(_ reader: inout Reader) throws -> String {
            let range = try reader.lengthDelimited()
            guard range.count <= limits.maximumTextBytes else { throw Malformed.invalid }
            return String(decoding: bytes[range], as: UTF8.self)
        }
    }

    /// The wire format over a range of the feed's bytes.
    private struct Reader {
        let bytes: [UInt8]
        var index: Int
        let end: Int

        init(bytes: [UInt8], range: Range<Int>) {
            self.bytes = bytes
            index = range.lowerBound
            end = range.upperBound
        }

        /// The next field number and wire type, nil at the end of the message. A tag is read as Java reads it: a
        /// varint whose low 32 bits are the tag; field number zero is malformed.
        mutating func tag() throws -> (field: UInt32, wire: UInt32)? {
            guard index < end else { return nil }
            let tag = UInt32(truncatingIfNeeded: try varint())
            let field = tag >> 3
            guard field != 0 else { throw Malformed.invalid }
            return (field, tag & 7)
        }

        /// A base-128 varint of at most ten bytes; bits past the 64th are dropped, as Java drops them.
        mutating func varint() throws -> UInt64 {
            var result: UInt64 = 0
            var shift: UInt64 = 0
            while shift < 64 {
                guard index < end else { throw Malformed.invalid }
                let byte = bytes[index]
                index += 1
                result |= UInt64(byte & 0x7F) << shift
                if byte & 0x80 == 0 { return result }
                shift += 7
            }
            throw Malformed.invalid
        }

        /// The range of a length-delimited value: its length read as a Java int, never negative or past the end.
        mutating func lengthDelimited() throws -> Range<Int> {
            let length = Int(Int32(truncatingIfNeeded: try varint()))
            guard length >= 0, length <= end - index else { throw Malformed.invalid }
            let range = index..<(index + length)
            index += length
            return range
        }

        /// Skips a field the decoder does not read; a group is skipped to its matching end, at most 100 deep.
        mutating func skip(field: UInt32, wire: UInt32, depth: Int = 0) throws {
            switch wire {
            case 0:
                _ = try varint()
            case 1:
                try advance(8)
            case 2:
                _ = try lengthDelimited()
            case 3:
                guard depth < 100 else { throw Malformed.invalid }
                while true {
                    guard let inner = try tag() else { throw Malformed.invalid }
                    if inner.wire == 4 {
                        guard inner.field == field else { throw Malformed.invalid }
                        return
                    }
                    try skip(field: inner.field, wire: inner.wire, depth: depth + 1)
                }
            case 5:
                try advance(4)
            default:
                // An end of group outside its group, and the wire types 6 and 7.
                throw Malformed.invalid
            }
        }

        private mutating func advance(_ count: Int) throws {
            guard count <= end - index else { throw Malformed.invalid }
            index += count
        }
    }
}

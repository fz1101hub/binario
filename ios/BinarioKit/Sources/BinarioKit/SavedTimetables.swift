import Foundation

/// One solution of the results as the user saw it, resolved to plain text at save time (Android's
/// `TimetableRow`): times, the trains, changes, the departure platform when known, the source and
/// when it answered. Saved rows never update.
public struct TimetableRow: Equatable, Hashable, Sendable {
    /// «HH:mm», railway local time.
    public let departure: String
    public let arrival: String?
    /// Days between departure and arrival (1 = the next day).
    public let arrivalDayOffset: Int
    public let durationMinutes: Int?
    public let trains: String
    public let changes: Int
    /// «Cambio a Bologna Centrale · attesa 12 min», in the language of the save.
    public let transfers: [String]
    public let platform: String?
    public let scheduledPlatform: String?
    public let source: String
    /// When the source answered; nil when unknown.
    public let fetchedAt: Date?
    /// Scheduled times only: no delay, platform or cancellation was checked.
    public let scheduledOnly: Bool

    public init(
        departure: String,
        arrival: String?,
        arrivalDayOffset: Int = 0,
        durationMinutes: Int?,
        trains: String,
        changes: Int,
        transfers: [String] = [],
        platform: String? = nil,
        scheduledPlatform: String? = nil,
        source: String,
        fetchedAt: Date? = nil,
        scheduledOnly: Bool = true
    ) {
        self.departure = departure
        self.arrival = arrival
        self.arrivalDayOffset = arrivalDayOffset
        self.durationMinutes = durationMinutes
        self.trains = trains
        self.changes = changes
        self.transfers = transfers
        self.platform = platform
        self.scheduledPlatform = scheduledPlatform
        self.source = source
        self.fetchedAt = fetchedAt
        self.scheduledOnly = scheduledOnly
    }
}

/// A saved result list of a route search («Orari salvati»): on this iPhone only, read-only and
/// marked as not updated.
public struct SavedTimetable: Equatable, Hashable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let origin: String
    public let destination: String
    public let serviceDate: PlannedLocalDate
    /// «HH:mm» the search started from.
    public let fromTime: String
    public let savedAt: Date
    public let rows: [TimetableRow]
    /// The return of a round trip; empty for a one-way search.
    public let returnRows: [TimetableRow]

    public init(
        id: String,
        name: String,
        origin: String,
        destination: String,
        serviceDate: PlannedLocalDate,
        fromTime: String,
        savedAt: Date,
        rows: [TimetableRow],
        returnRows: [TimetableRow] = []
    ) {
        self.id = id
        self.name = name
        self.origin = origin
        self.destination = destination
        self.serviceDate = serviceDate
        self.fromTime = fromTime
        self.savedAt = savedAt
        self.rows = rows
        self.returnRows = returnRows
    }

    public var solutionCount: Int { rows.count + returnRows.count }

    func renamed(_ newName: String) -> SavedTimetable {
        SavedTimetable(id: id, name: newName, origin: origin, destination: destination, serviceDate: serviceDate,
                       fromTime: fromTime, savedAt: savedAt, rows: rows, returnRows: returnRows)
    }
}

/// The JSON of the saved timetables, Android's `SavedTimetableCodec`: the same keys, in the app's
/// storage and in the local backup (`savedTimetables`), so a file moves between the two phones.
/// Bounded on read and write by `SharedData.SavedTimetables`; an unreadable entry is dropped on its
/// own, never the whole list.
public enum SavedTimetableCodec {
    private typealias Table = SharedData.SavedTimetables

    public static var maximumTimetables: Int { Table.maximumTimetables }
    public static var maximumNameLength: Int { Table.maximumNameLength }

    public static func jsonObjects(_ timetables: [SavedTimetable]) -> [[String: Any]] {
        timetables.prefix(Table.maximumTimetables).map { timetable in
            var value: [String: Any] = [
                "id": timetable.id,
                "name": String(timetable.name.prefix(Table.maximumNameLength)),
                "origin": String(timetable.origin.prefix(Table.maximumTextLength)),
                "destination": String(timetable.destination.prefix(Table.maximumTextLength)),
                "date": timetable.serviceDate.iso,
                "from": timetable.fromTime,
                "savedAt": milliseconds(timetable.savedAt),
                "rows": rowObjects(timetable.rows),
            ]
            if !timetable.returnRows.isEmpty { value["returnRows"] = rowObjects(timetable.returnRows) }
            return value
        }
    }

    public static func timetables(fromJSON array: [Any]) -> [SavedTimetable] {
        var seen = Set<String>()
        var result: [SavedTimetable] = []
        for item in array {
            guard let value = item as? [String: Any],
                  let id = text(value["id"]),
                  let origin = text(value["origin"]),
                  let destination = text(value["destination"]),
                  let date = (value["date"] as? String).flatMap({ PlannedLocalDate(iso: $0) }),
                  let from = value["from"] as? String, isTime(from) else { continue }
            let outbound = parseRows(value["rows"])
            let back = parseRows(value["returnRows"])
            guard !outbound.isEmpty || !back.isEmpty, seen.insert(id).inserted else { continue }
            let millis = PunctualityHistory.jsonInt(value["savedAt"]) ?? 0
            let name = text(value["name"]) ?? "\(origin) → \(destination)"
            result.append(SavedTimetable(
                id: id,
                name: String(name.prefix(Table.maximumNameLength)),
                origin: origin,
                destination: destination,
                serviceDate: date,
                fromTime: from,
                savedAt: Date(timeIntervalSince1970: Double(millis) / 1000),
                rows: outbound,
                returnRows: back
            ))
            if result.count == Table.maximumTimetables { break }
        }
        return result
    }

    public static func encode(_ timetables: [SavedTimetable]) -> Data? {
        try? JSONSerialization.data(withJSONObject: jsonObjects(timetables))
    }

    public static func decode(_ data: Data?) -> [SavedTimetable] {
        guard let data, let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return [] }
        return timetables(fromJSON: array)
    }

    /// `timetables` after saving `timetable`: newest first, one per id, within the limit.
    public static func withSaved(_ timetables: [SavedTimetable], _ timetable: SavedTimetable) -> [SavedTimetable] {
        Array(([timetable] + timetables.filter { $0.id != timetable.id }).prefix(Table.maximumTimetables))
    }

    /// `timetables` with `id` renamed; a blank name keeps the previous one.
    public static func renamed(_ timetables: [SavedTimetable], id: String, name: String) -> [SavedTimetable] {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Table.maximumNameLength))
        guard !clean.isEmpty else { return timetables }
        return timetables.map { $0.id == id ? $0.renamed(clean) : $0 }
    }

    // MARK: Rows

    private static func rowObjects(_ rows: [TimetableRow]) -> [[String: Any]] {
        rows.prefix(Table.maximumRows).map { row in
            var value: [String: Any] = [
                "departure": row.departure,
                "trains": String(row.trains.prefix(Table.maximumTextLength)),
                "changes": row.changes,
                "source": String(row.source.prefix(Table.maximumTextLength)),
                "fetchedAt": row.fetchedAt.map { milliseconds($0) } ?? 0,
                "scheduledOnly": row.scheduledOnly,
            ]
            if let arrival = row.arrival { value["arrival"] = arrival }
            if row.arrivalDayOffset != 0 { value["arrivalDayOffset"] = row.arrivalDayOffset }
            if let duration = row.durationMinutes { value["duration"] = duration }
            if !row.transfers.isEmpty {
                value["transfers"] = row.transfers.prefix(Table.maximumTransfers).map { String($0.prefix(Table.maximumTextLength)) }
            }
            if let platform = row.platform { value["platform"] = String(platform.prefix(Table.maximumTextLength)) }
            if let scheduled = row.scheduledPlatform { value["scheduledPlatform"] = String(scheduled.prefix(Table.maximumTextLength)) }
            return value
        }
    }

    private static func parseRows(_ raw: Any?) -> [TimetableRow] {
        guard let array = raw as? [Any] else { return [] }
        var parsed: [TimetableRow] = []
        for item in array {
            guard let value = item as? [String: Any], let departure = value["departure"] as? String, isTime(departure) else { continue }
            let arrival = (value["arrival"] as? String).flatMap { isTime($0) ? $0 : nil }
            let duration = PunctualityHistory.jsonInt(value["duration"]).flatMap { $0 >= 0 ? $0 : nil }
            let transfers = (value["transfers"] as? [Any] ?? []).compactMap { text($0) }
            let fetched = max(PunctualityHistory.jsonInt(value["fetchedAt"]) ?? 0, 0)
            let offset = min(max(PunctualityHistory.jsonInt(value["arrivalDayOffset"]) ?? 0, 0), Table.maximumArrivalDayOffset)
            let changes = min(max(PunctualityHistory.jsonInt(value["changes"]) ?? 0, 0), Table.maximumChanges)
            parsed.append(TimetableRow(
                departure: departure,
                arrival: arrival,
                arrivalDayOffset: offset,
                durationMinutes: duration,
                trains: text(value["trains"]) ?? "",
                changes: changes,
                transfers: Array(transfers.prefix(Table.maximumTransfers)),
                platform: text(value["platform"]),
                scheduledPlatform: text(value["scheduledPlatform"]),
                source: text(value["source"]) ?? "",
                fetchedAt: fetched > 0 ? Date(timeIntervalSince1970: Double(fetched) / 1000) : nil,
                scheduledOnly: value["scheduledOnly"] as? Bool ?? true
            ))
            if parsed.count == Table.maximumRows { break }
        }
        return parsed
    }

    /// A trimmed, non-empty text within the limit, as Android's `text`.
    private static func text(_ raw: Any?) -> String? {
        guard let value = raw as? String else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(Table.maximumTextLength))
    }

    /// «H:mm» or «HH:mm», as Android's pattern.
    private static func isTime(_ value: String) -> Bool {
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, (1...2).contains(parts[0].count), parts[1].count == 2 else { return false }
        return parts.allSatisfy { part in part.allSatisfy { $0.isASCII && $0.isNumber } }
    }

    private static func milliseconds(_ date: Date) -> Int {
        Int((date.timeIntervalSince1970 * 1000).rounded())
    }
}

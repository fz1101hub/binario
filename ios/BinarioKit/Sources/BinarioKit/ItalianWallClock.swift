import Foundation

/// A calendar day as the Agenda stores it: "2026-11-12", Android's `LocalDate.toString()`.
/// Proleptic Gregorian, years 1 to 9999; an impossible day (30 February, month 14) is not a date.
public struct PlannedLocalDate: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    public init?(year: Int, month: Int, day: Int) {
        guard (1...9_999).contains(year), (1...12).contains(month),
              (1...Self.daysInMonth(year: year, month: month)).contains(day) else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    /// "yyyy-MM-dd" in ASCII digits, nothing else.
    public init?(iso value: String) {
        let bytes = Array(value.utf8)
        guard bytes.count == 10, bytes[4] == UInt8(ascii: "-"), bytes[7] == UInt8(ascii: "-"),
              let year = ItalianWallClock.decimal(bytes[0..<4]),
              let month = ItalianWallClock.decimal(bytes[5..<7]),
              let day = ItalianWallClock.decimal(bytes[8..<10])
        else { return nil }
        self.init(year: year, month: month, day: day)
    }

    /// The day `days` after 1970-01-01 (before it when negative); nil outside years 1 to 9999.
    public init?(daysSinceEpoch days: Int) {
        let civil = ItalianWallClock.civil(fromDays: days)
        self.init(year: civil.year, month: civil.month, day: civil.day)
    }

    /// "2026-11-12".
    public var iso: String {
        LiveText.padded(year, width: 4) + "-" + LiveText.padded(month, width: 2) + "-" + LiveText.padded(day, width: 2)
    }

    public var description: String { iso }

    /// Days from 1970-01-01.
    public var daysSinceEpoch: Int {
        ItalianWallClock.days(year: year, month: month, day: day)
    }

    /// 1 Monday … 7 Sunday, as Android's `DayOfWeek.value`.
    public var isoWeekday: Int {
        // 1970-01-01 was a Thursday (4).
        let shifted: Int = (daysSinceEpoch + 3) % 7
        return (shifted < 0 ? shifted + 7 : shifted) + 1
    }

    public func adding(days: Int) -> PlannedLocalDate? {
        PlannedLocalDate(daysSinceEpoch: daysSinceEpoch + days)
    }

    public static func < (lhs: PlannedLocalDate, rhs: PlannedLocalDate) -> Bool {
        lhs.daysSinceEpoch < rhs.daysSinceEpoch
    }

    static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 2:
            let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            return leap ? 29 : 28
        case 4, 6, 9, 11:
            return 30
        default:
            return 31
        }
    }
}

/// A clock time as the Agenda stores it: "08:15", zero-padded, 00:00 to 23:59 (Android's
/// `LocalTime.toString()` of a whole minute). "9:00", "24:00" and "25:15" are not times.
public struct PlannedLocalTime: Hashable, Comparable, Sendable, CustomStringConvertible {
    public let hour: Int
    public let minute: Int

    public init?(hour: Int, minute: Int) {
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        self.hour = hour
        self.minute = minute
    }

    public init?(hhmm value: String) {
        let bytes = Array(value.utf8)
        guard bytes.count == 5, bytes[2] == UInt8(ascii: ":"),
              let hour = ItalianWallClock.decimal(bytes[0..<2]),
              let minute = ItalianWallClock.decimal(bytes[3..<5])
        else { return nil }
        self.init(hour: hour, minute: minute)
    }

    /// "08:15": what every screen prints, never a conversion to the phone's zone.
    public var hhmm: String {
        LiveText.padded(hour, width: 2) + ":" + LiveText.padded(minute, width: 2)
    }

    public var description: String { hhmm }

    public var minutesOfDay: Int { hour * 60 + minute }

    public static func < (lhs: PlannedLocalTime, rhs: PlannedLocalTime) -> Bool {
        lhs.minutesOfDay < rhs.minutesOfDay
    }
}

/// The Italian wall clock of the Agenda: every instant comes from the saved Italian date and time,
/// whatever zone the phone is in (Android's `PlannedJourneyDateTime` with `RailTime.defaultZone`).
///
/// The arithmetic does not depend on the phone's zone or on how Foundation's calendar treats a
/// time that does not exist: a time the daylight-saving change skips (02:00–02:59 on the last
/// Sunday of March in Italy) has no instant, and a time it repeats (the last Sunday of October)
/// is its first occurrence, in summer time, as Android's `getValidOffsets(...).first()`.
public enum ItalianWallClock {
    public static let zoneIdentifier = "Europe/Rome"

    public static let timeZone: TimeZone =
        TimeZone(identifier: zoneIdentifier) ?? TimeZone(secondsFromGMT: 3_600) ?? TimeZone.current

    /// The instant of `time` on `date` in `zone`; nil when the zone skips that time.
    public static func instant(date: PlannedLocalDate, time: PlannedLocalTime, zone: TimeZone = timeZone) -> Date? {
        // The wall time read as if it were UTC, then each offset the zone uses around it.
        let wall: Int = date.daysSinceEpoch * 86_400 + time.minutesOfDay * 60
        var offsets: [Int] = []
        for probe in [wall - 86_400, wall, wall + 86_400] {
            let offset = zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(probe)))
            if !offsets.contains(offset) { offsets.append(offset) }
        }
        // An offset counts when the zone really uses it at the instant it gives.
        let valid: [Int] = offsets.compactMap { offset -> Int? in
            let candidate: Int = wall - offset
            let actual = zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(candidate)))
            return actual == offset ? candidate : nil
        }
        guard let earliest = valid.min() else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(earliest))
    }

    /// The instant of a stored date ("2026-11-12"), time ("08:15") and IANA zone; nil when one of
    /// them is not valid or the zone skips that time.
    public static func instant(date: String, time: String, zoneIdentifier: String) -> Date? {
        guard let day = PlannedLocalDate(iso: date),
              let clock = PlannedLocalTime(hhmm: time),
              let zone = TimeZone(identifier: zoneIdentifier)
        else { return nil }
        return instant(date: day, time: clock, zone: zone)
    }

    /// The day `instant` falls on in `zone`: «oggi» is Italy's date, whatever the phone's zone.
    public static func date(of instant: Date, zone: TimeZone = timeZone) -> PlannedLocalDate? {
        PlannedLocalDate(daysSinceEpoch: floorDivision(localSeconds(of: instant, zone: zone), 86_400))
    }

    /// The clock time of `instant` in `zone`, seconds dropped.
    public static func time(of instant: Date, zone: TimeZone = timeZone) -> PlannedLocalTime? {
        let local = localSeconds(of: instant, zone: zone)
        let secondOfDay: Int = local - floorDivision(local, 86_400) * 86_400
        return PlannedLocalTime(hour: secondOfDay / 3_600, minute: (secondOfDay % 3_600) / 60)
    }

    private static func localSeconds(of instant: Date, zone: TimeZone) -> Int {
        let seconds: Double = instant.timeIntervalSince1970.rounded(.down)
        guard seconds.isFinite, abs(seconds) < 300_000_000_000 else { return 0 }
        let whole = Int(seconds)
        return whole + zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(whole)))
    }

    static func floorDivision(_ value: Int, _ divisor: Int) -> Int {
        let quotient = value / divisor
        return (value % divisor != 0 && (value < 0) != (divisor < 0)) ? quotient - 1 : quotient
    }

    /// Days from 1970-01-01 of a proleptic Gregorian date (H. Hinnant's `days_from_civil`).
    static func days(year: Int, month: Int, day: Int) -> Int {
        let shiftedYear: Int = month <= 2 ? year - 1 : year
        let era: Int = (shiftedYear >= 0 ? shiftedYear : shiftedYear - 399) / 400
        let yearOfEra: Int = shiftedYear - era * 400
        let shiftedMonth: Int = (month + 9) % 12
        let dayOfYear: Int = (153 * shiftedMonth + 2) / 5 + day - 1
        let dayOfEra: Int = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /// The proleptic Gregorian date `days` after 1970-01-01 (`civil_from_days`).
    static func civil(fromDays days: Int) -> (year: Int, month: Int, day: Int) {
        let shifted: Int = days + 719_468
        let era: Int = (shifted >= 0 ? shifted : shifted - 146_096) / 146_097
        let dayOfEra: Int = shifted - era * 146_097
        let yearOfEra: Int = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear: Int = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let shiftedMonth: Int = (5 * dayOfYear + 2) / 153
        let day: Int = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
        let month: Int = shiftedMonth < 10 ? shiftedMonth + 3 : shiftedMonth - 9
        let year: Int = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
        return (year, month, day)
    }

    /// ASCII digits as a number, nil for anything else.
    static func decimal(_ bytes: ArraySlice<UInt8>) -> Int? {
        guard !bytes.isEmpty else { return nil }
        var value = 0
        for byte in bytes {
            guard (48...57).contains(byte) else { return nil }
            value = value * 10 + Int(byte - 48)
        }
        return value
    }
}

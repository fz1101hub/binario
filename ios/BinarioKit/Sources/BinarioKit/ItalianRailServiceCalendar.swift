import Foundation

/// A calendar day, without a time or a zone.
public struct RailDay: Comparable, Hashable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    /// Nil for a day no calendar has («2026-02-30»).
    public init?(year: Int, month: Int, day: Int) {
        guard (1...12).contains(month), day >= 1,
              let date = Self.calendar.date(from: DateComponents(year: year, month: month, day: day)),
              Self.calendar.component(.day, from: date) == day else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    /// «2026-09-04».
    public init?(iso value: String) {
        let parts = value.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else { return nil }
        self.init(year: year, month: month, day: day)
    }

    /// The Italian day of `date`.
    public init(italianDayOf date: Date) {
        let parts = LiveRailTime.calendar.dateComponents([.year, .month, .day], from: date)
        year = parts.year ?? 1970
        month = parts.month ?? 1
        day = parts.day ?? 1
    }

    public var description: String {
        LiveText.padded(year, width: 4) + "-" + LiveText.padded(month, width: 2) + "-" + LiveText.padded(day, width: 2)
    }

    /// 1 for Monday … 7 for Sunday (ISO).
    public var isoWeekday: Int {
        guard let date = Self.calendar.date(from: DateComponents(year: year, month: month, day: day)) else { return 1 }
        return (Self.calendar.component(.weekday, from: date) + 5) % 7 + 1
    }

    public func adding(days: Int) -> RailDay {
        guard let date = Self.calendar.date(from: DateComponents(year: year, month: month, day: day)),
              let moved = Self.calendar.date(byAdding: .day, value: days, to: date) else { return self }
        let parts = Self.calendar.dateComponents([.year, .month, .day], from: moved)
        return RailDay(year: parts.year ?? year, month: parts.month ?? month, day: parts.day ?? day) ?? self
    }

    /// `hour`:`minute` of this day in Italian time.
    public func italianDate(hour: Int, minute: Int) -> Date? {
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        return LiveRailTime.calendar.date(from: components)
    }

    public static func < (lhs: RailDay, rhs: RailDay) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    private static let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current
        return calendar
    }()
}

/// Android's `ItalianRailServiceCalendar`: the national holidays that make a day a holiday for RFI's timetable (the
/// shared table italian-holidays-v1.json, Easter by the Gregorian computus) and the reading of RFI's free-text
/// «Periodicità e avvertenze». The cases are contracts/shared-rules/v1/calendar-v1.json and rfi-programmed-v1.json.
public enum ItalianRailServiceCalendar {
    public static func isHoliday(_ day: RailDay) -> Bool {
        let fixed = SharedData.ItalianHolidays.holidays.contains { holiday in
            holiday.month == day.month && holiday.day == day.day && day.year >= (holiday.fromYear ?? day.year)
        }
        if fixed { return true }
        let easter = easterSunday(day.year)
        return SharedData.ItalianHolidays.easterOffsetsDays.contains { easter?.adding(days: $0) == day }
    }

    /// The periodicity text read as clauses of day terms with a polarity. Positive terms say when a train runs; terms
    /// after a negation (NON, ESCLUSO, ECCETTO, TRANNE, SALVO) say when it does not, until the clause ends. Day names,
    /// three- and two-letter abbreviations, ranges («LUN-VEN», «DAL LUNEDÌ AL SABATO»), FERIALE, LAVORATIV*, every
    /// FESTIV* form and PREFESTIV* (Saturdays and the eves of holidays) are understood. A train runs when no positive
    /// term exists or one matches, and no negative term matches. A text with no term («Non ferma a X») runs every day.
    public static func runsOnDate(_ periodicity: String, _ day: RailDay) -> Bool {
        let terms = parse(periodicity)
        if terms.isEmpty { return true }
        let positives = terms.filter { !$0.negated }
        let negatives = terms.filter { $0.negated }
        let byPositives = positives.isEmpty || positives.contains { $0.kind.matches(day) }
        return byPositives && !negatives.contains { $0.kind.matches(day) }
    }

    /// The departure day of a row of an RFI station timetable, which lists one day 00:00–23:59: rows at or after the
    /// requested time leave on the requested day, earlier rows the next day; nil for a time outside the day. An
    /// unreadable time (`Int.max`) stays on the requested day.
    public static func rfiDepartureDay(rowMinutes: Int, serviceDay: RailDay, requestedMinutes: Int) -> RailDay? {
        if rowMinutes == Int.max { return serviceDay }
        guard (0..<(24 * 60)).contains(rowMinutes) else { return nil }
        return rowMinutes >= requestedMinutes ? serviceDay : serviceDay.adding(days: 1)
    }

    // MARK: - Terms

    private enum Kind {
        case days(Set<Int>)
        case holiday
        case weekday
        case holidayEve
        case daily

        func matches(_ day: RailDay) -> Bool {
            switch self {
            case let .days(days): return days.contains(day.isoWeekday)
            case .holiday: return day.isoWeekday == 7 || ItalianRailServiceCalendar.isHoliday(day)
            case .weekday: return day.isoWeekday != 7 && !ItalianRailServiceCalendar.isHoliday(day)
            // The day before a Sunday or holiday (so every Saturday), unless it is a holiday itself.
            case .holidayEve: return Kind.weekday.matches(day) && Kind.holiday.matches(day.adding(days: 1))
            case .daily: return true
            }
        }
    }

    private struct Term {
        let kind: Kind
        let negated: Bool
    }

    private static let fullDayWords: [String: Int] = [
        "LUNEDI": 1, "LUN": 1, "MARTEDI": 2, "MAR": 2, "MERCOLEDI": 3, "MER": 3, "GIOVEDI": 4, "GIO": 4,
        "VENERDI": 5, "VEN": 5, "SABATO": 6, "SABATI": 6, "SAB": 6, "DOMENICA": 7, "DOMENICHE": 7, "DOM": 7,
    ]
    /// Two-letter forms are ordinary Italian words too («MA», «ME», «DO»): they count only next to another day.
    private static let shortDayWords: [String: Int] = ["LU": 1, "MA": 2, "ME": 3, "GI": 4, "VE": 5, "SA": 6, "DO": 7]
    private static let negationWords: Set<String> = [
        "NON", "ESCLUSO", "ESCLUSA", "ESCLUSI", "ESCLUSE", "ESCL", "ECCETTO", "TRANNE", "SALVO",
    ]
    private static let clauseEnd = "|"
    private static let range = "-"
    /// Shortened forms written with a dot («Escl. sab. e fest.»): that dot does not end the clause.
    private static let abbreviations: Set<String> = [
        "ESCL", "ESC", "ECC", "LUN", "MAR", "MER", "GIO", "VEN", "SAB", "DOM", "LU", "MA", "ME", "GI", "VE", "SA", "DO",
        "FEST", "FESTIV", "FER", "FERIAL", "PREFEST", "PREFESTIV", "LAV", "LAVOR", "LAVORAT", "LAVORATIV", "GG",
    ]

    private static func isPeriodicityWord(_ token: String) -> Bool {
        fullDayWords[token] != nil || shortDayWords[token] != nil || negationWords.contains(token)
            || abbreviations.contains(token) || token.hasPrefix("FEST") || token.hasPrefix("PREFEST")
            || token.hasPrefix("FERIAL") || token.hasPrefix("LAVORATIV")
    }

    /// Android's tokens: runs of ASCII letters and digits, «.», «;», «:» and «-», after the accents are removed.
    private static func rawTokens(_ text: String) -> [String] {
        var tokens: [String] = []
        var current = String.UnicodeScalarView()
        func flush() {
            if !current.isEmpty { tokens.append(String(current)) }
            current = String.UnicodeScalarView()
        }
        for scalar in text.decomposedStringWithCanonicalMapping.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark:
                // Removed before the words are read («LUNEDÌ» is «LUNEDI»).
                continue
            default:
                break
            }
            if ("A"..."Z").contains(scalar) || ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
                current.append(scalar)
            } else {
                flush()
                if scalar == "." || scalar == ";" || scalar == ":" || scalar == "-" { tokens.append(String(scalar)) }
            }
        }
        flush()
        return tokens
    }

    /// Words, ranges and clause ends. «;» and «:» end a clause; a dot ends it unless it closes an abbreviation, and even
    /// then when a capitalised word that is no day term starts a new sentence («Escl. sab. Si effettua dom.»).
    private static func tokenize(_ periodicity: String) -> [String] {
        let raw = rawTokens(periodicity)
        var tokens: [String] = []
        for (index, token) in raw.enumerated() {
            switch token {
            case ";", ":":
                tokens.append(clauseEnd)
            case ".":
                let previous = index > 0 ? raw[index - 1].uppercased() : nil
                let next = index + 1 < raw.count ? raw[index + 1] : nil
                let newSentence = next.map { word in
                    (word.unicodeScalars.first.map { ("A"..."Z").contains($0) } ?? false) && !isPeriodicityWord(word.uppercased())
                } ?? false
                if previous == nil || !abbreviations.contains(previous ?? "") || newSentence { tokens.append(clauseEnd) }
            default:
                tokens.append(token.uppercased())
            }
        }
        return tokens
    }

    private static func parse(_ periodicity: String) -> [Term] {
        let tokens = tokenize(periodicity)
        func token(_ index: Int) -> String? { index >= 0 && index < tokens.count ? tokens[index] : nil }
        func anyDay(_ index: Int) -> Int? { token(index).flatMap { fullDayWords[$0] ?? shortDayWords[$0] } }
        func dayAt(_ index: Int) -> Int? {
            guard let word = token(index) else { return nil }
            if let full = fullDayWords[word] { return full }
            guard let short = shortDayWords[word] else { return nil }
            let neighbourIsDay = [index - 1, index + 1, index - 2, index + 2].contains { neighbour in
                guard neighbour >= 0, neighbour < tokens.count, anyDay(neighbour) != nil else { return false }
                let low = min(index, neighbour) + 1
                let high = max(index, neighbour)
                return (low..<high).allSatisfy { tokens[$0] == range }
            }
            return neighbourIsDay ? short : nil
        }

        var terms: [Term] = []
        var negated = false
        var index = 0
        while index < tokens.count {
            let word = tokens[index]
            if word == clauseEnd {
                negated = false
            } else if negationWords.contains(word) {
                negated = true
            } else if word == "GIORNALIERO" || word == "QUOTIDIANO"
                        || (word == "TUTTI" && token(index + 1) == "I" && token(index + 2) == "GIORNI") {
                terms.append(Term(kind: .daily, negated: negated))
            } else if word.hasPrefix("PREFEST") {
                terms.append(Term(kind: .holidayEve, negated: negated))
            } else if word.hasPrefix("FEST") {
                terms.append(Term(kind: .holiday, negated: negated))
            } else if word == "FERIALE" || word == "FERIALI" || word == "FER" || word == "FERIAL" || word.hasPrefix("LAVORATIV") {
                terms.append(Term(kind: .weekday, negated: negated))
            } else if (word == "DAL" || word == "DA"), let from = dayAt(index + 1),
                      token(index + 2) == "AL" || token(index + 2) == "A", let to = dayAt(index + 3) {
                terms.append(Term(kind: .days(dayRange(from, to)), negated: negated))
                index += 3
            } else if let from = dayAt(index), token(index + 1) == range, let to = dayAt(index + 2) {
                terms.append(Term(kind: .days(dayRange(from, to)), negated: negated))
                index += 2
            } else if let day = dayAt(index) {
                terms.append(Term(kind: .days([day]), negated: negated))
            }
            index += 1
        }
        return terms
    }

    /// The days from `from` to `to`, wrapping after Sunday.
    private static func dayRange(_ from: Int, _ to: Int) -> Set<Int> {
        var days: Set<Int> = [from]
        var day = from
        while day != to {
            day = day % 7 + 1
            days.insert(day)
        }
        return days
    }

    /// Meeus/Jones/Butcher Gregorian computus.
    static func easterSunday(_ year: Int) -> RailDay? {
        let a = year % 19
        let b = year / 100
        let c = year % 100
        let d = b / 4
        let e = b % 4
        let f = (b + 8) / 25
        let g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4
        let k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = (h + l - 7 * m + 114) % 31 + 1
        return RailDay(year: year, month: month, day: day)
    }
}

import Foundation

/// «Più presto» / «Più tardi» of one source of the results: a window being loaded, or the last one found nothing new.
public struct ResultsPageState: Equatable, Sendable {
    public var loading: Bool
    public var nothingNew: Bool

    public init(loading: Bool = false, nothingNew: Bool = false) {
        self.loading = loading
        self.nothingNew = nothingNew
    }

    public static let loadingPage = ResultsPageState(loading: true)
}

/// The windows of solutions loaded around a search (Android's `ResultsPaging`): «Più tardi» continues from the last
/// departure shown, «Più presto» looks before the first one. Each source (direct trains, journeys with changes) pages
/// from its own first and last departure; what a page repeats is dropped by identity.
public enum ResultsPaging {
    /// «Più presto» looks this far back first, then further while it finds fewer than a page.
    public static let earlierSpansMinutes = SharedData.ResultsPaging.earlierSpansMinutes
    /// «Più presto» shows at most this many earlier solutions per source, the closest to the first shown.
    public static let earlierPage = SharedData.ResultsPaging.earlierPage
    /// A window before the first departure that fills up is loaded this many times at most.
    public static let maximumFillLoads = SharedData.ResultsPaging.maximumFillLoads
    /// A window still too full starts closer to the first departure, never less than this before it.
    public static let minimumEarlierSpanMinutes = SharedData.ResultsPaging.minimumEarlierSpanMinutes
    /// Journeys with changes asked per window of «Più presto».
    public static let earlierJourneyResults = SharedData.ResultsPaging.earlierJourneyResults

    /// «Più tardi»: the items of the window from `last` (the same minute included), or nil when the source could not
    /// answer. Repeats of the items shown are left to the caller (`newServices`, `newOptions`).
    public static func later<Item>(
        _ last: Date, departureOf: (Item) -> Date?, load: (Date) async -> [Item]?
    ) async -> [Item]? {
        guard let page = await load(last) else { return nil }
        let lastMinute = WallClock.minutes(last)
        return page.filter { item in departureOf(item).map { WallClock.minutes($0) >= lastMinute } ?? false }
    }

    /// «Più presto»: up to `wanted` items leaving just before `first` and not before `notBefore` (the start of the day
    /// searched), from a window an hour back, then three, six and twelve while it holds fewer. A window that fills up
    /// before reaching `first` is continued from its last item; one still too full starts closer to `first`. Nil when
    /// the source could not answer; empty when nothing earlier leaves. Windows are minutes of the Italian clock, as
    /// Android's `LocalDateTime`: an hour back is an hour of the clock also on the nights the clock changes.
    public static func earlier<Item>(
        _ first: Date, departureOf: (Item) -> Date?, notBefore: Date? = nil, wanted: Int = earlierPage,
        load: (Date) async -> [Item]?
    ) async -> [Item]? {
        let firstMinute = WallClock.minutes(first)
        let floorMinute = notBefore.map { WallClock.minutes($0) }
        var answered = false
        var found: [Item] = []
        for span in earlierSpansMinutes {
            var from = firstMinute - span
            if let floorMinute, from < floorMinute { from = floorMinute }
            if from >= firstMinute { break }
            guard let loaded = await window(firstMinute, from: from, departureOf: departureOf, load: load) else {
                return answered ? latest(found, wanted: wanted, departureOf: departureOf) : nil
            }
            answered = true
            if loaded.overflow {
                let nearer = await closer(firstMinute, span: span, crowded: loaded.items, departureOf: departureOf, load: load)
                return latest(nearer, wanted: wanted, departureOf: departureOf)
            }
            found = loaded.items
            if found.count >= wanted || from == floorMinute { break }
        }
        return latest(found, wanted: wanted, departureOf: departureOf)
    }

    /// The items from `from` to before `first`, continued while full; nil when the source could not answer at all.
    private static func window<Item>(
        _ first: Int, from: Int, departureOf: (Item) -> Date?, load: (Date) async -> [Item]?
    ) async -> (items: [Item], overflow: Bool)? {
        var collected: [Item] = []
        var cursor = from
        for _ in 0..<maximumFillLoads {
            guard let page = await load(WallClock.date(cursor)) else { return collected.isEmpty ? nil : (collected, true) }
            let before = page.filter { item in minute(item, departureOf).map { $0 >= from && $0 < first } ?? false }
            collected += before
            let reachedFirst = page.contains { item in minute(item, departureOf).map { $0 >= first } ?? false }
            guard !reachedFirst, let lastBefore = before.compactMap({ minute($0, departureOf) }).max() else { return (collected, false) }
            cursor = lastBefore + 1
        }
        return (collected, true)
    }

    /// A window too full to reach `first`: windows closer to it until one does; the `crowded` items when none does.
    private static func closer<Item>(
        _ first: Int, span: Int, crowded: [Item], departureOf: (Item) -> Date?, load: (Date) async -> [Item]?
    ) async -> [Item] {
        var current = span
        while current > minimumEarlierSpanMinutes {
            current = max(minimumEarlierSpanMinutes, current / 2)
            guard let loaded = await window(first, from: first - current, departureOf: departureOf, load: load) else { return crowded }
            if !loaded.overflow { return loaded.items.isEmpty ? crowded : loaded.items }
        }
        return crowded
    }

    /// The last `wanted` items by departure (items without one first), in a stable order.
    private static func latest<Item>(_ items: [Item], wanted: Int, departureOf: (Item) -> Date?) -> [Item] {
        let sorted = items.enumerated().sorted { first, second in
            switch (minute(first.element, departureOf), minute(second.element, departureOf)) {
            case let (a?, b?) where a != b: return a < b
            case (nil, _?): return true
            case (_?, nil): return false
            default: return first.offset < second.offset
            }
        }.map(\.element)
        return Array(sorted.suffix(max(wanted, 0)))
    }

    /// The minute of the Italian clock an item leaves at.
    private static func minute<Item>(_ item: Item, _ departureOf: (Item) -> Date?) -> Int? {
        departureOf(item).map { WallClock.minutes($0) }
    }

    /// Whether the results show «Più presto» and «Più tardi», and their state, from the two sources (Android's
    /// `RouteResultsScreen`): each source that can page is asked; «nothing new» once every source that can page looked
    /// and found nothing.
    public struct Status: Equatable, Sendable {
        public let servicesCanPage: Bool
        public let journeysCanPage: Bool
        public let available: Bool
        public let earlierLoading: Bool
        public let laterLoading: Bool
        public let earlierNothingNew: Bool
        public let laterNothingNew: Bool

        public init(
            servicesCanPage: Bool, journeysCanPage: Bool, serviceEarlier: ResultsPageState, serviceLater: ResultsPageState,
            journeyEarlier: ResultsPageState, journeyLater: ResultsPageState, hasEntries: Bool
        ) {
            self.servicesCanPage = servicesCanPage
            self.journeysCanPage = journeysCanPage
            available = hasEntries && (servicesCanPage || journeysCanPage)
            earlierLoading = serviceEarlier.loading || (journeysCanPage && journeyEarlier.loading)
            laterLoading = serviceLater.loading || (journeysCanPage && journeyLater.loading)
            earlierNothingNew = !earlierLoading && (serviceEarlier.nothingNew || journeyEarlier.nothingNew)
                && (!servicesCanPage || serviceEarlier.nothingNew) && (!journeysCanPage || journeyEarlier.nothingNew)
            laterNothingNew = !laterLoading && (serviceLater.nothingNew || journeyLater.nothingNew)
                && (!servicesCanPage || serviceLater.nothingNew) && (!journeysCanPage || journeyLater.nothingNew)
        }
    }

    /// The trains of `page` not already in `shown`: another id, and not the same number leaving at the same minute.
    /// Later duplicates within `page` are dropped too.
    public static func newServices(shown: [TrainService], page: [TrainService]) -> [TrainService] {
        var ids = Set(shown.map(\.id))
        var runs = Set(shown.compactMap(runKey))
        var fresh: [TrainService] = []
        for service in page {
            let run = runKey(service)
            if !ids.contains(service.id), run.map({ !runs.contains($0) }) ?? true { fresh.append(service) }
            ids.insert(service.id)
            if let run { runs.insert(run) }
        }
        return fresh
    }

    /// The options of `page` not already in `shown`: another id and other vehicles at other times.
    public static func newOptions(shown: [JourneyPlanOption], page: [JourneyPlanOption]) -> [JourneyPlanOption] {
        var ids = Set(shown.map(\.id))
        var trips = Set(shown.map(tripKey))
        var fresh: [JourneyPlanOption] = []
        for option in page {
            let trip = tripKey(option)
            if !ids.contains(option.id), !trips.contains(trip) { fresh.append(option) }
            ids.insert(option.id)
            trips.insert(trip)
        }
        return fresh
    }

    private static func runKey(_ service: TrainService) -> String? {
        guard let departure = service.departureDate else { return nil }
        let digits = String(service.number.filter(\.isPlainDigit).drop { $0 == "0" })
        let number = digits.isEmpty ? service.number.trimmingCharacters(in: .whitespacesAndNewlines) : digits
        return "\(number)@\(Int(departure.timeIntervalSince1970 / 60))"
    }

    private static func tripKey(_ option: JourneyPlanOption) -> String {
        option.legs.map { leg in
            "\(leg.publicNumber ?? "null")@\(Int(leg.departure.timeIntervalSince1970))>\(Int(leg.arrival.timeIntervalSince1970))"
        }.joined(separator: "|")
    }
}

/// Minutes of the Italian clock (Android's `LocalDateTime` in `RailTime.defaultZone`): days since 1970-01-01 times
/// 1,440 plus the minute of the day, seconds dropped. A minute the clock skips is the first one after it.
enum WallClock {
    private static var rome: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        return calendar
    }

    private static var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        return calendar
    }

    static func minutes(_ date: Date) -> Int {
        let parts = rome.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let midnight = utc.date(from: DateComponents(year: parts.year, month: parts.month, day: parts.day)) ?? date
        let days = Int((midnight.timeIntervalSince1970 / 86_400).rounded(.down))
        return days * 1_440 + (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    static func date(_ minutes: Int) -> Date {
        let days = Int((Double(minutes) / 1_440).rounded(.down))
        let minuteOfDay = minutes - days * 1_440
        let day = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(days) * 86_400))
        let parts = DateComponents(year: day.year, month: day.month, day: day.day, hour: minuteOfDay / 60, minute: minuteOfDay % 60)
        return rome.date(from: parts) ?? Date(timeIntervalSince1970: TimeInterval(minutes) * 60)
    }
}

private extension Character {
    var isPlainDigit: Bool { ("0"..."9").contains(self) }
}

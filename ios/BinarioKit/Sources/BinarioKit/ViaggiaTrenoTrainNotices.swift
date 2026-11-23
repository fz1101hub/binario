import Foundation

/// One note of ViaggiaTreno's `smartcaring` answer (Android's `ViaggiaTrenoTrainNoticesAdapter.RawNote`):
/// its text, the Italian days it is valid on and the trains it names.
struct ViaggiaTrenoNote: Equatable, Sendable {
    struct Train: Equatable, Sendable {
        let number: String
        let originCode: String?
    }

    let id: String
    let text: String
    let englishText: String?
    /// The start of the first and of the last Italian day of validity.
    let validFrom: Date
    let validUntil: Date
    let insertedAt: Date?
    let trains: [Train]
    let mentionsStrike: Bool
}

/// The per-train notices of ViaggiaTreno («smart caring»), Android's `ViaggiaTrenoTrainNoticesAdapter`.
/// The feed and its reader are the shared table (contracts/shared-data/v1/notices-sources-v1.json,
/// `viaggiatreno-train-notices` and `trainNotices`); `ViaggiaTrenoLiveSource` asks it through the
/// realtime API's gate, and the answers are kept per train number. The answer lists notes of many
/// past days: only those valid on the service day, for the same number and, when known, the same
/// origin code, are shown, latest first. The notes reach the app over the cleartext hop: links
/// outside the publisher's HTTPS domains stay plain text.
final class ViaggiaTrenoTrainNotices: Sendable {
    let rules: NoticeRules
    let source: NoticeTable.Source
    let cache: TrainNoticeCache

    /// Nil when the table has no such feed (`SharedDataTests` proves it has).
    init?(rules: NoticeRules) {
        guard let source = NoticeTable.sources.first(where: { $0.id == NoticeSourceID.trainNotices }) else { return nil }
        self.rules = rules
        self.source = source
        cache = TrainNoticeCache(
            timeToLive: source.timeToLiveSeconds, maximumStale: source.maximumStaleSeconds,
            maximumEntries: NoticeTable.trainNotices.maximumCachedTrains
        )
    }

    /// The feed's limits, as `NoticeFeed` asks every feed of the table.
    var policy: LiveRequestPolicy {
        LiveRequestPolicy(
            accept: source.accept, requestTimeout: source.readTimeoutSeconds, totalTimeout: source.totalTimeoutSeconds,
            maximumBytes: source.maximumBytes, maximumRequests: source.maximumRequests,
            allowsViaggiaTrenoCleartextHop: source.viaggiaTrenoCleartextHop,
            allowsSameHostRedirects: source.redirects == "same-https-host"
        )
    }

    /// `…/smartcaring?commercialTrainNumber=<number>` for a number `number(_:)` accepted.
    func url(number: String) -> URL? {
        URL(string: "\(source.url)?commercialTrainNumber=\(number)")
    }

    /// The number as the feed takes it: trimmed, without leading zeros, one to six digits.
    static func number(_ value: String) -> String? {
        let trimmed = String(value.trimmingCharacters(in: .whitespacesAndNewlines).drop { $0 == "0" })
        return LiveText.isDigits(trimmed, count: 1...6) ? trimmed : nil
    }

    /// A ViaggiaTreno station code ("S01700"), upper-cased; nil for anything else.
    static func origin(_ value: String?) -> String? {
        guard let value else { return nil }
        let code = value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return NetworkReliability.isStationCode(code) ? code : nil
    }

    /// The notes of an answer; nil when it is not the expected list (`PublicSourceFormatException`).
    static func parse(_ body: String, rules: NoticeRules) -> [ViaggiaTrenoNote]? {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" { return [] }
        guard trimmed.hasPrefix("["), let array = LiveJSON.parse(trimmed) as? [Any] else { return nil }
        return array.prefix(NoticeTable.trainNotices.maximumNotes).compactMap { value -> ViaggiaTrenoNote? in
            guard let item = LiveJSONObject(value) else { return nil }
            return note(item, rules: rules)
        }
    }

    /// Android's tolerant `text`: nil when missing, JSON null, blank or «null».
    private static func text(_ item: LiveJSONObject, _ key: String) -> String? {
        guard let text = item.text(key), text != "null" else { return nil }
        return text
    }

    private static func note(_ item: LiveJSONObject, rules: NoticeRules) -> ViaggiaTrenoNote? {
        let limits = NoticeTable.trainNotices
        guard let id = text(item, "id"),
              let body = CleartextNoticeLinks.neutralize(text(item, "infoNote").map { String($0.prefix(limits.textLength)) }, rules: rules),
              let start = item.millis("startValidity"), let trains = item.array("trains") else { return nil }
        let end = item.millis("endValidity") ?? start
        let references = trains.prefix(limits.maximumTrains).compactMap { value -> ViaggiaTrenoNote.Train? in
            guard let train = LiveJSONObject(value), let number = text(train, "commercialTrainNumber") else { return nil }
            return ViaggiaTrenoNote.Train(number: String(number.drop { $0 == "0" }), originCode: text(train, "originCode")?.uppercased())
        }
        guard !references.isEmpty else { return nil }
        let english = CleartextNoticeLinks.neutralize(text(item, "infoNoteEn").map { String($0.prefix(limits.textLength)) }, rules: rules)
        return ViaggiaTrenoNote(
            id: id, text: body, englishText: english,
            validFrom: LiveRailTime.startOfDay(LiveRailTime.date(millis: start)),
            validUntil: LiveRailTime.startOfDay(LiveRailTime.date(millis: max(start, end))),
            insertedAt: item.millis("insertTimestamp").map(LiveRailTime.date(millis:)),
            trains: references, mentionsStrike: NoticeText.mentionsStrike([body, english], rules: rules)
        )
    }

    /// The notes valid on the Italian day of `serviceDate` for `number` and, when known, `origin`,
    /// each once, latest first (by insertion, then by id).
    static func select(_ notes: [ViaggiaTrenoNote], number: String, origin: String?, serviceDate: Date) -> [LiveTrainNotice] {
        let day = LiveRailTime.startOfDay(serviceDate)
        var seen: Set<String> = []
        let kept = notes.filter { note in
            note.validFrom <= day && day <= note.validUntil
                && note.trains.contains { $0.number == number && (origin == nil || $0.originCode == origin) }
                && seen.insert(note.id).inserted
        }
        let sorted = kept.sorted { first, second in
            let a = first.insertedAt ?? Date(timeIntervalSince1970: 0)
            let b = second.insertedAt ?? Date(timeIntervalSince1970: 0)
            return a != b ? a > b : first.id > second.id
        }
        return sorted.map { note in
            LiveTrainNotice(
                id: "\(NoticeSourceID.trainNotices):\(note.id)", text: note.text, englishText: note.englishText,
                validFrom: note.validFrom, validUntil: note.validUntil, publishedAt: note.insertedAt,
                mentionsStrike: note.mentionsStrike
            )
        }
    }
}

/// The last answer of each train number, Android's `ResilientSourceCache` with `maxEntries`: kept for
/// its time to live, then served as an earlier copy for at most `maximumStale` while a refresh fails;
/// the least recently read numbers go first. In memory only.
actor TrainNoticeCache {
    private let timeToLive: TimeInterval
    private let maximumStale: TimeInterval
    private let maximumEntries: Int
    private var kept: [String: (notes: [ViaggiaTrenoNote], fetchedAt: Date)] = [:]
    private var order: [String] = []

    init(timeToLive: TimeInterval, maximumStale: TimeInterval, maximumEntries: Int) {
        self.timeToLive = timeToLive
        self.maximumStale = maximumStale
        self.maximumEntries = max(1, maximumEntries)
    }

    func read(
        number: String, at now: Date, load: @escaping @Sendable () async -> NoticeFeedLoad<[ViaggiaTrenoNote]>
    ) async -> NoticeFeedRead<[ViaggiaTrenoNote]> {
        if let entry = kept[number], now < entry.fetchedAt.addingTimeInterval(timeToLive) {
            touch(number)
            return .fresh(entry.notes)
        }
        switch await load() {
        case let .loaded(notes, fetchedAt):
            if kept[number].map({ fetchedAt >= $0.fetchedAt }) ?? true { kept[number] = (notes, fetchedAt) }
            touch(number)
            return .fresh(notes)
        case let .failed(reason):
            if let entry = kept[number], now <= entry.fetchedAt.addingTimeInterval(timeToLive + maximumStale) {
                touch(number)
                return .stale(entry.notes)
            }
            return .failed(reason)
        }
    }

    private func touch(_ number: String) {
        order.removeAll { $0 == number }
        order.append(number)
        while order.count > maximumEntries {
            kept[order.removeFirst()] = nil
        }
    }
}

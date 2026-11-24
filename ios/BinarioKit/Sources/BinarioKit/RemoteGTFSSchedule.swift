import Foundation

/// One community GTFS archive of remote-gtfs-v1.json (European Sleeper) on this iPhone, as Android's
/// `RemoteGtfsFallbackDataSource` keeps it: downloaded only when a search or a board the other timetables cannot answer
/// needs it, with one HTTPS GET of the table's address (redirects only to HTTPS on the same host, the honest
/// User-Agent, at most `maximumArchiveBytes`), read into a timetable index (`RemoteGTFSIndex`) kept for
/// `cacheTimeToLiveSeconds` in the app's caches folder, within the launch. After a failure nothing is asked for `failureCooldownSeconds`;
/// after a refusal (403, 429, or 503 with Retry-After) for the Retry-After sent, else `refusalPauseSeconds`. Before
/// `notBefore` the archive answers nothing. Nothing about the user is sent.
public actor RemoteGTFSSchedule {
    public static let directoryName = "RemoteGTFS"

    public nonisolated let feed: SharedData.RemoteGtfs.RemoteFeed
    private let directory: URL
    private let client: LiveSourceClient
    private let now: @Sendable () -> Date
    private let published = PublishedIndex()
    private var current: (url: URL, expiresAt: Date)?
    private var pausedUntil: Date?
    private var loading: Task<URL?, Never>?
    private var reconciled = false

    public init(
        feed: SharedData.RemoteGtfs.RemoteFeed,
        directory: URL,
        transport: any LiveHTTPTransport = URLSessionLiveTransport(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.feed = feed
        self.directory = directory
        self.client = LiveSourceClient(transport: transport, now: now)
        self.now = now
    }

    static let policy = LiveRequestPolicy(
        accept: SharedData.RemoteGtfs.accept,
        requestTimeout: SharedData.RemoteGtfs.readTimeoutSeconds,
        totalTimeout: SharedData.RemoteGtfs.connectTimeoutSeconds + SharedData.RemoteGtfs.readTimeoutSeconds,
        maximumBytes: SharedData.RemoteGtfs.maximumArchiveBytes,
        maximumRequests: SharedData.RemoteGtfs.maximumRedirects + 1,
        allowsSameHostRedirects: true
    )

    /// The index built last, while it is valid, for the readers that cannot wait (`tripStops`).
    public nonisolated func publishedIndexURL() -> URL? {
        published.url(at: Date())
    }

    /// The index of the archive valid now, downloaded and built when there is none; nil before `notBefore`, during a
    /// pause, without a catalogue, or when the archive cannot be read. Concurrent callers share one download.
    public func index(forDay day: RailDay, stations: [Station]) async -> URL? {
        guard let first = RailDay(iso: feed.notBefore), first <= day else { return nil }
        let started = now()
        reconcileOnce()
        if let current, started < current.expiresAt { return current.url }
        if let pausedUntil, started < pausedUntil { return nil }
        guard !stations.isEmpty else { return nil }
        if let loading { return await loading.value }
        let task = Task { await self.load(stations: stations) }
        loading = task
        let url = await task.value
        loading = nil
        return url
    }

    private func load(stations: [Station]) async -> URL? {
        guard let url = URL(string: feed.url) else { return nil }
        let started = now()
        switch await client.getData(url, policy: Self.policy) {
        case let .failure(failure):
            record(failure, at: now())
            return nil
        case let .body(archive):
            let feed = self.feed
            let destination = fileURL(fingerprint: SHA256Digest.hex(archive))
            let directory = self.directory
            let built: Bool = await Task.detached(priority: .utility) {
                do {
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
                    try RemoteGTFSIndex.build(archive: archive, feed: feed, stations: stations, generatedOn: started, into: destination)
                    return SQLiteGTFSJourneyRepository.validatesDatabase(
                        at: destination, inputFingerprint: SHA256Digest.hex(archive), onlySourceID: feed.pack
                    )
                } catch {
                    return false
                }
            }.value
            guard built else {
                try? FileManager.default.removeItem(at: destination)
                pausedUntil = now().addingTimeInterval(SharedData.RemoteGtfs.failureCooldownSeconds)
                return nil
            }
            try? BundledGTFSScheduleInstaller.excludeFromBackup(destination)
            removeFiles(keeping: destination)
            let expiresAt = started.addingTimeInterval(SharedData.RemoteGtfs.cacheTimeToLiveSeconds)
            current = (destination, expiresAt)
            pausedUntil = nil
            published.set(destination, until: expiresAt)
            return destination
        }
    }

    /// Android's pause after a failed download: a refusal for its Retry-After or the refusal pause, any other failure
    /// for the cooldown. A cancelled request or a device without a connection asked nothing of the host.
    private func record(_ failure: LiveFetchFailure, at time: Date) {
        switch failure {
        case .cancelled, .offline, .paused:
            return
        case let .http(status, retryAfter) where status == 403 || status == 429 || (status == 503 && retryAfter != nil):
            let pause = min(retryAfter ?? SharedData.RemoteGtfs.refusalPauseSeconds, SharedData.SourceManners.maximumRetryAfterSeconds)
            pausedUntil = time.addingTimeInterval(max(pause, SharedData.RemoteGtfs.failureCooldownSeconds))
        default:
            pausedUntil = time.addingTimeInterval(SharedData.RemoteGtfs.failureCooldownSeconds)
        }
    }

    /// At the first question of this launch, every file a previous launch left is removed: as Android's cache, which
    /// lives in memory, the index lasts `cacheTimeToLiveSeconds` within one launch at most.
    private func reconcileOnce() {
        guard !reconciled else { return }
        reconciled = true
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [], options: [])) ?? []
        for url in urls where url.lastPathComponent.hasPrefix(".staged-") || url.lastPathComponent.hasPrefix(feed.pack + "-") {
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// «european-sleeper-<SHA-256 of the archive>.sqlite».
    private func fileURL(fingerprint: String) -> URL {
        directory.appendingPathComponent("\(feed.pack)-\(fingerprint).sqlite", isDirectory: false)
    }

    private func removeFiles(keeping kept: URL) {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [], options: [])) ?? []
        for url in urls where url.lastPathComponent.hasPrefix(feed.pack + "-") && url.standardizedFileURL.path != kept.standardizedFileURL.path {
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// True for a train of a downloaded archive (its identifier starts with the pack): the planner and «Partenze» say
    /// it comes from a coverage source.
    public static func isCoverageService(_ serviceID: String) -> Bool {
        SharedData.RemoteGtfs.feeds.contains { serviceID.hasPrefix($0.pack + ":") }
    }
}

/// The index URL of an archive, readable without waiting for the actor.
private final class PublishedIndex: @unchecked Sendable {
    private let lock = NSLock()
    private var value: (url: URL, expiresAt: Date)?

    func set(_ url: URL, until expiresAt: Date) {
        lock.lock()
        value = (url, expiresAt)
        lock.unlock()
    }

    func url(at time: Date) -> URL? {
        lock.lock()
        defer { lock.unlock() }
        guard let value, time < value.expiresAt else { return nil }
        return value.url
    }
}

public extension JourneySnapshot {
    /// Trains from a community coverage archive (European Sleeper/JBB): the planner says to check them with the operator.
    var usesCoverageTimetable: Bool { services.contains { RemoteGTFSSchedule.isCoverageService($0.id) } }
}

public extension StationBoardPage {
    var usesCoverageTimetable: Bool {
        departures.contains { RemoteGTFSSchedule.isCoverageService($0.service.id) }
            || firstDepartureNextDay.map { RemoteGTFSSchedule.isCoverageService($0.service.id) } == true
    }
}

/// What the iPhone asks when the bundled timetable gives no train, in Android's tiers: the official programmed
/// timetable of RFI first (`RfiProgrammedTimetable`, Android's primary route sources), then the community coverage
/// archives (`RemoteGTFSSchedule`, Android's `coverageRouteFallbacks`), which never take the place of a train an
/// official source gives. The catalogue is given once read (`useCatalogue`): the archives bind their stops to it and
/// RFI's rows are matched with the stations' aliases.
public final class TimetableFallbacks: @unchecked Sendable {
    public let rfi: RfiProgrammedTimetable?
    public let archives: [RemoteGTFSSchedule]
    private let lock = NSLock()
    private var stations: [Station] = []
    private var byID: [String: Station] = [:]

    public init(rfi: RfiProgrammedTimetable?, archives: [RemoteGTFSSchedule]) {
        self.rfi = rfi
        self.archives = archives
    }

    /// RFI and every archive of the shared table, the archives kept in `cachesDirectory`.
    public static func standard(cachesDirectory: URL, pauses: LiveSourcePauses = .shared) -> TimetableFallbacks {
        let directory = cachesDirectory.appendingPathComponent(RemoteGTFSSchedule.directoryName, isDirectory: true)
        return TimetableFallbacks(
            rfi: RfiProgrammedTimetable(pauses: pauses),
            archives: SharedData.RemoteGtfs.feeds.map { RemoteGTFSSchedule(feed: $0, directory: directory) }
        )
    }

    public func useCatalogue(_ catalogue: [Station]) {
        var index: [String: Station] = [:]
        for station in catalogue where index[station.id] == nil { index[station.id] = station }
        lock.lock()
        stations = catalogue
        byID = index
        lock.unlock()
    }

    private func catalogue() -> (stations: [Station], byID: [String: Station]) {
        lock.lock()
        defer { lock.unlock() }
        return (stations, byID)
    }

    /// The catalogue station of `identity`, with its aliases; the identity alone when the catalogue does not hold it.
    private func station(_ identity: StationIdentity, in byID: [String: Station]) -> Station {
        byID[identity.id] ?? Station(id: identity.id, name: identity.name, railwayCode: identity.railwayCode)
    }

    /// The trains of `query` from RFI's programmed timetable, else from an archive; nil when neither has one.
    func journeys(for query: JourneyQuery) async -> JourneyResult? {
        let known = catalogue()
        let origin = station(query.origin, in: known.byID)
        let destination = station(query.destination, in: known.byID)
        if let rfi, let snapshot = await rfi.journeys(origin: origin, destination: destination, departure: query.departure, query: query),
           !snapshot.services.isEmpty {
            return .available(snapshot)
        }
        let day = RailDay(italianDayOf: query.departure)
        for archive in archives {
            if Task.isCancelled { return nil }
            guard let url = await archive.index(forDay: day, stations: known.stations) else { continue }
            if case let .available(snapshot) = await SQLiteGTFSJourneyRepository(databaseURL: url).journeys(for: query) {
                return .available(snapshot)
            }
        }
        return nil
    }

    /// «Partenze» of `query` from RFI's programmed departures, else from an archive; nil when neither lists a train.
    func departures(for query: StationBoardQuery) async -> StationBoardResult? {
        let known = catalogue()
        let boardStation = station(query.station, in: known.byID)
        if let rfi, let page = await rfi.departures(station: boardStation, query: query) {
            return .available(page)
        }
        let day = RailDay(italianDayOf: query.from)
        for archive in archives {
            if Task.isCancelled { return nil }
            guard let url = await archive.index(forDay: day, stations: known.stations) else { continue }
            if case let .available(page) = await SQLiteGTFSJourneyRepository(databaseURL: url).departures(for: query),
               !page.departures.isEmpty || page.firstDepartureNextDay != nil {
                return .available(page)
            }
        }
        return nil
    }

    /// The index of the archive of `sourceID` built last, for the stops of its trips (Trip Updates).
    func indexURL(for sourceID: String) -> URL? {
        archives.first { $0.feed.pack == sourceID }?.publishedIndexURL()
    }
}

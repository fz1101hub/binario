import Foundation

/// How a download of a full foreign timetable ended.
public enum ForeignScheduleDownloadOutcome: Equatable, Sendable {
    case installed
    case failed(ForeignScheduleDownloadFailure)
}

public enum ForeignScheduleDownloadFailure: Equatable, Sendable {
    /// No connection, a time-out, a server error, or a redirect outside GitHub's download hosts.
    case network
    /// GitHub answered 403 or 429, now or before `until`: nothing is asked again until then.
    case refused(until: Date)
    /// The file received is not the pinned one (size, SHA-256, schema, pack): it was not used.
    case invalid
    /// The file could not be written on this iPhone.
    case storage
    /// The pack is not pinned, or its pinned timetable has ended.
    case notOffered
    /// Another download is running.
    case busy
    case cancelled
}

/// What this iPhone holds of a pinned pack.
public enum ForeignScheduleStatus: Equatable, Sendable {
    /// Only the bundled border zone.
    case borderOnly
    /// The full timetable of the current pin.
    case installed
    /// A full timetable of an earlier pin was removed: the current one can be downloaded (no
    /// download starts by itself).
    case updateAvailable
}

/// The full foreign timetables downloaded on this iPhone, one per pinned pack, in
/// `Application Support/ForeignSchedules/<sourceID>-<sha12>.sqlite` (excluded from backup).
///
/// A download starts only from a tap: one HTTPS GET of the pinned URL on github.com, redirected only
/// to GitHub's release-download hosts, with the honest User-Agent, at most the pinned size, checked
/// against the pinned SHA-256 and installed with the checks of the bundled index
/// (`BundledGTFSScheduleInstaller.installVerified`). One download at a time; nothing is retried by
/// itself; after a 403 or 429 nothing is asked again before the pause ends (the `Retry-After` GitHub
/// sent, at least 15 minutes, at most a day), in this process.
///
/// At launch `reconcile` removes staged leftovers, the files of packs no longer pinned and every
/// file of an earlier pin (its pack then reads «aggiornamento disponibile» until downloaded again or
/// deleted). `generation` counts the changes, for the readers to reopen the timetables.
public actor ForeignScheduleStore {
    public static let directoryName = "ForeignSchedules"
    static let refusalPause: TimeInterval = 900
    static let maximumRefusalPause: TimeInterval = 86_400
    static let updateMarkerExtension = "update-available"

    public let pins: ForeignSchedulePins
    public private(set) var generation: UInt64 = 0
    private let directory: URL
    private let client: LiveSourceClient
    private let now: @Sendable () -> Date
    private var downloading = false
    private var pausedUntil: Date?

    public init(
        directory: URL,
        pins: ForeignSchedulePins,
        transport: any LiveHTTPTransport = URLSessionLiveTransport(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.directory = directory
        self.pins = pins
        self.client = LiveSourceClient(transport: transport, now: now)
        self.now = now
    }

    /// The installed full timetables that match their current pin, by source. Removes what no
    /// current pin names, a file that no longer validates, and staged leftovers.
    public func reconcile() -> [String: URL] {
        let fileManager = FileManager.default
        let urls = (try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [],
            options: []
        )) ?? []
        var installed: [String: URL] = [:]
        var markers: [String: URL] = [:]
        for url in urls {
            let name = url.lastPathComponent
            if name.hasPrefix(BundledGTFSScheduleInstaller.foreignStagedPrefix) {
                try? fileManager.removeItem(at: url)
                continue
            }
            if url.pathExtension == Self.updateMarkerExtension {
                markers[url.deletingPathExtension().lastPathComponent] = url
                continue
            }
            guard url.pathExtension == "sqlite" else { continue }
            let parsed = Self.parse(fileName: name)
            if let parsed, let pack = pins.pack(parsed.sourceID), pack.isDownloadable, let artifact = pack.ios,
               artifact.shortDigest == parsed.digest, installed[pack.sourceID] == nil,
               Self.verifies(url, artifact: artifact, sourceID: pack.sourceID) {
                try? BundledGTFSScheduleInstaller.excludeFromBackup(url)
                installed[pack.sourceID] = BundledGTFSScheduleInstaller.withoutCachedResourceValues(url)
                continue
            }
            // An earlier pin, a damaged file or a pack no longer pinned: removed, never used.
            try? fileManager.removeItem(at: url)
            if let parsed, pins.pack(parsed.sourceID)?.isDownloadable == true, markers[parsed.sourceID] == nil {
                markers[parsed.sourceID] = writeMarker(parsed.sourceID)
            }
        }
        for (sourceID, marker) in markers {
            let stillUseful = installed[sourceID] == nil && pins.pack(sourceID)?.isDownloadable == true
            if !stillUseful { try? fileManager.removeItem(at: marker) }
        }
        return installed
    }

    /// The status of every downloadable pack, from the files present (`reconcile` checked them).
    public func statuses() -> [String: ForeignScheduleStatus] {
        var result: [String: ForeignScheduleStatus] = [:]
        for pack in pins.downloadablePacks {
            guard let artifact = pack.ios else { continue }
            if FileManager.default.fileExists(atPath: fileURL(pack.sourceID, artifact: artifact).path) {
                result[pack.sourceID] = .installed
            } else if FileManager.default.fileExists(atPath: markerURL(pack.sourceID).path) {
                result[pack.sourceID] = .updateAvailable
            } else {
                result[pack.sourceID] = .borderOnly
            }
        }
        return result
    }

    /// When GitHub refused a download, the end of the pause; nil when a download may be asked.
    public func refusedUntil() -> Date? {
        guard let pausedUntil, now() < pausedUntil else { return nil }
        return pausedUntil
    }

    /// Downloads, checks and installs the pinned full timetable of `sourceID`. Called only after
    /// the user tapped «Scarica»; cancelling the calling task stops the transfer.
    public func download(_ sourceID: String) async -> ForeignScheduleDownloadOutcome {
        guard let pack = pins.pack(sourceID), pack.isDownloadable, let artifact = pack.ios else {
            return .failed(.notOffered)
        }
        let started = now()
        guard !pack.hasEnded(before: ForeignSchedulePins.romeDay(started)) else { return .failed(.notOffered) }
        if let pausedUntil, started < pausedUntil { return .failed(.refused(until: pausedUntil)) }
        guard !downloading else { return .failed(.busy) }
        if Task.isCancelled { return .failed(.cancelled) }
        downloading = true
        defer { downloading = false }

        let fetched = await client.getData(artifact.url, policy: .githubReleaseAsset(maximumBytes: artifact.sizeBytes))
        let compressed: Data
        switch fetched {
        case let .body(body):
            compressed = body
        case let .failure(failure):
            return .failed(record(failure))
        }
        if Task.isCancelled { return .failed(.cancelled) }

        let destination = fileURL(sourceID, artifact: artifact)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
            _ = try BundledGTFSScheduleInstaller().installVerified(
                compressed: compressed,
                expected: artifact,
                sourceID: sourceID,
                into: destination
            )
        } catch BundledGTFSScheduleError.fileSystemFailure {
            return .failed(.storage)
        } catch {
            return .failed(.invalid)
        }
        removeFiles(of: sourceID, keeping: destination)
        try? FileManager.default.removeItem(at: markerURL(sourceID))
        generation &+= 1
        return .installed
    }

    /// Removes the full timetable of `sourceID` (and an «aggiornamento disponibile» mark): the
    /// bundled border zone serves the pack again. A reader that has the file open finishes its read.
    public func delete(_ sourceID: String) {
        removeFiles(of: sourceID, keeping: nil)
        try? FileManager.default.removeItem(at: markerURL(sourceID))
        generation &+= 1
    }

    /// What a failed transfer means for the user, and the pause after a refusal.
    private func record(_ failure: LiveFetchFailure) -> ForeignScheduleDownloadFailure {
        switch failure {
        case .cancelled:
            return .cancelled
        case .tooLarge:
            return .invalid
        case let .paused(until):
            return .refused(until: until)
        case let .http(status, retryAfter):
            guard status == 403 || status == 429 else { return .network }
            let asked: TimeInterval = retryAfter ?? 0
            let pause: TimeInterval = min(max(asked, Self.refusalPause), Self.maximumRefusalPause)
            let until = now().addingTimeInterval(pause)
            pausedUntil = until
            return .refused(until: until)
        case .invalidRequest, .network, .offline, .timedOut, .redirectRefused, .tooManyRedirects:
            return .network
        }
    }

    private func fileURL(_ sourceID: String, artifact: ForeignSchedulePins.Artifact) -> URL {
        directory.appendingPathComponent("\(sourceID)-\(artifact.shortDigest).sqlite", isDirectory: false)
    }

    private func markerURL(_ sourceID: String) -> URL {
        directory.appendingPathComponent("\(sourceID).\(Self.updateMarkerExtension)", isDirectory: false)
    }

    private func writeMarker(_ sourceID: String) -> URL {
        let url = markerURL(sourceID)
        if (try? Data().write(to: url, options: .atomic)) != nil {
            try? BundledGTFSScheduleInstaller.excludeFromBackup(url)
        }
        return url
    }

    /// Every installed file of `sourceID` but `keeping`.
    private func removeFiles(of sourceID: String, keeping: URL?) {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [],
            options: [.skipsHiddenFiles]
        )) ?? []
        let kept = keeping?.standardizedFileURL.path
        for url in urls where url.pathExtension == "sqlite" {
            guard Self.parse(fileName: url.lastPathComponent)?.sourceID == sourceID,
                  url.standardizedFileURL.path != kept else { continue }
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// The pinned size and the checks of `validatesDatabase`, with the single pack: a launch does
    /// not hash the whole file again (its name carries the pin's digest; the download checked it).
    private static func verifies(_ url: URL, artifact: ForeignSchedulePins.Artifact, sourceID: String) -> Bool {
        let fresh = BundledGTFSScheduleInstaller.withoutCachedResourceValues(url)
        guard let size = try? fresh.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size == artifact.databaseSizeBytes else { return false }
        return SQLiteGTFSJourneyRepository.validatesDatabase(
            at: fresh,
            inputFingerprint: artifact.inputFingerprintSHA256,
            onlySourceID: sourceID
        )
    }

    /// «oebb-gtfs-8e7ed242109b.sqlite»: the source and the 12 hex of its pin.
    static func parse(fileName: String) -> (sourceID: String, digest: String)? {
        guard fileName.hasSuffix(".sqlite") else { return nil }
        let stem = String(fileName.dropLast(".sqlite".count))
        guard let dash = stem.lastIndex(of: "-") else { return nil }
        let sourceID = String(stem[stem.startIndex..<dash])
        let digest = String(stem[stem.index(after: dash)...])
        guard !sourceID.isEmpty, digest.utf8.count == 12,
              digest.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) })
        else { return nil }
        return (sourceID, digest)
    }
}

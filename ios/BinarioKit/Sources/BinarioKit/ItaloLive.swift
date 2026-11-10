import Foundation

/// A station of the Italo passenger pages: its operator code, label, page slug and other labels.
struct ItaloPublicStation: Equatable, Sendable {
    let code: String
    let name: String
    let slug: String
    let aliases: [String]

    var names: [String] { [name] + aliases }
}

/// The Italo payloads and pages, read as Android's `ItaloPublicJson`, `ItaloPublicStationAdapter`
/// and `ItaloTrainNumberAdapter` read them. Pure functions.
enum ItaloParser {
    static let maximumBoardRows = 400
    static let maximumCalls = 500
    static let maximumCatalogueRows = 1_000
    static let maximumNameLength = 96
    /// How long an Italo board value stays live (Android: 60 seconds); a run: 30 seconds.
    static let boardTimeToLive: TimeInterval = 60
    static let runTimeToLive: TimeInterval = 30

    /// `italoStationKey`: accents removed, upper case, letters and digits only.
    static func stationKey(_ value: String) -> String {
        LiveText.catalogueStationKey(value, sanPrefix: false)
    }

    /// The slug Android derives from a label: accents removed, lower case, other characters as "-".
    static func slug(_ value: String) -> String {
        let folded = value.folding(options: [.diacriticInsensitive], locale: Locale(identifier: "en_US_POSIX")).lowercased()
        var result = ""
        var pendingDash = false
        for scalar in folded.unicodeScalars {
            if ("a"..."z").contains(scalar) || ("0"..."9").contains(scalar) {
                if pendingDash, !result.isEmpty { result.append("-") }
                pendingDash = false
                result.unicodeScalars.append(scalar)
            } else {
                pendingDash = true
            }
        }
        return result
    }

    static func isSlug(_ value: String) -> Bool { slugPattern.contains(value) }
    static func isCode(_ value: String) -> Bool { codePattern.contains(value) }

    private static let slugPattern = LiveRegex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private static let codePattern = LiveRegex("^[A-Za-z0-9_]{2,12}$")
    private static let publishedLabel = LiveRegex("^[\\p{L}\\p{N} .'’()_-]+$")
    private static let scriptCatalogue = LiveRegex(#"["']?station(?:List|Coding)["']?\s*[:=]\s*[\[{]"#, caseInsensitive: true)
    private static let clockPattern = LiveRegex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
    private static let numberPattern = LiveRegex("^\\d{3,5}$")
    private static let msDate = LiveRegex(#"^/Date\((\d+)(?:[+-]\d+)?\)/$"#)
    private static let isoDateTime = LiveRegex(
        #"^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d{2}:?\d{2})?)?$"#
    )
    private static let italianDateTime = LiveRegex(#"^(\d{2})/(\d{2})/(\d{4}) (\d{2}):(\d{2})(?::(\d{2}))?$"#)

    // MARK: Station catalogue

    /// `parseItaloStationCatalog`: the station list of the passenger page (the `station-to-url`
    /// JSON, labels of its links and options, older `stationList`/`stationCoding` assignments).
    static func catalogue(_ html: String) -> [ItaloPublicStation] {
        let page = LiveHTML(html)
        var stations: [ItaloPublicStation] = []
        func add(code: String, name: String, slug published: String = "") {
            guard isCode(code), !LiveText.compact(name).isEmpty else { return }
            let resolved = published.isEmpty ? Self.slug(name) : published
            stations.append(ItaloPublicStation(code: code, name: name, slug: resolved, aliases: []))
        }
        var labelsBySlug: [String: [String]] = [:]
        for link in page.elements(where: { $0.name == "a" && $0.attributes["href"] != nil }) {
            let href = page.tag(link).attributes["href"] ?? ""
            let path = URLComponents(string: href)?.path ?? ""
            let trimmedPath = path.hasSuffix("/") ? String(path.dropLast()) : path
            guard let marker = trimmedPath.range(of: "/it/stazione/") else { continue }
            let linkSlug = String(trimmedPath[marker.upperBound...])
            let label = page.text(link)
            if isSlug(linkSlug), !label.isEmpty { labelsBySlug[linkSlug, default: []].append(label) }
        }
        var labelsByCode: [String: String] = [:]
        for option in page.elements(where: { $0.name == "option" && $0.attributes["value"] != nil }) {
            guard let value = page.tag(option).attributes["value"], isCode(value) else { continue }
            labelsByCode[value] = page.text(option)
        }
        // Current passenger page: the code/URL map is JSON inside a span, not a script assignment.
        let spans = page.elements {
            $0.attributes["data-ntv-name"]?.lowercased() == "station-to-url" && $0.attributes["data-ntv-type"]?.lowercased() == "json"
        }
        for span in spans {
            guard let rows = LiveJSON.parse(page.wholeText(span)) as? [Any] else { continue }
            for item in rows.prefix(maximumCatalogueRows) {
                guard let row = LiveJSONObject(item) else { continue }
                let code = first(row, ["code"])
                let published = first(row, ["urlCoding"])
                guard isCode(code), (1...100).contains(published.count), publishedLabel.contains(published) else { continue }
                // The live page also puts display labels ("Genova Brignole") in urlCoding.
                let pageSlug = Self.slug(published)
                guard isSlug(pageSlug) else { continue }
                let known = ItaloStationFallbacks.single { $0.code == code }
                    ?? ItaloStationFallbacks.single {
                        // A curated label survives a changed code (BO2 → BC_, published as "bologna").
                        let name = $0.name.hasSuffix(" Centrale") ? String($0.name.dropLast(" Centrale".count)) : $0.name
                        return stationKey(name) == stationKey(published)
                    }
                let slugLabel = pageSlug.replacingOccurrences(of: "-", with: " ")
                let publishedLabels = labelsBySlug[pageSlug] ?? []
                var name = first(row, ["name", "label", "Descrizione", "NomeStazione"])
                if name.isEmpty { name = labelsByCode[code] ?? "" }
                if name.isEmpty { name = known?.name ?? "" }
                if name.isEmpty, published.contains(where: { $0.isUppercase }) || published.contains(" ") { name = published }
                if name.isEmpty, publishedLabels.count == 1 { name = publishedLabels[0] }
                if name.isEmpty { name = slugLabel }
                let aliases = (publishedLabels + [published, slugLabel] + (known?.aliases ?? [])).filter { !$0.isEmpty }
                stations.append(ItaloPublicStation(code: code, name: name, slug: pageSlug, aliases: unique(aliases)))
            }
        }
        for end in scriptCatalogue.matchEnds(in: html, limit: 16) {
            switch LiveJSON.value(in: html, startingAtUTF16Offset: end - 1) {
            case let rows as [Any]:
                for item in rows.prefix(maximumCatalogueRows) {
                    guard let row = LiveJSONObject(item) else { continue }
                    add(code: first(row, ["code", "value", "Codice", "CodiceStazione", "stationCode"]),
                        name: first(row, ["name", "label", "text", "Descrizione", "NomeStazione", "stationName"]),
                        slug: first(row, ["slug"]))
                }
            case let object as [String: Any]:
                for code in object.keys.sorted().prefix(maximumCatalogueRows) {
                    if let label = object[code] as? String {
                        add(code: code, name: label)
                    } else if let row = LiveJSONObject(object[code]) {
                        let own = first(row, ["code", "CodiceStazione"])
                        add(code: own.isEmpty ? code : own, name: first(row, ["name", "label", "Descrizione", "NomeStazione"]),
                            slug: first(row, ["slug"]))
                    }
                }
            default:
                continue
            }
        }
        // One station per code: the longest label, every label and slug as aliases.
        var order: [String] = []
        var byCode: [String: [ItaloPublicStation]] = [:]
        for station in stations {
            if byCode[station.code] == nil { order.append(station.code) }
            byCode[station.code, default: []].append(station)
        }
        return order.compactMap { code -> ItaloPublicStation? in
            guard let alternatives = byCode[code], let first = alternatives.first else { return nil }
            var longest = first
            for alternative in alternatives where alternative.name.utf16.count > longest.name.utf16.count { longest = alternative }
            let aliases = alternatives.flatMap { [$0.name, $0.slug.replacingOccurrences(of: "-", with: " ")] + $0.aliases }
            return ItaloPublicStation(code: first.code, name: longest.name, slug: first.slug, aliases: unique(aliases))
        }
    }

    /// The first of `keys` with a text other than «null», "" without one.
    private static func first(_ row: LiveJSONObject, _ keys: [String]) -> String {
        for key in keys {
            if let value = row.text(key), value != "null" { return value }
        }
        return ""
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen: Set<String> = []
        return values.filter { seen.insert($0).inserted }
    }

    // MARK: Station board

    /// `parseItaloPublicBoard` with `LegacyStationBoardInterop.build`: the departures of the asked
    /// station, nil when the answer is not JSON. Another station's board gives no rows.
    static func departures(
        _ text: String, stationNames: [String], window: LiveBoardWindow, fetchedAt: Date
    ) -> [LiveDeparture]? {
        guard text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{"),
              let root = LiveJSONObject(LiveJSON.parse(text)) else { return nil }
        if root.bool("IsEmpty") { return [] }
        let requested = Set(stationNames.map(stationKey))
        var accepted = requested
        for station in ItaloStationFallbacks.all where station.names.contains(where: { requested.contains(stationKey($0)) }) {
            accepted.formUnion(station.names.map(stationKey))
        }
        let published = first(root, ["DescrizioneLocalita"])
        if !published.isEmpty, !accepted.contains(stationKey(published)) { return [] }
        let observed = observation(root.text("LastUpdate") ?? "", now: fetchedAt)
        let fresh = observed.map { LiveRailTime.isRecent($0, fetched: fetchedAt) } ?? false
        guard let rows = root.array("ListaTreniPartenza") else { return [] }
        let boardObservation = LiveObservation(
            source: .italo, fetchedAt: fetchedAt, observedAt: observed ?? fetchedAt,
            expiresAt: fetchedAt.addingTimeInterval(boardTimeToLive)
        )
        var result: [LiveDeparture] = []
        for item in rows.prefix(maximumBoardRows) {
            guard let row = LiveJSONObject(item) else { continue }
            let number = first(row, ["Numero"])
            let destination = first(row, ["DescrizioneLocalita"])
            let clock = first(row, ["OraPassaggio"])
            guard numberPattern.contains(number), !destination.isEmpty, destination.count <= maximumNameLength,
                  clockPattern.contains(clock),
                  let scheduled = LiveBoardTime.resolve(clock, from: window.lateSince ?? window.from, to: window.to) else { continue }
            let delay = Int(first(row, ["Ritardo"])).flatMap { ViaggiaTrenoParser.delayRange.contains($0) ? $0 : nil }
            let cancelled = LiveText.hasExplicitCancellationStatus(first(row, ["Informazioni"]) + " " + first(row, ["Descrizione"]))
            let verified = fresh && (delay != nil || cancelled)
            var expected: Date?
            if verified && !cancelled {
                let published = first(row, ["NuovoOrario"])
                if clockPattern.contains(published) {
                    expected = LiveBoardTime.expected(published, scheduled: scheduled)
                } else {
                    expected = scheduled.addingTimeInterval(TimeInterval(delay ?? 0) * 60)
                }
            }
            guard window.keeps(scheduled: scheduled, expected: expected) else { continue }
            let platformText = first(row, ["Binario"])
            let platform = platformText.isEmpty || ["-", "N/D"].contains(platformText) || platformText.count > 12 ? nil : platformText
            let status: LiveDepartureStatus? = verified ? (cancelled ? .cancelled : ((delay ?? 0) > 0 ? .delayed : .onTime)) : nil
            result.append(LiveDeparture(
                id: "italo-status:\(number):\(LiveRailTime.isoDay(scheduled))", number: number, operatorID: "italo",
                category: "Italo", destination: destination, scheduled: scheduled,
                expected: expected.map { LiveSourced($0, observation: boardObservation) },
                platform: platform.map { LiveSourced($0, observation: boardObservation) }, scheduledPlatform: nil,
                status: status.map { LiveSourced($0, observation: boardObservation) }, sources: [.italo], viaggiaTrenoRun: nil,
                observation: boardObservation
            ))
        }
        return result
    }

    // MARK: Train number

    /// `ItaloTrainNumberAdapter.parse`: the run of `RicercaTrenoService`, nil when it is not the
    /// asked number or does not run on the asked day.
    static func run(_ root: LiveJSONObject, query: LiveTrainNumberQuery, serviceDay: Date, fetchedAt: Date) -> LiveRunStatus? {
        guard let run = root.object("TrainSchedule") else { return nil }
        let number = [run.text("NumeroTreno"), run.text("TrainNumber"), run.text("RfiTrainNumber")]
            .compactMap { $0 }.first { $0 != "null" }
        guard let number, number.count <= 12, query.matches(number) else { return nil }
        let departureDay = dateTime(run.text("DepartureDate"), day: serviceDay).map(LiveRailTime.startOfDay) ?? LiveRailTime.startOfDay(serviceDay)

        var calls: [(row: LiveJSONObject, reached: Bool)] = []
        if let origin = run.object("StazionePartenza") { calls.append((origin, true)) }
        for (key, reached) in [("StazioniFerme", true), ("StazioniNonFerme", false)] {
            for item in (run.array(key) ?? []).prefix(maximumCalls) {
                if let row = LiveJSONObject(item) { calls.append((row, reached)) }
            }
        }
        var seen: Set<String> = []
        calls = calls.filter { call in
            let identity = ["LocationCode", "StationNumber", "EstimatedDepartureTime", "EstimatedArrivalTime"]
                .map { call.row.text($0) ?? "\u{0}" }.joined(separator: "\u{1}")
            return seen.insert(identity).inserted
        }
        calls = calls.enumerated().sorted { first, second in
            let firstOrder = first.element.row.int("StationNumber") ?? Int.max
            let secondOrder = second.element.row.int("StationNumber") ?? Int.max
            return firstOrder == secondOrder ? first.offset < second.offset : firstOrder < secondOrder
        }.map { $0.element }
        guard calls.count >= 2 else { return nil }

        var day = departureDay
        var previous: Date?
        var stops: [(name: String, scheduled: Date, actual: Date?, platform: String?)] = []
        for (index, call) in calls.enumerated() {
            let row = call.row
            guard let name = LiveText.bounded(text(row, "LocationDescription"), maximumLength: maximumNameLength),
                  let scheduledText = text(row, index == 0 ? "EstimatedDepartureTime" : "EstimatedArrivalTime")
                    ?? text(row, "EstimatedDepartureTime") ?? text(row, "EstimatedArrivalTime"),
                  var scheduled = dateTime(scheduledText, day: day) else { continue }
            if let previous, scheduled < previous.addingTimeInterval(-12 * 3_600) {
                scheduled = LiveRailTime.addingDays(1, to: scheduled) ?? scheduled
            }
            day = LiveRailTime.startOfDay(scheduled)
            previous = scheduled
            let actualText = text(row, index == 0 ? "ActualDepartureTime" : "ActualArrivalDateTime") ?? text(row, "ActualArrivalTime")
            var actual: Date?
            if call.reached, let candidate = dateTime(actualText, day: day) {
                // A default or future actual time is no observation; midnight itself is a railway time.
                let year = LiveRailTime.calendar.component(.year, from: candidate)
                if year >= 2000, candidate <= fetchedAt.addingTimeInterval(60), candidate >= scheduled.addingTimeInterval(-15 * 60) {
                    actual = candidate
                }
            }
            let platform = LiveText.bounded(text(row, "ActualArrivalPlatform"), maximumLength: 12)
            stops.append((name, scheduled, actual, platform))
        }
        guard stops.count >= 2, let firstStop = stops.first, let lastStop = stops.last,
              LiveRailTime.days(from: firstStop.scheduled, to: serviceDay) >= 0,
              LiveRailTime.days(from: serviceDay, to: lastStop.scheduled) >= 0 else { return nil }

        let observed = root.text("LastUpdate").flatMap { observation($0, now: fetchedAt) }.flatMap {
            LiveRailTime.isRecent($0, fetched: fetchedAt) ? $0 : nil
        }
        let disruption = run.object("Distruption")
        let delay = disruption?.int("DelayAmount").flatMap { ViaggiaTrenoParser.delayRange.contains($0) ? $0 : nil }
        let state = disruption?.text("RunningState") ?? ""
        let cancelled = LiveText.hasExplicitCancellationStatus(state)
        let running = ["IN CORSA", "IN VIAGGIO", "ARRIVATO"].contains(state.uppercased())
        let verified = observed != nil && (cancelled || (delay != nil && (stops.contains { $0.actual != nil } || running)))
        let delaySeconds = TimeInterval(delay ?? 0) * 60
        let liveStops = stops.map { stop -> LiveStopStatus in
            let expected = stop.actual ?? (verified && !cancelled ? stop.scheduled.addingTimeInterval(delaySeconds) : stop.scheduled)
            return LiveStopStatus(
                stationCode: nil, stationName: stop.name, scheduled: stop.scheduled, scheduledDeparture: nil, expected: expected,
                actual: stop.actual, actualDeparture: nil, platform: stop.platform, scheduledPlatform: nil,
                delayMinutes: LiveRailTime.wholeMinutes(from: stop.scheduled, to: expected), passed: stop.actual != nil,
                skipped: false, operationalStatusVerified: stop.actual != nil || (verified && !cancelled)
            )
        }
        guard let departure = liveStops.first, let arrival = liveStops.last else { return nil }
        let liveState: LiveRunState = verified && cancelled ? .cancelled : (verified && (delay ?? 0) > 0 ? .delayed : .onTime)
        return LiveRunStatus(
            id: "italo:\(number):\(LiveRailTime.millis(departure.scheduled))", number: number, category: "Italo",
            operatorName: "Italo", operatorID: "italo", originCode: nil, originName: departure.stationName,
            destinationName: arrival.stationName, scheduledDeparture: departure.scheduled,
            expectedDeparture: departure.expected, platform: departure.platform, scheduledPlatform: nil, state: liveState,
            cancellation: verified && cancelled ? .full : .none, delayMinutes: verified ? delay : nil,
            operationalStatusVerified: verified,
            lastObservation: observed.map { LiveRunObservationPoint(stationName: nil, at: $0, isRecent: true) },
            stops: liveStops,
            observation: LiveObservation(
                source: .italo, fetchedAt: fetchedAt, observedAt: verified ? (observed ?? fetchedAt) : fetchedAt,
                expiresAt: fetchedAt.addingTimeInterval(runTimeToLive)
            ),
            viaggiaTrenoRun: nil
        )
    }

    private static func text(_ row: LiveJSONObject, _ key: String) -> String? {
        row.text(key).flatMap { $0 == "null" ? nil : $0 }
    }

    // MARK: Times

    /// Android's `dateTime`: `/Date(ms)/`, an ISO instant, an ISO local date-time or date (Italian
    /// time), or a clock time on `day`.
    static func dateTime(_ value: String?, day: Date) -> Date? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), !value.isEmpty, value.count <= 40 else { return nil }
        if let groups = msDate.firstMatch(in: value), let digits = groups[1], let millis = Int64(digits) {
            return LiveRailTime.date(millis: millis)
        }
        if let date = iso(value, requireTime: false) { return date }
        guard let clock = LiveRailTime.clock(value) else { return nil }
        let seconds = value.split(separator: ":").dropFirst(2).first.flatMap { Int($0.prefix(2)) } ?? 0
        return LiveRailTime.date(onDayOf: day, hour: clock.hour, minute: clock.minute, second: seconds)
    }

    /// Android's `parseItaloObservation`: an empty value is a current board (now); a clock time is
    /// the latest one not after now.
    static func observation(_ value: String, now: Date) -> Date? {
        let value = value.trimmingCharacters(in: .whitespaces)
        if value.isEmpty { return now }
        guard value.count <= 40 else { return nil }
        if let groups = msDate.firstMatch(in: value), let digits = groups[1], let millis = Int64(digits) {
            return LiveRailTime.date(millis: millis)
        }
        if let date = iso(value, requireTime: true) { return date }
        if let groups = italianDateTime.firstMatch(in: value), groups.count == 7,
           let day = groups[1].flatMap({ Int($0) }), let month = groups[2].flatMap({ Int($0) }),
           let year = groups[3].flatMap({ Int($0) }), let hour = groups[4].flatMap({ Int($0) }),
           let minute = groups[5].flatMap({ Int($0) }) {
            let second = groups[6].flatMap { Int($0) } ?? 0
            return local(year: year, month: month, day: day, hour: hour, minute: minute, second: second, offset: nil)
        }
        guard let clock = LiveRailTime.clock(value) else { return nil }
        let seconds = value.split(separator: ":").dropFirst(2).first.flatMap { Int($0.prefix(2)) } ?? 0
        guard var instant = LiveRailTime.date(onDayOf: now, hour: clock.hour, minute: clock.minute, second: seconds) else { return nil }
        if instant > now.addingTimeInterval(LiveRailTime.futureToleranceSeconds) {
            instant = LiveRailTime.addingDays(-1, to: instant) ?? instant
        }
        return instant
    }

    private static func iso(_ value: String, requireTime: Bool) -> Date? {
        guard let groups = isoDateTime.firstMatch(in: value), groups.count == 8,
              let year = groups[1].flatMap({ Int($0) }), let month = groups[2].flatMap({ Int($0) }),
              let day = groups[3].flatMap({ Int($0) }) else { return nil }
        guard let hourText = groups[4], let hour = Int(hourText), let minute = groups[5].flatMap({ Int($0) }) else {
            if requireTime { return nil }
            return local(year: year, month: month, day: day, hour: 0, minute: 0, second: 0, offset: nil)
        }
        let second = groups[6].flatMap { Int($0) } ?? 0
        var offset: Int?
        if let zone = groups[7] {
            if zone == "Z" {
                offset = 0
            } else {
                let digits = zone.dropFirst().filter { $0 != ":" }
                guard digits.count == 4, let hours = Int(digits.prefix(2)), let minutes = Int(digits.suffix(2)),
                      hours <= 18, minutes <= 59 else { return nil }
                let magnitude = hours * 3_600 + minutes * 60
                offset = zone.hasPrefix("-") ? -magnitude : magnitude
            }
        }
        return local(year: year, month: month, day: day, hour: hour, minute: minute, second: second, offset: offset)
    }

    /// The instant of a calendar date and time, in Italian time or at `offset` seconds from UTC.
    private static func local(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, offset: Int?) -> Date? {
        guard (1...9_999).contains(year), (1...12).contains(month), (1...31).contains(day), (0...23).contains(hour),
              (0...59).contains(minute), (0...59).contains(second) else { return nil }
        var calendar = LiveRailTime.calendar
        if let offset, let zone = TimeZone(secondsFromGMT: offset) { calendar.timeZone = zone }
        let components = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        guard let date = calendar.date(from: components),
              calendar.component(.day, from: date) == day, calendar.component(.month, from: date) == month else { return nil }
        return date
    }
}

/// Android's `ItaloPublicStationFallbacks`: reviewed passenger-facing Italo codes, used when the
/// public page moves its station list away; never inferred from RFI or ViaggiaTreno codes.
enum ItaloStationFallbacks {
    static let all: [ItaloPublicStation] = {
        let aliases: [String: [String]] = [
            "RRO": ["Milano Expo Rho", "Rho Fiera"], "NAC": ["Napoli"],
            "RCE": ["Reggio Calabria"], "LON": ["Lamezia Terme C"],
            "SDC": ["Scalea"], "VLH": ["Vallo d.Lucania"], "TSC": ["Trieste C.le"],
            "LTL": ["Latisana-Lignano"], "PGR": ["Portogruaro"], "SDP": ["S.Dona-Jesolo"],
            "DSG": ["Desenzano"], "PSY": ["Peschiera"], "VSL": ["Venezia S.Lucia"],
            "SMN": ["Firenze S.M.N.", "Firenze S. Maria Novella"], "AAV": ["Mediopadana R.Emilia"],
        ]
        let stations: [(String, String)] = [
            ("MC_", "Milano Centrale"), ("RRO", "Milano Rho Fiera"), ("RMT", "Roma Termini"),
            ("RTB", "Roma Tiburtina"), ("SMN", "Firenze Santa Maria Novella"), ("BO2", "Bologna Centrale"),
            ("AAV", "Reggio Emilia AV Mediopadana"), ("OUE", "Torino Porta Susa"), ("TOP", "Torino Porta Nuova"),
            ("RG_", "Milano Rogoredo"), ("NAF", "Napoli Afragola"), ("NAC", "Napoli Centrale"),
            ("CEA", "Caserta"), ("SAL", "Salerno"), ("RCE", "Reggio di Calabria Centrale"),
            ("VSG", "Villa S. Giovanni"), ("RUT", "Rosarno"), ("LON", "Lamezia Terme Centrale"),
            ("PAR", "Paola"), ("SDC", "Scalea S. Domenica Talao"), ("MRT", "Maratea"), ("SRI", "Sapri"),
            ("VLH", "Vallo della Lucania Castelnuovo"), ("AGR", "Agropoli Castellabate"),
            ("TSC", "Trieste Centrale"), ("MNF", "Monfalcone"), ("RHA", "Trieste Airport"),
            ("LTL", "Latisana Lignano Bibione"), ("PGR", "Portogruaro Caorle"), ("SDP", "S. Dona di Piave Jesolo"),
            ("VEM", "Venezia Mestre"), ("VSL", "Venezia Santa Lucia"), ("PD_", "Padova"), ("VPN", "Verona Porta Nuova"),
            ("BSC", "Brescia"), ("DSG", "Desenzano del Garda Sirmione"), ("PSY", "Peschiera del Garda"),
            ("VIC", "Vicenza"), ("F__", "Ferrara"), ("R__", "Rovigo"), ("BAC", "Bari Centrale"),
            ("ML_", "Molfetta"), ("BIG", "Bisceglie"), ("TR_", "Trani"), ("BLT", "Barletta"),
            ("FG_", "Foggia"), ("BEN", "Benevento"), ("BLZ", "Bolzano"), ("TCN", "Trento"), ("RVR", "Rovereto"),
        ]
        return stations.map { code, name in
            ItaloPublicStation(code: code, name: name, slug: ItaloParser.slug(name), aliases: aliases[code] ?? [])
        }
    }()

    /// The only fallback station for which `predicate` holds, nil for none or several.
    static func single(_ predicate: (ItaloPublicStation) -> Bool) -> ItaloPublicStation? {
        let matches = all.filter(predicate)
        return matches.count == 1 ? matches[0] : nil
    }

    /// Android's `ItaloStationSlugs.byName`: the pages Binario knows by station name.
    static let knownSlugs: [String: String] = {
        let names: [(String, String)] = [
            ("Roma Termini", "roma-termini"), ("Roma Tiburtina", "roma-tiburtina"), ("Napoli Centrale", "napoli-centrale"),
            ("Firenze S.M.N.", "firenze-santa-maria-novella"), ("Firenze Santa Maria Novella", "firenze-santa-maria-novella"),
            ("Milano Centrale", "milano-centrale"), ("Torino Porta Nuova", "torino-porta-nuova"),
            ("Venezia Santa Lucia", "venezia-santa-lucia"), ("Venezia S. Lucia", "venezia-santa-lucia"),
        ]
        var slugs: [String: String] = [:]
        for (name, slug) in names { slugs[LiveText.legacyStationKey(name)] = slug }
        return slugs
    }()
}

/// The Italo station list, Android's `HttpItaloPublicStationFetcher`: read once a day (a minute
/// after a failure), the reviewed fallback list until then.
final class ItaloStationCatalogue: @unchecked Sendable {
    static let refreshInterval: TimeInterval = 24 * 3_600
    static let retryInterval: TimeInterval = 60

    private let lock = NSLock()
    private var stations = ItaloStationFallbacks.all
    private var refreshAfter = Date.distantPast

    func stations(now: Date, load: @Sendable () async -> LiveFetchResult) async -> [ItaloPublicStation] {
        if needsRefresh(at: now) {
            switch await load() {
            case let .body(body):
                let parsed = ItaloParser.catalogue(body.text)
                update(parsed.isEmpty ? ItaloStationFallbacks.all : parsed, refreshAfter: now.addingTimeInterval(Self.refreshInterval))
            case .failure(.cancelled):
                break
            case .failure:
                update(nil, refreshAfter: now.addingTimeInterval(Self.retryInterval))
            }
        }
        return current()
    }

    private func needsRefresh(at now: Date) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return now >= refreshAfter
    }

    private func update(_ parsed: [ItaloPublicStation]?, refreshAfter next: Date) {
        lock.lock()
        if let parsed { stations = parsed }
        refreshAfter = next
        lock.unlock()
    }

    private func current() -> [ItaloPublicStation] {
        lock.lock()
        defer { lock.unlock() }
        return stations
    }
}

/// Italo's public «Italo in Viaggio» pages, Android's `ItaloTrainNumberAdapter` and
/// `ItaloPublicStationAdapter`: no account, no sales or app endpoints, HTTPS to
/// italoinviaggio.italotreno.com only.
public struct ItaloLiveSource: LiveTrainStatusSource, LiveDepartureBoardSource {
    public let source: LiveSource = .italo

    static let baseURL = "https://italoinviaggio.italotreno.com"
    /// Android's `RatePolicy`: one request per resource every 30 seconds.
    static let minimumInterval: TimeInterval = 30
    static let policy = LiveRequestPolicy(
        accept: "application/json,text/html", requestTimeout: 10, totalTimeout: 10, maximumBytes: 2 * 1_048_576
    )

    let client: LiveSourceClient
    let gate: LiveSourceGate
    let catalogue: ItaloStationCatalogue
    let now: @Sendable () -> Date

    init(client: LiveSourceClient, gate: LiveSourceGate, catalogue: ItaloStationCatalogue = ItaloStationCatalogue(), now: @escaping @Sendable () -> Date) {
        self.client = client
        self.gate = gate
        self.catalogue = catalogue
        self.now = now
    }

    public init(
        transport: any LiveHTTPTransport = URLSessionLiveTransport(), pauses: LiveSourcePauses = .shared,
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.init(
            client: LiveSourceClient(transport: transport, now: now),
            gate: LiveSourceGate(source: .italo, configuration: .init(minimumInterval: Self.minimumInterval), pauses: pauses, now: now),
            now: now
        )
    }

    func body(_ path: String) async -> LiveFetchResult {
        let address = Self.baseURL + path
        guard let url = URL(string: address) else { return .failure(.invalidRequest) }
        let client = self.client
        return await gate.fetch(address) { await client.get(url, policy: Self.policy) }
    }

    public func runs(for request: LiveTrainStatusRequest) async -> LiveSourceAnswer<[LiveRunStatus]> {
        let query = LiveTrainNumberQuery(request.number)
        guard let number = query.numeric, LiveText.isDigits(number, count: 3...5) else { return .answered([], complete: true) }
        let fetched = now()
        // Italo's page tells today's runs only.
        guard LiveRailTime.sameDay(request.serviceDate, fetched) else { return .answered([], complete: true) }
        switch await body("/api/RicercaTrenoService?TrainNumber=\(number)") {
        case let .failure(failure):
            return .unavailable(failure.unavailableReason)
        case let .body(body):
            guard let root = LiveJSONObject(LiveJSON.parse(body.text)) else { return .unavailable(.unreadable) }
            if root.bool("IsEmpty") { return .answered([], complete: true) }
            guard let run = ItaloParser.run(root, query: query, serviceDay: request.serviceDate, fetchedAt: body.fetchedAt) else {
                // Android: a run of another number or day is an invalid answer, not «no train».
                return .unavailable(.unreadable)
            }
            return .answered([run], complete: true)
        }
    }

    public func departures(for window: LiveBoardWindow) async -> LiveSourceAnswer<[LiveDeparture]> {
        let names = [window.station.name] + window.station.aliases
        let stations = await catalogue.stations(now: now()) { [self] in await self.body("/it/stazione") }
        let keys = Set(names.map(ItaloParser.stationKey))
        let slug = ItaloStationFallbacks.knownSlugs[LiveText.legacyStationKey(window.station.name)]
            ?? Self.single(stations.filter { $0.names.contains { keys.contains(ItaloParser.stationKey($0)) } })?.slug
        guard let slug else { return .unavailable(Task.isCancelled ? .cancelled : .notCovered) }
        let known = ItaloStationFallbacks.single { $0.slug == slug }
        let knownKeys = Set((known?.names ?? []).map(ItaloParser.stationKey))
        let station = Self.single(stations.filter { station in
            station.slug == slug || station.code == known?.code
                || (!knownKeys.isEmpty && station.names.contains { knownKeys.contains(ItaloParser.stationKey($0)) })
        })
        // The code comes from the catalogue: two to twelve letters, digits or "_".
        guard let station, ItaloParser.isCode(station.code) else { return .unavailable(.notCovered) }
        switch await body("/api/RicercaStazioneService?CodiceStazione=\(station.code)") {
        case let .failure(failure):
            return .unavailable(failure.unavailableReason)
        case let .body(body):
            guard let rows = ItaloParser.departures(body.text, stationNames: names, window: window, fetchedAt: body.fetchedAt) else {
                return .unavailable(.unreadable)
            }
            return .answered(rows, complete: true)
        }
    }

    private static func single(_ stations: [ItaloPublicStation]) -> ItaloPublicStation? {
        stations.count == 1 ? stations[0] : nil
    }
}

import Foundation
import BinarioKit

/// The texts of Android's smaller features on the iPhone: the CO2 estimate, the shares of a train,
/// a stop and the results (Android's `TrainShareFormatter` and `TimetableShareFormatter`, same
/// templates and layout), the personal punctuality and «Tipi di treno». Android's strings in the
/// 17 languages; counted texts come from Localizable.stringsdict, the counted number first.
enum PersonalTexts {
    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: Locale.current, arguments: arguments)
    }

    // MARK: CO2

    /// «CO₂ stimata: 4,8 kg (auto ~31 kg, aereo ~74 kg)».
    static func co2Line(_ estimate: Co2Estimate) -> String {
        let kg = co2Kilograms(estimate)
        return format("result.co2.line", kg.train, kg.car, kg.plane)
    }

    /// The same estimate in words, for VoiceOver.
    static func co2Spoken(_ estimate: Co2Estimate) -> String {
        let kg = co2Kilograms(estimate)
        return format("result.co2.spoken", kg.train, kg.car, kg.plane)
    }

    private static func co2Kilograms(_ estimate: Co2Estimate) -> (train: String, car: String, plane: String) {
        (Co2Estimator.displayKg(estimate.trainKg, locale: .current),
         Co2Estimator.displayKg(estimate.carKg, locale: .current),
         Co2Estimator.displayKg(estimate.planeKg, locale: .current))
    }

    /// The credit of the emission factors, with source, values and the report's page.
    static var co2Credits: String {
        let table = SharedData.Co2Estimate.self
        return format(
            "settings.about.credits_co2",
            table.source,
            String(Int(table.trainGramsPerPkm.rounded())),
            String(Int(table.carGramsPerPkm.rounded())),
            String(Int(table.planeGramsPerPkmLong.rounded())),
            String(Int(table.planeGramsPerPkmShort.rounded())),
            table.sourceUrl
        )
    }

    // MARK: A train

    /// Android's category labels of the share, from the published category and operator.
    static func categoryLabel(category: String, operatorName: String) -> String {
        if operatorName.lowercased().contains("italo") { return NSLocalizedString("result.share.category_italo", comment: "") }
        let code = String(category.uppercased().prefix { $0.isLetter })
        switch code {
        case "FR", "FA", "FB": return NSLocalizedString("result.share.category_frecce", comment: "")
        case "IC", "ICN": return NSLocalizedString("result.share.category_intercity", comment: "")
        case "RV": return NSLocalizedString("result.share.category_fast_regional", comment: "")
        case "R", "REG", "RE": return NSLocalizedString("result.share.category_regional", comment: "")
        default: return NSLocalizedString("result.share.category_other", comment: "")
        }
    }

    /// The text of «Condividi» in a train detail (Android's `TrainShareFormatter.create`): the train,
    /// its route, departure, platform and state, its stops when a source published them, what the
    /// data are, where they come from and when they were read.
    static func trainShare(
        service: TrainService,
        origin: String,
        live: AppModel.TrainLiveStatus?,
        timetableSource: String
    ) -> (title: String, text: String) {
        let title = format("result.share.train_title", service.number, origin, service.destination)
        var lines: [String] = [NSLocalizedString("result.share.detail_title", comment: ""), ""]
        lines.append(format("result.share.operator_category", service.operatorName,
                            categoryLabel(category: service.category, operatorName: service.operatorName), service.number))
        lines.append(format("result.share.route", origin, service.destination))
        let expected = live.flatMap { LiveTexts.boardingDeparture($0.boarding) }.map { LiveTexts.clock($0.time) } ?? service.scheduledDeparture
        lines.append(format("result.share.expected_departure", expected) + " ("
            + format("result.share.scheduled_departure", service.scheduledDeparture) + ")")
        if let platform = live?.boarding.platform ?? service.platform {
            lines.append(format("result.share.platform", platform))
        } else {
            lines.append(NSLocalizedString("result.share.platform_to_confirm", comment: ""))
        }
        let status: String
        var delay = 0
        if let live, live.run.cancellation == .full || live.run.state == .cancelled {
            status = NSLocalizedString("result.share.status_cancelled", comment: "")
        } else if let live, live.run.operationalStatusVerified {
            delay = live.run.delayMinutes ?? 0
            status = NSLocalizedString(delay > 0 ? "result.share.status_delayed" : "result.share.status_on_time", comment: "")
        } else {
            status = NSLocalizedString("result.share.scheduled_data", comment: "")
        }
        let delayText = delay > 0 ? " (" + format("result.share.stop_delay", delay).trimmingCharacters(in: CharacterSet(charactersIn: ", ")) + ")" : ""
        lines.append(format("result.share.status", status) + delayText)
        if let stops = live?.run.stops, !stops.isEmpty {
            lines.append("")
            lines.append(NSLocalizedString("result.share.stops_title", comment: "") + ":")
            for stop in stops {
                let platform = stop.platform.map { format("result.share.stop_platform", $0) } ?? ""
                let stopDelay = stop.delayMinutes > 0 ? format("result.share.stop_delay", stop.delayMinutes) : ""
                lines.append(format("result.share.stop", stop.stationName, LiveTexts.clock(stop.expected), platform, stopDelay))
            }
        }
        lines.append("")
        if let live {
            lines.append(format("result.share.data", format("result.share.live_data", NSLocalizedString("result.share.fresh", comment: ""))))
            lines.append(format("result.share.timetable_source", LiveTexts.sourceName(live.run.observation.source)))
            lines.append(format("result.share.updated", LiveTexts.clock(live.run.observation.fetchedAt)))
        } else {
            lines.append(format("result.share.data", NSLocalizedString("result.share.scheduled_data", comment: "")))
            if !timetableSource.isEmpty { lines.append(format("result.share.timetable_source", timetableSource)) }
            lines.append(NSLocalizedString("result.share.never_updated", comment: ""))
        }
        return (title, lines.joined(separator: "\n"))
    }

    /// «Treno 2153, fermata Milano Centrale: arr. 10:05 (previsto 10:12), bin. 3 (era 5)».
    static func stopShare(train: String, stop: LiveStopStatus, isFirst: Bool) -> String {
        let scheduled = LiveTexts.clock(stop.scheduledDeparture ?? stop.scheduled)
        var time = format(isFirst ? "result.share.stop_departure" : "result.share.stop_arrival", scheduled)
        let expected = LiveTexts.clock(stop.expected)
        if stop.operationalStatusVerified, expected != scheduled {
            time += " (" + format("result.share.stop_expected", expected) + ")"
        }
        if let shown = stop.platform?.trimmingCharacters(in: .whitespaces), !shown.isEmpty {
            time += ", "
            if let before = platformChange(shown, stop.scheduledPlatform) {
                time += format("result.share.stop_platform_changed", shown, before)
            } else {
                time += format("result.share.stop_platform_short", shown)
            }
        }
        return format("result.share.stop_line", train, stop.stationName, time)
    }

    /// The scheduled platform when the shown one differs from it (Android's `PlatformChange.of`).
    static func platformChange(_ shown: String, _ scheduled: String?) -> String? {
        guard let scheduled = scheduled?.trimmingCharacters(in: .whitespaces), !scheduled.isEmpty,
              scheduled.caseInsensitiveCompare(shown) != .orderedSame else { return nil }
        return scheduled
    }

    // MARK: A result list

    /// «lun 2 nov · dalle 17:00»: the date and starting time of a search, absolute.
    static func searchDate(_ date: PlannedLocalDate, from time: String) -> String {
        let day = NoticeTexts.day(date, Date.FormatStyle(timeZone: ItalianWallClock.timeZone).weekday(.abbreviated).day().month(.abbreviated))
        return format("result.share.date_from_time", day, time)
    }

    /// «2 nov 2026, 17:32»: when a timetable was saved.
    static func savedAt(_ date: Date) -> String {
        date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened, timeZone: ItalianWallClock.timeZone))
    }

    /// One row of the results as saved and shared: departure, train, platform, source, the time of
    /// the live values when they were shown.
    static func timetableRow(_ service: TrainService, live: StationBoardLiveStatus?, source: String) -> TimetableRow {
        let label = service.isTrain ? ReliableAlternatives.trainLabel(service) : VehicleTexts.rowLabel(service)
        return TimetableRow(
            departure: service.scheduledDeparture,
            arrival: nil,
            durationMinutes: nil,
            trains: label,
            changes: 0,
            platform: live?.platform ?? service.platform,
            scheduledPlatform: live?.platform != nil ? service.platform : nil,
            source: live.map { $0.sourceLabel } ?? source,
            fetchedAt: live?.observedAt,
            scheduledOnly: live == nil
        )
    }

    /// The whole list (Android's `TimetableShareFormatter.list`): a header with stations, date and
    /// starting time, one line per solution, the return of a round trip, the sources, and when the
    /// data were read or saved.
    static func listShare(
        origin: String,
        destination: String,
        dateLabel: String,
        rows: [TimetableRow],
        returnRows: [TimetableRow],
        savedAt: Date? = nil
    ) -> (title: String, text: String) {
        let title = format("result.share.timetable_header", origin, destination, dateLabel)
        var lines = [title]
        rows.forEach { lines += listLines($0) }
        if !returnRows.isEmpty {
            lines.append("")
            lines.append(NSLocalizedString("result.share.return_title", comment: ""))
            returnRows.forEach { lines += listLines($0) }
        }
        lines.append("")
        let all = rows + returnRows
        var sources: [String] = []
        for source in all.map(\.source) where !source.isEmpty && !sources.contains(source) { sources.append(source) }
        if !sources.isEmpty { lines.append(format("result.share.timetable_source", sources.joined(separator: ", "))) }
        if all.contains(where: \.scheduledOnly) { lines.append(NSLocalizedString("result.share.scheduled_data", comment: "")) }
        if let savedAt {
            lines.append(format("result.share.saved_not_updated", Self.savedAt(savedAt)))
        } else if let fetched = all.compactMap(\.fetchedAt).max() {
            lines.append(format("result.share.updated", LiveTexts.clock(fetched)))
        }
        return (title, lines.joined(separator: "\n"))
    }

    /// «• 17:40 → 21:00 · 3h 20min · Diretto · REG 2153 · Binario 7», changes of train below it.
    static func listLines(_ row: TimetableRow) -> [String] {
        let main = [times(row), row.trains.isEmpty ? nil : row.trains, platform(row)].compactMap { $0 }.joined(separator: " · ")
        return ["• " + main] + row.transfers.map { "   " + $0 }
    }

    static func times(_ row: TimetableRow) -> String {
        var parts: [String] = []
        if let arrival = row.arrival {
            parts.append(format("result.share.route", row.departure, arrival)
                + (row.arrivalDayOffset > 0 ? " (" + NSLocalizedString("result.share.next_day", comment: "") + ")" : ""))
        } else {
            parts.append(row.departure)
        }
        if let minutes = row.durationMinutes { parts.append(duration(minutes)) }
        // A saved row with changes comes from a journey with changes: its own words are in its trains.
        if row.changes <= 0 { parts.append(NSLocalizedString("result.share.direct", comment: "")) }
        return parts.joined(separator: " · ")
    }

    static func platform(_ row: TimetableRow) -> String? {
        guard let shown = row.platform?.trimmingCharacters(in: .whitespaces), !shown.isEmpty else { return nil }
        if let before = platformChange(shown, row.scheduledPlatform) {
            return format("result.share.platform_changed_label", shown, before)
        }
        return format("result.share.platform_label", shown)
    }

    /// «3h 20min»: Android's language-neutral duration of a share.
    static func duration(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes)min" }
        if minutes % 60 == 0 { return "\(minutes / 60)h" }
        return "\(minutes / 60)h \(minutes % 60)min"
    }

    // MARK: Personal punctuality

    /// «12 min», Android's `minutes_short`.
    static func minutes(_ value: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.minutes_short", comment: ""), value)
    }

    static func observations(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.punctuality.observations", comment: ""), count)
    }

    static func bucketLabel(_ bucket: PunctualityBucket) -> String {
        switch bucket {
        case .onTime: NSLocalizedString("result.punctuality.bucket_on_time", comment: "")
        case .upTo15: NSLocalizedString("result.punctuality.bucket_up_to_15", comment: "")
        case .upTo30: NSLocalizedString("result.punctuality.bucket_up_to_30", comment: "")
        case .over30: NSLocalizedString("result.punctuality.bucket_over_30", comment: "")
        case .cancelled: NSLocalizedString("result.punctuality.bucket_cancelled", comment: "")
        }
    }

    /// «Soppresso», «+7 min» or «In orario» for one day of the history.
    static func observationState(_ observation: PunctualityObservation) -> String {
        if observation.cancelled { return NSLocalizedString("result.punctuality.cancelled", comment: "") }
        if observation.delayMinutes > 0 { return format("result.punctuality.delay", observation.delayMinutes) }
        return NSLocalizedString("result.punctuality.on_time", comment: "")
    }

    // MARK: Tipi di treno

    static func groupLabel(_ group: TrainTypeGroup) -> String {
        switch group {
        case .highSpeed: NSLocalizedString("departures.types.high_speed", comment: "")
        case .intercityNight: NSLocalizedString("departures.types.intercity", comment: "")
        case .regionalSuburban: NSLocalizedString("departures.types.regional", comment: "")
        case .italo: NSLocalizedString("departures.types.italo", comment: "")
        case .replacementBus: NSLocalizedString("departures.types.bus", comment: "")
        case .international: NSLocalizedString("departures.types.international", comment: "")
        }
    }

    static func hiddenTrains(_ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.types.hidden_trains", comment: ""), count)
    }

    /// «1,2 km» or «850 m» from a distance in metres.
    static func distance(_ meters: Double) -> String {
        if meters < 1_000 { return format("departures.distance_m", Int(meters.rounded())) }
        return format("departures.distance_km", meters / 1_000)
    }
}

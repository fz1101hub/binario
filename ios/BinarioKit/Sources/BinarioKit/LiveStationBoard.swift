import Foundation

extension LiveDeparture {
    /// What «Partenze» shows of this live row on a bundled timetable row (`StationBoardDeparture.live`):
    /// the values still live at `now` (Android's `withoutExpiredObservations`), named after the source
    /// of the expected time, else of the state, else of the platform. Nil when no value is left, so
    /// the row stays «orario programmato». A bare «RITARDO» has no expected time and no delay here.
    /// The delay is counted from `timetable`, the time the row prints, when given: a source may list
    /// the train a few minutes from the bundled time (a timetable change), and «In orario» must never
    /// stand next to a time the source has moved.
    public func stationBoardLiveStatus(at now: Date, timetable: Date? = nil) -> StationBoardLiveStatus? {
        let expected = self.expected.flatMap { $0.observation.expiresAt > now ? $0 : nil }
        let status = self.status.flatMap { $0.observation.expiresAt > now ? $0 : nil }
        let platform = self.platform.flatMap { $0.observation.expiresAt > now ? $0 : nil }
        guard let observation = expected?.observation ?? status?.observation ?? platform?.observation else { return nil }
        let cancelled = status?.value == .cancelled
        return StationBoardLiveStatus(
            expectedDeparture: cancelled ? nil : expected?.value,
            delayMinutes: cancelled ? nil : expected.map { LiveRailTime.wholeMinutes(from: timetable ?? scheduled, to: $0.value) },
            cancelled: cancelled, platform: platform?.value, sourceLabel: observation.source.displayName,
            observedAt: observation.observedAt, source: observation.source, platformSource: platform?.observation.source,
            lateWithoutMinutes: !cancelled && expected == nil && status?.value == .delayed
        )
    }
}

extension LiveOverlay {
    /// After the last live row, «Partenze» goes on with the bundled timetable's rows, marked
    /// «orario programmato», so the board does not shrink to what the live board covers. The
    /// owner's switch: false shows the live rows alone whenever the live board answers.
    public static let appendsTimetableAfterLiveRows = true

    /// «Partenze», live first (the owner's rule, as Android's `StationBoardAggregateService`): when
    /// `board` has usable rows (a live value at `now`, still to leave), the board is the live rows,
    /// each matched to its bundled train (same number key and operator family, within
    /// `timetableTolerance`, each live row to one train: `departures(for:in:)`) to keep the
    /// timetable's number, destination and planned time, the others named as the source names
    /// them; a bundled train no live row matches is not shown inside the live board's span (up to
    /// its last row) and follows it, «orario programmato», when `appendsTimetableAfterLiveRows`.
    /// Without usable live rows (the live board failed, is paused, timed out or is empty) the
    /// bundled rows are the board, as before, with the live values of the trains a row matches.
    public static func stationBoard(_ rows: [StationBoardDeparture], with board: LiveDepartureBoard, at now: Date) -> [StationBoardDeparture] {
        let planned: [PlannedTrain?] = rows.map { row -> PlannedTrain? in
            row.service.hasTrainNumber
                ? PlannedTrain(number: row.service.number, scheduled: row.departure, operatorName: row.service.operatorName) : nil
        }
        let usable = board.departures.filter { live in
            live.stationBoardLiveStatus(at: now) != nil && stillToLeave(live, at: now)
        }
        guard let spanEnd = usable.map(\.scheduled).max() else {
            // The bundled timetable, as before: no live row to put first.
            let matched = departures(for: planned, in: board.departures)
            return rows.indices.map { index -> StationBoardDeparture in
                let row = rows[index]
                guard let live = matched[index],
                      let status = live.stationBoardLiveStatus(at: now, timetable: row.departure) else { return row }
                return StationBoardDeparture(departure: row.departure, service: row.service, terminus: row.terminus, live: status)
            }
        }
        let matched = departures(for: planned, in: usable)
        var claimed: Set<String> = []
        var shown: [StationBoardDeparture] = []
        for index in rows.indices {
            let row = rows[index]
            if let live = matched[index], let status = live.stationBoardLiveStatus(at: now, timetable: row.departure) {
                claimed.insert(liveKey(live))
                shown.append(StationBoardDeparture(departure: row.departure, service: row.service, terminus: row.terminus, live: status))
            } else if appendsTimetableAfterLiveRows, row.departure > spanEnd {
                shown.append(row)
            }
        }
        for live in usable where !claimed.contains(liveKey(live)) {
            if let row = liveOnlyRow(live, at: now) { shown.append(row) }
        }
        return shown.enumerated()
            .sorted { first, second in
                first.element.departure != second.element.departure
                    ? first.element.departure < second.element.departure : first.offset < second.offset
            }
            .map { $0.element }
    }

    /// Still to be taken at `now`: until a minute after its expected departure, or its scheduled
    /// one without an expected time (a cancelled train stays listed as «Soppresso» until then).
    static func stillToLeave(_ live: LiveDeparture, at now: Date) -> Bool {
        let departure = max(live.scheduled, live.expected?.value ?? live.scheduled)
        return ReliableAlternatives.stillToDepart(departure, now: now)
    }

    /// A live row no bundled train matches, as the source names it: its number, category,
    /// operator and destination, its scheduled time and the timetable platform it publishes. The
    /// terminus is the destination's name, without a catalogue identity.
    static func liveOnlyRow(_ live: LiveDeparture, at now: Date) -> StationBoardDeparture? {
        let number = Station.singleSpaced(live.number)
        let destination = Station.singleSpaced(live.destination)
        guard !number.isEmpty, number.count <= 32, !destination.isEmpty, destination.count <= 96,
              let status = live.stationBoardLiveStatus(at: now) else { return nil }
        let category = Station.singleSpaced(live.category)
        let time = LiveRailTime.hhmm(live.scheduled)
        let service = TrainService(
            id: "live:\(liveKey(live))",
            number: number,
            operatorName: operatorDisplayName(live.operatorID) ?? category,
            category: category,
            destination: destination,
            scheduledDeparture: time,
            expectedDeparture: time,
            status: OperationalState.programmed.rawValue,
            platform: live.scheduledPlatform,
            delayMinutes: 0
        )
        return StationBoardDeparture(
            departure: live.scheduled,
            service: service,
            terminus: StationIdentity(id: "live:\(destination)", name: destination),
            live: status
        )
    }

    /// One live row: its identifier and its scheduled time (a source may list a number twice).
    private static func liveKey(_ live: LiveDeparture) -> String {
        "\(live.id):\(Int64(live.scheduled.timeIntervalSince1970))"
    }

    private static func operatorDisplayName(_ operatorID: String?) -> String? {
        switch operatorID {
        case "trenitalia": return "Trenitalia"
        case "trenord": return "Trenord"
        case "italo": return "Italo"
        default: return nil
        }
    }
}

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
    /// The «Partenze» rows of the bundled timetable with what `board` observed of their trains
    /// (same number key, scheduled within five minutes); every other row stays as it is.
    public static func stationBoard(_ rows: [StationBoardDeparture], with board: LiveDepartureBoard, at now: Date) -> [StationBoardDeparture] {
        rows.map { row -> StationBoardDeparture in
            guard row.service.hasTrainNumber,
                  let live = departure(number: row.service.number, scheduled: row.departure, in: board.departures),
                  let status = live.stationBoardLiveStatus(at: now, timetable: row.departure) else { return row }
            return StationBoardDeparture(departure: row.departure, service: row.service, terminus: row.terminus, live: status)
        }
    }
}

import Foundation
import SwiftUI
import BinarioKit

/// How a journey with changes is written (Android's `JourneyRows.kt`): leg labels, changes, waits, times, the chips and
/// the warnings of a change. The texts are Android's in the 17 languages.
enum JourneyTexts {
    /// «REG 2153», «Bus sostitutivo S11 4417», «ItaloBus 1060», «Metro M1», «A piedi»: never empty.
    static func legLabel(_ leg: JourneyPlanLeg) -> String {
        let line = leg.lineLabel?.trimmingCharacters(in: .whitespaces).nonEmpty
        let number = leg.publicNumber?.trimmingCharacters(in: .whitespaces).nonEmpty
        guard let vehicle = leg.vehicle else { return NSLocalizedString("result.journey.leg_walk", comment: "") }
        guard let key = vehicle.labelKey else { return [line, number].compactMap { $0 }.joined(separator: " ").nonEmpty ?? "—" }
        let label = NSLocalizedString(key, comment: "")
        if vehicle.isUrban { return TimetableVehicle.urbanLabel(label, line: line) }
        let bus = TimetableVehicle.busLabel(label, vehicle: vehicle, line: line, number: number)
        return [bus, number].compactMap { $0 }.joined(separator: " ")
    }

    /// «REX 1826»: the run a vehicle goes on as («Resta a bordo»).
    static func continuationLabel(_ continuation: JourneyInSeatContinuation) -> String {
        [continuation.lineLabel, continuation.publicNumber].compactMap { $0?.trimmingCharacters(in: .whitespaces).nonEmpty }
            .joined(separator: " ").nonEmpty ?? "—"
    }

    /// «REG 2153 › RE 24617»: every vehicle in order, walks left out.
    static func trainsLabel(_ row: JourneyOptionRow) -> String {
        row.option.vehicleLegs.map(legLabel).joined(separator: " › ")
    }

    /// «Diretto», «1 cambio», «2 cambi».
    static func changes(_ count: Int) -> String {
        guard count > 0 else { return NSLocalizedString("result.journey.direct", comment: "") }
        return String.localizedStringWithFormat(NSLocalizedString("result.journey.changes", comment: ""), count)
    }

    /// «Attesa di 15 minuti».
    static func wait(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.journey.transfer_wait", comment: ""), max(minutes, 0))
    }

    /// «Cambio a Milano Centrale».
    static func transferAt(_ station: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.journey.transfer_at", comment: ""), station)
    }

    /// «Resta a bordo: il treno prosegue come REX 1826».
    static func inSeat(_ continuation: JourneyInSeatContinuation) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.journey.in_seat", comment: ""), continuationLabel(continuation))
    }

    /// «Arrivo previsto 10:12, partenza prevista 10:20».
    static func liveTimes(arrival: Date, departure: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.journey.transfer_live_times", comment: ""), time(arrival), time(departure))
    }

    /// «Non ci sono più treni oggi: primo domani alle 05:40».
    static func noMoreToday(_ first: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.journey.no_more_today", comment: ""), time(first))
    }

    /// «Nessun treno fino alle 13:05».
    static func noTrainsUntil(_ first: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.journey.no_trains_until", comment: ""), time(first))
    }

    /// «Coincidenza a rischio» / «Coincidenza persa», and in the detail «Coincidenza confermata»; nil without live data.
    static func risk(_ level: TransferRiskLevel, rowLevel: Bool = false) -> String? {
        switch level {
        case .missed: return NSLocalizedString("result.journey.transfer_missed", comment: "")
        case .atRisk: return NSLocalizedString("result.journey.transfer_at_risk", comment: "")
        case .ok: return rowLevel ? nil : NSLocalizedString("result.journey.transfer_confirmed", comment: "")
        case .unknown: return nil
        }
    }

    /// The label of a chip of the results.
    static func filter(_ filter: JourneyResultsFilter) -> String {
        switch filter {
        case .all: return NSLocalizedString("result.journey.filter.all", comment: "")
        case .direct: return NSLocalizedString("result.journey.filter.direct", comment: "")
        case .fewestChanges: return NSLocalizedString("result.journey.filter.fewest_changes", comment: "")
        case .fastest: return NSLocalizedString("result.journey.filter.fastest", comment: "")
        case .leastWalking: return NSLocalizedString("result.journey.filter.least_walking", comment: "")
        case .mostReliable: return NSLocalizedString("result.order.most_reliable", comment: "")
        }
    }

    /// «Qualsiasi», «Diretto», «Max 1 cambio», «Max 2 cambi».
    static func changeFilter(_ filter: JourneyChangeFilter) -> String {
        switch filter {
        case .any: return NSLocalizedString("planner.changes.any", comment: "")
        case .direct: return NSLocalizedString("planner.changes.direct", comment: "")
        case .upToOne: return NSLocalizedString("planner.changes.one", comment: "")
        case .upToTwo: return NSLocalizedString("planner.changes.two", comment: "")
        }
    }

    /// «Solo viaggi diretti, senza cambi», as VoiceOver reads a changes filter.
    static func changeFilterAccessible(_ filter: JourneyChangeFilter) -> String {
        switch filter {
        case .any: return NSLocalizedString("planner.changes.any_accessible", comment: "")
        case .direct: return NSLocalizedString("planner.changes.direct_accessible", comment: "")
        case .upToOne: return NSLocalizedString("planner.changes.one_accessible", comment: "")
        case .upToTwo: return NSLocalizedString("planner.changes.two_accessible", comment: "")
        }
    }

    /// Why the journeys with changes are empty (Android's `JourneyEmptyReason`).
    static func empty(_ status: JourneyPlanStatus, hasOptions: Bool) -> String {
        if hasOptions { return NSLocalizedString("result.journey.empty.no_direct", comment: "") }
        switch status {
        case .found, .noSolution: return NSLocalizedString("result.journey.empty.no_solution", comment: "")
        case .notCovered: return NSLocalizedString("result.journey.empty.not_covered", comment: "")
        case .scheduleExpired: return NSLocalizedString("result.journey.empty.schedule_expired", comment: "")
        case .unavailable: return NSLocalizedString("result.journey.empty.unavailable", comment: "")
        }
    }

    /// The SF Symbol of a leg.
    static func symbol(_ leg: JourneyPlanLeg) -> String {
        switch leg.mode {
        case .train: return "train.side.front.car"
        case .bus, .replacementBus: return VehicleTexts.busSymbol
        case .metro: return "tram.fill.tunnel"
        case .tram: return "tram"
        case .funicular: return "cablecar"
        case .walk: return "figure.walk"
        }
    }

    /// «08:12» in Italian time, as the timetables print it.
    static func time(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }

    /// «2 h 05 min», as the system writes a duration.
    static func duration(_ minutes: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.allowedUnits = minutes >= 60 ? [.hour, .minute] : [.minute]
        formatter.unitsStyle = .abbreviated
        formatter.zeroFormattingBehavior = .dropLeading
        return formatter.string(from: TimeInterval(max(minutes, 0) * 60)) ?? "\(minutes)"
    }

    /// The arrival is on a later day than the departure (Italian time).
    static func arrivesNextDay(_ row: JourneyOptionRow) -> Bool {
        JourneyResultsMerge.encodedDay(row.arrival) > JourneyResultsMerge.encodedDay(row.departure)
    }

    /// The reliability lines of a journey: its weakest train (named when the journey has more than one) and its
    /// weakest change («Coincidenza a Verona riuscita 8 volte su 10»).
    static func reliabilityLines(_ reliability: JourneyPlanReliability, row: JourneyOptionRow) -> [String] {
        var lines: [String] = []
        if let weakest = reliability.weakestLeg {
            let stop = weakest.stop
            if reliability.legs.count > 1, row.legs.indices.contains(weakest.legIndex) {
                let label = legLabel(row.legs[weakest.legIndex])
                if stop.cancelledEveryDay {
                    lines.append(String.localizedStringWithFormat(
                        NSLocalizedString("result.journey.leg_cancelled_share", comment: ""), stop.daysCancelled, stop.daysObserved, label
                    ))
                } else {
                    lines.append(([String.localizedStringWithFormat(
                        NSLocalizedString("result.journey.leg_on_time", comment: ""), stop.onTime, stop.samples, label
                    )] + Array(ReliabilityTexts.trainParts(stop).dropFirst())).joined(separator: " · "))
                }
            } else {
                lines.append(ReliabilityTexts.trainParts(stop).joined(separator: " · "))
            }
        }
        if let connection = reliability.weakestConnection {
            lines.append(String.localizedStringWithFormat(
                NSLocalizedString("result.journey.connection", comment: ""), connection.odds.successes, connection.odds.samples,
                connection.stationLabel
            ))
        }
        return lines
    }
}

extension String {
    /// The string, or nil when empty.
    fileprivate var nonEmpty: String? { isEmpty ? nil : self }
}

import Foundation
import SwiftUI
import BinarioKit

/// The texts of live data: a train's state («prev. 08:17 · +5 min», «Soppresso», «In orario»),
/// where each value comes from and when it was observed, and the sources' status lines. Android's
/// wording (strings.xml) wherever it has one. A scheduled time is never worded as live: only a value
/// a source published is called «previsto» or «effettivo». Counted texts come from
/// Localizable.stringsdict, the counted number first.
enum LiveTexts {
    /// The source as Settings and the rows name it; never translated.
    static func sourceName(_ source: LiveSource) -> String {
        switch source {
        case .rfiStationBoard: return "RFI"
        case .viaggiaTreno: return "ViaggiaTreno"
        case .italo: return "Italo"
        }
    }

    /// "08:17", in Italian time as the timetables print it.
    static func clock(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Rome") ?? .current
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        let hour: Int = parts.hour ?? 0
        let minute: Int = parts.minute ?? 0
        return String(format: "%02ld:%02ld", locale: Locale(identifier: "en_US_POSIX"), hour, minute)
    }

    // MARK: Pieces

    /// «+5 min», next to an expected time.
    static func delayBadge(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.delay_short", comment: ""), minutes)
    }

    /// «In ritardo di 5 minuti».
    static func delaySentence(_ minutes: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.delay", comment: ""), minutes)
    }

    /// «prev. 08:17».
    static func expectedShortTime(_ date: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.expected_short", comment: ""), clock(date))
    }

    /// «Previsto 08:17», what VoiceOver says for «prev. 08:17».
    static func expectedTime(_ date: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.expected", comment: ""), clock(date))
    }

    /// «Effettivo 08:17»: a time the source observed.
    static func actualTime(_ date: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.actual", comment: ""), clock(date))
    }

    /// «Programmato 08:12», what VoiceOver says before a live time.
    static func scheduledTime(_ date: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.scheduled", comment: ""), clock(date))
    }

    /// «Fonte: ViaggiaTreno».
    static func fromSource(_ source: LiveSource) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.source", comment: ""), sourceName(source))
    }

    /// «Binario da RFI», when the platform comes from another source than the time.
    static func platformFrom(_ source: LiveSource) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.platform_source", comment: ""), sourceName(source))
    }

    /// «Rilevato alle 08:15».
    static func observedAt(_ date: Date) -> String {
        String.localizedStringWithFormat(NSLocalizedString("result.live.observed", comment: ""), clock(date))
    }

    /// «Dati in tempo reale da RFI e ViaggiaTreno».
    static func fromSources(_ sources: [LiveSource]) -> String {
        let names = sources.map { sourceName($0) }.formatted(.list(type: .and))
        return String.localizedStringWithFormat(NSLocalizedString("result.live.from_sources", comment: ""), names)
    }

    /// «ViaggiaTreno in pausa fino alle 10:42».
    static func paused(_ pause: LiveSourcePause) -> String {
        String.localizedStringWithFormat(
            NSLocalizedString("result.live.paused", comment: ""), sourceName(pause.source), clock(pause.until)
        )
    }

    /// «binario 3», inside a spoken sentence.
    static func platformSpoken(_ platform: String) -> String {
        String.localizedStringWithFormat(NSLocalizedString("departures.row.platform_spoken", comment: ""), platform)
    }

    static var cancelled: String { NSLocalizedString("result.live.cancelled", comment: "") }
    static var partiallyCancelled: String { NSLocalizedString("result.live.partially_cancelled", comment: "") }
    static var onTime: String { NSLocalizedString("result.live.on_time", comment: "") }
    static var lateUnpublished: String { NSLocalizedString("result.live.late_unpublished", comment: "") }
    static var unverified: String { NSLocalizedString("result.live.unverified", comment: "") }
    static var passed: String { NSLocalizedString("result.live.passed", comment: "") }
    static var skipped: String { NSLocalizedString("result.live.skipped", comment: "") }
    static var added: String { NSLocalizedString("result.live.added", comment: "") }

    // MARK: A row of «Partenze» or of the planner

    /// What a row shows of its live status; nil when only its platform is live.
    static func state(_ status: StationBoardLiveStatus) -> String? {
        if status.cancelled { return cancelled }
        if let expected = status.expectedDeparture {
            let delay: Int = status.delayMinutes ?? 0
            if delay > 0 { return [expectedShortTime(expected), delayBadge(delay)].joined(separator: " · ") }
            return delay == 0 ? onTime : expectedShortTime(expected)
        }
        return status.lateWithoutMinutes ? lateUnpublished : nil
    }

    /// Late or cancelled: the state takes the error colour, besides its words.
    static func isDisrupted(_ status: StationBoardLiveStatus) -> Bool {
        let delay: Int = status.delayMinutes ?? 0
        return status.cancelled || status.lateWithoutMinutes || delay > 0
    }

    /// «Fonte: ViaggiaTreno», «Binario da RFI» when the platform has its own source, «Rilevato alle 08:15».
    static func provenance(_ status: StationBoardLiveStatus) -> [String] {
        var parts: [String] = []
        if let source = status.source {
            parts.append(fromSource(source))
        } else {
            parts.append(String.localizedStringWithFormat(NSLocalizedString("result.live.source", comment: ""), status.sourceLabel))
        }
        if status.platform != nil, let platformSource = status.platformSource, platformSource != status.source {
            parts.append(platformFrom(platformSource))
        }
        parts.append(observedAt(status.observedAt))
        return parts
    }

    /// The live part of a row's spoken sentence: «Previsto 08:17», «In ritardo di 5 minuti» (or
    /// «Soppresso», «In orario»…), the platform when the row shows it here, then the provenance.
    static func spoken(_ status: StationBoardLiveStatus, includingPlatform: Bool) -> [String] {
        var parts: [String] = []
        if status.cancelled {
            parts.append(cancelled)
        } else if let expected = status.expectedDeparture {
            let delay: Int = status.delayMinutes ?? 0
            if delay > 0 {
                parts.append(expectedTime(expected))
                parts.append(delaySentence(delay))
            } else if delay == 0 {
                parts.append(onTime)
            } else {
                parts.append(expectedTime(expected))
            }
        } else if status.lateWithoutMinutes {
            parts.append(lateUnpublished)
        }
        if includingPlatform, let platform = status.platform {
            parts.append(platformSpoken(platform))
        }
        return parts + provenance(status)
    }

    // MARK: A run (train detail, «I miei treni»)

    /// The run at a glance: «Soppresso»; «Parzialmente soppresso» then the state at the boarding
    /// station: «Fermata saltata», «In ritardo di 5 minuti», «In orario», or «Stato operativo non
    /// verificato» without a recent observation of a train that has left.
    static func runState(_ status: AppModel.TrainLiveStatus) -> [String] {
        let run = status.run
        if run.cancellation == .full || run.state == .cancelled { return [cancelled] }
        var parts: [String] = []
        if run.cancellation == .partial { parts.append(partiallyCancelled) }
        if status.boarding.skipped {
            parts.append(skipped)
        } else if run.operationalStatusVerified, let delay = run.delayMinutes {
            parts.append(delay > 0 ? delaySentence(delay) : onTime)
        } else {
            parts.append(unverified)
        }
        return parts
    }

    static func isDisrupted(_ status: AppModel.TrainLiveStatus) -> Bool {
        let run = status.run
        let delay: Int = run.operationalStatusVerified ? (run.delayMinutes ?? 0) : 0
        return run.cancellation != .none || run.state == .cancelled || status.boarding.skipped || delay > 0
    }

    /// «Fonte: ViaggiaTreno · Rilevato alle 10:43».
    static func provenance(_ status: AppModel.TrainLiveStatus) -> [String] {
        [fromSource(status.run.observation.source), observedAt(status.run.observation.observedAt)]
    }

    /// The departure the source published at the boarding stop: its actual departure, else the
    /// scheduled one moved by a verified delay; nil without a value of the source.
    static func boardingDeparture(_ stop: LiveStopStatus) -> (time: Date, actual: Bool)? {
        if stop.skipped { return nil }
        if let departed = stop.actualDeparture { return (time: departed, actual: true) }
        // The first stop publishes its departure as its only time.
        if stop.scheduledDeparture == nil, let departed = stop.actual { return (time: departed, actual: true) }
        guard stop.operationalStatusVerified else { return nil }
        let scheduled: Date = stop.scheduledDeparture ?? stop.scheduled
        let delay: TimeInterval = TimeInterval(stop.delayMinutes) * 60
        return (time: scheduled.addingTimeInterval(delay), actual: false)
    }

    /// «Milano Lambrate, 10:42» (or the time alone), the last place the source observed the train.
    static func lastObservation(_ point: LiveRunObservationPoint) -> String {
        guard let name = point.stationName, !name.isEmpty else { return clock(point.at) }
        return "\(name), \(clock(point.at))"
    }

    /// A stop of the run: «Fermata straordinaria» for a stop the timetable does not have, then
    /// «Effettivo 10:17» or «prev. 10:17», «+5 min», «Bin. 3».
    static func stopParts(_ stop: LiveStopStatus) -> [String] {
        if stop.skipped { return [skipped] }
        var parts: [String] = []
        if stop.added { parts.append(added) }
        if let actual = stop.actual {
            parts.append(actualTime(actual))
        } else if stop.operationalStatusVerified, stop.expected != stop.scheduled {
            parts.append(expectedShortTime(stop.expected))
        }
        if stop.operationalStatusVerified, stop.delayMinutes > 0 { parts.append(delayBadge(stop.delayMinutes)) }
        if let platform = stop.platform { parts.append(DeparturesTexts.platform(platform)) }
        return parts
    }

    /// A stop as VoiceOver reads it: «Milano Centrale. Programmato 10:12. Effettivo 10:17. In
    /// ritardo di 5 minuti. binario 3. Fermata superata».
    static func spokenStop(_ stop: LiveStopStatus) -> String {
        var parts: [String] = [stop.stationName, scheduledTime(stop.scheduled)]
        if stop.skipped {
            parts.append(skipped)
        } else {
            if stop.added { parts.append(added) }
            if let actual = stop.actual {
                parts.append(actualTime(actual))
            } else if stop.operationalStatusVerified, stop.expected != stop.scheduled {
                parts.append(expectedTime(stop.expected))
            }
            if stop.operationalStatusVerified, stop.delayMinutes > 0 { parts.append(delaySentence(stop.delayMinutes)) }
            if let platform = stop.platform { parts.append(platformSpoken(platform)) }
        }
        if stop.passed { parts.append(passed) }
        return parts.joined(separator: ". ")
    }
}

/// The live state of a listed train: «prev. 08:17 · +5 min», «In orario», «Soppresso» or «In
/// ritardo (minuti non pubblicati)», the platform when the row shows it here, then where the values
/// come from and when they were observed. VoiceOver reads it as one sentence; colour never says it alone.
struct LiveStatusLine: View {
    let status: StationBoardLiveStatus
    /// The planner rows show the live platform on this line; «Partenze» shows it at the top of the row.
    var showsPlatform = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if let state = LiveTexts.state(status) {
                    Label {
                        Text(state)
                    } icon: {
                        Image(systemName: status.cancelled ? "xmark.octagon" : "dot.radiowaves.left.and.right")
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(LiveTexts.isDisrupted(status) ? BinarioPalette.statusError : Color.primary)
                }
                if showsPlatform, let platform = status.platform {
                    Text(DeparturesTexts.platform(platform))
                        .font(.subheadline.weight(.semibold))
                }
            }
            Text(LiveTexts.provenance(status).joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(LiveTexts.spoken(status, includingPlatform: showsPlatform).joined(separator: ". ")))
    }
}

/// Under a list that live sources can update: «Dati in tempo reale da RFI e ViaggiaTreno» or why
/// there are none, then «ViaggiaTreno in pausa fino alle 10:42» for each paused source.
struct LiveSourcesFooter: View {
    let state: AppModel.LiveState
    let sources: [LiveSource]
    let pauses: [LiveSourcePause]

    var body: some View {
        switch state {
        case .off, .idle:
            EmptyView()
        case .loading:
            HStack(spacing: 8) {
                ProgressView()
                Text("result.live.loading")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        case .available:
            if !sources.isEmpty {
                Label(LiveTexts.fromSources(sources), systemImage: "dot.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        case .unavailable:
            Label("result.live.unavailable", systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        ForEach(pauses, id: \.source) { pause in
            Label(LiveTexts.paused(pause), systemImage: "pause.circle")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

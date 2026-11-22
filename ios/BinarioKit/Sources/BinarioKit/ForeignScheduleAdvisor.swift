import Foundation

/// What the planner or «Partenze» offers about a full foreign timetable not on this iPhone.
public struct ForeignScheduleOffer: Equatable, Sendable {
    public enum Kind: Equatable, Sendable {
        /// The search found no train and a full timetable would serve it: asked once (a dialog).
        case dialog
        /// Trains were found, or a station board is shown, but the full timetable would add trains:
        /// one line with its own «Scarica», never a dialog.
        case banner
    }

    public let kind: Kind
    /// The packs that can be downloaded, in the order of the pins.
    public let packs: [ForeignSchedulePins.Pack]
    /// The packs that would serve it but whose pinned timetable has ended: the app needs an update,
    /// nothing is offered for download.
    public let ended: [ForeignSchedulePins.Pack]
}

/// When the iPhone offers a full foreign timetable (owner decision Q3, design §4.4).
///
/// The iPhone plans direct trains only, so a pack P that is pinned, not installed, matters for a
/// search when **both** ends are among the stations P's full timetable serves and at least one lies
/// outside P's border zone (a zone station's trains are already complete in the bundled part):
/// - no train found: a dialog, unless the user said «Non ora» to P in this launch;
/// - trains found: one line only when both ends lie outside P's zone;
/// - «Partenze» of a station P serves outside its zone: one line.
/// A pin whose timetable has ended is never offered: the app needs an update. Packs bundled whole
/// (no pin) are never offered, and with no pins file nothing is.
public struct ForeignScheduleAdvisor: Sendable {
    public let pins: ForeignSchedulePins
    /// The packs whose full timetable is installed.
    public let installed: Set<String>
    /// The packs the user declined in this launch («Non ora»): no dialog for them.
    public let declined: Set<String>

    public init(pins: ForeignSchedulePins, installed: Set<String>, declined: Set<String> = []) {
        self.pins = pins
        self.installed = installed
        self.declined = declined
    }

    /// The offer after a search between two catalogue stations; `today` is the Rome day, yyyymmdd.
    public func offer(origin: String, destination: String, foundTrains: Bool, today: Int) -> ForeignScheduleOffer? {
        var packs: [ForeignSchedulePins.Pack] = []
        var ended: [ForeignSchedulePins.Pack] = []
        for pack in pins.downloadablePacks where !installed.contains(pack.sourceID) {
            guard pack.servedStationIDs.contains(origin), pack.servedStationIDs.contains(destination) else { continue }
            let originOutside = pack.needsFullTimetable(origin)
            let destinationOutside = pack.needsFullTimetable(destination)
            let matters: Bool = foundTrains ? (originOutside && destinationOutside) : (originOutside || destinationOutside)
            guard matters else { continue }
            if pack.hasEnded(before: today) {
                ended.append(pack)
            } else if foundTrains || !declined.contains(pack.sourceID) {
                packs.append(pack)
            }
        }
        guard !packs.isEmpty || !ended.isEmpty else { return nil }
        return ForeignScheduleOffer(kind: foundTrains ? .banner : .dialog, packs: packs, ended: ended)
    }

    /// The line of «Partenze» for a station the full timetable of a pack serves outside its zone.
    public func boardOffer(station: String, today: Int) -> ForeignScheduleOffer? {
        var packs: [ForeignSchedulePins.Pack] = []
        var ended: [ForeignSchedulePins.Pack] = []
        for pack in pins.downloadablePacks where !installed.contains(pack.sourceID) && pack.needsFullTimetable(station) {
            if pack.hasEnded(before: today) {
                ended.append(pack)
            } else {
                packs.append(pack)
            }
        }
        guard !packs.isEmpty || !ended.isEmpty else { return nil }
        return ForeignScheduleOffer(kind: .banner, packs: packs, ended: ended)
    }
}

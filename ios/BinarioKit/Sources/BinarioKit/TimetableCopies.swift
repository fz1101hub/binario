import Foundation

/// One train two packs of the bundled timetable both publish (the national Trenitalia pack and
/// Trenitalia France's for Milano – Paris, Trenitalia's and the Swiss pack for a EuroCity), kept
/// once, as Android's route search keeps it (`EmbeddedGtfsRouteAdapter.withoutDuplicates` with
/// `PlannedPackOrder`): the national packs' copy first, the foreign packs' copy first at a station
/// no national pack serves (Lugano, Innsbruck), the Italian regional packs in between.
enum TimetableCopies {
    // The roles are the shared table contracts/shared-data/v1/pack-roles-v1.json (`SharedData.PackRoles`).
    /// `PlannedPackOrder.nationalPacks`.
    static let nationalPacks: Set<String> = SharedData.PackRoles.nationalPacks
    /// The packs whose first catalogue country is not Italy (`PlannedPackClass.FOREIGN`, which
    /// Android derives from the catalogue; FOREIGN_PACKS of the index builder). Every other pack of
    /// the index is an Italian regional one.
    static let foreignPacks: Set<String> = SharedData.PackRoles.foreignPacks
    /// `EmbeddedGtfsRouteAdapter.CROSS_PUBLISHING_PACKS`: packs that republish partner operators'
    /// trains under their own numbering, so a copy there may carry another number.
    static let crossPublishingPacks: Set<String> = SharedData.PackRoles.crossPublishingPacks

    /// A service as the copies are compared: its pack, number, operator, and the minutes it leaves
    /// and arrives (the arrival at the searched destination, nil on a board), with the names of the
    /// two stops (the terminus on a board) and the terminus station when the catalogue knows it.
    struct Run {
        let sourceID: String
        let number: String
        let operatorName: String
        let departure: Date
        let arrival: Date?
        let originName: String
        let destinationName: String
        var destinationStationID: String? = nil
    }

    /// The order of a pack's copy: national, Italian regional, foreign; with `border` (no national
    /// pack serves an end of the search) foreign, Italian regional, national.
    static func rank(_ sourceID: String, border: Bool) -> Int {
        let national = nationalPacks.contains(sourceID)
        let foreign = foreignPacks.contains(sourceID)
        if border { return foreign ? 0 : national ? 2 : 1 }
        return national ? 0 : foreign ? 2 : 1
    }

    /// The planner's copies (`withoutDuplicates`): in departure order, the preferred pack first,
    /// one service per number, minute and pair of stops; then, between two packs, one service per
    /// departure and arrival minute when `mayBeSameRun`. The indices of `runs` kept, in that order.
    static func kept(_ runs: [Run], border: Bool) -> [Int] {
        var identities = Set<String>()
        var keptByTimes: [String: [Int]] = [:]
        var kept: [Int] = []
        for index in preferredOrder(runs, border: border) {
            let run = runs[index]
            let identity = [
                compactNumber(run.number), String(minute(run.departure)),
                TimetableNaming.normalize(run.originName), TimetableNaming.normalize(run.destinationName),
            ].joined(separator: "|")
            guard identities.insert(identity).inserted else { continue }
            if let arrival = run.arrival {
                let times = "\(minute(run.departure))|\(minute(arrival))"
                let others = keptByTimes[times] ?? []
                if others.contains(where: { runs[$0].sourceID != run.sourceID && mayBeSameRun(runs[$0], run) }) { continue }
                keptByTimes[times] = others + [index]
            }
            kept.append(index)
        }
        return kept
    }

    /// The board's copies (Android's board clusters one public run: same number, same minute, same
    /// terminus): between two packs, a departure of the same core number at the same minute to
    /// the same terminus is one train. The indices of `runs` kept, in the preferred order.
    static func keptOnBoard(_ runs: [Run], border: Bool) -> [Int] {
        var keptByMinute: [Int64: [Int]] = [:]
        var kept: [Int] = []
        for index in preferredOrder(runs, border: border) {
            let run = runs[index]
            let others = keptByMinute[minute(run.departure)] ?? []
            let copy = others.contains { other in
                let listed = runs[other]
                guard listed.sourceID != run.sourceID,
                      let number = coreNumber(run.number), number == coreNumber(listed.number) else { return false }
                if let first = listed.destinationStationID, let second = run.destinationStationID { return first == second }
                return TimetableNaming.normalize(listed.destinationName) == TimetableNaming.normalize(run.destinationName)
            }
            if copy { continue }
            keptByMinute[minute(run.departure)] = others + [index]
            kept.append(index)
        }
        return kept
    }

    /// `mayBeSameRun`: two services of different packs at the same minutes are the same run when
    /// their numbers match, when the operator is the same, or when one pack republishes partner
    /// operators' trains; two Italian operators' own trains with different numbers are two trains.
    static func mayBeSameRun(_ first: Run, _ second: Run) -> Bool {
        if let number = coreNumber(first.number), number == coreNumber(second.number) { return true }
        let firstOperator = first.operatorName.trimmingCharacters(in: .whitespaces).lowercased()
        if !firstOperator.isEmpty, firstOperator == second.operatorName.trimmingCharacters(in: .whitespaces).lowercased() { return true }
        return crossPublishingPacks.contains(first.sourceID) || crossPublishingPacks.contains(second.sourceID)
    }

    /// `coreTrainNumber`: the last run of digits after the last « - », without leading zeros
    /// («S11 - 25017» is 25017, «REX 1826» is 1826); nil without one.
    static func coreNumber(_ number: String) -> String? {
        let tail = number.range(of: " - ", options: .backwards).map { String(number[$0.upperBound...]) } ?? number
        var runs: [String] = []
        var current = ""
        for character in tail {
            if character.isASCII && character.isNumber {
                current.append(character)
            } else if !current.isEmpty {
                runs.append(current)
                current = ""
            }
        }
        if !current.isEmpty { runs.append(current) }
        guard let last = runs.last else { return nil }
        let trimmed = String(last.drop(while: { $0 == "0" }))
        return trimmed.isEmpty ? nil : trimmed
    }

    /// The departure order, then the preferred pack, then the number (the sort of Android's route
    /// search before it removes the copies); equal services keep their order.
    private static func preferredOrder(_ runs: [Run], border: Bool) -> [Int] {
        runs.indices.sorted { first, second in
            let one = runs[first], other = runs[second]
            if minute(one.departure) != minute(other.departure) { return minute(one.departure) < minute(other.departure) }
            let firstRank = rank(one.sourceID, border: border), secondRank = rank(other.sourceID, border: border)
            if firstRank != secondRank { return firstRank < secondRank }
            if one.number != other.number { return one.number < other.number }
            return first < second
        }
    }

    /// `normalizeTrainNumber`: upper case, letters and digits only.
    private static func compactNumber(_ number: String) -> String {
        String(number.uppercased().unicodeScalars.filter { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }.map(Character.init))
    }

    /// Android compares the timetable's minutes.
    private static func minute(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 / 60).rounded(.down))
    }
}

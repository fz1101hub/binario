import Foundation

/// The connections of one search, sorted by departure minute (Android's `ConnectionScanTimetable`). A connection leaves
/// `departureNode` at `departureMinute` and reaches `arrivalNode` at `arrivalMinute` on `trip`. A node is a transfer
/// point; changing at node `n` needs `transferMinutes[n]` between the arrival and the next departure.
///
/// «Resta a bordo»: a traveller still on board after connection `c` is on board of every trip `continuationTrip[i]`
/// for `i` = `continuationStart[c]`, then `continuationNext[i]`, until -1: no change and no change time, and only for
/// a traveller who rode `c`.
struct ConnectionScanTimetable: Sendable {
    static let noPickup: UInt8 = 1
    static let noDropOff: UInt8 = 2

    let nodeCount: Int
    let tripCount: Int
    let transferMinutes: [Int]
    let departureNode: [Int]
    let arrivalNode: [Int]
    let departureMinute: [Int]
    let arrivalMinute: [Int]
    let trip: [Int]
    let boarding: [UInt8]
    /// Walks between nearby nodes: from node `n` to `footTarget[i]` in `footMinutes[i]` for `i` in
    /// `footStart[n]..<footStart[n + 1]` (walk, margin and change time).
    let footStart: [Int]
    let footTarget: [Int]
    let footMinutes: [Int]
    /// Per connection, the first in-seat continuation after it (an index of `continuationTrip`) or -1; nil: none.
    let continuationStart: [Int]?
    let continuationTrip: [Int]
    let continuationNext: [Int]

    enum Invalid: Error { case columns, transfers, footpaths, continuations, order }

    init(
        nodeCount: Int,
        tripCount: Int,
        transferMinutes: [Int],
        departureNode: [Int],
        arrivalNode: [Int],
        departureMinute: [Int],
        arrivalMinute: [Int],
        trip: [Int],
        boarding: [UInt8],
        footStart: [Int]? = nil,
        footTarget: [Int] = [],
        footMinutes: [Int] = [],
        continuationStart: [Int]? = nil,
        continuationTrip: [Int] = [],
        continuationNext: [Int] = []
    ) throws {
        let size = departureNode.count
        guard arrivalNode.count == size, departureMinute.count == size, arrivalMinute.count == size,
              trip.count == size, boarding.count == size else { throw Invalid.columns }
        guard transferMinutes.count == nodeCount else { throw Invalid.transfers }
        let starts = footStart ?? Array(repeating: 0, count: nodeCount + 1)
        guard starts.count == nodeCount + 1, footTarget.count == footMinutes.count, starts[nodeCount] == footTarget.count else {
            throw Invalid.footpaths
        }
        guard continuationStart.map({ $0.count == size }) ?? true, continuationTrip.count == continuationNext.count else {
            throw Invalid.continuations
        }
        var index = 1
        while index < size {
            if departureMinute[index - 1] > departureMinute[index] { throw Invalid.order }
            index += 1
        }
        self.nodeCount = nodeCount
        self.tripCount = tripCount
        self.transferMinutes = transferMinutes
        self.departureNode = departureNode
        self.arrivalNode = arrivalNode
        self.departureMinute = departureMinute
        self.arrivalMinute = arrivalMinute
        self.trip = trip
        self.boarding = boarding
        self.footStart = starts
        self.footTarget = footTarget
        self.footMinutes = footMinutes
        self.continuationStart = continuationStart
        self.continuationTrip = continuationTrip
        self.continuationNext = continuationNext
    }

    var size: Int { departureNode.count }

    /// Minutes of the footpath from `from` to `to`, or -1 when there is none.
    func footpathMinutes(from: Int, to: Int) -> Int {
        for index in footStart[from]..<footStart[from + 1] where footTarget[index] == to { return footMinutes[index] }
        return -1
    }

    func pickupAllowed(_ connection: Int) -> Bool { boarding[connection] & Self.noPickup == 0 }
    func dropOffAllowed(_ connection: Int) -> Bool { boarding[connection] & Self.noDropOff == 0 }

    static func boardingFlags(pickupAllowed: Bool, dropOffAllowed: Bool) -> UInt8 {
        (pickupAllowed ? 0 : noPickup) | (dropOffAllowed ? 0 : noDropOff)
    }
}

/// One vehicle of a journey: boarded on `boardConnection`, left after `alightConnection`.
struct ScannedLeg: Equatable, Hashable, Sendable {
    let boardConnection: Int
    let alightConnection: Int
}

/// A walk of a journey from node `fromNode` to `toNode` in `minutes`; -1 for the origin or the destination.
struct ScannedWalk: Equatable, Sendable {
    let fromNode: Int
    let toNode: Int
    let minutes: Int
}

struct ScannedJourney: Equatable, Sendable {
    let legs: [ScannedLeg]
    let departureMinute: Int
    let arrivalMinute: Int
    var accessWalk: ScannedWalk?
    /// For each change, the walk between the two vehicles, or nil when both use the same node.
    var transferWalks: [ScannedWalk?]
    var egressWalk: ScannedWalk?

    init(
        legs: [ScannedLeg],
        departureMinute: Int,
        arrivalMinute: Int,
        accessWalk: ScannedWalk? = nil,
        transferWalks: [ScannedWalk?]? = nil,
        egressWalk: ScannedWalk? = nil
    ) {
        self.legs = legs
        self.departureMinute = departureMinute
        self.arrivalMinute = arrivalMinute
        self.accessWalk = accessWalk
        self.transferWalks = transferWalks ?? Array(repeating: nil, count: max(legs.count - 1, 0))
        self.egressWalk = egressWalk
    }

    var transfers: Int { legs.count - 1 }

    /// Minutes spent walking: from the origin, between vehicles and to the destination.
    var walkMinutes: Int {
        (accessWalk?.minutes ?? 0) + transferWalks.reduce(0) { $0 + ($1?.minutes ?? 0) } + (egressWalk?.minutes ?? 0)
    }
}

/// The journeys of `ConnectionScanPlanner.planWithOutcome`; `exhausted`: no other Pareto-optimal option leaves
/// inside the window.
struct PlanOutcome: Sendable {
    let journeys: [ScannedJourney]
    let exhausted: Bool
}

struct ConnectionScanQuery: Sendable {
    /// Origin nodes: the journey may board there with no walk.
    var origins: [Bool]
    /// Destination nodes: the journey ends when it alights there.
    var destinations: [Bool]
    /// Earliest departure from an origin node.
    var departureMinute: Int
    /// No journey leaves an origin node after this minute (the search window).
    var latestDepartureMinute: Int
    var maxTransfers: Int
    var maxResults: Int
    /// Bound on the earliest-arrival scans of one search.
    var maxIterations: Int
    /// No vehicle is boarded after this minute; a traveller already on board stays on board.
    var lastConnectionMinute: Int
    /// Minutes of walking from the origin to a nearby node (-1: none).
    var originWalkMinutes: [Int]?
    /// Minutes of walking from a node to the destination (-1: none).
    var destinationWalkMinutes: [Int]?
    /// «Preferisci: meno camminate»: of two equal options, the one that walks less. Ranking only.
    var preferLessWalking: Bool
    /// Longest wait between two vehicles before an option counts as one with a long wait.
    var maxChangeWaitMinutes: Int

    enum Invalid: Error { case query }

    init(
        origins: [Bool],
        destinations: [Bool],
        departureMinute: Int,
        latestDepartureMinute: Int,
        maxTransfers: Int,
        maxResults: Int,
        maxIterations: Int,
        lastConnectionMinute: Int? = nil,
        originWalkMinutes: [Int]? = nil,
        destinationWalkMinutes: [Int]? = nil,
        preferLessWalking: Bool = false,
        maxChangeWaitMinutes: Int = Int.max
    ) throws {
        let last = lastConnectionMinute ?? latestDepartureMinute
        guard (0...6).contains(maxTransfers), maxResults > 0, maxIterations > 0,
              latestDepartureMinute >= departureMinute, last >= latestDepartureMinute, maxChangeWaitMinutes >= 0,
              originWalkMinutes.map({ $0.count == origins.count }) ?? true,
              destinationWalkMinutes.map({ $0.count == destinations.count }) ?? true
        else { throw Invalid.query }
        self.origins = origins
        self.destinations = destinations
        self.departureMinute = departureMinute
        self.latestDepartureMinute = latestDepartureMinute
        self.maxTransfers = maxTransfers
        self.maxResults = maxResults
        self.maxIterations = maxIterations
        self.lastConnectionMinute = last
        self.originWalkMinutes = originWalkMinutes
        self.destinationWalkMinutes = destinationWalkMinutes
        self.preferLessWalking = preferLessWalking
        self.maxChangeWaitMinutes = maxChangeWaitMinutes
    }
}

/// Connection Scan Algorithm with transfer counting, as Android's `ConnectionScanPlanner`: for every number of vehicles
/// the earliest arrival at every node; a round k boards only from a node reached in round k-1 at least its change time
/// earlier; footpaths are relaxed when a node is reached and never count as a change. `planWithOutcome` repeats the scan
/// from one minute after the earliest departure found, then drops dominated options and needless long waits, keeps one
/// option per train sequence and per (departure, arrival, changes), sorted by departure.
struct ConnectionScanPlanner: Sendable {
    let timetable: ConnectionScanTimetable

    static let unreached = Int.max

    func plan(_ query: ConnectionScanQuery, isCancelled: () -> Bool = { false }) -> [ScannedJourney] {
        planWithOutcome(query, isCancelled: isCancelled).journeys
    }

    func planWithOutcome(_ query: ConnectionScanQuery, isCancelled: () -> Bool = { false }) -> PlanOutcome {
        guard query.origins.count == timetable.nodeCount, query.destinations.count == timetable.nodeCount else {
            return PlanOutcome(journeys: [], exhausted: true)
        }
        let access = walkTimes(query.origins, query.originWalkMinutes)
        let egress = walkTimes(query.destinations, query.destinationWalkMinutes)
        guard access.contains(where: { $0 >= 0 }), egress.contains(where: { $0 >= 0 }) else {
            return PlanOutcome(journeys: [], exhausted: true)
        }
        for node in 0..<timetable.nodeCount where query.origins[node] && query.destinations[node] {
            return PlanOutcome(journeys: [], exhausted: true)
        }
        var state = ScanState(rounds: query.maxTransfers + 1, nodes: timetable.nodeCount, trips: timetable.tripCount, access: access, egress: egress)
        var found: [ScannedJourney] = []
        var from = query.departureMinute
        var iterations = 0
        var exhausted = false
        while true {
            if from > query.latestDepartureMinute {
                exhausted = true
                break
            }
            if iterations >= query.maxIterations || isCancelled() { break }
            iterations += 1
            let run = scan(from: from, query: query, state: &state, isCancelled: isCancelled)
            if run.isEmpty {
                exhausted = true
                break
            }
            found += run
            from = (run.map(\.departureMinute).min() ?? from) + 1
            let selected = select(found, query: query)
            // A later scan never dominates an option that already arrived (with its extra wait) before `from`.
            if selected.count >= query.maxResults,
               selected.prefix(query.maxResults).allSatisfy({ $0.arrivalMinute + excessWait($0, query.maxChangeWaitMinutes) < from }) {
                break
            }
        }
        let selected = select(found, query: query)
        return PlanOutcome(journeys: Array(selected.prefix(query.maxResults)), exhausted: exhausted && selected.count <= query.maxResults)
    }

    private func scan(from: Int, query: ConnectionScanQuery, state: inout ScanState, isCancelled: () -> Bool) -> [ScannedJourney] {
        let nodes = timetable.nodeCount
        let trips = timetable.tripCount
        let rounds = state.rounds
        state.reset()
        let size = timetable.size
        var connection = firstDepartingAtOrAfter(from)
        while connection < size {
            let departure = timetable.departureMinute[connection]
            // Nothing leaving at or after the best direct arrival can improve any round.
            if departure >= state.bestAtDestination[1] { break }
            let boardingOpen = departure <= query.lastConnectionMinute
            if connection & 4095 == 0, isCancelled() { break }
            let trip = timetable.trip[connection]
            let boardNode = timetable.departureNode[connection]
            let alightNode = timetable.arrivalNode[connection]
            let arrives = timetable.arrivalMinute[connection]
            let pickup = timetable.pickupAllowed(connection)
            let dropOff = timetable.dropOffAllowed(connection)
            for round in 1...rounds {
                let slot = (round - 1) * trips + trip
                var boarded = state.boardedAt[slot]
                if boarded < 0, pickup, boardingOpen {
                    let reachable: Bool
                    if round == 1 {
                        let walk = state.access[boardNode]
                        // The journey leaves the origin `walk` minutes before this departure, inside the window.
                        reachable = walk >= 0 && departure - walk >= from && departure - walk <= query.latestDepartureMinute
                    } else {
                        reachable = state.ready[(round - 1) * nodes + boardNode] <= departure
                    }
                    if reachable {
                        state.boardedAt[slot] = connection
                        boarded = connection
                    }
                }
                if boarded < 0 { continue }
                // Still on board here: on board of the trips the vehicle goes on as, boarded where this one was.
                if let continuationStart = timetable.continuationStart {
                    var link = continuationStart[connection]
                    while link >= 0 {
                        let next = (round - 1) * trips + timetable.continuationTrip[link]
                        if state.boardedAt[next] < 0 { state.boardedAt[next] = boarded }
                        link = timetable.continuationNext[link]
                    }
                }
                guard dropOff else { continue }
                let cell = round * nodes + alightNode
                guard arrives < state.arrival[cell] else { continue }
                state.arrival[cell] = arrives
                state.parentBoard[cell] = boarded
                state.parentAlight[cell] = connection
                if round < rounds {
                    // Ready for the next vehicle here after the change time, or at a nearby node after the walk.
                    let base = round * nodes
                    let here = arrives + timetable.transferMinutes[alightNode]
                    if here < state.ready[base + alightNode] {
                        state.ready[base + alightNode] = here
                        state.readyFrom[base + alightNode] = alightNode
                    }
                    for path in timetable.footStart[alightNode]..<timetable.footStart[alightNode + 1] {
                        let target = timetable.footTarget[path]
                        let there = arrives + timetable.footMinutes[path]
                        if there < state.ready[base + target] {
                            state.ready[base + target] = there
                            state.readyFrom[base + target] = alightNode
                        }
                    }
                }
                let walk = state.egress[alightNode]
                if walk >= 0, arrives + walk < state.bestAtDestination[round] {
                    state.bestAtDestination[round] = arrives + walk
                    state.destinationNode[round] = alightNode
                }
            }
            connection += 1
        }

        var journeys: [ScannedJourney] = []
        var bestWithFewer = Self.unreached
        for round in 1...rounds {
            let best = state.bestAtDestination[round]
            if best >= bestWithFewer { continue }
            bestWithFewer = best
            let destination = state.destinationNode[round]
            if destination >= 0, let journey = reconstruct(round: round, destination: destination, state: state) {
                journeys.append(journey)
            }
        }
        return journeys
    }

    private func reconstruct(round: Int, destination: Int, state: ScanState) -> ScannedJourney? {
        let nodes = timetable.nodeCount
        var legs: [ScannedLeg] = []
        var walks: [ScannedWalk?] = []
        var node = destination
        var current = round
        var firstBoardNode = -1
        while current >= 1 {
            let cell = current * nodes + node
            let board = state.parentBoard[cell]
            let alight = state.parentAlight[cell]
            if board < 0 || alight < 0 { return nil }
            legs.append(ScannedLeg(boardConnection: board, alightConnection: alight))
            let boardNode = timetable.departureNode[board]
            if current > 1 {
                let from = state.readyFrom[(current - 1) * nodes + boardNode]
                if from < 0 { return nil }
                walks.append(from == boardNode ? nil
                    : ScannedWalk(fromNode: from, toNode: boardNode, minutes: max(timetable.footpathMinutes(from: from, to: boardNode), 0)))
                node = from
            } else {
                firstBoardNode = boardNode
            }
            current -= 1
        }
        let accessMinutes = state.access[firstBoardNode]
        let egressMinutes = state.egress[destination]
        if accessMinutes < 0 || egressMinutes < 0 { return nil }
        legs.reverse()
        walks.reverse()
        return ScannedJourney(
            legs: legs,
            departureMinute: timetable.departureMinute[legs[0].boardConnection] - accessMinutes,
            arrivalMinute: timetable.arrivalMinute[legs[legs.count - 1].alightConnection] + egressMinutes,
            accessWalk: accessMinutes > 0 ? ScannedWalk(fromNode: -1, toNode: firstBoardNode, minutes: accessMinutes) : nil,
            transferWalks: walks,
            egressWalk: egressMinutes > 0 ? ScannedWalk(fromNode: destination, toNode: -1, minutes: egressMinutes) : nil
        )
    }

    /// Per node, 0 for the given nodes, else the walk minutes or -1.
    private func walkTimes(_ nodes: [Bool], _ walk: [Int]?) -> [Int] {
        nodes.indices.map { node in
            if nodes[node] { return 0 }
            if let walk, walk[node] >= 0 { return walk[node] }
            return -1
        }
    }

    /// First connection departing at or after `minute`.
    private func firstDepartingAtOrAfter(_ minute: Int) -> Int {
        var low = 0
        var high = timetable.size
        while low < high {
            let middle = (low + high) / 2
            if timetable.departureMinute[middle] < minute { low = middle + 1 } else { high = middle }
        }
        return low
    }

    /// Minutes of the longest wait between two vehicles of `journey` (a walk between them included); 0 without changes.
    func longestWait(_ journey: ScannedJourney) -> Int {
        var longest = 0
        for index in journey.legs.indices.dropFirst() {
            let wait = timetable.departureMinute[journey.legs[index].boardConnection]
                - timetable.arrivalMinute[journey.legs[index - 1].alightConnection]
            longest = max(longest, wait)
        }
        return longest
    }

    /// How much the longest wait of `journey` exceeds `maxWaitMinutes`; 0 when it does not.
    func excessWait(_ journey: ScannedJourney, _ maxWaitMinutes: Int) -> Int {
        maxWaitMinutes == Int.max ? 0 : max(longestWait(journey) - maxWaitMinutes, 0)
    }

    private func select(_ found: [ScannedJourney], query: ConnectionScanQuery) -> [ScannedJourney] {
        // A long wait is worth avoiding when another option with a shorter one arrives no later than this option's
        // arrival plus its extra wait.
        let excess = found.map { excessWait($0, query.maxChangeWaitMinutes) }
        var patient: [ScannedJourney] = []
        for (index, journey) in found.enumerated() {
            let keep = excess[index] == 0 || !found.indices.contains { other in
                excess[other] < excess[index] && found[other].arrivalMinute + excess[other] <= journey.arrivalMinute + excess[index]
            }
            if keep { patient.append(journey) }
        }
        var byTrainsIndex: [[Int]: Int] = [:]
        var byTrains: [ScannedJourney] = []
        for journey in patient {
            Self.offer(journey, key: journey.legs.map { timetable.trip[$0.boardConnection] }, lessWalking: query.preferLessWalking,
                       into: &byTrainsIndex, list: &byTrains)
        }
        var byCriteriaIndex: [CriteriaKey: Int] = [:]
        var candidates: [ScannedJourney] = []
        for journey in byTrains {
            Self.offer(journey, key: CriteriaKey(departure: journey.departureMinute, arrival: journey.arrivalMinute, transfers: journey.transfers),
                       lessWalking: query.preferLessWalking, into: &byCriteriaIndex, list: &candidates)
        }
        let kept = candidates.indices.filter { candidate in
            !candidates.indices.contains { other in other != candidate && Self.dominates(candidates[other], candidates[candidate]) }
        }.map { candidates[$0] }
        // A stable sort, as Kotlin's sortedWith.
        return kept.enumerated().sorted { first, second in
            let a = first.element
            let b = second.element
            if a.departureMinute != b.departureMinute { return a.departureMinute < b.departureMinute }
            if a.arrivalMinute != b.arrivalMinute { return a.arrivalMinute < b.arrivalMinute }
            if a.transfers != b.transfers { return a.transfers < b.transfers }
            return first.offset < second.offset
        }.map(\.element)
    }

    /// The first option of `key` stays, unless «meno camminate» and `journey` walks less (it then takes its place).
    private static func offer<Key: Hashable>(
        _ journey: ScannedJourney, key: Key, lessWalking: Bool, into kept: inout [Key: Int], list: inout [ScannedJourney]
    ) {
        if let index = kept[key] {
            if lessWalking, journey.walkMinutes < list[index].walkMinutes { list[index] = journey }
        } else {
            kept[key] = list.count
            list.append(journey)
        }
    }

    private struct CriteriaKey: Hashable {
        let departure: Int
        let arrival: Int
        let transfers: Int
    }

    private static func dominates(_ first: ScannedJourney, _ second: ScannedJourney) -> Bool {
        first.departureMinute >= second.departureMinute && first.arrivalMinute <= second.arrivalMinute
            && first.transfers <= second.transfers
            && (first.departureMinute > second.departureMinute || first.arrivalMinute < second.arrivalMinute
                || first.transfers < second.transfers)
    }

    private struct ScanState {
        let rounds: Int
        let access: [Int]
        let egress: [Int]
        var arrival: [Int]
        /// Earliest minute a traveller who used `round` vehicles can board another one at a node.
        var ready: [Int]
        /// The node where that traveller left the vehicle (itself, or the start of the walk).
        var readyFrom: [Int]
        var parentBoard: [Int]
        var parentAlight: [Int]
        var boardedAt: [Int]
        var bestAtDestination: [Int]
        var destinationNode: [Int]

        init(rounds: Int, nodes: Int, trips: Int, access: [Int], egress: [Int]) {
            self.rounds = rounds
            self.access = access
            self.egress = egress
            let cells = (rounds + 1) * nodes
            arrival = Array(repeating: ConnectionScanPlanner.unreached, count: cells)
            ready = Array(repeating: ConnectionScanPlanner.unreached, count: cells)
            readyFrom = Array(repeating: -1, count: cells)
            parentBoard = Array(repeating: -1, count: cells)
            parentAlight = Array(repeating: -1, count: cells)
            boardedAt = Array(repeating: -1, count: rounds * trips)
            bestAtDestination = Array(repeating: ConnectionScanPlanner.unreached, count: rounds + 1)
            destinationNode = Array(repeating: -1, count: rounds + 1)
        }

        mutating func reset() {
            for index in arrival.indices {
                arrival[index] = ConnectionScanPlanner.unreached
                ready[index] = ConnectionScanPlanner.unreached
                readyFrom[index] = -1
                parentBoard[index] = -1
                parentAlight[index] = -1
            }
            for index in boardedAt.indices { boardedAt[index] = -1 }
            for index in bestAtDestination.indices {
                bestAtDestination[index] = ConnectionScanPlanner.unreached
                destinationNode[index] = -1
            }
        }
    }
}

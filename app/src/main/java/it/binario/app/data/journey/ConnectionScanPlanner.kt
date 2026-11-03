package it.binario.app.data.journey

/**
 * The connections of one search, sorted by departure minute (ties: arrival minute, then trip order). A connection
 * leaves [departureNode] at [departureMinute] and reaches [arrivalNode] at [arrivalMinute] on [trip]. A node is a
 * transfer point (one catalogue station, or one station of a pack that the catalogue does not know); changing
 * trains at node `n` needs [transferMinutes]`[n]` between the arrival and the next departure.
 */
internal class ConnectionScanTimetable(
    val nodeCount: Int,
    val tripCount: Int,
    val transferMinutes: IntArray,
    val departureNode: IntArray,
    val arrivalNode: IntArray,
    val departureMinute: IntArray,
    val arrivalMinute: IntArray,
    val trip: IntArray,
    private val boarding: ByteArray,
    /**
     * Walking transfers between nearby nodes (two stations, a station and a metro stop): the footpaths leaving node
     * `n` are `footTarget[i]`, `footMinutes[i]` for `i` in `footStart[n] until footStart[n + 1]`. [footMinutes] is the
     * whole time from the arrival at `n` to being ready to board at the target (walk, margin and change time).
     */
    val footStart: IntArray = IntArray(nodeCount + 1),
    val footTarget: IntArray = IntArray(0),
    val footMinutes: IntArray = IntArray(0)
) {
    init {
        val size = departureNode.size
        require(arrivalNode.size == size && departureMinute.size == size && arrivalMinute.size == size &&
            trip.size == size && boarding.size == size) { "Colonne delle connessioni di lunghezza diversa" }
        require(transferMinutes.size == nodeCount) { "Un tempo di cambio per nodo" }
        require(footStart.size == nodeCount + 1 && footTarget.size == footMinutes.size &&
            footStart[nodeCount] == footTarget.size) { "Percorsi a piedi non coerenti" }
        for (index in 1 until size) {
            require(departureMinute[index - 1] <= departureMinute[index]) { "Connessioni non ordinate per partenza" }
        }
    }

    val size: Int get() = departureNode.size

    /** Minutes of the footpath from [from] to [to], or -1 when there is none. */
    fun footpathMinutes(from: Int, to: Int): Int {
        for (index in footStart[from] until footStart[from + 1]) if (footTarget[index] == to) return footMinutes[index]
        return -1
    }

    fun pickupAllowed(connection: Int): Boolean = boarding[connection].toInt() and NO_PICKUP == 0
    fun dropOffAllowed(connection: Int): Boolean = boarding[connection].toInt() and NO_DROP_OFF == 0

    companion object {
        const val NO_PICKUP = 1
        const val NO_DROP_OFF = 2

        fun boardingFlags(pickupAllowed: Boolean, dropOffAllowed: Boolean): Byte =
            ((if (pickupAllowed) 0 else NO_PICKUP) or (if (dropOffAllowed) 0 else NO_DROP_OFF)).toByte()
    }
}

/** One vehicle of a journey: boarded on [boardConnection], left after [alightConnection] (same trip). */
internal data class ScannedLeg(val boardConnection: Int, val alightConnection: Int)

/**
 * A walk of a journey: from node [fromNode] to node [toNode] in [minutes]. The walk from the origin has no
 * [fromNode] (-1), the walk to the destination no [toNode] (-1).
 */
internal data class ScannedWalk(val fromNode: Int, val toNode: Int, val minutes: Int)

internal data class ScannedJourney(
    val legs: List<ScannedLeg>,
    val departureMinute: Int,
    val arrivalMinute: Int,
    /** Walk from the origin to the first boarding node, when the journey does not start at an origin node. */
    val accessWalk: ScannedWalk? = null,
    /** For each change (`legs.size - 1`), the walk between the two vehicles, or null when both use the same node. */
    val transferWalks: List<ScannedWalk?> = List((legs.size - 1).coerceAtLeast(0)) { null },
    /** Walk from the last alighting node to the destination, when it is not a destination node. */
    val egressWalk: ScannedWalk? = null
) {
    val transfers: Int get() = legs.size - 1

    /** Minutes spent walking: from the origin, between vehicles and to the destination. */
    val walkMinutes: Int get() =
        (accessWalk?.minutes ?: 0) + transferWalks.sumOf { it?.minutes ?: 0 } + (egressWalk?.minutes ?: 0)
}

/**
 * The journeys of [ConnectionScanPlanner.planWithOutcome]. [exhausted]: every scan of the window was made (the last one
 * found nothing or the window ended) and nothing was cut by `maxResults`, so no other Pareto-optimal option leaves
 * inside the window.
 */
internal data class PlanOutcome(val journeys: List<ScannedJourney>, val exhausted: Boolean)

internal data class ConnectionScanQuery(
    /** Origin nodes: the journey may board there with no walk. */
    val origins: BooleanArray,
    /** Destination nodes: the journey ends when it alights there. */
    val destinations: BooleanArray,
    /** Earliest departure from an origin node. */
    val departureMinute: Int,
    /** No journey leaves an origin node after this minute (the search window). */
    val latestDepartureMinute: Int,
    val maxTransfers: Int,
    val maxResults: Int,
    /** Bound on the earliest-arrival scans of one search (each one starts after the previous best departure). */
    val maxIterations: Int,
    /**
     * No connection departing after this minute is used: a journey that leaves inside the window can go on with
     * connections up to here, so a long trip leaving near the end of the window still arrives.
     */
    val lastConnectionMinute: Int = latestDepartureMinute,
    /**
     * Minutes of walking from the origin to a nearby node (-1: none), so that a journey can start at a metro stop near
     * the requested station. Origin nodes need no walk whatever this says.
     */
    val originWalkMinutes: IntArray? = null,
    /** Minutes of walking from a node to the destination (-1: none). Destination nodes need no walk. */
    val destinationWalkMinutes: IntArray? = null,
    /**
     * "Preferisci: meno camminate": of two options with the same trains, or the same departure, arrival and changes,
     * the one that walks less is kept (otherwise the first found). Ranking only, never another journey.
     */
    val preferLessWalking: Boolean = false
) {
    init {
        require(maxTransfers in 0..6) { "Numero di cambi non supportato" }
        require(originWalkMinutes == null || originWalkMinutes.size == origins.size) { "Un tempo a piedi per nodo" }
        require(destinationWalkMinutes == null || destinationWalkMinutes.size == destinations.size) { "Un tempo a piedi per nodo" }
        require(maxResults > 0) { "Serve almeno un risultato" }
        require(maxIterations > 0) { "Serve almeno una scansione" }
        require(latestDepartureMinute >= departureMinute) { "Finestra di ricerca non valida" }
        require(lastConnectionMinute >= latestDepartureMinute) { "Orizzonte delle connessioni non valido" }
    }
}

/**
 * Connection Scan Algorithm with transfer counting (Dibbelt et al., "Connection Scan Algorithm", 2013/2018), the
 * multi-criteria variant that keeps, for every number of vehicles k, the earliest arrival at every node.
 *
 * One scan answers "leaving at or after t": for k = 1..maxTransfers+1 it gives the earliest arrival at the
 * destination with exactly k vehicles, and k is kept only if it arrives strictly earlier than every option with
 * fewer vehicles (a Pareto set on arrival time and number of changes). A trip can be boarded in round k only when
 * the traveller reached the boarding node in round k-1 at least the node's transfer time before the connection
 * departs (round 1: from an origin node). Staying on a trip needs no transfer.
 *
 * Walking (Dibbelt et al., section 3.2, footpaths relaxed when a node is reached): alighting at node `u` at time `t`
 * makes `u` ready for the next vehicle at `t` + its transfer time and every node `v` with a footpath from `u` ready at
 * `t` + the footpath's minutes. A walk is not a vehicle and never counts as a change. The query may also start with a
 * walk from the origin to a nearby node and end with a walk from a nearby node to the destination; a journey that
 * only walks is never returned.
 *
 * [plan] repeats the scan from one minute after the earliest departure found so far, so options leaving later are
 * found too; afterwards it removes dominated options (leaving no later, arriving no earlier, with no fewer
 * changes), keeps one option per train sequence and per (departure, arrival, changes) and returns at most
 * maxResults options sorted by departure. Memory is O(rounds × (nodes + trips)), allocated once per search.
 */
internal class ConnectionScanPlanner(private val timetable: ConnectionScanTimetable) {

    fun plan(query: ConnectionScanQuery, ensureActive: () -> Unit = {}): List<ScannedJourney> =
        planWithOutcome(query, ensureActive).journeys

    fun planWithOutcome(query: ConnectionScanQuery, ensureActive: () -> Unit = {}): PlanOutcome {
        require(query.origins.size == timetable.nodeCount && query.destinations.size == timetable.nodeCount) {
            "Origini e destinazioni devono coprire tutti i nodi"
        }
        val access = walkTimes(query.origins, query.originWalkMinutes)
        val egress = walkTimes(query.destinations, query.destinationWalkMinutes)
        if (access.none { it >= 0 } || egress.none { it >= 0 }) return PlanOutcome(emptyList(), exhausted = true)
        if ((0 until timetable.nodeCount).any { query.origins[it] && query.destinations[it] }) return PlanOutcome(emptyList(), exhausted = true)

        val state = ScanState(query.maxTransfers + 1, timetable.nodeCount, timetable.tripCount, access, egress)
        val found = ArrayList<ScannedJourney>()
        var from = query.departureMinute
        var iterations = 0
        var exhausted = false
        while (true) {
            if (from > query.latestDepartureMinute) {
                exhausted = true
                break
            }
            if (iterations >= query.maxIterations) break
            iterations += 1
            ensureActive()
            val run = scan(from, query, state, ensureActive)
            if (run.isEmpty()) {
                exhausted = true
                break
            }
            found += run
            from = run.minOf { it.departureMinute } + 1
            val selected = select(found, query.preferLessWalking)
            // A later scan only finds options leaving at or after `from`, which can never dominate an option
            // that already arrived before `from`: once the first maxResults options all did, they are final.
            if (selected.size >= query.maxResults && selected.take(query.maxResults).all { it.arrivalMinute < from }) break
        }
        val selected = select(found, query.preferLessWalking)
        return PlanOutcome(selected.take(query.maxResults), exhausted && selected.size <= query.maxResults)
    }

    private fun scan(
        from: Int,
        query: ConnectionScanQuery,
        state: ScanState,
        ensureActive: () -> Unit
    ): List<ScannedJourney> {
        val nodes = timetable.nodeCount
        val trips = timetable.tripCount
        val rounds = state.rounds
        state.reset()
        val arrival = state.arrival
        val ready = state.ready
        val readyFrom = state.readyFrom
        val boardedAt = state.boardedAt
        val parentBoard = state.parentBoard
        val parentAlight = state.parentAlight
        val bestAtDestination = state.bestAtDestination
        val destinationNode = state.destinationNode
        val access = state.access
        val egress = state.egress
        val departureMinute = timetable.departureMinute
        val arrivalMinute = timetable.arrivalMinute
        val departureNode = timetable.departureNode
        val arrivalNode = timetable.arrivalNode
        val tripOf = timetable.trip
        val transferMinutes = timetable.transferMinutes
        val footStart = timetable.footStart
        val footTarget = timetable.footTarget
        val footMinutes = timetable.footMinutes

        var connection = firstDepartingAtOrAfter(from)
        val size = timetable.size
        while (connection < size) {
            val departure = departureMinute[connection]
            if (departure > query.lastConnectionMinute) break
            // Nothing leaving at or after the best direct arrival can improve any round (see class comment).
            if (departure >= bestAtDestination[1]) break
            if (connection and 4095 == 0) ensureActive()
            val trip = tripOf[connection]
            val boardNode = departureNode[connection]
            val alightNode = arrivalNode[connection]
            val arrives = arrivalMinute[connection]
            val pickup = timetable.pickupAllowed(connection)
            val dropOff = timetable.dropOffAllowed(connection)
            for (round in 1..rounds) {
                val slot = (round - 1) * trips + trip
                var boarded = boardedAt[slot]
                if (boarded < 0 && pickup) {
                    val reachable = if (round == 1) {
                        val walk = access[boardNode]
                        // The journey leaves the origin `walk` minutes before this departure, inside the window.
                        walk >= 0 && departure - walk >= from && departure - walk <= query.latestDepartureMinute
                    } else {
                        ready[(round - 1) * nodes + boardNode] <= departure
                    }
                    if (reachable) {
                        boardedAt[slot] = connection
                        boarded = connection
                    }
                }
                if (boarded >= 0 && dropOff) {
                    val cell = round * nodes + alightNode
                    if (arrives < arrival[cell]) {
                        arrival[cell] = arrives
                        parentBoard[cell] = boarded
                        parentAlight[cell] = connection
                        if (round < rounds) {
                            // Ready for the next vehicle here after the change time, or at a nearby node after the walk.
                            val base = round * nodes
                            val here = arrives + transferMinutes[alightNode]
                            if (here < ready[base + alightNode]) {
                                ready[base + alightNode] = here
                                readyFrom[base + alightNode] = alightNode
                            }
                            for (path in footStart[alightNode] until footStart[alightNode + 1]) {
                                val target = footTarget[path]
                                val there = arrives + footMinutes[path]
                                if (there < ready[base + target]) {
                                    ready[base + target] = there
                                    readyFrom[base + target] = alightNode
                                }
                            }
                        }
                        val walk = egress[alightNode]
                        if (walk >= 0 && arrives + walk < bestAtDestination[round]) {
                            bestAtDestination[round] = arrives + walk
                            destinationNode[round] = alightNode
                        }
                    }
                }
            }
            connection += 1
        }

        val journeys = ArrayList<ScannedJourney>()
        var bestWithFewer = UNREACHED
        for (round in 1..rounds) {
            val best = bestAtDestination[round]
            if (best >= bestWithFewer) continue
            bestWithFewer = best
            val destination = destinationNode[round]
            if (destination >= 0) reconstruct(round, destination, state)?.let(journeys::add)
        }
        return journeys
    }

    private fun reconstruct(round: Int, destination: Int, state: ScanState): ScannedJourney? {
        val nodes = timetable.nodeCount
        val legs = ArrayList<ScannedLeg>(round)
        val walks = ArrayList<ScannedWalk?>(round)
        var node = destination
        var current = round
        var firstBoardNode = -1
        while (current >= 1) {
            val cell = current * nodes + node
            val board = state.parentBoard[cell]
            val alight = state.parentAlight[cell]
            if (board < 0 || alight < 0) return null
            legs += ScannedLeg(board, alight)
            val boardNode = timetable.departureNode[board]
            if (current > 1) {
                val from = state.readyFrom[(current - 1) * nodes + boardNode]
                if (from < 0) return null
                walks += if (from == boardNode) null else ScannedWalk(from, boardNode, timetable.footpathMinutes(from, boardNode).coerceAtLeast(0))
                node = from
            } else {
                firstBoardNode = boardNode
            }
            current -= 1
        }
        val accessMinutes = state.access[firstBoardNode]
        val egressMinutes = state.egress[destination]
        if (accessMinutes < 0 || egressMinutes < 0) return null
        legs.reverse()
        walks.reverse()
        return ScannedJourney(
            legs = legs,
            departureMinute = timetable.departureMinute[legs.first().boardConnection] - accessMinutes,
            arrivalMinute = timetable.arrivalMinute[legs.last().alightConnection] + egressMinutes,
            accessWalk = if (accessMinutes > 0) ScannedWalk(-1, firstBoardNode, accessMinutes) else null,
            transferWalks = walks,
            egressWalk = if (egressMinutes > 0) ScannedWalk(destination, -1, egressMinutes) else null
        )
    }

    /** Per node, 0 for the given nodes, else the walk minutes ([walk]) or -1. */
    private fun walkTimes(nodes: BooleanArray, walk: IntArray?): IntArray = IntArray(nodes.size) { node ->
        when {
            nodes[node] -> 0
            walk != null && walk[node] >= 0 -> walk[node]
            else -> -1
        }
    }

    /** First connection departing at or after [minute] (binary search on the sorted departures). */
    private fun firstDepartingAtOrAfter(minute: Int): Int {
        var low = 0
        var high = timetable.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (timetable.departureMinute[middle] < minute) low = middle + 1 else high = middle
        }
        return low
    }

    private fun select(found: List<ScannedJourney>, preferLessWalking: Boolean = false): List<ScannedJourney> {
        fun <K> MutableMap<K, ScannedJourney>.offer(key: K, journey: ScannedJourney) {
            val kept = putIfAbsent(key, journey) ?: return
            if (preferLessWalking && journey.walkMinutes < kept.walkMinutes) put(key, journey)
        }
        val byTrains = LinkedHashMap<List<Int>, ScannedJourney>()
        for (journey in found) {
            byTrains.offer(journey.legs.map { timetable.trip[it.boardConnection] }, journey)
        }
        val byCriteria = LinkedHashMap<Triple<Int, Int, Int>, ScannedJourney>()
        for (journey in byTrains.values) {
            byCriteria.offer(Triple(journey.departureMinute, journey.arrivalMinute, journey.transfers), journey)
        }
        val candidates = byCriteria.values.toList()
        return candidates
            .filter { candidate -> candidates.none { other -> other !== candidate && dominates(other, candidate) } }
            .sortedWith(compareBy<ScannedJourney> { it.departureMinute }.thenBy { it.arrivalMinute }.thenBy { it.transfers })
    }

    private fun dominates(first: ScannedJourney, second: ScannedJourney): Boolean =
        first.departureMinute >= second.departureMinute &&
            first.arrivalMinute <= second.arrivalMinute &&
            first.transfers <= second.transfers &&
            (first.departureMinute > second.departureMinute || first.arrivalMinute < second.arrivalMinute ||
                first.transfers < second.transfers)

    private class ScanState(val rounds: Int, nodes: Int, trips: Int, val access: IntArray, val egress: IntArray) {
        val arrival = IntArray((rounds + 1) * nodes)
        /** Earliest minute a traveller who used `round` vehicles can board another one at a node. */
        val ready = IntArray((rounds + 1) * nodes)
        /** The node where that traveller left the vehicle (itself, or the start of the walk). */
        val readyFrom = IntArray((rounds + 1) * nodes)
        val parentBoard = IntArray((rounds + 1) * nodes)
        val parentAlight = IntArray((rounds + 1) * nodes)
        val boardedAt = IntArray(rounds * trips)
        val bestAtDestination = IntArray(rounds + 1)
        val destinationNode = IntArray(rounds + 1)

        fun reset() {
            arrival.fill(UNREACHED)
            ready.fill(UNREACHED)
            readyFrom.fill(-1)
            parentBoard.fill(-1)
            parentAlight.fill(-1)
            boardedAt.fill(-1)
            bestAtDestination.fill(UNREACHED)
            destinationNode.fill(-1)
        }
    }

    private companion object {
        const val UNREACHED = Int.MAX_VALUE
    }
}

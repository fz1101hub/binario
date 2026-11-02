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
    private val boarding: ByteArray
) {
    init {
        val size = departureNode.size
        require(arrivalNode.size == size && departureMinute.size == size && arrivalMinute.size == size &&
            trip.size == size && boarding.size == size) { "Colonne delle connessioni di lunghezza diversa" }
        require(transferMinutes.size == nodeCount) { "Un tempo di cambio per nodo" }
        for (index in 1 until size) {
            require(departureMinute[index - 1] <= departureMinute[index]) { "Connessioni non ordinate per partenza" }
        }
    }

    val size: Int get() = departureNode.size

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

internal data class ScannedJourney(
    val legs: List<ScannedLeg>,
    val departureMinute: Int,
    val arrivalMinute: Int
) {
    val transfers: Int get() = legs.size - 1
}

internal data class ConnectionScanQuery(
    val origins: BooleanArray,
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
    val lastConnectionMinute: Int = latestDepartureMinute
) {
    init {
        require(maxTransfers in 0..6) { "Numero di cambi non supportato" }
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
 * [plan] repeats the scan from one minute after the earliest departure found so far, so options leaving later are
 * found too; afterwards it removes dominated options (leaving no later, arriving no earlier, with no fewer
 * changes), keeps one option per train sequence and per (departure, arrival, changes) and returns at most
 * maxResults options sorted by departure. Memory is O(rounds × (nodes + trips)), allocated once per search.
 */
internal class ConnectionScanPlanner(private val timetable: ConnectionScanTimetable) {

    fun plan(query: ConnectionScanQuery, ensureActive: () -> Unit = {}): List<ScannedJourney> {
        require(query.origins.size == timetable.nodeCount && query.destinations.size == timetable.nodeCount) {
            "Origini e destinazioni devono coprire tutti i nodi"
        }
        if ((0 until timetable.nodeCount).none { query.origins[it] } ||
            (0 until timetable.nodeCount).none { query.destinations[it] }) return emptyList()
        if ((0 until timetable.nodeCount).any { query.origins[it] && query.destinations[it] }) return emptyList()

        val state = ScanState(query.maxTransfers + 1, timetable.nodeCount, timetable.tripCount)
        val found = ArrayList<ScannedJourney>()
        var from = query.departureMinute
        var iterations = 0
        while (from <= query.latestDepartureMinute && iterations < query.maxIterations) {
            iterations += 1
            ensureActive()
            val run = scan(from, query, state, ensureActive)
            if (run.isEmpty()) break
            found += run
            from = run.minOf { it.departureMinute } + 1
            val selected = select(found)
            // A later scan only finds options leaving at or after `from`, which can never dominate an option
            // that already arrived before `from`: once the first maxResults options all did, they are final.
            if (selected.size >= query.maxResults && selected.take(query.maxResults).all { it.arrivalMinute < from }) break
        }
        return select(found).take(query.maxResults)
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
        val boardedAt = state.boardedAt
        val parentBoard = state.parentBoard
        val parentAlight = state.parentAlight
        val bestAtDestination = state.bestAtDestination
        val departureMinute = timetable.departureMinute
        val arrivalMinute = timetable.arrivalMinute
        val departureNode = timetable.departureNode
        val arrivalNode = timetable.arrivalNode
        val tripOf = timetable.trip
        val transferMinutes = timetable.transferMinutes

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
                        query.origins[boardNode] && departure <= query.latestDepartureMinute
                    } else {
                        val reached = arrival[(round - 1) * nodes + boardNode]
                        reached != UNREACHED && reached + transferMinutes[boardNode] <= departure
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
                        if (query.destinations[alightNode] && arrives < bestAtDestination[round]) bestAtDestination[round] = arrives
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
            var destination = -1
            for (node in 0 until nodes) {
                if (query.destinations[node] && arrival[round * nodes + node] == best) {
                    destination = node
                    break
                }
            }
            if (destination >= 0) reconstruct(round, destination, state, query)?.let(journeys::add)
        }
        return journeys
    }

    private fun reconstruct(round: Int, destination: Int, state: ScanState, query: ConnectionScanQuery): ScannedJourney? {
        val nodes = timetable.nodeCount
        val legs = ArrayList<ScannedLeg>(round)
        var node = destination
        var current = round
        while (current >= 1) {
            val cell = current * nodes + node
            val board = state.parentBoard[cell]
            val alight = state.parentAlight[cell]
            if (board < 0 || alight < 0) return null
            legs += ScannedLeg(board, alight)
            node = timetable.departureNode[board]
            current -= 1
        }
        if (!query.origins[node]) return null
        legs.reverse()
        return ScannedJourney(
            legs = legs,
            departureMinute = timetable.departureMinute[legs.first().boardConnection],
            arrivalMinute = timetable.arrivalMinute[legs.last().alightConnection]
        )
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

    private fun select(found: List<ScannedJourney>): List<ScannedJourney> {
        val byTrains = LinkedHashMap<List<Int>, ScannedJourney>()
        for (journey in found) {
            byTrains.putIfAbsent(journey.legs.map { timetable.trip[it.boardConnection] }, journey)
        }
        val byCriteria = LinkedHashMap<Triple<Int, Int, Int>, ScannedJourney>()
        for (journey in byTrains.values) {
            byCriteria.putIfAbsent(Triple(journey.departureMinute, journey.arrivalMinute, journey.transfers), journey)
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

    private class ScanState(val rounds: Int, nodes: Int, trips: Int) {
        val arrival = IntArray((rounds + 1) * nodes)
        val parentBoard = IntArray((rounds + 1) * nodes)
        val parentAlight = IntArray((rounds + 1) * nodes)
        val boardedAt = IntArray(rounds * trips)
        val bestAtDestination = IntArray(rounds + 1)

        fun reset() {
            arrival.fill(UNREACHED)
            parentBoard.fill(-1)
            parentAlight.fill(-1)
            boardedAt.fill(-1)
            bestAtDestination.fill(UNREACHED)
        }
    }

    private companion object {
        const val UNREACHED = Int.MAX_VALUE
    }
}

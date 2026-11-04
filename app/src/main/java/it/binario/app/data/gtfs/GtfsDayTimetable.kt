package it.binario.app.data.gtfs

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SourceId
import java.time.LocalDate

/** One trip of a [GtfsDayTimetable]; its connections are `firstConnection until firstConnection + connectionCount`. */
internal class GtfsDayTrip(
    val tripId: String,
    /** The number the route search shows for the same trip (trip_short_name, numeric headsign, line, trip_id). */
    val publicNumber: String,
    /** route_short_name ("RE11", "REG", "EC"), display only. */
    val lineLabel: String?,
    val operatorName: String?,
    val category: TrainCategory,
    val operator: RailOperator,
    val mode: LegMode,
    val firstConnection: Int,
    val connectionCount: Int,
    /** GTFS block_id: trips of one block and service day run with the same vehicle one after the other; null if none. */
    val blockId: String? = null,
    /** GTFS wheelchair_accessible: [WHEELCHAIR_YES], [WHEELCHAIR_NO] or [WHEELCHAIR_UNKNOWN] (0 or empty). */
    val wheelchairAccessible: Byte = WHEELCHAIR_UNKNOWN,
    /** GTFS bikes_allowed: [BIKES_YES], [BIKES_NO] or [BIKES_UNKNOWN] (0, empty or no column). */
    val bikesAllowed: Byte = BIKES_UNKNOWN
)

/** GTFS wheelchair_boarding / wheelchair_accessible values; anything but 1 is never treated as accessible. */
internal const val WHEELCHAIR_UNKNOWN: Byte = 0
internal const val WHEELCHAIR_YES: Byte = 1
internal const val WHEELCHAIR_NO: Byte = 2

/** "1" and "2" as published; 0, empty and anything else are unknown. */
internal fun wheelchairValue(raw: String?): Byte = when (raw?.trim()) {
    "1" -> WHEELCHAIR_YES
    "2" -> WHEELCHAIR_NO
    else -> WHEELCHAIR_UNKNOWN
}

/** GTFS bikes_allowed values: 1 allowed, 2 not allowed, anything else (0, empty, no column) unknown. */
internal const val BIKES_UNKNOWN: Byte = 0
internal const val BIKES_YES: Byte = 1
internal const val BIKES_NO: Byte = 2

/** "1" and "2" as published; 0, empty, a missing column and anything else are unknown. Never inferred from a category. */
internal fun bikesValue(raw: String?): Byte = when (raw?.trim()) {
    "1" -> BIKES_YES
    "2" -> BIKES_NO
    else -> BIKES_UNKNOWN
}

/**
 * bikes_allowed of one vehicle leg run as several trips one after the other ("Resta a bordo"): allowed only when every
 * trip allows bikes, not allowed as soon as one does not, unknown otherwise.
 */
internal fun bikesAcrossTrips(first: Byte, next: Byte): Byte = when {
    first == BIKES_NO || next == BIKES_NO -> BIKES_NO
    first == BIKES_YES && next == BIKES_YES -> BIKES_YES
    else -> BIKES_UNKNOWN
}

/** bikes_allowed of two packs' copies of the same run: a published value wins over an unknown one, two different ones are unknown. */
internal fun bikesOfCopies(first: Byte, second: Byte): Byte = when {
    first == BIKES_UNKNOWN -> second
    second == BIKES_UNKNOWN || second == first -> first
    else -> BIKES_UNKNOWN
}

/** True for [BIKES_YES], false for [BIKES_NO], null when unknown: what the app shows. */
internal fun bikesOnBoard(value: Byte): Boolean? = when (value) {
    BIKES_YES -> true
    BIKES_NO -> false
    else -> null
}

/**
 * The connections of one embedded pack on one GTFS service day, in trip order: connection `c` leaves
 * [departureStop] at [departureMinute] and reaches [arrivalStop] at [arrivalMinute] on trip [connectionTrip].
 * Minutes count from the service day's midnight, so a run after midnight keeps 24:00+ values. Stops are a
 * day-local table of only the stops that some connection uses. Immutable once built, so it can be shared by
 * concurrent searches.
 */
internal class GtfsDayTimetable private constructor(
    val sourceId: SourceId,
    val serviceDate: LocalDate,
    val stopIds: Array<String>,
    val stopNames: Array<String>,
    val trips: Array<GtfsDayTrip>,
    val connectionTrip: IntArray,
    val departureStop: IntArray,
    val arrivalStop: IntArray,
    val departureMinute: IntArray,
    val arrivalMinute: IntArray,
    private val boarding: ByteArray
) {
    val connectionCount: Int get() = connectionTrip.size

    fun pickupAllowed(connection: Int): Boolean = boarding[connection].toInt() and NO_PICKUP == 0
    fun dropOffAllowed(connection: Int): Boolean = boarding[connection].toInt() and NO_DROP_OFF == 0

    /** The latest departure minute of the day (-1 without connections), computed once. */
    val lastDepartureMinute: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { departureMinute.maxOrNull() ?: -1 }

    /**
     * The trips of every GTFS block with two trips or more, each in order of first departure: the runs one vehicle makes
     * one after the other ("Resta a bordo"). Computed once per day and shared by the searches that use it.
     */
    val blockSequences: List<IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val byBlock = HashMap<String, MutableList<Int>>()
        trips.forEachIndexed { index, trip -> trip.blockId?.let { byBlock.getOrPut(it) { ArrayList(2) } += index } }
        byBlock.values.filter { it.size > 1 }
            .map { block -> block.sortedBy { departureMinute[trips[it].firstConnection] }.toIntArray() }
    }

    /** Rough retained size, used to bound the planner's cache. */
    fun approximateBytes(): Long = connectionCount * (5L * Int.SIZE_BYTES + 1) + trips.size * 96L + stopIds.size * 96L

    internal class Builder(
        private val sourceId: SourceId,
        private val serviceDate: LocalDate,
        indexStopCount: Int,
        private val stopId: (Int) -> String,
        private val stopName: (Int) -> String
    ) {
        private val dayStopOf = IntArray(indexStopCount) { -1 }
        private val stopIds = ArrayList<String>()
        private val stopNames = ArrayList<String>()
        private val trips = ArrayList<GtfsDayTrip>()
        private var connectionTrip = IntArray(1024)
        private var departureStop = IntArray(1024)
        private var arrivalStop = IntArray(1024)
        private var departureMinute = IntArray(1024)
        private var arrivalMinute = IntArray(1024)
        private var boarding = ByteArray(1024)
        private var count = 0
        private var pending: PendingTrip? = null

        fun beginTrip(
            tripId: String,
            publicNumber: String,
            lineLabel: String?,
            operatorName: String?,
            category: TrainCategory,
            operator: RailOperator,
            mode: LegMode,
            blockId: String? = null,
            wheelchairAccessible: Byte = WHEELCHAIR_UNKNOWN,
            bikesAllowed: Byte = BIKES_UNKNOWN
        ) {
            check(pending == null) { "Trip precedente non chiuso" }
            pending = PendingTrip(tripId, publicNumber, lineLabel, operatorName, category, operator, mode, count, blockId, wheelchairAccessible, bikesAllowed)
        }

        fun connection(
            departureStop: Int,
            arrivalStop: Int,
            departureMinute: Int,
            arrivalMinute: Int,
            pickupAllowed: Boolean,
            dropOffAllowed: Boolean
        ) {
            val trip = checkNotNull(pending) { "Connessione fuori da un trip" }
            ensureCapacity(count + 1)
            connectionTrip[count] = trips.size
            this.departureStop[count] = dayStop(departureStop)
            this.arrivalStop[count] = dayStop(arrivalStop)
            this.departureMinute[count] = departureMinute
            this.arrivalMinute[count] = arrivalMinute
            boarding[count] = ((if (pickupAllowed) 0 else NO_PICKUP) or (if (dropOffAllowed) 0 else NO_DROP_OFF)).toByte()
            count += 1
            trip.connections += 1
        }

        /** Closes the trip; a trip without any connection leaves no trace. */
        fun endTrip() {
            val trip = checkNotNull(pending) { "Nessun trip aperto" }
            pending = null
            if (trip.connections == 0) return
            trips += GtfsDayTrip(
                tripId = trip.tripId,
                publicNumber = trip.publicNumber,
                lineLabel = trip.lineLabel,
                operatorName = trip.operatorName,
                category = trip.category,
                operator = trip.operator,
                mode = trip.mode,
                firstConnection = trip.firstConnection,
                connectionCount = trip.connections,
                blockId = trip.blockId,
                wheelchairAccessible = trip.wheelchairAccessible,
                bikesAllowed = trip.bikesAllowed
            )
        }

        fun build(): GtfsDayTimetable {
            check(pending == null) { "Trip non chiuso" }
            return GtfsDayTimetable(
                sourceId = sourceId,
                serviceDate = serviceDate,
                stopIds = stopIds.toTypedArray(),
                stopNames = stopNames.toTypedArray(),
                trips = trips.toTypedArray(),
                connectionTrip = connectionTrip.copyOf(count),
                departureStop = departureStop.copyOf(count),
                arrivalStop = arrivalStop.copyOf(count),
                departureMinute = departureMinute.copyOf(count),
                arrivalMinute = arrivalMinute.copyOf(count),
                boarding = boarding.copyOf(count)
            )
        }

        private fun dayStop(indexStop: Int): Int {
            val existing = dayStopOf[indexStop]
            if (existing >= 0) return existing
            val created = stopIds.size
            stopIds += stopId(indexStop)
            stopNames += stopName(indexStop)
            dayStopOf[indexStop] = created
            return created
        }

        private fun ensureCapacity(required: Int) {
            if (required <= connectionTrip.size) return
            val capacity = (connectionTrip.size * 2).coerceAtLeast(required)
            connectionTrip = connectionTrip.copyOf(capacity)
            departureStop = departureStop.copyOf(capacity)
            arrivalStop = arrivalStop.copyOf(capacity)
            departureMinute = departureMinute.copyOf(capacity)
            arrivalMinute = arrivalMinute.copyOf(capacity)
            boarding = boarding.copyOf(capacity)
        }

        private class PendingTrip(
            val tripId: String,
            val publicNumber: String,
            val lineLabel: String?,
            val operatorName: String?,
            val category: TrainCategory,
            val operator: RailOperator,
            val mode: LegMode,
            val firstConnection: Int,
            val blockId: String?,
            val wheelchairAccessible: Byte,
            val bikesAllowed: Byte,
            var connections: Int = 0
        )
    }

    private companion object {
        const val NO_PICKUP = 1
        const val NO_DROP_OFF = 2
    }
}

/**
 * stops.txt of one pack: stop_id, stop_name, parent_station (blank when the stop has no parent), stop_code (blank
 * when absent), stop_lat/stop_lon (NaN when absent or unreadable) and wheelchair_boarding ([WHEELCHAIR_UNKNOWN] when
 * absent; a platform without its own value takes its parent station's, as the GTFS reference says).
 */
internal class GtfsStopDirectory(
    val ids: Array<String>,
    val names: Array<String>,
    val parents: Array<String>,
    val codes: Array<String> = Array(ids.size) { "" },
    val latitudes: DoubleArray = DoubleArray(ids.size) { Double.NaN },
    val longitudes: DoubleArray = DoubleArray(ids.size) { Double.NaN },
    val wheelchair: ByteArray = ByteArray(ids.size)
) {
    init {
        require(ids.size == names.size && ids.size == parents.size)
        require(ids.size == codes.size && ids.size == latitudes.size && ids.size == longitudes.size && ids.size == wheelchair.size)
    }

    companion object {
        /**
         * The wheelchair_boarding of every stop: its own 1 or 2, else (0 or empty) its parent station's. Only one level
         * is followed (platform → station), like the GTFS reference.
         */
        fun inheritWheelchair(ids: Array<String>, parents: Array<String>, own: ByteArray): ByteArray {
            val indexById = HashMap<String, Int>(ids.size * 2)
            ids.forEachIndexed { index, id -> indexById.putIfAbsent(id, index) }
            return ByteArray(ids.size) { index ->
                val value = own[index]
                if (value != WHEELCHAIR_UNKNOWN || parents[index].isEmpty()) value
                else indexById[parents[index]]?.let { own[it] } ?: WHEELCHAIR_UNKNOWN
            }
        }
    }

    val size: Int get() = ids.size

    fun latitude(index: Int): Double? = latitudes[index].takeIf { it.isFinite() && it in -90.0..90.0 }
    fun longitude(index: Int): Double? = longitudes[index].takeIf { it.isFinite() && it in -180.0..180.0 }
}

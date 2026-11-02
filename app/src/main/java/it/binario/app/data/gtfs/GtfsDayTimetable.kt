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
    val connectionCount: Int
)

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
            mode: LegMode
        ) {
            check(pending == null) { "Trip precedente non chiuso" }
            pending = PendingTrip(tripId, publicNumber, lineLabel, operatorName, category, operator, mode, count)
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
                connectionCount = trip.connections
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
 * when absent) and stop_lat/stop_lon (NaN when absent or unreadable).
 */
internal class GtfsStopDirectory(
    val ids: Array<String>,
    val names: Array<String>,
    val parents: Array<String>,
    val codes: Array<String> = Array(ids.size) { "" },
    val latitudes: DoubleArray = DoubleArray(ids.size) { Double.NaN },
    val longitudes: DoubleArray = DoubleArray(ids.size) { Double.NaN }
) {
    init {
        require(ids.size == names.size && ids.size == parents.size)
        require(ids.size == codes.size && ids.size == latitudes.size && ids.size == longitudes.size)
    }

    val size: Int get() = ids.size

    fun latitude(index: Int): Double? = latitudes[index].takeIf { it.isFinite() && it in -90.0..90.0 }
    fun longitude(index: Int): Double? = longitudes[index].takeIf { it.isFinite() && it in -180.0..180.0 }
}

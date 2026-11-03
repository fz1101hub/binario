package it.binario.app

import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.repository.withoutExpiredObservations
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How a departure at the arrival stop relates to the open train's arrival there. */
enum class ConnectionRisk { MISSED, AT_RISK, OK, CANCELLED }

/**
 * The open train's arrival at the stop whose departures are searched. [expected] equals
 * [scheduled] unless the stop carries a verified operational time ([isLive]): a scheduled-only
 * arrival is never presented as a live estimate.
 */
data class ConnectionArrival(
    val station: String,
    val scheduled: Instant,
    val expected: Instant,
    val isLive: Boolean
) {
    /** Requesting the board from here keeps a planned connection visible after a delay made it impossible. */
    val boardFrom: Instant get() = minOf(scheduled, expected)
}

data class ArrivalConnection(
    val entry: StationBoardEntry,
    /** The live prediction when the board publishes one, otherwise the scheduled departure. */
    val departure: Instant,
    val isLive: Boolean,
    /** Connection departure minus the train's (expected, if known) arrival. */
    val buffer: Duration,
    val risk: ConnectionRisk
)

/**
 * Pure transfer-buffer rules for the "connections at arrival" section of the train detail. The
 * window is [expected arrival, expected arrival + 90 min]; a connection planned after the scheduled
 * arrival that now leaves before the expected one stays in the list as missed.
 */
object ConnectionRiskClassifier {
    val WINDOW: Duration = Duration.ofMinutes(90)
    val AT_RISK_BELOW: Duration = Duration.ofMinutes(5)
    const val MAX_MISSED_SHOWN = 3
    const val MAX_REACHABLE_SHOWN = 12

    private val clockTime = DateTimeFormatter.ofPattern("H:mm")
    private val halfDay = Duration.ofHours(12)

    fun classify(buffer: Duration): ConnectionRisk = when {
        buffer.isNegative -> ConnectionRisk.MISSED
        buffer < AT_RISK_BELOW -> ConnectionRisk.AT_RISK
        else -> ConnectionRisk.OK
    }

    /**
     * The stop the user chose (for example with the stop alarm) while the train has not passed
     * it yet, otherwise the last stop of the train.
     */
    fun connectionStop(stops: List<TrainStop>, preferredStation: String?): TrainStop? =
        preferredStation?.trim()?.takeIf(String::isNotEmpty)?.let { preferred ->
            stops.drop(1).firstOrNull { !it.completed && it.station.equals(preferred, ignoreCase = true) }
        } ?: stops.lastOrNull()?.takeIf { stops.size > 1 }

    /**
     * Stop times are clock times: the date comes from the train's scheduled departure (or
     * [fallbackDate] when the source gives none) and a time earlier than the departure is after
     * midnight. The expected time is the occurrence of its clock time nearest to the scheduled one.
     */
    fun arrival(
        service: TrainService,
        stop: TrainStop,
        fallbackDate: LocalDate,
        zone: ZoneId = RailTime.defaultZone
    ): ConnectionArrival? {
        val scheduledTime = parse(stop.scheduled) ?: return null
        val departure = service.scheduledDepartureDateTime
            ?: LocalDateTime.of(fallbackDate, parse(service.scheduledDeparture) ?: scheduledTime)
        val scheduledDate = if (scheduledTime < departure.toLocalTime()) departure.toLocalDate().plusDays(1) else departure.toLocalDate()
        val scheduled = LocalDateTime.of(scheduledDate, scheduledTime).atZone(zone).toInstant()
        val verified = stop.operationalStatusVerified ?: service.operationalStatusVerified
        val expected = if (verified) parse(stop.expected)?.let { nearest(scheduled, it, zone) } else null
        return ConnectionArrival(stop.station, scheduled, expected ?: scheduled, isLive = expected != null)
    }

    /** Departures of the board inside the window, without this same train, earliest first. */
    fun connections(
        arrival: ConnectionArrival,
        entries: List<StationBoardEntry>,
        trainNumber: String,
        now: Instant
    ): List<ArrivalConnection> {
        val latest = arrival.expected.plus(WINDOW)
        return entries.asSequence()
            .map { it.withoutExpiredObservations(now) }
            .filterNot { isSameTrain(it.publicNumber, trainNumber) }
            .mapNotNull { entry ->
                val scheduled = entry.scheduledTime.toInstant()
                val expected = entry.expectedTime?.value?.toInstant()
                val departure = expected ?: scheduled
                val inWindow = departure <= latest && (departure >= arrival.expected || scheduled >= arrival.scheduled)
                if (!inWindow) return@mapNotNull null
                val buffer = Duration.between(arrival.expected, departure)
                val risk = if (entry.status?.value == RunStatus.CANCELLED) ConnectionRisk.CANCELLED else classify(buffer)
                ArrivalConnection(entry, departure, isLive = expected != null, buffer = buffer, risk = risk)
            }
            .sortedWith(compareBy<ArrivalConnection> { it.departure }.thenBy { it.entry.publicNumber.orEmpty() })
            .toList()
    }

    /** At most the last few missed connections, then the reachable ones, so a long delay cannot hide them. */
    fun forDisplay(connections: List<ArrivalConnection>): List<ArrivalConnection> {
        val missed = connections.filter { it.risk == ConnectionRisk.MISSED }.takeLast(MAX_MISSED_SHOWN)
        val others = connections.filter { it.risk != ConnectionRisk.MISSED }.take(MAX_REACHABLE_SHOWN)
        return (missed + others).sortedWith(compareBy<ArrivalConnection> { it.departure }.thenBy { it.entry.publicNumber.orEmpty() })
    }

    /** "REG 2345" and "2345" are the same public train; numbers without digits compare as written. */
    fun isSameTrain(boardNumber: String?, trainNumber: String): Boolean {
        val board = boardNumber?.trim().orEmpty()
        val train = trainNumber.trim()
        if (board.isEmpty() || train.isEmpty()) return false
        val boardDigits = digits(board)
        val trainDigits = digits(train)
        return if (boardDigits.isNotEmpty() && trainDigits.isNotEmpty()) boardDigits == trainDigits
        else board.equals(train, ignoreCase = true)
    }

    private fun digits(value: String): String = Regex("\\d+").findAll(value).lastOrNull()?.value?.trimStart('0').orEmpty()

    private fun nearest(reference: Instant, time: LocalTime, zone: ZoneId): Instant {
        val referenceDate = reference.atZone(zone).toLocalDate()
        val candidate = LocalDateTime.of(referenceDate, time).atZone(zone).toInstant()
        return when {
            Duration.between(reference, candidate) > halfDay -> LocalDateTime.of(referenceDate.minusDays(1), time).atZone(zone).toInstant()
            Duration.between(candidate, reference) > halfDay -> LocalDateTime.of(referenceDate.plusDays(1), time).atZone(zone).toInstant()
            else -> candidate
        }
    }

    private fun parse(value: String): LocalTime? = runCatching { LocalTime.parse(value.trim(), clockTime) }.getOrNull()
}

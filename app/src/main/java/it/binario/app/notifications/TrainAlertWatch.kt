package it.binario.app.notifications

import it.binario.app.CommuterAlertPlanner
import it.binario.app.data.RailOperator
import it.binario.app.data.RailTime
import it.binario.app.data.notices.TrainNoticeContext
import it.binario.app.data.repository.TrainNumberQuery
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * A train whose new notices the user asked to be told about (the bell in the train detail), kept with what the
 * notice sources need: the numeric train number, the ViaggiaTreno origin code when known and the operator.
 */
data class TrainAlertSubscription(
    val number: String,
    val publicNumber: String,
    val originCode: String?,
    val operator: RailOperator,
    /** Scheduled departure "HH:mm" of the train, when known: the check window is built around it. */
    val departure: String,
    val label: String,
    val subscribedAt: Instant
) {
    val key: String get() = TrainAlertWatch.key(number)

    fun noticeContext(serviceDate: LocalDate): TrainNoticeContext = TrainNoticeContext(
        publicNumber = publicNumber,
        serviceDate = serviceDate,
        originCode = originCode,
        operator = operator,
        stationNames = emptyList()
    )
}

/** A notice as the dedupe sees it: its stable source id, its text and its publication time when published. */
data class WatchedNotice(val id: String, val text: String, val publishedAt: Instant?)

/** A followed-trip train with its scheduled times, which then decide the window instead of the favourite's. */
data class FollowedTrainTimes(val number: String, val departure: Instant, val arrival: Instant)

/**
 * Rules of the notice notifications for followed and favourite trains: checks every [INTERVAL] in each train's
 * window of its day of travel, never in the quiet hours of the commuter alerts (22:00 to 07:00), and only notices
 * the device has not seen yet are notified.
 */
object TrainAlertWatch {
    val INTERVAL: Duration = Duration.ofMinutes(15)
    val BEFORE_DEPARTURE: Duration = Duration.ofHours(4)
    val AFTER_DEPARTURE: Duration = Duration.ofHours(3)
    val AFTER_ARRIVAL: Duration = Duration.ofMinutes(30)
    val QUIET_END: LocalTime = LocalTime.of(7, 0)
    val QUIET_START: LocalTime = LocalTime.of(22, 0)
    const val MAX_SUBSCRIPTIONS = 16
    const val MAX_SEEN = 400
    const val SEEN_RETENTION_DAYS = 7L

    /** "REG 2153", "2153" and "02153" are the same public train: the numeric part when there is one. */
    fun key(number: String): String = TrainNumberQuery(number, LocalDate.MIN).let { it.numeric ?: it.normalized }

    /** A subscription for a train of the detail; null when no notice source matches its number. */
    fun subscriptionOf(context: TrainNoticeContext?, number: String, departure: String, label: String, now: Instant): TrainAlertSubscription? {
        if (context == null || !context.matchesTrenitaliaNumbers) return null
        return TrainAlertSubscription(number.trim(), context.publicNumber, context.originCode, context.operator, departure.trim(), label, now)
    }

    /**
     * The window of [subscription] on [date]: around the followed trip's times when the train is in it, else
     * around the favourite's departure, else the whole day; always inside 07:00 – 22:00.
     */
    fun window(subscription: TrainAlertSubscription, date: LocalDate, followed: List<FollowedTrainTimes>, zone: ZoneId = RailTime.defaultZone): ClosedRange<Instant>? {
        val dayStart = date.atTime(QUIET_END).atZone(zone).toInstant()
        val dayEnd = date.atTime(QUIET_START).atZone(zone).toInstant()
        val trip = followed.firstOrNull { key(it.number) == subscription.key && it.departure.atZone(zone).toLocalDate() == date }
        val (start, end) = when {
            trip != null -> trip.departure.minus(BEFORE_DEPARTURE) to trip.arrival.plus(AFTER_ARRIVAL)
            else -> runCatching { LocalTime.parse(subscription.departure) }.getOrNull()
                ?.let { time -> date.atTime(time).atZone(zone).toInstant() }
                ?.let { departure -> departure.minus(BEFORE_DEPARTURE) to departure.plus(AFTER_DEPARTURE) }
                ?: (dayStart to dayEnd)
        }
        val clippedStart = maxOf(start, dayStart)
        val clippedEnd = minOf(end, dayEnd)
        return if (clippedStart < clippedEnd) clippedStart..clippedEnd else null
    }

    fun isQuiet(now: Instant, zone: ZoneId = RailTime.defaultZone): Boolean = CommuterAlertPlanner.isQuiet(now.atZone(zone).toLocalTime())

    /** The subscriptions to check at [now]: those whose window of today is open. */
    fun due(subscriptions: List<TrainAlertSubscription>, followed: List<FollowedTrainTimes>, now: Instant, zone: ZoneId = RailTime.defaultZone): List<TrainAlertSubscription> {
        if (isQuiet(now, zone)) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        return subscriptions.filter { subscription -> window(subscription, today, followed, zone)?.contains(now) == true }
    }

    /** The next check: in [INTERVAL] while a window is open, else the next window opening today or tomorrow. */
    fun nextRun(subscriptions: List<TrainAlertSubscription>, followed: List<FollowedTrainTimes>, now: Instant, zone: ZoneId = RailTime.defaultZone): Instant? {
        if (subscriptions.isEmpty()) return null
        if (due(subscriptions, followed, now, zone).isNotEmpty()) return now.plus(INTERVAL)
        val today = now.atZone(zone).toLocalDate()
        return (0L..1L).asSequence().flatMap { offset ->
            subscriptions.asSequence().mapNotNull { window(it, today.plusDays(offset), followed, zone)?.start }
        }.filter { it.isAfter(now) }.minOrNull()
    }

    /** Notices not seen before; one published before the subscription was already in the detail the user opened. */
    fun newNotices(notices: List<WatchedNotice>, seen: Map<String, Long>, subscribedAt: Instant): List<WatchedNotice> = notices
        .distinctBy { it.id }
        .filter { it.id !in seen }
        .filter { notice -> notice.publishedAt?.isBefore(subscribedAt) != true }

    /** [seen] with [notices] added (first-seen day kept), older than [SEEN_RETENTION_DAYS] dropped, newest kept. */
    fun remember(seen: Map<String, Long>, notices: List<WatchedNotice>, today: LocalDate): Map<String, Long> {
        val day = today.toEpochDay()
        val merged = LinkedHashMap(seen)
        notices.forEach { merged.putIfAbsent(it.id, day) }
        return merged.entries.filter { day - it.value < SEEN_RETENTION_DAYS }
            .sortedByDescending { it.value }
            .take(MAX_SEEN)
            .associate { it.key to it.value }
    }

    /** Only the trains still favourite or in the followed trip are watched. */
    fun active(subscriptions: List<TrainAlertSubscription>, favouriteNumbers: Collection<String>, followedNumbers: Collection<String>): List<TrainAlertSubscription> {
        val keys = (favouriteNumbers + followedNumbers).mapTo(hashSetOf(), ::key)
        return subscriptions.filter { it.key in keys }
    }

    fun nextRunAt(subscriptions: List<TrainAlertSubscription>, followed: List<FollowedTrainTimes>, now: ZonedDateTime): ZonedDateTime? =
        nextRun(subscriptions, followed, now.toInstant(), now.zone)?.atZone(now.zone)
}

/** JSON of the subscriptions and of the seen notice ids in local preferences; damaged entries are skipped. */
object TrainAlertCodec {
    fun encodeSubscriptions(subscriptions: List<TrainAlertSubscription>): String = JSONArray().apply {
        subscriptions.forEach { subscription ->
            put(JSONObject().apply {
                put("number", subscription.number)
                put("publicNumber", subscription.publicNumber)
                subscription.originCode?.let { put("origin", it) }
                put("operator", subscription.operator.name)
                put("departure", subscription.departure)
                put("label", subscription.label)
                put("subscribedAt", subscription.subscribedAt.toEpochMilli())
            })
        }
    }.toString()

    fun decodeSubscriptions(raw: String?): List<TrainAlertSubscription> {
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until minOf(array.length(), TrainAlertWatch.MAX_SUBSCRIPTIONS)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                TrainAlertSubscription(
                    number = value.getString("number").also { require(it.isNotBlank()) },
                    publicNumber = value.getString("publicNumber").also { require(it.isNotBlank()) },
                    originCode = value.optString("origin").takeIf { it.isNotBlank() },
                    operator = RailOperator.valueOf(value.getString("operator")),
                    departure = value.optString("departure"),
                    label = value.optString("label"),
                    subscribedAt = Instant.ofEpochMilli(value.getLong("subscribedAt"))
                )
            }.getOrNull()
        }.distinctBy { it.key }
    }

    fun encodeSeen(seen: Map<String, Long>): String = JSONObject().apply { seen.forEach { (id, day) -> put(id, day) } }.toString()

    fun decodeSeen(raw: String?): Map<String, Long> {
        val value = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return value.keys().asSequence().take(TrainAlertWatch.MAX_SEEN * 2).associateWith { value.optLong(it) }
    }
}

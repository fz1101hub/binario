package it.binario.app.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import it.binario.app.CommuterAlertPlanner
import it.binario.app.R
import it.binario.app.data.PlatformChange
import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.journeys.LiveStopTimes
import it.binario.app.journeys.TransferRiskLevel
import java.time.Instant
import java.time.ZoneId

/** A change on the followed trip that deserves a notification with sound, not only new text in the silent one. */
sealed interface FollowedTripChange {
    /** The train of the trip it concerns. */
    val legIndex: Int
    /** Identifies the change for the dedupe: the same change is notified once per trip. */
    val key: String

    data class Cancelled(override val legIndex: Int, override val key: String) : FollowedTripChange
    data class PlatformChanged(
        override val legIndex: Int,
        val station: String,
        val platform: String,
        val previous: String,
        override val key: String
    ) : FollowedTripChange
    data class ConnectionAtRisk(override val legIndex: Int, val level: TransferRiskLevel, override val key: String) : FollowedTripChange
}

/**
 * Which changes of a refresh are notified: the current train is cancelled, its platform at the boarding stop moves
 * (published by the source next to the timetable platform, or different from the one the previous refresh read), or
 * the next change becomes at risk or missed. Pure, so the decisions are the same in tests and on the phone.
 */
object FollowedTripAlerts {
    /** The platform at the boarding stop of the current train while it has not left it, with the timetable one. */
    data class BoardingPlatform(val legIndex: Int, val station: String, val platform: String, val scheduled: String?)

    fun boardingPlatform(trip: FollowedTrip, view: FollowedTripView, services: List<TrainService?>): BoardingPlatform? {
        val service = services.getOrNull(view.legIndex)?.takeIf { it.operationalStatusVerified } ?: return null
        val leg = trip.legs[view.legIndex]
        val index = LiveStopTimes.stopNamed(service.stops, leg.boardStation)
        val stop = service.stops.getOrNull(index)
        if (stop?.completed == true) return null
        val platform = (stop?.platform ?: service.platform.takeIf { index <= 0 })?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val scheduled = (stop?.scheduledPlatform ?: service.scheduledPlatform.takeIf { index <= 0 })?.trim()?.takeIf(String::isNotEmpty)
        return BoardingPlatform(view.legIndex, stop?.station ?: leg.boardStation, platform, scheduled)
    }

    /**
     * The changes of this refresh not notified yet ([notified] keys). [previousBoarding] is the boarding platform the
     * previous refresh read for the same train, if any.
     */
    fun changes(
        trip: FollowedTrip,
        view: FollowedTripView,
        services: List<TrainService?>,
        previousBoarding: BoardingPlatform?,
        notified: Set<String>
    ): List<FollowedTripChange> = buildList {
        val leg = trip.legs[view.legIndex]
        // A cancelled train says it all: its platform and the change it was to reach no longer matter.
        if (view.cancelled) {
            add(FollowedTripChange.Cancelled(view.legIndex, "cancelled|${view.legIndex}|${TrainAlertWatch.key(leg.number)}"))
            return@buildList
        }
        boardingPlatform(trip, view, services)?.let { boarding ->
            val earlier = previousBoarding?.takeIf { it.legIndex == boarding.legIndex }?.platform
            val change = PlatformChange.of(boarding.platform, boarding.scheduled) ?: earlier?.let { PlatformChange.of(boarding.platform, it) }
            if (change != null) {
                add(
                    FollowedTripChange.PlatformChanged(
                        view.legIndex, boarding.station, change.actual, change.scheduled,
                        "platform|${view.legIndex}|${PlatformChange.normalize(change.actual)}"
                    )
                )
            }
        }
        view.transfer?.level?.takeIf { it == TransferRiskLevel.AT_RISK || it == TransferRiskLevel.MISSED }?.let { level ->
            add(FollowedTripChange.ConnectionAtRisk(view.legIndex, level, "transfer|${view.legIndex}|${level.name}"))
        }
    }.filterNot { it.key in notified }

    /** Quiet hours of the commuter alerts (22:00 to 07:00): the notification is still shown, without sound. */
    fun isQuiet(now: Instant, zone: ZoneId = RailTime.defaultZone): Boolean = CommuterAlertPlanner.isQuiet(now.atZone(zone).toLocalTime())
}

/** Posts the alerting notification of [FollowedTripAlerts] on its own high-importance channel. */
internal object FollowedTripAlertNotifier {
    fun notify(context: Context, store: FollowedTripStore, trip: FollowedTrip, view: FollowedTripView, services: List<TrainService?>, now: Instant) {
        val boarding = FollowedTripAlerts.boardingPlatform(trip, view, services)
        val changes = FollowedTripAlerts.changes(trip, view, services, store.lastBoarding(), store.alertedKeys())
        store.recordBoarding(boarding)
        if (changes.isEmpty() || !LiveNotifications.allowed(context)) return
        val lines = changes.map { text(context, trip, view, it) }
        val notification = NotificationCompat.Builder(context, LiveChannel.FOLLOWED_TRIP_ALERTS.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(lines.first().first)
            .setContentText(lines.first().second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n") { (title, text) -> "$title · $text" }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(FollowedTripAlerts.isQuiet(now))
            .setAutoCancel(true)
            .setContentIntent(LiveNotifications.openApp(context, LiveNotifications.FOLLOWED_TRIP_ALERT_ID))
            .build()
        if (LiveNotifications.post(context, LiveNotifications.FOLLOWED_TRIP_ALERT_ID, notification)) store.recordAlerted(changes.map { it.key })
    }

    private fun text(context: Context, trip: FollowedTrip, view: FollowedTripView, change: FollowedTripChange): Pair<String, String> {
        val leg = trip.legs[change.legIndex]
        val label = leg.label.ifBlank { leg.number }
        return when (change) {
            is FollowedTripChange.Cancelled -> context.getString(R.string.followed_trip_title_cancelled, label) to trip.title
            is FollowedTripChange.PlatformChanged ->
                context.getString(R.string.platform_changed_label, change.platform, change.previous) to "$label · ${change.station}"
            is FollowedTripChange.ConnectionAtRisk -> context.getString(
                if (change.level == TransferRiskLevel.MISSED) R.string.journey_transfer_missed else R.string.journey_transfer_at_risk
            ) to context.getString(R.string.followed_trip_transfer, view.transferStation ?: leg.alightStation, view.nextLegLabel.orEmpty())
        }
    }
}

package it.binario.app.data.live

import it.binario.app.data.urban.HttpsBytesFetcher
import it.binario.app.shared.SharedData
import java.time.Duration

/**
 * The open GTFS-Realtime Trip Updates feeds and the Swiss boards as the shared table gives them
 * (contracts/shared-data/v1/realtime-feeds-v1.json, `SharedData.RealtimeFeeds`): the same addresses, limits, caches and
 * pause labels the iPhone reads.
 */
internal object RealtimeFeedTable {
    val STA: SharedData.RealtimeFeeds.TripUpdatesFeed = tripUpdates("alto-adige-sta-realtime")
    val SNCF: SharedData.RealtimeFeeds.TripUpdatesFeed = tripUpdates("sncf-gtfs-rt")
    val TRENITALIA_FRANCE: SharedData.RealtimeFeeds.TripUpdatesFeed = tripUpdates("trenitalia-france-gtfs-rt")
    val SWISS: SharedData.RealtimeFeeds.SwissBoard = SharedData.RealtimeFeeds.SWISS_BOARD

    /** How long a Trip Updates observation stays usable, from the feed's own observation time. */
    val OBSERVATION_WINDOW: Duration = Duration.ofSeconds(SharedData.RealtimeFeeds.OBSERVATION_WINDOW_SECONDS.toLong())

    /** An observation this far ahead of the clock is still accepted. */
    val FUTURE_TOLERANCE: Duration = Duration.ofSeconds(SharedData.RealtimeFeeds.FUTURE_TOLERANCE_SECONDS.toLong())

    private fun tripUpdates(id: String): SharedData.RealtimeFeeds.TripUpdatesFeed =
        SharedData.RealtimeFeeds.TRIP_UPDATES.single { it.id == id }

    /** The timetable pack the Trip Updates feed [feedId] joins; null for a feed the table does not list. */
    fun packOf(feedId: String): String? = SharedData.RealtimeFeeds.TRIP_UPDATES.firstOrNull { it.id == feedId }?.pack

    /** The label a source of the table is shown with ("SNCF", "STA Alto Adige"). */
    fun labelOf(feedId: String): String? = SharedData.RealtimeFeeds.TRIP_UPDATES.firstOrNull { it.id == feedId }?.label

    fun SharedData.RealtimeFeeds.TripUpdatesFeed.timeToLive(): Duration = Duration.ofSeconds(timeToLiveSeconds.toLong())

    /** The binary feed's fetcher: HTTPS on the same host, the table's byte cap, time limits and requests. */
    fun SharedData.RealtimeFeeds.TripUpdatesFeed.bytesFetcher(): HttpsBytesFetcher = HttpsBytesFetcher(
        maxBytes = maximumBytes,
        connectTimeoutMillis = connectTimeoutSeconds * 1_000,
        readTimeoutMillis = readTimeoutSeconds * 1_000,
        totalTimeoutMillis = totalTimeoutSeconds * 1_000L,
        accept = accept,
        maxRequests = maximumRequests
    )
}

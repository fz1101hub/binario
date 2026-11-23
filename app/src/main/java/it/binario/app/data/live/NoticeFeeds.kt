package it.binario.app.data.live

import it.binario.app.shared.SharedData
import java.time.Duration

/**
 * A rail notice feed of the shared table contracts/shared-data/v1/notices-sources-v1.json ([SharedData.NoticesSources],
 * the iPhone's too): its address, limits, rate and cache, by source id.
 */
internal fun noticeFeed(id: String): SharedData.NoticesSources.Source = SharedData.NoticesSources.SOURCES.single { it.id == id }

/** One request per this interval (the feed's [RatePolicy]). */
internal val SharedData.NoticesSources.Source.minimumInterval: Duration get() = Duration.ofSeconds(minimumIntervalSeconds.toLong())

internal val SharedData.NoticesSources.Source.timeToLive: Duration get() = Duration.ofSeconds(timeToLiveSeconds.toLong())

/** How long past its time to live the last answer is still shown when a refresh fails. */
internal val SharedData.NoticesSources.Source.maximumStale: Duration get() = Duration.ofSeconds(maximumStaleSeconds.toLong())

/** The delay before the cache retries a failed refresh; the feeds read through [ResilientSourceCache] all have one. */
internal val SharedData.NoticesSources.Source.retryDelay: Long get() = requireNotNull(retryDelayMillis) { "$id: no retry delay" }.toLong()

/** The [PublicSourceRequestPolicy] of a feed read through [HttpPublicSourceFetcher] (same-resource redirects). */
internal fun SharedData.NoticesSources.Source.requestPolicy(): PublicSourceRequestPolicy = PublicSourceRequestPolicy(
    accept = accept,
    connectTimeoutMillis = connectTimeoutSeconds * 1_000,
    readTimeoutMillis = readTimeoutSeconds * 1_000,
    totalTimeoutMillis = totalTimeoutSeconds * 1_000L,
    maxBytes = maximumBytes,
    maxRequests = maximumRequests,
    allowViaggiaTrenoCleartextRedirect = viaggiaTrenoCleartextHop
)

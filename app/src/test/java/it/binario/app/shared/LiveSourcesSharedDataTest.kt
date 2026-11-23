package it.binario.app.shared

import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.RfiStationCatalog
import it.binario.app.data.live.ViaggiaTrenoApi
import it.binario.app.data.live.ViaggiaTrenoRealtimeAdapter
import it.binario.app.data.live.ViaggiaTrenoRealtimeFetcher
import it.binario.app.data.live.ViaggiaTrenoRequests
import it.binario.app.data.live.ViaggiaTrenoTrainNoticesAdapter
import it.binario.app.data.live.noticeFeed
import java.io.File
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-data/v1/viaggiatreno-v1.json, rfi-monitor-v1.json and the per-train notice feed of
 * notices-sources-v1.json: read from their JSON, as the iPhone's SharedDataTests read them, against the generated
 * [SharedData] and the adapters that use them.
 */
class LiveSourcesSharedDataTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun table(name: String) = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/$name").readText(Charsets.UTF_8))
    private fun JSONArray.ints() = (0 until length()).map(::getInt).toSet()

    @Test fun `the ViaggiaTreno realtime API is the shared table`() {
        val json = table("viaggiatreno-v1.json")
        with(SharedData.ViaggiaTreno) {
            assertEquals(json.getString("baseUrl"), BASE_URL)
            assertEquals(
                listOf("departuresResource", "arrivalsResource", "runResource", "numberSearchResource").map(json::getString),
                ViaggiaTrenoApi.RESOURCES
            )
            assertEquals(json.getInt("connectTimeoutSeconds"), CONNECT_TIMEOUT_SECONDS)
            assertEquals(json.getInt("readTimeoutSeconds"), READ_TIMEOUT_SECONDS)
            assertEquals(json.getInt("totalTimeoutSeconds"), TOTAL_TIMEOUT_SECONDS)
            assertEquals(json.getInt("maximumBytes"), MAXIMUM_BYTES)
            assertEquals(json.getInt("maximumRequests"), MAXIMUM_REQUESTS)
            assertEquals(json.getInt("detailPermits"), DETAIL_PERMITS)
            assertEquals(json.getInt("runDetailsTimeoutSeconds"), RUN_DETAILS_TIMEOUT_SECONDS)
            assertEquals(json.getInt("lookbackTimeoutSeconds"), LOOKBACK_TIMEOUT_SECONDS)
            assertEquals(json.getInt("maximumAutocompleteLines"), MAXIMUM_AUTOCOMPLETE_LINES)
            assertEquals(json.getInt("maximumRunReferences"), MAXIMUM_RUN_REFERENCES)
            assertEquals(json.getInt("maximumBoardRows"), MAXIMUM_BOARD_ROWS)
            assertEquals(json.getInt("numberSearchPastDays"), NUMBER_SEARCH_PAST_DAYS)
            assertEquals(json.getInt("extraordinaryStopType"), EXTRAORDINARY_STOP_TYPE)
            assertEquals(json.getInt("suppressedStopType"), SUPPRESSED_STOP_TYPE)
            assertEquals(json.getJSONArray("trenordClients").ints(), TRENORD_CLIENTS)
            assertEquals(json.getJSONArray("trenitaliaRegionalClients").ints(), TRENITALIA_REGIONAL_CLIENTS)
            assertEquals(json.getJSONArray("trenitaliaLongDistanceClients").ints(), TRENITALIA_LONG_DISTANCE_CLIENTS)
        }
        assertEquals(json.getInt("detailPermits"), ViaggiaTrenoRequests.DETAIL_PERMITS)
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { "[]" })
        assertEquals(Duration.ofSeconds(json.getLong("minimumIntervalSeconds")), adapter.descriptor.ratePolicy.minimumRequestInterval)
        assertEquals(Duration.ofSeconds(json.getLong("timeToLiveSeconds")), adapter.descriptor.ratePolicy.defaultTimeToLive)
    }

    @Test fun `the RFI monitor is the shared table`() {
        val json = table("rfi-monitor-v1.json")
        with(SharedData.RfiMonitor) {
            assertEquals(json.getString("catalogueUrl"), CATALOGUE_URL)
            assertEquals(json.getString("monitorUrl"), MONITOR_URL)
            assertEquals(json.getString("accept"), ACCEPT)
            assertEquals(json.getInt("connectTimeoutSeconds"), CONNECT_TIMEOUT_SECONDS)
            assertEquals(json.getInt("readTimeoutSeconds"), READ_TIMEOUT_SECONDS)
            assertEquals(json.getInt("totalTimeoutSeconds"), TOTAL_TIMEOUT_SECONDS)
            assertEquals(json.getInt("maximumBytes"), MAXIMUM_BYTES)
            assertEquals(json.getInt("catalogueRefreshSeconds"), CATALOGUE_REFRESH_SECONDS)
            assertEquals(json.getInt("catalogueRetrySeconds"), CATALOGUE_RETRY_SECONDS)
        }
        assertEquals(json.getString("catalogueUrl"), RfiStationCatalog.URL)
        val adapter = RfiStationBoardAdapter(fetcher = { "" })
        assertEquals(Duration.ofSeconds(json.getLong("minimumIntervalSeconds")), adapter.descriptor.ratePolicy.minimumRequestInterval)
        assertEquals(Duration.ofSeconds(json.getLong("timeToLiveSeconds")), adapter.descriptor.ratePolicy.defaultTimeToLive)
    }

    @Test fun `the per-train notices are the shared feed and reader`() {
        val json = table("notices-sources-v1.json")
        val source = (0 until json.getJSONArray("sources").length()).map(json.getJSONArray("sources")::getJSONObject)
            .single { it.getString("id") == "viaggiatreno-train-notices" }
        val feed = noticeFeed("viaggiatreno-train-notices")
        assertEquals(source.getString("url"), feed.url)
        assertEquals(feed.url, ViaggiaTrenoTrainNoticesAdapter.BASE_URL)
        assertEquals("viaggiatreno-train-notices", ViaggiaTrenoTrainNoticesAdapter.SOURCE_ID.value)
        assertTrue(source.getBoolean("viaggiaTrenoCleartextHop"))
        val adapter = ViaggiaTrenoTrainNoticesAdapter()
        assertEquals(Duration.ofSeconds(source.getLong("minimumIntervalSeconds")), adapter.descriptor.ratePolicy.minimumRequestInterval)
        assertEquals(Duration.ofSeconds(source.getLong("timeToLiveSeconds")), adapter.descriptor.ratePolicy.defaultTimeToLive)
        val rules = json.getJSONObject("trainNotices")
        with(SharedData.NoticesSources.TRAIN_NOTICES) {
            assertEquals(rules.getInt("maximumNotes"), maximumNotes)
            assertEquals(rules.getInt("textLength"), textLength)
            assertEquals(rules.getInt("maximumTrains"), maximumTrains)
            assertEquals(rules.getInt("maximumCachedTrains"), maximumCachedTrains)
        }
    }
}

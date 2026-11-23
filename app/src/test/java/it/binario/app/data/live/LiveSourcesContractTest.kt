package it.binario.app.data.live

import it.binario.app.data.RailTime
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/live-sources-v1.json, read as it is, the iPhone's LiveSourcesContractTests too: the operator
 * of a ViaggiaTreno row, a row of the arrivals board and the per-train notices of a day, through the Android adapters.
 */
class LiveSourcesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val contract = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/live-sources-v1.json").readText(Charsets.UTF_8))
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONObject.optText(key: String): String? = if (isNull(key)) null else getString(key)

    private fun board(rows: JSONArray, now: Instant, to: Instant, kind: BoardKind) = runBlocking {
        val adapter = ViaggiaTrenoRealtimeAdapter(ViaggiaTrenoRealtimeFetcher { rows.toString() }, Clock.fixed(now, RailTime.defaultZone))
        val request = StationBoardRequest(StationId("S08217"), "Roma Tiburtina", railwayCode = "S08217", from = now, to = to, kind = kind)
        (adapter.getStationBoard(request) as ProviderResult.Success).value
    }

    @Test fun `the operator of a ViaggiaTreno row is the shared rule`() {
        val now = Instant.ofEpochMilli(1_792_137_600_000)
        for (case in contract.getJSONArray("viaggiaTrenoOperator").objects()) {
            val row = JSONObject(case.getJSONObject("row").toString())
                .put("numeroTreno", 12345).put("codOrigine", "S08001").put("dataPartenzaTreno", 1_792_101_600_000)
                .put("orarioPartenza", now.plusSeconds(600).toEpochMilli()).put("destinazione", "Viterbo")
            val entry = board(JSONArray().put(row), now, now.plusSeconds(3_600), BoardKind.DEPARTURES).entries.single()
            assertEquals(case.toString(), case.optText("operator"), entry.operatorId?.value)
        }
    }

    @Test fun `a row of the arrivals board is the shared rule`() {
        val cases = contract.getJSONObject("viaggiaTrenoArrivals")
        val now = Instant.ofEpochMilli(cases.getLong("now"))
        val entries = board(cases.getJSONArray("rows"), now, now.plusSeconds(cases.getLong("toSeconds")), BoardKind.ARRIVALS).entries
        val expected = cases.getJSONArray("expected").objects()
        assertEquals(expected.map { it.getString("number") }, entries.map { it.publicNumber })
        for ((case, entry) in expected.zip(entries)) {
            assertEquals(case.getString("endpoint"), entry.destinationOrOriginLabel)
            assertEquals(case.getString("scheduled"), entry.scheduledTime.withZoneSameInstant(RailTime.defaultZone).format(hhmm))
            assertEquals(case.optText("expected"), entry.expectedTime?.value?.withZoneSameInstant(RailTime.defaultZone)?.format(hhmm))
            assertEquals(case.optText("platform"), entry.platform?.value)
            assertEquals(case.optText("scheduledPlatform"), entry.scheduledPlatform?.value)
            val status = when (entry.status?.value) {
                RunStatus.ON_TIME -> "onTime"
                RunStatus.DELAYED -> "delayed"
                RunStatus.CANCELLED -> "cancelled"
                else -> null
            }
            assertEquals(case.optText("status"), status)
            assertEquals(case.getString("run"), entry.nativeRunIds.values.single())
        }
    }

    @Test fun `the notices of a train on a day are the shared rule`() {
        val adapter = ViaggiaTrenoTrainNoticesAdapter(RecordingPublicSourceFetcher { "[]" }, Clock.systemUTC(), 0)
        for (case in contract.getJSONArray("trainNotices").objects()) {
            val body = File(repositoryRoot, case.getString("fixture")).readText(Charsets.UTF_8)
            val notices = adapter.select(
                adapter.parseNotes(body), case.getString("number"), case.optText("origin"),
                LocalDate.parse(case.getString("serviceDate")), Instant.EPOCH
            )
            val ids = (0 until case.getJSONArray("ids").length()).map(case.getJSONArray("ids")::getString)
            assertEquals(case.toString(), ids, notices.map { it.id })
            case.optString("textStartsWith").takeIf(String::isNotEmpty)?.let { assertTrue(notices.single().textFor("it").startsWith(it)) }
            case.optString("englishStartsWith").takeIf(String::isNotEmpty)?.let { assertTrue(notices.single().textFor("en").startsWith(it)) }
        }
    }
}

package it.binario.app.data.live

import it.binario.app.data.RailOperator
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/live-text-v1.json: the explicit cancellation of a live row and the operator of an RFI
 * monitor row, read from the same file by the iPhone tests (the patterns stay in each language's code).
 */
class LiveTextContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/live-text-v1.json").readText(Charsets.UTF_8))

    @Test fun `explicit cancellation words are the shared ones`() {
        val cases = rules.getJSONArray("explicitCancellation")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val text = case.getString("text")
            assertEquals("«$text»", case.getBoolean("cancelled"), text.hasExplicitCancellationStatus())
        }
    }

    @Test fun `the operator of an RFI row is the shared one`() {
        val adapter = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { error("offline") })
        val cases = rules.getJSONArray("rfiRowOperator")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val operator = adapter.operatorFrom(case.getString("carrier"), case.getString("category"), case.getString("number"))
            // Android also tells the Trenitalia services apart; the shared rule is the operator.
            val id = when (operator) {
                RailOperator.ITALO -> "italo"
                RailOperator.TRENORD -> "trenord"
                RailOperator.TRENITALIA_AV, RailOperator.TRENITALIA_REGIONALE, RailOperator.TRENITALIA_LONG_DISTANCE -> "trenitalia"
                else -> null
            }
            assertEquals("$case", if (case.isNull("operator")) null else case.getString("operator"), id)
        }
    }
}

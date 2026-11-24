package it.binario.app.widget

import it.binario.app.data.core.ServiceKind
import it.binario.app.shared.SharedData
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/train-type-groups-v1.json: the train type group of a station board row of unknown service
 * kind, or of a bus, read as the iPhone's PersonalFeaturesContractTests read it.
 */
class TrainTypeGroupsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/train-type-groups-v1.json").readText(Charsets.UTF_8))

    @Test fun `every case of the shared rule`() {
        val cases = rules.getJSONArray("cases")
        assertEquals(17, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val kind = if (case.getBoolean("bus")) ServiceKind.REPLACEMENT_BUS else ServiceKind.UNKNOWN
            val operatorId = if (case.isNull("operatorId")) null else case.getString("operatorId")
            val group = WidgetTrainTypes.groupOf(kind, operatorId, case.getString("label"), null)
            val expected = if (case.isNull("expected")) null else case.getString("expected")
            assertEquals(case.toString(), expected, group?.name)
        }
    }

    @Test fun `the groups are the shared table`() {
        assertEquals(SharedData.TrainTypeGroups.GROUPS, TrainTypeGroup.entries.map { it.name })
    }
}

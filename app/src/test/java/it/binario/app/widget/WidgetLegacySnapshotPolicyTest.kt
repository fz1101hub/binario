package it.binario.app.widget

import it.binario.app.data.DataConfidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLegacySnapshotPolicyTest {
    @Test
    fun `lo snapshot demo ereditato viene scartato`() {
        assertTrue(WidgetStateStore.shouldDiscardLegacyDemo(DataConfidence.DEMONSTRATION))
    }

    @Test
    fun `gli snapshot provider e GTFS restano leggibili`() {
        assertFalse(WidgetStateStore.shouldDiscardLegacyDemo(DataConfidence.OPERATOR_PRIMARY))
        assertFalse(WidgetStateStore.shouldDiscardLegacyDemo(DataConfidence.STATIC_ONLY))
    }
}

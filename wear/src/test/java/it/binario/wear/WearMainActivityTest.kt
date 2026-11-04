package it.binario.wear

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compose for Wear does not run on the JVM, so the composition is checked statically. */
class WearMainActivityTest {
    @Test
    fun `home screen scrolls in a scaffolded scaling list sized for round screens`() {
        val source = activitySource()

        assertTrue(source.contains("BinarioWearTheme { AppScaffold {"))
        assertTrue(source.contains("ScreenScaffold(scrollState = listState) { contentPadding ->"))
        assertTrue(source.contains("ScalingLazyColumn(state = listState, contentPadding = contentPadding"))
        assertTrue(source.contains("import androidx.wear.compose.foundation.lazy.itemsIndexed"))
        assertFalse(source.contains("import androidx.compose.foundation.lazy.LazyColumn"))
    }

    @Test
    fun `activity revalidates the snapshot on resume because the expiry callback runs on uptime`() {
        // WearSnapshotRuntimeTest proves current() revokes an expired value before the scheduler
        // fires; the Activity must call it on resume, which is checked here on the override.
        assertNotNull(WearMainActivity::class.java.getDeclaredMethod("onResume"))
        assertTrue(activitySource().contains("WearSnapshotStore.current(this)"))
    }

    @Test
    fun `empty state tells a missing phone app apart from disabled sharing`() {
        val source = activitySource()

        assertTrue(source.contains("getCapability(PHONE_APP_CAPABILITY, CapabilityClient.FILTER_ALL)"))
        assertTrue(source.contains("WearPhoneAppPresence.fromNodeCount(info.nodes.size)"))
        assertTrue(source.contains("stringResource(emptySnapshotMessageResId(phoneApp))"))
    }

    @Test
    fun `activity reads the Data Layer on resume and reports what the refresh request did`() {
        val source = activitySource()
        val onResume = source.substring(source.indexOf("override fun onResume()"), source.indexOf("private fun requestRefreshFromPhone()"))

        assertTrue(onResume.contains("WearSnapshotStore.bootstrapFromDataLayer(this)"))
        assertTrue(source.contains("getCapability(PHONE_APP_CAPABILITY, CapabilityClient.FILTER_REACHABLE)"))
        assertTrue(source.contains(".addOnFailureListener { refreshFeedback.value = WearRefreshFeedback.UNREACHABLE }"))
        assertTrue(source.contains(".addOnCompleteListener { task -> delivery.record(task.isSuccessful) }"))
        assertTrue(source.contains("LaunchedEffect(snapshot?.generatedAtEpochMillis) { refreshFeedback.value = null }"))
        assertTrue(source.contains("liveRegion = LiveRegionMode.Polite"))
        assertFalse(source.contains("connectedNodes"))
    }

    @Test
    fun `service rows are plain containers with one spoken label, not clickable cards without an action`() {
        val source = activitySource()

        assertFalse(source.contains("onClick = {}"))
        assertFalse(source.contains("import androidx.wear.compose.material3.Card"))
        assertTrue(source.contains(".background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)"))
        assertTrue(source.contains(".clearAndSetSemantics { contentDescription = service.accessibleLabel(context, reliability) }"))
    }

    private fun activitySource(): String = sequenceOf(File("src/main/java"), File("wear/src/main/java"))
        .firstOrNull(File::isDirectory)
        ?.resolve("it/binario/wear/WearMainActivity.kt")
        ?.readText()
        ?: error("Unable to locate the Wear activity source from ${File(".").absolutePath}")
}

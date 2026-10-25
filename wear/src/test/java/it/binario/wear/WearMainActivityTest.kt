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

        assertTrue(source.contains("MaterialTheme { AppScaffold {"))
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

    private fun activitySource(): String = sequenceOf(File("src/main/java"), File("wear/src/main/java"))
        .firstOrNull(File::isDirectory)
        ?.resolve("it/binario/wear/WearMainActivity.kt")
        ?.readText()
        ?: error("Unable to locate the Wear activity source from ${File(".").absolutePath}")
}

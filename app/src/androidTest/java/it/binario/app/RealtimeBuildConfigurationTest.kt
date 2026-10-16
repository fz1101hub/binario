package it.binario.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.data.repository.BinarioDataGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the actual installed test APK, including its source registry. No network calls. */
@RunWith(AndroidJUnit4::class)
class RealtimeBuildConfigurationTest {
    @Test fun personalTestApkEnablesOperationalSources() {
        assertTrue("L’APK personale di test deve includere le fonti live", BuildConfig.PERSONAL_REALTIME_SOURCES)
        assertEquals(setOf("rfi-station-board", "italo-status", "viaggiatreno"),
            BinarioDataGraph.stationBoardProviders().map { it.descriptor.id.value }.toSet())
    }
}

package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.sources.RailSourceCatalog
import org.junit.Test

class GtfsArtifactDownloaderTest {
    private val downloader = GtfsArtifactDownloader()

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte gtfs disponibile soltanto via gateway http`() {
        downloader.open(RailSourceCatalog.byId(SourceId("amt-genova-casella-gtfs"))!!)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte realtime best effort`() {
        downloader.open(RailSourceCatalog.byId(SourceId("viaggiatreno"))!!)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte gtfs senza url artefatto stabilizzato`() {
        val sourceWithoutArtifact = RailSourceCatalog
            .byId(SourceId("eav-gtfs"))!!
            .copy(downloadOrProbeUri = null)

        downloader.open(sourceWithoutArtifact)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `fallback community richiede opt in esplicito`() {
        val europeanSleeper = InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs"))!!
        downloader.open(europeanSleeper)
    }
}

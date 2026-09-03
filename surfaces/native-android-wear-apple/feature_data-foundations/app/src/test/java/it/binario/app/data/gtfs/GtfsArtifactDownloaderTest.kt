package it.binario.app.data.gtfs

import it.binario.app.data.sources.RailSourceCatalog
import org.junit.Test

class GtfsArtifactDownloaderTest {
    private val downloader = GtfsArtifactDownloader()

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte gtfs disponibile soltanto via gateway http`() {
        downloader.open(RailSourceCatalog.byId(it.binario.app.data.core.SourceId("amt-genova-casella-gtfs"))!!)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte realtime best effort`() {
        downloader.open(RailSourceCatalog.byId(it.binario.app.data.core.SourceId("viaggiatreno"))!!)
    }

    @Test(expected = GtfsDownloadException::class)
    fun `rifiuta una fonte gtfs senza url artefatto stabilizzato`() {
        downloader.open(RailSourceCatalog.byId(it.binario.app.data.core.SourceId("eav-gtfs"))!!)
    }
}

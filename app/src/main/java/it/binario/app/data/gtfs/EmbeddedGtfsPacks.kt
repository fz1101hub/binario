package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.RailSourceCatalog
import java.io.InputStream
import java.time.Instant

/**
 * Data pack statici validati e inclusi nell’APK. L’app non li aggiorna dalla
 * rete: l’aggiornamento avviene solo sostituendo asset, manifest e impronte
 * in una nuova release verificata.
 */
data class EmbeddedGtfsPack(
    val sourceId: SourceId,
    val assetPath: String
)

object EmbeddedGtfsPacks {
    val all = listOf(
        EmbeddedGtfsPack(SourceId("amt-genova-casella-gtfs"), "gtfs/amt-genova-casella-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("arst-gtfs"), "gtfs/arst-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("cotral-ferro-gtfs"), "gtfs/cotral-ferro-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("eav-gtfs"), "gtfs/eav-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("hzpp-gtfs"), "gtfs/hzpp-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/lombardia-trenord-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("sncf-gtfs"), "gtfs/sncf-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("toscana-tft-gtfs"), "gtfs/toscana-tft-gtfs.zip"),
        EmbeddedGtfsPack(SourceId("trentino-gtfs"), "gtfs/trentino-extraurban-gtfs.zip")
    )

    val sourceIds: Set<SourceId> = all.mapTo(linkedSetOf()) { it.sourceId }
}

/**
 * Caricatore lazy, testabile senza Android: la piattaforma fornisce soltanto
 * la funzione per aprire un asset. I pack non vengono letti all’avvio della UI.
 */
class EmbeddedGtfsPackLoader(
    private val openAsset: (String) -> InputStream,
    private val inspector: GtfsArchiveInspector = GtfsArchiveInspector()
) {
    fun load(pack: EmbeddedGtfsPack, importedAt: Instant = Instant.now()): GtfsArchiveSummary {
        val source = requireNotNull(RailSourceCatalog.byId(pack.sourceId)) {
            "Fonte non presente nel catalogo: ${pack.sourceId.value}"
        }
        return openAsset(pack.assetPath).use { stream -> inspector.inspect(source, stream, importedAt) }
    }

    fun loadAll(importedAt: Instant = Instant.now()): List<GtfsArchiveSummary> =
        EmbeddedGtfsPacks.all.map { pack -> load(pack, importedAt) }
}

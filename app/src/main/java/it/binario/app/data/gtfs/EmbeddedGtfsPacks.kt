package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.RailSourceCatalog
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate

/**
 * Data pack statici validati e inclusi nell’APK. L’app non li aggiorna dalla
 * rete: l’aggiornamento avviene solo sostituendo asset, manifest e impronte
 * in una nuova release verificata.
 *
 * La copertura segue il principio best-available: una fonte meno completa può
 * restare attiva in un territorio se è l'unica disponibile, mentre le fonti
 * migliori prevalgono dove descrivono la stessa corsa.
 */
data class EmbeddedGtfsPack(
    val sourceId: SourceId,
    val assetPath: String,
    /**
     * Ultimo giorno di servizio coperto dal pack: massimo fra end_date di
     * calendar.txt e le date con exception_type=1 di calendar_dates.txt.
     * Deve coincidere con lastServiceDate nel manifest; null se sconosciuto.
     */
    val lastServiceDate: LocalDate? = null
)

object EmbeddedGtfsPacks {
    val all = listOf(
        EmbeddedGtfsPack(SourceId("alto-adige-sta-gtfs"), "gtfs/alto-adige-sta-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("amt-genova-casella-gtfs"), "gtfs/amt-genova-casella-gtfs.zip", LocalDate.of(2026, 9, 27)),
        EmbeddedGtfsPack(SourceId("arst-gtfs"), "gtfs/arst-gtfs.zip", LocalDate.of(2026, 12, 31)),
        EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/ch-otd-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("cotral-ferro-gtfs"), "gtfs/cotral-ferro-gtfs.zip", LocalDate.of(2026, 9, 6)),
        EmbeddedGtfsPack(SourceId("eav-gtfs"), "gtfs/eav-gtfs.zip", LocalDate.of(2027, 5, 13)),
        EmbeddedGtfsPack(SourceId("hzpp-gtfs"), "gtfs/hzpp-gtfs.zip", LocalDate.of(2026, 12, 13)),
        EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/lombardia-trenord-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("oebb-gtfs"), "gtfs/oebb-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("sncf-gtfs"), "gtfs/sncf-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("toscana-tft-gtfs"), "gtfs/toscana-tft-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("trentino-gtfs"), "gtfs/trentino-extraurban-gtfs.zip", LocalDate.of(2027, 6, 25))
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

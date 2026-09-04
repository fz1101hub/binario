package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.TrainDataSource
import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.InternationalSourceCatalog
import it.binario.app.data.sources.RailSourceDefinition
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Remote GTFS is deliberately a last-resort path. It is used for a source
 * explicitly classified BEST_EFFORT_FALLBACK and only after higher-quality
 * route sources have failed. Bytes are cached in memory to avoid repeated
 * downloads during a session.
 */
class RemoteGtfsFallbackDataSource(
    private val source: RailSourceDefinition,
    private val parserSourceId: SourceId,
    private val downloader: GtfsArtifactDownloader = GtfsArtifactDownloader(),
    private val clock: Clock = Clock.systemUTC(),
    private val notBefore: LocalDate? = null,
    private val cacheTtl: Duration = Duration.ofHours(6)
) : TrainDataSource {
    @Volatile private var cached: CachedFeed? = null

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        notBefore?.let { if (query.serviceDate < it) return DataResult.Failure("La fonte fallback non è ancora valida per la data selezionata.") }
        val bytes = runCatching { feedBytes() }.getOrElse {
            return DataResult.Failure("La fonte GTFS fallback ${source.displayName} non è disponibile: ${it.message.orEmpty()}")
        }
        val adapter = EmbeddedGtfsRouteAdapter(
            openAsset = { ByteArrayInputStream(bytes) },
            packs = listOf(EmbeddedGtfsPack(parserSourceId, "remote/${source.id.value}.zip")),
            clock = { clock.millis() }
        )
        return when (val result = adapter.loadRoute(route, direction, query)) {
            is DataResult.Success -> DataResult.Success(
                result.snapshot.copy(
                    providerName = "${source.displayName} · fallback best-effort",
                    notice = listOfNotNull(
                        "Copertura fallback: usata perché le fonti ferroviarie primarie non hanno restituito questa tratta. Verifica l'orario con il vettore prima del viaggio.",
                        result.snapshot.notice
                    ).joinToString(" ")
                )
            )
            is DataResult.Failure -> result
        }
    }

    private fun feedBytes(): ByteArray {
        val now = clock.instant()
        cached?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.bytes }
        val bytes = downloader.open(source, allowBestEffortFallback = true).use { session -> session.input.readBytes() }
        require(bytes.isNotEmpty()) { "Archivio GTFS vuoto" }
        return bytes.also { cached = CachedFeed(it, now.plus(cacheTtl)) }
    }

    private data class CachedFeed(val bytes: ByteArray, val expiresAt: java.time.Instant)

    companion object {
        fun europeanSleeper(clock: Clock = Clock.systemUTC()): RemoteGtfsFallbackDataSource =
            RemoteGtfsFallbackDataSource(
                source = requireNotNull(InternationalSourceCatalog.byId(SourceId("european-sleeper-gtfs"))),
                // The legacy source id already exists in RailSourceCatalog and is used only as parser provenance.
                parserSourceId = SourceId("european-sleeper"),
                clock = clock,
                notBefore = LocalDate.of(2026, 9, 9)
            )
    }
}

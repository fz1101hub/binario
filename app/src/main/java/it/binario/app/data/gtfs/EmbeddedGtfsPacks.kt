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
    val lastServiceDate: LocalDate? = null,
    /** How stop_code ties a stop of this pack to a catalogue station; [GtfsStopCodeScheme.NONE] matches by name only. */
    val stopCodeScheme: GtfsStopCodeScheme = GtfsStopCodeScheme.NONE,
    /**
     * route_type 3 of this pack are the operator's own scheduled coaches (Trenitalia buses, ItaloBus), shown as
     * [it.binario.app.data.core.LegMode.BUS]. In every other pack a bus route is a rail replacement bus.
     */
    val busRoutesAreScheduledCoaches: Boolean = false,
    /** Railway pack or city network (metro, tram, funicular); see [EmbeddedGtfsPackKind]. */
    val kind: EmbeddedGtfsPackKind = EmbeddedGtfsPackKind.RAIL,
    /**
     * For a [EmbeddedGtfsPackKind.URBAN] pack, the areas it serves (a city, or the two cities of ARST's light rail):
     * the journey planner reads it only for a search with an end inside one of them.
     */
    val areas: List<TransitArea> = emptyList(),
    /**
     * Shipped only in personal builds ([EmbeddedGtfsPacks.personalSourcesIncluded]): a licence that does not allow
     * redistribution in a public app (non-commercial, or public but not stated), see SOURCE_POLICY.md.
     */
    val personalOnly: Boolean = false,
    /**
     * Suffixes the curated catalogue adds to this operator's stations ("Matera Centrale FAL", "Bari Centrale FNB"):
     * a stop published as "Matera Centrale" is that station before any station named "Matera Centrale" alone.
     */
    val catalogueNameSuffixes: List<String> = emptyList(),
    /**
     * The pack also publishes the operator's combined itineraries as trips (train A, a change, train B): the same
     * physical run is then several trips, some under the first train's number (Italo's NeTEx products). The readers keep
     * one trip per run of a service day, the train under its own number (EmbeddedGtfsRouteAdapter, `copiesOn`). Off for
     * the packs whose trips at the same minutes are coupled portions, each a train of its own (Trenord, SNCF, ÖBB).
     */
    val itineraryCopies: Boolean = false
) {
    val isUrban: Boolean get() = kind == EmbeddedGtfsPackKind.URBAN
}

/** What a pack holds. */
enum class EmbeddedGtfsPackKind {
    /** Trains (and the operator's coaches): stops are matched to catalogue stations; route search, boards, journeys. */
    RAIL,
    /**
     * Metro, tram and funicular lines of one city: stops stay stops of the pack (never merged with a railway station by
     * name) and join stations and other networks only by short walks. Journeys and the stop search only.
     */
    URBAN
}

/** A circle around a city centre: an urban pack takes part in a search when an end of it lies inside. */
data class TransitArea(val name: String, val latitude: Double, val longitude: Double, val radiusKm: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0 && radiusKm > 0) { "Area non valida" }
    }

    fun contains(latitude: Double, longitude: Double): Boolean =
        GtfsStationCodeIndex.distanceKm(this.latitude, this.longitude, latitude, longitude) <= radiusKm
}

/** The station code a pack publishes as stop_code, used to find its catalogue station before the name. */
enum class GtfsStopCodeScheme {
    NONE,
    /** Trenitalia location id ("830001700"), the catalogue's trenitaliaLocationId; see [GtfsStationCodeIndex]. */
    TRENITALIA_LOCATION_ID,
    /** Italo's three-character station code ("MC_"), mapped by the reviewed [ItaloGtfsStationCodes] table. */
    ITALO_STATION_CODE
}

object EmbeddedGtfsPacks {
    /**
     * True in personal builds (the debug test APK; a release only with `-Pbinario.personalSources=true`): their packs
     * marked [EmbeddedGtfsPack.personalOnly] are in the APK (`app/src/personal/assets/gtfs-personal/`) and used.
     */
    val personalSourcesIncluded: Boolean = it.binario.app.BuildConfig.PERSONAL_TRANSIT_SOURCES

    /** The railway packs of the first release, in their reviewed order (the route search, boards and journeys). */
    private val corePacks = listOf(
        EmbeddedGtfsPack(SourceId("alto-adige-sta-gtfs"), "gtfs/alto-adige-sta-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("arst-gtfs"), "gtfs/arst-gtfs.zip", LocalDate.of(2026, 12, 31)),
        EmbeddedGtfsPack(SourceId("ch-otd-gtfs"), "gtfs/ch-otd-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("eav-gtfs"), "gtfs/eav-gtfs.zip", LocalDate.of(2026, 12, 31)),
        EmbeddedGtfsPack(SourceId("hzpp-gtfs"), "gtfs/hzpp-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(
            SourceId("italo-gtfs"), "gtfs/italo-gtfs.zip", LocalDate.of(2026, 12, 12),
            stopCodeScheme = GtfsStopCodeScheme.ITALO_STATION_CODE, busRoutesAreScheduledCoaches = true, itineraryCopies = true
        ),
        EmbeddedGtfsPack(SourceId("lombardia-trenord-gtfs"), "gtfs/lombardia-trenord-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("oebb-gtfs"), "gtfs/oebb-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(SourceId("sncf-gtfs"), "gtfs/sncf-gtfs.zip", LocalDate.of(2027, 4, 30)),
        EmbeddedGtfsPack(SourceId("toscana-tft-gtfs"), "gtfs/toscana-tft-gtfs.zip", LocalDate.of(2026, 12, 12)),
        EmbeddedGtfsPack(
            SourceId("trenitalia-gtfs"), "gtfs/trenitalia-gtfs.zip", LocalDate.of(2027, 12, 11),
            stopCodeScheme = GtfsStopCodeScheme.TRENITALIA_LOCATION_ID, busRoutesAreScheduledCoaches = true
        ),
        EmbeddedGtfsPack(SourceId("trentino-gtfs"), "gtfs/trentino-extraurban-gtfs.zip", LocalDate.of(2027, 6, 25))
    )

    /**
     * Packs added after the core ones (`gtfs-transit/`, open data in every build; `gtfs-personal/`, personal builds
     * only): regional railways and city networks. See TransitGtfsPacks.
     */
    val transit: List<EmbeddedGtfsPack> = TransitGtfsPacks.all

    /** Every pack of this build: the core railway packs, then the transit packs this build includes. */
    fun forBuild(personalSources: Boolean): List<EmbeddedGtfsPack> =
        corePacks + transit.filter { personalSources || !it.personalOnly }

    private val included: List<EmbeddedGtfsPack> = forBuild(personalSourcesIncluded)

    /** The railway packs of this build: route search, train-number search, boards and journeys. */
    val all: List<EmbeddedGtfsPack> = included.filterNot(EmbeddedGtfsPack::isUrban)

    /** The city packs (metro, tram, funicular) of this build: journeys with changes, the stop search and stop boards. */
    val urban: List<EmbeddedGtfsPack> = included.filter(EmbeddedGtfsPack::isUrban)

    private val urbanStopPrefixes: List<String> = transit.filter(EmbeddedGtfsPack::isUrban).map { "gtfs:${it.sourceId.value}:" }

    /** True for the id of a city-pack stop (`gtfs:<city pack>:<stop>`), whatever the build. */
    fun isUrbanStopId(id: String): Boolean = urbanStopPrefixes.any { id.startsWith(it) && id.length > it.length }

    /** Every pack of this build, railway then urban: what the registry enables. */
    val sourceIds: Set<SourceId> = included.mapTo(linkedSetOf()) { it.sourceId }

    /**
     * The credit lines of this build's transit packs (Settings › Info): network, attribution with licence, and the
     * page that publishes the data. One line per source, in pack order.
     */
    fun transitCredits(): List<String> = included
        .filter { it in transit }
        .distinctBy(EmbeddedGtfsPack::sourceId)
        .mapNotNull { pack ->
            RailSourceCatalog.byId(pack.sourceId)?.let { source ->
                "${source.displayName}: ${source.attribution ?: source.operatorOrAuthority}. ${source.landingPage}"
            }
        }
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

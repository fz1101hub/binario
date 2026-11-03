package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.time.LocalDate

/**
 * Regional railways and city networks (metro, tram, funiculars) added after the core railway packs. Assets live in
 * `gtfs-transit/` (open data, every build) and `gtfs-personal/` (personal builds only, [EmbeddedGtfsPack.personalOnly]),
 * each with its own manifest, SHA256SUMS and the same build and verification pipeline as `gtfs/`
 * (`tools/build_gtfs_packs.py`). Sources, licences and refresh steps: docs/data-sources/SOURCE_POLICY.md and
 * GTFS_PACK_REFRESH.md. `lastServiceDate` must match each manifest (EmbeddedGtfsAssetIntegrityTest).
 */
internal object TransitGtfsPacks {
    val all: List<EmbeddedGtfsPack> = listOf(
        EmbeddedGtfsPack(
            SourceId("fal-basilicata-puglia"),
            "gtfs-transit/fal-basilicata-puglia.zip",
            LocalDate.of(2026, 12, 31),
            catalogueNameSuffixes = listOf("FAL")
        ),
        EmbeddedGtfsPack(
            SourceId("ferrotramviaria-bari-barletta"),
            "gtfs-transit/ferrotramviaria-bari-barletta.zip",
            LocalDate.of(2026, 12, 31),
            catalogueNameSuffixes = listOf("FNB", "FBN")
        ),
        EmbeddedGtfsPack(
            SourceId("fdg-gargano"),
            "gtfs-transit/fdg-gargano.zip",
            LocalDate.of(2026, 12, 31)
        ),
        EmbeddedGtfsPack(
            SourceId("micotra-udine-villach"),
            "gtfs-transit/micotra-udine-villach.zip",
            LocalDate.of(2026, 12, 12)
        ),
        EmbeddedGtfsPack(
            SourceId("amt-genova-casella-gtfs"),
            "gtfs-transit/amt-genova-casella-gtfs.zip",
            LocalDate.of(2026, 12, 18)
        ),
        EmbeddedGtfsPack(
            SourceId("cotral-ferro-gtfs"),
            "gtfs-personal/cotral-ferro-gtfs.zip",
            LocalDate.of(2026, 12, 13),
            catalogueNameSuffixes = listOf("Cotral"),
            personalOnly = true
        ),
        EmbeddedGtfsPack(
            SourceId("milano-atm-gtfs"),
            "gtfs-transit/milano-atm-gtfs.zip",
            LocalDate.of(2026, 11, 30),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Milano", 45.4642, 9.19, 20.0))
        ),
        EmbeddedGtfsPack(
            SourceId("genova-amt-gtfs"),
            "gtfs-transit/genova-amt-gtfs.zip",
            LocalDate.of(2026, 12, 18),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Genova", 44.4072, 8.934, 15.0))
        ),
        EmbeddedGtfsPack(
            SourceId("firenze-gest-gtfs"),
            "gtfs-transit/firenze-gest-gtfs.zip",
            LocalDate.of(2026, 12, 30),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Firenze", 43.7731, 11.256, 12.0))
        ),
        EmbeddedGtfsPack(
            SourceId("arst-metro-gtfs"),
            "gtfs-transit/arst-metro-gtfs.zip",
            LocalDate.of(2026, 12, 31),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Cagliari", 39.2238, 9.1217, 15.0), TransitArea("Sassari", 40.7259, 8.5557, 10.0))
        ),
        EmbeddedGtfsPack(
            SourceId("napoli-anm-gtfs"),
            "gtfs-personal/napoli-anm-gtfs.zip",
            LocalDate.of(2026, 12, 31),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Napoli", 40.8518, 14.2681, 12.0)),
            personalOnly = true
        ),
        EmbeddedGtfsPack(
            SourceId("venezia-actv-gtfs"),
            "gtfs-personal/venezia-actv-gtfs.zip",
            LocalDate.of(2026, 12, 23),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Venezia Mestre", 45.49, 12.24, 10.0)),
            personalOnly = true
        ),
        EmbeddedGtfsPack(
            SourceId("roma-mobilita-gtfs"),
            "gtfs-personal/roma-mobilita-gtfs.zip",
            LocalDate.of(2026, 12, 9),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Roma", 41.8986, 12.4964, 20.0)),
            personalOnly = true
        ),
        EmbeddedGtfsPack(
            SourceId("gtt-gtfs"),
            "gtfs-personal/gtt-gtfs.zip",
            LocalDate.of(2026, 12, 31),
            kind = EmbeddedGtfsPackKind.URBAN,
            areas = listOf(TransitArea("Torino", 45.0703, 7.6869, 15.0)),
            personalOnly = true
        )
    )
}

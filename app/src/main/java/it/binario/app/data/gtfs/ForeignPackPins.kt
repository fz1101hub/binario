package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class ForeignPackPinsException(message: String) : IllegalArgumentException(message)

/**
 * The full Android pack of one country, published once on GitHub Releases under a content-addressed tag and never
 * changed: the app downloads exactly these bytes ([sizeBytes], [sha256]) from [url] and nothing else.
 */
data class ForeignPackDownloadPin(
    val tag: String,
    val asset: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /** SHA-256 of the GTFS content (the tools' `contentSha256`), kept for the record; null when not published. */
    val contentSha256: String?
)

/**
 * One foreign pack of the pins file. A pack whose licence allows publishing it on its own has a download pin and is
 * bundled as its border subset ([borderOnly]); any other pack is bundled whole, exactly as before, with no pin.
 */
data class ForeignPackEntry(
    val sourceId: SourceId,
    /** ISO 3166 code of the country the pack covers ("CH", "AT", "FR"): it picks the texts of the prompts. */
    val country: String,
    /** Last service day of the full pack, the date the app uses once the full pack is installed. */
    val lastServiceDate: LocalDate,
    val fullServiceUntil: LocalDate?,
    /** Catalogue ids (without the app's `curated:` prefix) of the stations the full pack serves. */
    val servedStationIds: Set<String>,
    /** The served stations whose trains are all in the border subset: the only covered ends while it is not installed. */
    val zoneStationIds: Set<String>,
    /** True iff the bundled file is the border subset. */
    val borderOnly: Boolean,
    val android: ForeignPackDownloadPin?
) {
    /** The bundled file is the border subset and the full pack can be downloaded. */
    val downloadable: Boolean get() = borderOnly && android != null

    /** The full pack of this version ends before [date]: it is no longer offered (the app needs an update). */
    fun expiredOn(date: LocalDate): Boolean = lastServiceDate.isBefore(date)

    /** True when [stationId] (an app station id, `curated:<id>`, or a bare catalogue id) is served by the full pack. */
    fun serves(stationId: String?): Boolean = stationId != null && catalogueId(stationId) in servedStationIds

    /** True when [stationId] is a zone station: its trains in this pack are all in the border subset. */
    fun inZone(stationId: String?): Boolean = stationId != null && catalogueId(stationId) in zoneStationIds

    companion object {
        /** The catalogue id of an app station id (`curated:S01700` → `S01700`). */
        fun catalogueId(stationId: String): String = stationId.removePrefix(CURATED_PREFIX)

        private const val CURATED_PREFIX = "curated:"
    }
}

/**
 * The pins file `gtfs/foreign_packs.json` (schema 1), written by the tools next to the border subsets and read by the
 * signed app. It is validated whole: a file that breaks a rule is not used (see [load]), so a malformed pin can never
 * make the app download from another address or accept other bytes.
 */
class ForeignPackPins(val packs: List<ForeignPackEntry>, val radiusKm: Double? = null) {
    private val bySource: Map<SourceId, ForeignPackEntry> = packs.associateBy(ForeignPackEntry::sourceId)

    fun entry(sourceId: SourceId): ForeignPackEntry? = bySource[sourceId]

    /** The packs bundled as their border subset. */
    val borderOnly: List<ForeignPackEntry> get() = packs.filter(ForeignPackEntry::borderOnly)

    /** The packs whose full version can be downloaded (border subset bundled and a pin). */
    val downloadable: List<ForeignPackEntry> get() = packs.filter(ForeignPackEntry::downloadable)

    companion object {
        const val ASSET_PATH = "gtfs/foreign_packs.json"
        const val SCHEMA_VERSION = 1
        const val REPOSITORY = "fz1101hub/binario"
        /** Every download starts here; the apps refuse any other prefix. */
        const val GITHUB_RELEASE_URL_PREFIX = "https://github.com/fz1101hub/binario/releases/download/"
        const val GITHUB_HOST = "github.com"
        /** Where github.com redirects a release asset download today. */
        const val GITHUB_RELEASE_DOWNLOAD_HOST = "release-assets.githubusercontent.com"
        /** The host GitHub used before for the same redirect. */
        const val GITHUB_LEGACY_RELEASE_DOWNLOAD_HOST = "objects.githubusercontent.com"
        const val FULL_PACK_TAG_PREFIX = "gtfs-full-"
        /** No full pack weighs anywhere near this (the largest is about 5 MB). */
        const val MAX_PACK_BYTES = 64L * 1024L * 1024L
        private const val MAX_FILE_BYTES = 1024 * 1024

        val EMPTY = ForeignPackPins(emptyList())

        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val SOURCE_ID = Regex("^[a-z0-9][a-z0-9-]{1,63}$")
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val ASSET = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.zip$")

        /**
         * The pins of this build, or [EMPTY] when the file is absent (a build without border subsets) or breaks a rule:
         * every pack is then read as bundled, exactly as before.
         */
        fun load(openAsset: (String) -> InputStream): ForeignPackPins {
            val text = try {
                openAsset(ASSET_PATH).use { stream ->
                    val bytes = stream.readNBytesCompat(MAX_FILE_BYTES + 1)
                    if (bytes.size > MAX_FILE_BYTES) return EMPTY
                    String(bytes, Charsets.UTF_8)
                }
            } catch (_: FileNotFoundException) {
                return EMPTY
            } catch (_: IOException) {
                return EMPTY
            }
            return try {
                parse(text)
            } catch (_: ForeignPackPinsException) {
                EMPTY
            }
        }

        /** Parses and validates [json]; throws [ForeignPackPinsException] on the first rule it breaks. */
        fun parse(json: String): ForeignPackPins {
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                throw ForeignPackPinsException("JSON non valido")
            }
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) throw ForeignPackPinsException("schemaVersion non supportata")
            root.optString("repository", REPOSITORY).let { if (it != REPOSITORY) throw ForeignPackPinsException("repository inatteso") }
            val radius = root.optJSONObject("borderZone")?.takeIf { it.has("radiusKm") }?.let { zone ->
                (zone.opt("radiusKm") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }
                    ?: throw ForeignPackPinsException("radiusKm non valido")
            }
            val values = root.optJSONArray("packs") ?: throw ForeignPackPinsException("packs mancante")
            val packs = ArrayList<ForeignPackEntry>()
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: throw ForeignPackPinsException("pack $index non è un oggetto")
                val entry = entry(value)
                if (packs.any { it.sourceId == entry.sourceId }) throw ForeignPackPinsException("pack ripetuto: ${entry.sourceId.value}")
                packs += entry
            }
            return ForeignPackPins(packs, radius)
        }

        private fun entry(value: JSONObject): ForeignPackEntry {
            val sourceId = value.requiredString("sourceId").also {
                if (!SOURCE_ID.matches(it)) throw ForeignPackPinsException("sourceId non valido")
            }
            val country = value.requiredString("country").also {
                if (!COUNTRY.matches(it)) throw ForeignPackPinsException("country non valido: $sourceId")
            }
            val lastServiceDate = value.date("lastServiceDate", sourceId)
                ?: throw ForeignPackPinsException("lastServiceDate mancante: $sourceId")
            val fullServiceUntil = value.date("fullServiceUntil", sourceId)
            val served = value.ids("servedStationIds", sourceId)
            val zone = value.ids("zoneStationIds", sourceId)
            if (!served.containsAll(zone)) throw ForeignPackPinsException("zoneStationIds non incluse in servedStationIds: $sourceId")
            if (!value.has("borderOnly") || value.opt("borderOnly") !is Boolean) {
                throw ForeignPackPinsException("borderOnly mancante: $sourceId")
            }
            val borderOnly = value.getBoolean("borderOnly")
            val android = if (value.isNull("android")) null else {
                val pin = value.optJSONObject("android") ?: throw ForeignPackPinsException("android non valido: $sourceId")
                downloadPin(pin, sourceId)
            }
            return ForeignPackEntry(SourceId(sourceId), country, lastServiceDate, fullServiceUntil, served, zone, borderOnly, android)
        }

        private fun downloadPin(pin: JSONObject, sourceId: String): ForeignPackDownloadPin {
            val sha256 = pin.requiredString("sha256").also {
                if (!SHA256.matches(it)) throw ForeignPackPinsException("sha256 non valido: $sourceId")
            }
            val expectedTag = FULL_PACK_TAG_PREFIX + sourceId + "-" + sha256.take(12)
            val tag = pin.requiredString("tag").also {
                if (it != expectedTag) throw ForeignPackPinsException("tag non conforme: $sourceId")
            }
            val asset = pin.requiredString("asset").also {
                if (!ASSET.matches(it)) throw ForeignPackPinsException("asset non valido: $sourceId")
            }
            val url = pin.requiredString("url").also {
                if (it != "$GITHUB_RELEASE_URL_PREFIX$tag/$asset") throw ForeignPackPinsException("url non conforme: $sourceId")
            }
            val size = pin.opt("sizeBytes") as? Number
            val sizeBytes = size?.toLong()?.takeIf { it.toDouble() == size.toDouble() && it in 1..MAX_PACK_BYTES }
                ?: throw ForeignPackPinsException("sizeBytes non valido: $sourceId")
            val contentSha256 = if (pin.has("contentSha256") && !pin.isNull("contentSha256")) {
                pin.requiredString("contentSha256").also {
                    if (!SHA256.matches(it)) throw ForeignPackPinsException("contentSha256 non valido: $sourceId")
                }
            } else null
            return ForeignPackDownloadPin(tag, asset, url, sizeBytes, sha256, contentSha256)
        }

        private fun JSONObject.requiredString(name: String): String {
            val value = opt(name) as? String ?: throw ForeignPackPinsException("$name mancante")
            if (value.isBlank() || value != value.trim()) throw ForeignPackPinsException("$name non valido")
            return value
        }

        private fun JSONObject.date(name: String, sourceId: String): LocalDate? {
            if (!has(name) || isNull(name)) return null
            val text = opt(name) as? String ?: throw ForeignPackPinsException("$name non valido: $sourceId")
            return try {
                LocalDate.parse(text)
            } catch (_: DateTimeParseException) {
                throw ForeignPackPinsException("$name non valido: $sourceId")
            }
        }

        private fun JSONObject.ids(name: String, sourceId: String): Set<String> {
            val values: JSONArray = optJSONArray(name) ?: throw ForeignPackPinsException("$name mancante: $sourceId")
            val ids = LinkedHashSet<String>()
            for (index in 0 until values.length()) {
                val id = values.opt(index) as? String
                if (id.isNullOrBlank() || id != id.trim() || id.contains(':')) throw ForeignPackPinsException("$name non valido: $sourceId")
                ids += id
            }
            return ids
        }

        /** Reads at most [limit] bytes (InputStream.readNBytes is API 33). */
        private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (output.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

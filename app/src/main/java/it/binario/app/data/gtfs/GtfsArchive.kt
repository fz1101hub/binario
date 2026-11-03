package it.binario.app.data.gtfs

import it.binario.app.data.core.AliasType
import it.binario.app.data.core.CanonicalStation
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.GeoPoint
import it.binario.app.data.core.Precision
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationAlias
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationCatalogProvider
import it.binario.app.data.provider.StationSearchRequest
import it.binario.app.data.sources.RailSourceDefinition
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipInputStream

private val requiredGtfsFiles = setOf("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")

data class GtfsSafetyLimits(
    val maxEntries: Int = 64,
    val maxEntryBytes: Long = 128L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 512L * 1024L * 1024L,
    val maxStations: Int = 250_000,
    val maxCsvLineLength: Int = 32_768
) {
    init {
        require(maxEntries > 0)
        require(maxEntryBytes > 0)
        require(maxTotalUncompressedBytes >= maxEntryBytes)
        require(maxStations > 0)
        require(maxCsvLineLength > 0)
    }
}

class GtfsValidationException(message: String) : IllegalArgumentException(message)

data class GtfsArchiveSummary(
    val sourceId: it.binario.app.data.core.SourceId,
    val importedAt: Instant,
    val agencyNames: List<String>,
    val feedVersion: String?,
    val stations: List<CanonicalStation>,
    val presentFiles: Set<String>,
    val totalUncompressedBytes: Long,
    val sha256: String
)

/**
 * Parser offline dei data pack GTFS. Non apre URL, non salva token e non è un
 * client di rete: gli artefatti sono acquisiti fuori dalla UI e verificati qui.
 */
class GtfsArchiveInspector(
    private val limits: GtfsSafetyLimits = GtfsSafetyLimits()
) {
    fun inspect(source: RailSourceDefinition, stream: InputStream, importedAt: Instant = Instant.now()): GtfsArchiveSummary {
        require(it.binario.app.data.sources.SourceFormat.GTFS_ZIP in source.formats) {
            "La fonte ${source.id.value} non è un feed GTFS ZIP"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val presentFiles = linkedSetOf<String>()
        val agencyNames = mutableListOf<String>()
        val stations = mutableListOf<CanonicalStation>()
        var feedVersion: String? = null
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.isDirectory) {
                    entry = zip.nextEntry
                    continue
                }
                entryCount += 1
                if (entryCount > limits.maxEntries) throw GtfsValidationException("Il data pack supera il numero massimo di file")
                val fileName = entry.name.substringAfterLast('/')
                if (fileName.isBlank() || entry.name.contains("..")) throw GtfsValidationException("Nome file GTFS non valido")
                presentFiles += fileName

                val sink = BoundedEntryInputStream(zip, limits.maxEntryBytes) { consumed ->
                    totalBytes += consumed
                    if (totalBytes > limits.maxTotalUncompressedBytes) {
                        throw GtfsValidationException("Il data pack supera il limite non compresso")
                    }
                }

                when (fileName) {
                    "agency.txt" -> agencyNames += parseAgency(sink, limits)
                    "feed_info.txt" -> feedVersion = parseFeedVersion(sink, limits)
                    "stops.txt" -> stations += parseStations(source, sink, importedAt, limits)
                    else -> drainAndDigest(sink, digest)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val missing = requiredGtfsFiles - presentFiles
        if (missing.isNotEmpty()) throw GtfsValidationException("File GTFS obbligatori mancanti: ${missing.sorted().joinToString()}")
        if (agencyNames.isEmpty()) throw GtfsValidationException("agency.txt non contiene agenzie valide")
        if (stations.isEmpty()) throw GtfsValidationException("stops.txt non contiene stazioni valide")

        return GtfsArchiveSummary(
            sourceId = source.id,
            importedAt = importedAt,
            agencyNames = agencyNames.distinct(),
            feedVersion = feedVersion,
            stations = stations.distinctBy { it.id },
            presentFiles = presentFiles,
            totalUncompressedBytes = totalBytes,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    private fun parseAgency(input: InputStream, limits: GtfsSafetyLimits): List<String> {
        val rows = readCsv(input, limits)
        val nameIndex = rows.header.indexOf("agency_name")
        if (nameIndex < 0) throw GtfsValidationException("agency.txt non contiene agency_name")
        return rows.rows.mapNotNull { it.getOrNull(nameIndex)?.trim()?.takeIf(String::isNotBlank) }
    }

    private fun parseFeedVersion(input: InputStream, limits: GtfsSafetyLimits): String? {
        val rows = readCsv(input, limits)
        val versionIndex = rows.header.indexOf("feed_version")
        return versionIndex.takeIf { it >= 0 }
            ?.let { rows.rows.firstOrNull()?.getOrNull(it)?.trim()?.takeIf(String::isNotBlank) }
    }

    private fun parseStations(
        source: RailSourceDefinition,
        input: InputStream,
        importedAt: Instant,
        limits: GtfsSafetyLimits
    ): List<CanonicalStation> {
        val rows = readCsv(input, limits, limits.maxStations + 1)
        if (rows.rows.size > limits.maxStations) throw GtfsValidationException("stops.txt supera il limite di stazioni")
        val idIndex = rows.header.indexOf("stop_id")
        val nameIndex = rows.header.indexOf("stop_name")
        val latitudeIndex = rows.header.indexOf("stop_lat")
        val longitudeIndex = rows.header.indexOf("stop_lon")
        val locationTypeIndex = rows.header.indexOf("location_type")
        if (idIndex < 0 || nameIndex < 0) throw GtfsValidationException("stops.txt richiede stop_id e stop_name")

        return rows.rows.mapNotNull { row ->
            val nativeId = row.getOrNull(idIndex)?.trim().orEmpty()
            val name = row.getOrNull(nameIndex)?.trim().orEmpty()
            if (nativeId.isBlank() || name.isBlank()) return@mapNotNull null
            val locationType = row.getOrNull(locationTypeIndex)?.trim()
            if (locationType != null && locationType !in setOf("", "0", "1")) return@mapNotNull null
            val latitude = row.getOrNull(latitudeIndex)?.toDoubleOrNull()
            val longitude = row.getOrNull(longitudeIndex)?.toDoubleOrNull()
            val point = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
            val ref = SourceRef(
                sourceId = source.id,
                nativeId = nativeId,
                fetchedAt = importedAt,
                confidence = Confidence.OPEN_DATA,
                licenseId = source.attribution,
                traceId = "gtfs:${source.id.value}:$nativeId"
            )
            CanonicalStation(
                id = StationId("${source.id.value}:$nativeId"),
                canonicalName = name,
                countryCode = source.countryCodes.singleOrNull() ?: "ZZ",
                coordinates = point,
                aliases = listOf(StationAlias(source.id, nativeId, name, AliasType.GTFS_STOP)),
                sources = setOf(ref)
            )
        }
    }

    private fun drainAndDigest(input: InputStream, digest: MessageDigest) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }

    private fun readCsv(input: InputStream, limits: GtfsSafetyLimits, maxRows: Int = 100_000): CsvTable {
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            val headerLine = reader.readLine() ?: throw GtfsValidationException("File CSV GTFS vuoto")
            if (headerLine.length > limits.maxCsvLineLength) throw GtfsValidationException("Intestazione CSV troppo lunga")
            val header = parseCsvLine(headerLine).map { it.trim() }
            val rows = mutableListOf<List<String>>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length > limits.maxCsvLineLength) throw GtfsValidationException("Riga CSV troppo lunga")
                if (line.isBlank()) continue
                rows += parseCsvLine(line)
                if (rows.size > maxRows) break
            }
            return CsvTable(header, rows)
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            when (val char = line[index]) {
                '"' -> if (quoted && line.getOrNull(index + 1) == '"') {
                    current.append('"')
                    index += 1
                } else {
                    quoted = !quoted
                }
                ',' -> if (quoted) current.append(char) else {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        if (quoted) throw GtfsValidationException("CSV con virgolette non chiuse")
        values += current.toString()
        return values
    }

    private data class CsvTable(val header: List<String>, val rows: List<List<String>>)

    private class BoundedEntryInputStream(
        private val upstream: InputStream,
        private val maxBytes: Long,
        private val onRead: (Long) -> Unit
    ) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            val result = upstream.read()
            if (result >= 0) register(1)
            return result
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val result = upstream.read(buffer, offset, length)
            if (result > 0) register(result.toLong())
            return result
        }

        private fun register(bytes: Long) {
            consumed += bytes
            if (consumed > maxBytes) throw GtfsValidationException("Una entry GTFS supera il limite")
            onRead(bytes)
        }
    }
}

/** Provider locale per il catalogo stazioni estratto da un data pack GTFS validato. */
class GtfsStationCatalogProvider(
    private val source: RailSourceDefinition,
    private val summary: GtfsArchiveSummary,
    override val descriptor: ProviderDescriptor = ProviderDescriptor(
        id = source.id,
        displayName = source.displayName,
        capabilities = setOf(ProviderCapability.STATION_CATALOG),
        coverage = CoverageDescriptor(countryCodes = source.countryCodes, description = source.coverage),
        accessMode = source.accessMode,
        ratePolicy = RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofHours(6)),
        schemaVersion = 1
    )
) : StationCatalogProvider {
    override suspend fun searchStations(request: StationSearchRequest): ProviderResult<List<CanonicalStation>> {
        val normalizedQuery = request.query.lowercase(Locale.ROOT).trim()
        val matches = summary.stations.asSequence()
            .filter { it.canonicalName.lowercase(Locale.ROOT).contains(normalizedQuery) }
            .take(request.limit)
            .toList()
        return ProviderResult.Success(matches, responseMeta())
    }

    override suspend fun resolveStation(alias: StationAlias): ProviderResult<CanonicalStation> {
        if (alias.sourceId != source.id) return unsupported("Alias non gestito dalla fonte ${source.id.value}")
        val station = summary.stations.firstOrNull { candidate -> candidate.aliases.any { it.nativeCode == alias.nativeCode } }
            ?: return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_FOUND, "Stazione non trovata"))
        return ProviderResult.Success(station, responseMeta())
    }

    private fun responseMeta() = ProviderResponseMeta(
        providerId = source.id,
        fetchedAt = summary.importedAt,
        expiresAt = summary.importedAt.plus(Duration.ofHours(6)),
        fromCache = true,
        responseFingerprint = summary.sha256
    )

    private fun unsupported(message: String): ProviderResult.Failure = ProviderResult.Failure(
        ProviderError(ProviderErrorKind.NOT_SUPPORTED, message)
    )
}

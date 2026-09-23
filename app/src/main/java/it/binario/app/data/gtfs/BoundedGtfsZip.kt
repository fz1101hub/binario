package it.binario.app.data.gtfs

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Limiti del parser usato nei percorsi runtime dei data pack embedded.
 *
 * I valori accettano gli asset verificati inclusi nella release (il maggiore è
 * AMT Genova, poco sopra 103 MiB non compressi e 1.355.184 stop_times) ma
 * impediscono che un feed malformato, un zip bomb o un indice inatteso possa
 * far crescere la memoria senza limite.
 */
data class GtfsCompactIndexLimits(
    val maxEntries: Int = 64,
    val maxEntryBytes: Long = 128L * 1024L * 1024L,
    val maxTotalUncompressedBytes: Long = 128L * 1024L * 1024L,
    val maxCsvLineLength: Int = 32_768,
    val maxAgencies: Int = 10_000,
    val maxStops: Int = 300_000,
    val maxRoutes: Int = 100_000,
    val maxTrips: Int = 150_000,
    val maxCalendars: Int = 100_000,
    val maxCalendarDateRows: Int = 500_000,
    val maxStopTimeRows: Int = 1_500_000,
    val maxRouteCandidateTrips: Int = 150_000,
    val maxServicesPerPack: Int = 256
) {
    init {
        require(maxEntries > 0)
        require(maxEntryBytes > 0)
        require(maxTotalUncompressedBytes >= maxEntryBytes)
        require(maxCsvLineLength > 0)
        require(maxAgencies > 0)
        require(maxStops > 0)
        require(maxRoutes > 0)
        require(maxTrips > 0)
        require(maxCalendars > 0)
        require(maxCalendarDateRows > 0)
        require(maxStopTimeRows > 0)
        require(maxRouteCandidateTrips > 0)
        require(maxServicesPerPack > 0)
    }
}

/** Il feed ha oltrepassato un limite runtime prima che possa causare pressione di memoria. */
class GtfsCompactIndexLimitException(message: String) : IllegalArgumentException(message)

/**
 * Scansiona una volta uno ZIP GTFS senza creare una tabella in memoria. Le
 * callback ricevono un lettore CSV lazy; al ritorno il resto della entry viene
 * drenato attraverso lo stesso contatore, quindi i limiti valgono anche per
 * file non necessari al caso d'uso corrente.
 */
internal class BoundedGtfsZipScanner(
    private val limits: GtfsCompactIndexLimits
) {
    fun scan(
        stream: InputStream,
        wantedTables: Set<String>,
        onTable: (String, GtfsCsvRows) -> Unit
    ) {
        var totalUncompressedBytes = 0L
        var entryCount = 0
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entryCount += 1
                    if (entryCount > limits.maxEntries) {
                        throw GtfsCompactIndexLimitException("Il data pack GTFS supera il numero massimo di file")
                    }
                    if (entry.name.contains("..")) {
                        throw GtfsCompactIndexLimitException("Nome file GTFS non valido")
                    }
                    val tableName = entry.name.substringAfterLast('/')
                    val bounded = BoundedEntryInputStream(zip, limits.maxEntryBytes) { consumed ->
                        totalUncompressedBytes += consumed
                        if (totalUncompressedBytes > limits.maxTotalUncompressedBytes) {
                            throw GtfsCompactIndexLimitException("Il data pack GTFS supera il limite non compresso")
                        }
                    }
                    if (tableName in wantedTables) {
                        onTable(tableName, GtfsCsvRows(bounded, limits.maxCsvLineLength))
                    }
                    drain(bounded)
                    zip.closeEntry()
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (input.read(buffer) >= 0) {
            // Il BoundedEntryInputStream contabilizza e applica i limiti.
        }
    }
}

/** CSV a consumo: una riga viene tenuta solo per la durata della callback. */
internal class GtfsCsvRows(
    input: InputStream,
    private val maxLineLength: Int
) {
    private val reader = BoundedLineReader(input, maxLineLength)
    val header: List<String> = reader.readLine()?.let(::parseGtfsCsvLine).orEmpty()

    fun forEachRow(maxRows: Int, consumer: (List<String>) -> Unit): Int {
        var rowCount = 0
        while (true) {
            val line = reader.readLine() ?: return rowCount
            if (line.isBlank()) continue
            rowCount += 1
            if (rowCount > maxRows) {
                throw GtfsCompactIndexLimitException("La tabella GTFS supera il limite di righe")
            }
            consumer(parseGtfsCsvLine(line))
        }
    }
}

/**
 * Diversamente da BufferedReader.readLine(), interrompe la lettura prima di
 * allocare una String per una riga arbitrariamente lunga.
 */
private class BoundedLineReader(input: InputStream, private val maxLineLength: Int) {
    private val reader = InputStreamReader(input, StandardCharsets.UTF_8)

    fun readLine(): String? {
        val result = StringBuilder()
        var sawCharacter = false
        while (true) {
            val value = reader.read()
            if (value < 0) return if (sawCharacter) result.toString() else null
            sawCharacter = true
            val character = value.toChar()
            if (character == '\n') return result.toString()
            if (character == '\r') continue
            if (result.length >= maxLineLength) {
                throw GtfsCompactIndexLimitException("Riga CSV GTFS troppo lunga")
            }
            result.append(character)
        }
    }
}

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
        if (consumed > maxBytes) {
            throw GtfsCompactIndexLimitException("Una entry GTFS supera il limite non compresso")
        }
        onRead(bytes)
    }
}

internal fun parseGtfsCsvLine(line: String): List<String> {
    val values = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        when (val character = line[index]) {
            '"' -> if (quoted && line.getOrNull(index + 1) == '"') {
                current.append('"')
                index += 1
            } else {
                quoted = !quoted
            }
            ',' -> if (quoted) current.append(character) else {
                values += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
        index += 1
    }
    values += current.toString()
    return values
}

internal fun List<String>.gtfsValue(index: Int): String = getOrNull(index)?.trim().orEmpty()
internal fun List<String>.gtfsRawValue(index: Int): String = getOrNull(index).orEmpty()

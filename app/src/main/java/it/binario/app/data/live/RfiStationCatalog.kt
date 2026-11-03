package it.binario.app.data.live

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.io.IOException
import java.text.Normalizer
import java.time.Clock
import java.util.Locale

/** Official monitor catalogue: exact names/aliases only, with ambiguous matches rejected. */
internal class RfiStationCatalog(
    private val fetcher: RfiMonitorFetcher,
    private val clock: Clock
) {
    private val mutex = Mutex()
    private var names: Map<String, Set<Int>> = emptyMap()
    private var refreshAfter = 0L

    suspend fun placeId(stationNames: List<String>): Int? = mutex.withLock {
        if (clock.millis() >= refreshAfter) {
            try {
                val parsed = parse(fetcher.fetch(URL))
                if (parsed.isEmpty()) throw IOException("Catalogo stazioni RFI vuoto")
                names = parsed
                refreshAfter = clock.millis() + 24 * 60 * 60_000L
            } catch (_: IOException) {
                refreshAfter = clock.millis() + 60_000L
            }
        }
        stationNames.flatMap { names[key(it)].orEmpty() }.distinct().singleOrNull()
    }

    companion object {
        const val URL = "https://iechub.rfi.it/ArriviPartenze"

        internal fun parse(html: String): Map<String, Set<Int>> = Jsoup.parse(html)
            .select("select#ElencoLocalita option[value]")
            .mapNotNull { option ->
                val id = option.attr("value").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                key(option.text()).takeIf(String::isNotBlank)?.let { it to id }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

        private fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .uppercase(Locale.ROOT).replace("S. ", "SAN ")
            .replace(Regex("[^A-Z0-9]"), "")
    }
}

package it.binario.app.ui.common

import it.binario.app.data.Station
import java.util.Locale

/** A route for a header: [short] for the eye ("Roma → Torino"), [full] for screen readers and the body. */
internal data class RouteHeadline(val short: String, val full: String)

/**
 * Short city form of station names for the headers of the render ("Roma → Torino" instead of "Roma Termini →
 * Torino Porta Nuova" wrapping on two lines).
 *
 * A station's own city label from the catalogue wins when it is the leading word(s) of the name ("Roma" for "Roma
 * Termini"). Otherwise the shortener is deliberately conservative: it only removes the designator of a main station
 * ("Centrale", "Termini", "Porta Nuova", "S. M. Novella", "Santa Lucia", "Piazza Principe", "Hbf") from the end of
 * the name, so "Roma Tiburtina" or "Venezia Mestre" stay whole. The full names are kept whenever the short form
 * would be empty or ambiguous: both ends in the same city ("Roma Termini → Roma Ostiense", "Milano Centrale →
 * Milano Porta Garibaldi").
 */
internal object StationShortNames {
    private val designators = listOf(
        "Centrale", "C.le", "Termini", "Porta Nuova", "P. Nuova", "P.Nuova",
        "S. M. Novella", "S.M. Novella", "S.M.N.", "SMN", "Santa Maria Novella",
        "Santa Lucia", "S. Lucia", "Piazza Principe", "P. Principe", "P.Principe",
        "Hbf", "Hauptbahnhof"
    )
    private val designatorPattern = Regex(
        "^(.+?)\\s+(" + designators.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")$",
        RegexOption.IGNORE_CASE
    )

    /** The city part of [stationName] without a main-station designator, or null when it carries none. */
    fun cityOf(stationName: String): String? {
        val trimmed = stationName.trim()
        val city = designatorPattern.matchEntire(trimmed)?.groupValues?.get(1)?.trim()?.trimEnd(',', '-', '–')?.trim()
            ?: return null
        return city.takeIf { it.count(Char::isLetter) >= 2 }
    }

    /**
     * Short label of one station: [city] when the catalogue gives one that starts the name, else the name without
     * a main-station designator, else the name itself.
     */
    fun shortName(name: String, city: String? = null): String {
        val trimmed = name.trim()
        val catalogueCity = city?.trim()?.takeIf { candidate ->
            candidate.count(Char::isLetter) >= 2 && !candidate.equals(trimmed, ignoreCase = true) && startsWithWords(trimmed, candidate)
        }
        return catalogueCity ?: cityOf(trimmed) ?: trimmed
    }

    fun route(origin: Station, destination: Station): RouteHeadline =
        route(origin.name, destination.name, origin.city, destination.city)

    fun route(origin: String, destination: String, originCity: String? = null, destinationCity: String? = null): RouteHeadline {
        val fullOrigin = origin.trim()
        val fullDestination = destination.trim()
        val full = "$fullOrigin → $fullDestination"
        val shortOrigin = shortName(fullOrigin, originCity)
        val shortDestination = shortName(fullDestination, destinationCity)
        val sameCity = shortOrigin.equals(shortDestination, ignoreCase = true) ||
            (shortOrigin != fullOrigin && startsWithWords(fullDestination, shortOrigin)) ||
            (shortDestination != fullDestination && startsWithWords(fullOrigin, shortDestination))
        val short = if (sameCity || shortOrigin.isBlank() || shortDestination.isBlank()) full else "$shortOrigin → $shortDestination"
        return RouteHeadline(short = short, full = full)
    }

    /** [name] begins with the whole words of [prefix] ("Roma Ostiense" with "Roma", not "Romano" with "Roma"). */
    private fun startsWithWords(name: String, prefix: String): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        val lowerPrefix = prefix.lowercase(Locale.ROOT)
        if (!lowerName.startsWith(lowerPrefix)) return false
        if (lowerName.length == lowerPrefix.length) return true
        val next = lowerName[lowerPrefix.length]
        return !next.isLetterOrDigit()
    }
}

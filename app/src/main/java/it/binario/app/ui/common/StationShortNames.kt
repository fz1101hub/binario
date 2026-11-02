package it.binario.app.ui.common

import java.util.Locale

/** A route for a header: [short] for the eye ("Roma → Torino"), [full] for screen readers and the body. */
internal data class RouteHeadline(val short: String, val full: String)

/**
 * Short city form of station names for the headers of the render ("Roma → Torino"). The station catalogue has no
 * separate city field, so the shortener is deliberately conservative: it only removes a known designator of a main
 * station ("Centrale", "Termini", "Porta Nuova", "S. M. Novella", "Hbf", ...) from the end of the name and keeps the
 * full name whenever the result would be empty, too short, or the same on both ends ("Roma Termini → Roma Ostiense").
 */
internal object StationShortNames {
    private val designators = listOf(
        "Centrale", "C.le", "Termini", "Tiburtina", "Ostiense", "Trastevere",
        "Porta Nuova", "Porta Susa", "Porta Garibaldi", "Garibaldi", "Rogoredo", "Lambrate", "Cadorna",
        "S. M. Novella", "S.M. Novella", "Santa Maria Novella", "Rifredi", "Campo di Marte", "Campo Marte",
        "Santa Lucia", "S. Lucia", "Brignole", "Piazza Principe", "P. Principe", "Mergellina",
        "Hbf", "Hauptbahnhof"
    )
    private val pattern = Regex(
        "^(.+?)\\s+(" + designators.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")$",
        RegexOption.IGNORE_CASE
    )

    /** The city part of [stationName], or null when the name carries no known designator. */
    fun cityOf(stationName: String): String? {
        val trimmed = stationName.trim()
        val city = pattern.matchEntire(trimmed)?.groupValues?.get(1)?.trim()?.trimEnd(',', '-', '–') ?: return null
        return city.takeIf { it.count(Char::isLetter) >= 2 }
    }

    fun route(origin: String, destination: String): RouteHeadline {
        val full = "${origin.trim()} → ${destination.trim()}"
        val shortOrigin = cityOf(origin) ?: origin.trim()
        val shortDestination = cityOf(destination) ?: destination.trim()
        val ambiguous = shortOrigin.lowercase(Locale.ROOT) == shortDestination.lowercase(Locale.ROOT)
        val short = if (ambiguous || shortOrigin.isBlank() || shortDestination.isBlank()) full else "$shortOrigin → $shortDestination"
        return RouteHeadline(short = short, full = full)
    }
}

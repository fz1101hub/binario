package it.binario.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * Decides whether a stop name published by an operator (for example RFI's "Firenze S.M.N." or
 * "MILANO C.LE") names the given catalogue station.
 *
 * The whole name must match, token by token: "Cisterna di Latina" never names "Latina" and
 * "Roma Tiburtina" never names "Roma". Only the abbreviations railway timetables actually use are
 * expanded: one- or two-letter prefixes ("S.", "M.", "P."), runs of initials ("SMN"), and a few
 * two-part forms ("C.LE", "M.MA", "P.ZA", "V.LE"). Italian linking words ("di", "del", ...) and
 * the "(Tutte le stazioni)" suffix are ignored, and junk catalogue aliases (codes, one-letter
 * tokens) never take part. This replaces the bidirectional-substring matching that let a short
 * name or alias match any longer stop (Opus audit N-M2).
 */
internal object RailStopNameMatcher {
    private val combiningMarks = Regex("\\p{M}+")
    private val separators = Regex("[^A-Z0-9]+")
    private val allStationsSuffix = Regex("\\(\\s*TUTTE\\s+LE\\s+STAZIONI\\s*\\)")
    private val fillers = setOf("DI", "DEL", "DELLA", "DELLE", "DEI", "DEGLI", "DE", "D", "IN", "SU", "SUL", "SULLA", "AL", "ALLA", "E")
    private val twoPartAbbreviations = mapOf(
        ("C" to "LE") to "CENTRALE",
        ("M" to "MA") to "MARITTIMA",
        ("P" to "ZA") to "PIAZZA",
        ("V" to "LE") to "VIALE"
    )

    fun matches(station: Station, published: String): Boolean {
        val publishedTokens = tokens(published)
        if (publishedTokens.isEmpty()) return false
        return candidateNames(station).any { name -> sequenceMatches(publishedTokens, tokens(name)) }
    }

    private fun candidateNames(station: Station): List<String> =
        listOf(station.name) + station.aliases.filter { alias ->
            !alias.contains('_') && !alias.startsWith("@") && tokens(alias).sumOf { it.length } >= 3
        }

    internal fun tokens(value: String): List<String> {
        val upper = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .uppercase(Locale.ROOT)
            .replace(allStationsSuffix, " ")
        val raw = upper.split(separators).filter(String::isNotBlank)
        val joined = mutableListOf<String>()
        var index = 0
        while (index < raw.size) {
            val pair = raw.getOrNull(index + 1)?.let { twoPartAbbreviations[raw[index] to it] }
            if (pair != null) {
                joined += pair
                index += 2
            } else {
                joined += raw[index]
                index += 1
            }
        }
        return joined.filterNot(fillers::contains)
    }

    private fun sequenceMatches(published: List<String>, station: List<String>): Boolean {
        if (station.isEmpty()) return false
        if (published == station) return true
        // Abbreviations only bridge the rest of a name: at least one real word must agree,
        // so "P" alone never names "Piacenza".
        if (published.none { it.length >= 3 && it in station }) return false
        val memo = HashMap<Pair<Int, Int>, Boolean>()
        fun match(i: Int, j: Int): Boolean = memo.getOrPut(i to j) {
            when {
                i == published.size && j == station.size -> true
                i == published.size || j == station.size -> false
                else -> {
                    val p = published[i]
                    val s = station[j]
                    (p == s && match(i + 1, j + 1)) ||
                        (isAbbreviationOf(p, s) && match(i + 1, j + 1)) ||
                        (isAbbreviationOf(s, p) && match(i + 1, j + 1)) ||
                        initialsSpan(p, station, j)?.let { span -> match(i + 1, j + span) } == true ||
                        initialsSpan(s, published, i)?.let { span -> match(i + span, j + 1) } == true
                }
            }
        }
        return match(0, 0)
    }

    /** "S" for "SANTA", "PT" never: only one- or two-letter alphabetic prefixes count. */
    private fun isAbbreviationOf(short: String, full: String): Boolean =
        short.length in 1..2 && short.all(Char::isLetter) && full.length > short.length && full.startsWith(short)

    /** "SMN" spanning "SANTA MARIA NOVELLA" (or "S M NOVELLA"): returns how many tokens it covers. */
    private fun initialsSpan(initials: String, tokens: List<String>, from: Int): Int? {
        if (initials.length !in 2..4 || !initials.all(Char::isLetter)) return null
        if (from + initials.length > tokens.size) return null
        val covered = tokens.subList(from, from + initials.length)
        return initials.length.takeIf { covered.indices.all { covered[it].first() == initials[it] } && covered.any { it.length > 1 } }
    }
}

/** Strict, whole-name stop matching for operator timetables; see [RailStopNameMatcher]. */
fun Station.isNamedBy(publishedStopName: String): Boolean = RailStopNameMatcher.matches(this, publishedStopName)

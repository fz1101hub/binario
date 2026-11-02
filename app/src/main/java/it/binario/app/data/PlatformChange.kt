package it.binario.app.data

import java.util.Locale

/**
 * A platform the source moved away from the timetable: [actual] is where the train now calls, [scheduled] the
 * platform of the timetable. It exists only when the same source publishes both and they really differ, so a
 * missing timetable platform never reads as a change.
 */
data class PlatformChange(val actual: String, val scheduled: String) {
    companion object {
        fun of(actual: String?, scheduled: String?): PlatformChange? {
            val now = actual?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val planned = scheduled?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return if (normalize(now) == normalize(planned)) null else PlatformChange(now, planned)
        }

        /** "05" and "5", "7 Ovest" and "7  OVEST" name the same platform. */
        internal fun normalize(value: String): String = value.trim()
            .uppercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .replace(LEADING_ZEROS, "")

        private val WHITESPACE = Regex("\\s+")
        private val LEADING_ZEROS = Regex("^0+(?=\\d)")
    }
}

/**
 * Templates of the platform labels, read from the resources at the UI boundary so that the wording stays testable
 * on the JVM: [short] "Bin. %1$s", [changedShort] "Bin. %1$s (era %2$s)", [spoken] "Binario %1$s",
 * [changedSpoken] "Binario cambiato: %1$s, previsto %2$s", [unavailable] "Binario non disponibile".
 */
data class PlatformLabelStrings(
    val locale: Locale,
    val short: String,
    val changedShort: String,
    val spoken: String,
    val changedSpoken: String,
    val unavailable: String
)

/** The visible and the spoken form of a platform, with the timetable platform when the source moved the train. */
object PlatformLabels {
    /** "Bin. 7 (era 5)", "Bin. 7", or null when the source gives no platform. */
    fun short(actual: String?, scheduled: String?, strings: PlatformLabelStrings): String? {
        val shown = actual?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val change = PlatformChange.of(shown, scheduled)
        return if (change != null) strings.changedShort.format(strings.locale, change.actual, change.scheduled)
        else strings.short.format(strings.locale, shown)
    }

    /** "Binario cambiato: 7, previsto 5", "Binario 7" or "Binario non disponibile". */
    fun spoken(actual: String?, scheduled: String?, strings: PlatformLabelStrings): String {
        val shown = actual?.trim()?.takeIf(String::isNotEmpty) ?: return strings.unavailable
        val change = PlatformChange.of(shown, scheduled)
        return if (change != null) strings.changedSpoken.format(strings.locale, change.actual, change.scheduled)
        else strings.spoken.format(strings.locale, shown)
    }
}

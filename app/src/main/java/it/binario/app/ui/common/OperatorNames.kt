package it.binario.app.ui.common

import it.binario.app.data.RailOperator
import java.util.Locale

/**
 * The operator of a station board entry as a name ("Trenitalia", "FART/SSIF"), never its internal identifier
 * ("trenitalia", "fart-ssif") or a numeric owner code of the German timetables: those are left out.
 */
internal object OperatorNames {
    private val KNOWN: Map<String, RailOperator> = mapOf(
        "trenitalia" to RailOperator.TRENITALIA_LONG_DISTANCE,
        "italo" to RailOperator.ITALO,
        "trenord" to RailOperator.TRENORD,
        "sbb" to RailOperator.SBB,
        "tilo" to RailOperator.TILO,
        "bls" to RailOperator.BLS,
        "rhb" to RailOperator.RHB,
        "fart-ssif" to RailOperator.FART_SSIF,
        "sncf" to RailOperator.SNCF,
        "oebb" to RailOperator.OEBB,
        "db" to RailOperator.DB,
        "westbahn" to RailOperator.WESTBAHN,
        "european-sleeper" to RailOperator.EUROPEAN_SLEEPER
    )

    /** The display name of [id]; an unknown identifier is kept only when it already reads as a name. */
    fun displayName(id: String): String? {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return null
        KNOWN[trimmed.lowercase(Locale.ROOT)]?.let { return it.shortLabel }
        return trimmed.takeIf { value -> value.any(Char::isUpperCase) || value.contains(' ') }
    }
}

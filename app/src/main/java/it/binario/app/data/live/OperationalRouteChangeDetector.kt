package it.binario.app.data.live

import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind

/**
 * Conservative parser for textual operational notices exposed next to a train.
 * It only raises a route-change flag when explicit railway wording is present;
 * station labels are extracted only when the provider writes them explicitly.
 */
internal object OperationalRouteChangeDetector {
    private val diverted = Regex(
        "\\b(?:instradat[oa]|deviat[oa]|deviazione|percorso modificato|variazione di percorso|via alternativa)\\b",
        RegexOption.IGNORE_CASE
    )
    private val skipped = Regex(
        "\\b(?:non effettua fermata|salta(?:ta)? la fermata|fermate soppresse|non ferma a)\\b",
        RegexOption.IGNORE_CASE
    )
    private val added = Regex(
        "\\b(?:ferma anche a|fermata straordinaria|fermata aggiuntiva|effettua fermata anche)\\b",
        RegexOption.IGNORE_CASE
    )
    private val shortTurn = Regex(
        "\\b(?:limitato a|termina la corsa a|termine corsa a|non prosegue oltre)\\b",
        RegexOption.IGNORE_CASE
    )
    private val replacement = Regex(
        "\\b(?:servizio sostitutivo|bus sostitutiv|autobus sostitutiv)\\b",
        RegexOption.IGNORE_CASE
    )

    private val viaCapture = Regex(
        "\\b(?:deviat[oa]|instradat[oa])\\s+via\\s+([A-Za-zÀ-ÿ0-9 .'-]{2,60}?)(?=\\s+(?:per|con|a causa|dalle|dalla|e poi)\\b|[.;,]|$)",
        RegexOption.IGNORE_CASE
    )
    private val shortTurnCapture = Regex(
        "\\b(?:limitato a|termina la corsa a|termine corsa a)\\s+([A-Za-zÀ-ÿ0-9 .'-]{2,60}?)(?=\\s+(?:per|a causa|dalle|dalla|e non)\\b|[.;,]|$)",
        RegexOption.IGNORE_CASE
    )
    private val skippedCapture = Regex(
        "\\b(?:non effettua fermata a|non ferma a|salta(?:ta)? la fermata di?)\\s+([A-Za-zÀ-ÿ0-9 .'-]{2,60}?)(?=\\s+(?:per|a causa|dalle|dalla|e)\\b|[.;,]|$)",
        RegexOption.IGNORE_CASE
    )
    private val addedCapture = Regex(
        "\\b(?:ferma anche a|fermata aggiuntiva a|effettua fermata anche a)\\s+([A-Za-zÀ-ÿ0-9 .'-]{2,60}?)(?=\\s+(?:per|a causa|dalle|dalla|e)\\b|[.;,]|$)",
        RegexOption.IGNORE_CASE
    )

    fun detect(text: String): OperationalRouteChange? {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return null

        val kind = when {
            replacement.containsMatchIn(normalized) -> RouteChangeKind.REPLACEMENT_SERVICE
            shortTurn.containsMatchIn(normalized) -> RouteChangeKind.SHORT_TURN
            skipped.containsMatchIn(normalized) -> RouteChangeKind.SKIPPED_STOPS
            added.containsMatchIn(normalized) -> RouteChangeKind.ADDED_STOPS
            diverted.containsMatchIn(normalized) -> RouteChangeKind.DIVERTED
            else -> return null
        }

        return OperationalRouteChange(
            kind = kind,
            summary = normalized.take(360),
            skippedStopLabels = skippedCapture.find(normalized)?.groupValues?.getOrNull(1)?.cleanLabel()?.let(::listOf).orEmpty(),
            addedStopLabels = addedCapture.find(normalized)?.groupValues?.getOrNull(1)?.cleanLabel()?.let(::listOf).orEmpty(),
            viaStopLabels = viaCapture.find(normalized)?.groupValues?.getOrNull(1)?.cleanLabel()?.let(::listOf).orEmpty(),
            replacementDestinationLabel = shortTurnCapture.find(normalized)?.groupValues?.getOrNull(1)?.cleanLabel()
        )
    }

    private fun String.cleanLabel(): String = trim().trimEnd('.', ',', ';', ':').take(60)
}

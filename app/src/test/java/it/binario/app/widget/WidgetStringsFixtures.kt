package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.TrainCategory
import java.util.Locale

/** Hand-written catalogues: the widget must render whatever language it is given, never Italian literals. */
internal fun italianWidgetStrings() = WidgetStrings(
    locale = Locale.ITALIAN,
    statusOnTime = "OK",
    statusDelayed = "+%1\$d",
    statusCancelled = "SOPP.",
    statusDemo = "DEMO",
    statusScheduled = "PROGRAM.",
    qualityScore = "Q%1\$d",
    serviceAccessibility = "Operatore %1\$s. %2\$s %3\$s verso %4\$s. Partenza prevista %5\$s. %6\$s. %7\$s.",
    delayMinutes = "+%1\$d min",
    scheduledUnverified = "Stato operativo non verificato",
    platform = "Binario %1\$s",
    platformUnavailable = "Binario non disponibile",
    dataAccessibility = "Provider %1\$s. %2\$s. %3\$s. %4\$s",
    qualityScoreAccessibility = ", punteggio qualità %1\$d su 100",
    warningsAccessibility = ". Avvisi: %1\$s",
    confidenceLabels = mapOf(DataConfidence.INFRASTRUCTURE_PRIMARY to "INFRASTRUTTURA", DataConfidence.STATIC_ONLY to "PROGRAMMATO").withDefault { it.name },
    freshnessLabels = mapOf(Freshness.AGING to "IN AGGIORNAMENTO", Freshness.FRESH to "AGGIORNATO").withDefault { it.name },
    warningLabels = mapOf(SnapshotWarning.SOURCE_CONFLICT to "CONFLITTO FONTI").withDefault { it.name },
    statusLabels = mapOf(ServiceStatus.ON_TIME to "In orario", ServiceStatus.DELAYED to "In ritardo", ServiceStatus.CANCELLED to "Soppresso"),
    categoryLabels = mapOf(TrainCategory.REGIONALE to "Regionale").withDefault { it.name },
    directionLabels = mapOf(Direction.OUTBOUND to "Andata", Direction.RETURN to "Ritorno"),
    confidenceAccessibleLabels = mapOf(DataConfidence.INFRASTRUCTURE_PRIMARY to "Fonte primaria del gestore dell’infrastruttura").withDefault { it.name },
    freshnessAccessibleLabels = mapOf(Freshness.AGING to "Dati in aggiornamento").withDefault { it.name },
    qualityBandLabels = mapOf(DataQualityBand.RELIABLE to "Dato affidabile").withDefault { it.name },
    warningAccessibleLabels = mapOf(SnapshotWarning.SOURCE_CONFLICT to "Fonti in conflitto").withDefault { it.name }
)

internal fun englishWidgetStrings() = WidgetStrings(
    locale = Locale.ENGLISH,
    statusOnTime = "OK",
    statusDelayed = "+%1\$d",
    statusCancelled = "CANC.",
    statusDemo = "DEMO",
    statusScheduled = "SCHED.",
    qualityScore = "Q%1\$d",
    serviceAccessibility = "Operator %1\$s. %2\$s %3\$s to %4\$s. Expected departure %5\$s. %6\$s. %7\$s.",
    delayMinutes = "+%1\$d min",
    scheduledUnverified = "Operational status unverified",
    platform = "Platform %1\$s",
    platformUnavailable = "Platform unavailable",
    dataAccessibility = "Source %1\$s. %2\$s. %3\$s. %4\$s",
    qualityScoreAccessibility = ", quality score out of 100: %1\$d",
    warningsAccessibility = ". Warnings: %1\$s",
    confidenceLabels = mapOf(DataConfidence.INFRASTRUCTURE_PRIMARY to "INFRASTRUCTURE", DataConfidence.STATIC_ONLY to "SCHEDULED").withDefault { it.name },
    freshnessLabels = mapOf(Freshness.AGING to "UPDATING", Freshness.FRESH to "UPDATED").withDefault { it.name },
    warningLabels = mapOf(SnapshotWarning.SOURCE_CONFLICT to "SOURCE CONFLICT").withDefault { it.name },
    statusLabels = mapOf(ServiceStatus.ON_TIME to "On time", ServiceStatus.DELAYED to "Delayed", ServiceStatus.CANCELLED to "Cancelled"),
    categoryLabels = mapOf(TrainCategory.REGIONALE to "Regional").withDefault { it.name },
    directionLabels = mapOf(Direction.OUTBOUND to "Outbound", Direction.RETURN to "Return"),
    confidenceAccessibleLabels = mapOf(DataConfidence.INFRASTRUCTURE_PRIMARY to "Infrastructure manager primary source").withDefault { it.name },
    freshnessAccessibleLabels = mapOf(Freshness.AGING to "Data updating").withDefault { it.name },
    qualityBandLabels = mapOf(DataQualityBand.RELIABLE to "Reliable data").withDefault { it.name },
    warningAccessibleLabels = mapOf(SnapshotWarning.SOURCE_CONFLICT to "Sources in conflict").withDefault { it.name }
)

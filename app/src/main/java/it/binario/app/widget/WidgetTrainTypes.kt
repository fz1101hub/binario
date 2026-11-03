package it.binario.app.widget

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.ServiceKind
import java.util.Locale

/**
 * Maps the categories, service kinds and operators the sources already publish onto the train type
 * groups of the widget settings. Every function returns null when the data do not say which group a
 * train belongs to: such a train is never hidden by an exclusion.
 */
object WidgetTrainTypes {
    /** Operators outside Italy, or running cross-border international services. */
    private val internationalOperators = setOf(
        RailOperator.SBB, RailOperator.BLS, RailOperator.RHB, RailOperator.SNCF, RailOperator.OEBB,
        RailOperator.DB, RailOperator.WESTBAHN, RailOperator.EUROPEAN_SLEEPER
    )
    private val internationalOperatorIds = setOf("sbb", "bls", "rhb", "sncf", "oebb", "db", "westbahn", "european-sleeper")
    private val highSpeedCodes = setOf("FR", "FA", "FB", "AV", "ES", "ESC")
    private val internationalCodes = setOf("EC", "EN", "NJ", "RJ", "RJX", "ICE", "TGV", "EST")
    private val intercityCodes = setOf("IC", "ICN")
    private val busCodes = setOf("BUS", "B", "SEV", "EV")
    private val regionalCode = Regex("(R|RE|REX|RV|REG|RB|TER|S|SFM|FL|FM|MET)\\d{0,3}")

    /** Route widget rows: category and operator of the route snapshot, plus a replacement-service flag. */
    fun groupOf(category: TrainCategory, operator: RailOperator, replacementService: Boolean = false): TrainTypeGroup? = when {
        replacementService -> TrainTypeGroup.REPLACEMENT_BUS
        operator == RailOperator.ITALO || category == TrainCategory.AV_ITALO -> TrainTypeGroup.ITALO
        operator in internationalOperators -> TrainTypeGroup.INTERNATIONAL
        category == TrainCategory.FRECCE -> TrainTypeGroup.HIGH_SPEED
        category == TrainCategory.INTERCITY -> TrainTypeGroup.INTERCITY_NIGHT
        category == TrainCategory.REGIONALE || category == TrainCategory.REGIONALE_VELOCE ||
            category == TrainCategory.TRENORD_REGIO || category == TrainCategory.METROPOLITANO -> TrainTypeGroup.REGIONAL_SUBURBAN
        else -> null
    }

    fun isReplacement(routeChange: RouteChangeKind?): Boolean = routeChange == RouteChangeKind.REPLACEMENT_SERVICE

    /** Station board rows: service kind, operator id and line label of the board entry, and its status. */
    fun groupOf(kind: ServiceKind, operatorId: String?, lineLabel: String?, status: RunStatus?): TrainTypeGroup? {
        val operator = operatorId?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val code = lineLabel?.uppercase(Locale.ROOT)?.replace(" ", "").orEmpty()
        val prefix = code.takeWhile(Char::isLetter)
        return when {
            status == RunStatus.REPLACED_BY_BUS || kind == ServiceKind.REPLACEMENT_BUS || prefix in busCodes -> TrainTypeGroup.REPLACEMENT_BUS
            operator == "italo" -> TrainTypeGroup.ITALO
            operator in internationalOperatorIds -> TrainTypeGroup.INTERNATIONAL
            kind == ServiceKind.HIGH_SPEED -> TrainTypeGroup.HIGH_SPEED
            kind == ServiceKind.INTERCITY || kind == ServiceKind.NIGHT -> TrainTypeGroup.INTERCITY_NIGHT
            kind == ServiceKind.REGIONAL || kind == ServiceKind.SUBURBAN -> TrainTypeGroup.REGIONAL_SUBURBAN
            prefix in internationalCodes -> TrainTypeGroup.INTERNATIONAL
            prefix in highSpeedCodes -> TrainTypeGroup.HIGH_SPEED
            prefix in intercityCodes -> TrainTypeGroup.INTERCITY_NIGHT
            code.isNotEmpty() && regionalCode.matches(code) -> TrainTypeGroup.REGIONAL_SUBURBAN
            else -> null
        }
    }

    /** A row is hidden only when its group is known and excluded. */
    fun isHidden(group: TrainTypeGroup?, excluded: Set<TrainTypeGroup>): Boolean = group != null && group in excluded
}

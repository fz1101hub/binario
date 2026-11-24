package it.binario.app.widget

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.ServiceKind
import it.binario.app.shared.SharedData
import java.util.Locale

/**
 * Maps the categories, service kinds and operators the sources already publish onto the train type
 * groups of the widget settings. Every function returns null when the data do not say which group a
 * train belongs to: such a train is never hidden by an exclusion. The codes and operators are
 * contracts/shared-data/v1/widgets-v1.json; the board rule's cases, which the iPhone runs too, are
 * contracts/shared-rules/v1/widgets-v1.json (`typeGroups`).
 */
object WidgetTrainTypes {
    /** Operators outside Italy, or running cross-border international services. */
    private val internationalOperatorIds = SharedData.Widgets.INTERNATIONAL_OPERATOR_IDS
    /** The operators of [internationalOperatorIds], by their identifier ("european-sleeper" is EUROPEAN_SLEEPER). */
    private val internationalOperators = RailOperator.entries.filter { operatorId(it) in internationalOperatorIds }.toSet()
    private val highSpeedCodes = SharedData.Widgets.HIGH_SPEED_CODES
    private val internationalCodes = SharedData.Widgets.INTERNATIONAL_CODES
    private val intercityCodes = SharedData.Widgets.INTERCITY_CODES
    private val busCodes = SharedData.Widgets.BUS_CODES
    private val regionalCodes = SharedData.Widgets.REGIONAL_CODES

    /** The identifier of an operator in the shared table: the constant's name in lower case, hyphenated. */
    internal fun operatorId(operator: RailOperator): String = operator.name.lowercase(Locale.ROOT).replace('_', '-')

    /** A regional code: one of [regionalCodes] and at most `REGIONAL_MAXIMUM_DIGITS` digits («R», «RE12», «S11»). */
    private fun isRegionalCode(code: String): Boolean {
        val letters = code.takeWhile { it in 'A'..'Z' }
        val digits = code.substring(letters.length)
        return letters in regionalCodes && digits.length <= SharedData.Widgets.REGIONAL_MAXIMUM_DIGITS && digits.all { it in '0'..'9' }
    }

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
            operator == SharedData.Widgets.ITALO_OPERATOR_ID -> TrainTypeGroup.ITALO
            operator in internationalOperatorIds -> TrainTypeGroup.INTERNATIONAL
            kind == ServiceKind.HIGH_SPEED -> TrainTypeGroup.HIGH_SPEED
            kind == ServiceKind.INTERCITY || kind == ServiceKind.NIGHT -> TrainTypeGroup.INTERCITY_NIGHT
            kind == ServiceKind.REGIONAL || kind == ServiceKind.SUBURBAN -> TrainTypeGroup.REGIONAL_SUBURBAN
            prefix in internationalCodes -> TrainTypeGroup.INTERNATIONAL
            prefix in highSpeedCodes -> TrainTypeGroup.HIGH_SPEED
            prefix in intercityCodes -> TrainTypeGroup.INTERCITY_NIGHT
            code.isNotEmpty() && isRegionalCode(code) -> TrainTypeGroup.REGIONAL_SUBURBAN
            else -> null
        }
    }

    /** A row is hidden only when its group is known and excluded. */
    fun isHidden(group: TrainTypeGroup?, excluded: Set<TrainTypeGroup>): Boolean = group != null && group in excluded
}

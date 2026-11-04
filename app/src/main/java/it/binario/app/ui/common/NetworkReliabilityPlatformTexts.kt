package it.binario.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import it.binario.app.R
import it.binario.app.data.reliability.UsualPlatform
import it.binario.app.data.reliability.WeekdayRemark
import java.time.DayOfWeek

/**
 * The texts of the usual platform and of the weekday remarks of the network statistics, as functions of their numbers:
 * resolved from the app resources on screen ([rememberUsualPlatformStrings]) and from the resource files in the tests.
 */
class UsualPlatformStrings(
    /** «Di solito al binario 12 (8 volte su 10)» (plural on the second number). */
    val usualPlatform: (name: String, count: Int, total: Int) -> String,
    /** «Il venerdì», «La domenica»: the subject of a weekday remark. */
    val weekday: (day: DayOfWeek) -> String,
    /** «Il venerdì è in orario 2 volte su 5, peggio della media» (plural on the on-time count). */
    val weekdayWorse: (weekday: String, onTime: Int, total: Int) -> String,
    /** «La domenica è in orario 5 volte su 5, meglio della media». */
    val weekdayBetter: (weekday: String, onTime: Int, total: Int) -> String
)

/** The lines of the usual platform and of the weekday remarks, in the style of the other statistics lines. */
object UsualPlatformLines {
    fun usualPlatform(platform: UsualPlatform, strings: UsualPlatformStrings): NetworkReliabilityLine =
        NetworkReliabilityLine(listOf(strings.usualPlatform(platform.name, platform.count, platform.observed)))

    fun weekday(remark: WeekdayRemark, strings: UsualPlatformStrings): NetworkReliabilityLine {
        val day = strings.weekday(remark.weekday)
        val text = if (remark.better) strings.weekdayBetter(day, remark.onTime, remark.samples) else strings.weekdayWorse(day, remark.onTime, remark.samples)
        return NetworkReliabilityLine(listOf(text))
    }
}

@Composable
internal fun rememberUsualPlatformStrings(): UsualPlatformStrings {
    val resources = LocalResources.current
    return remember(resources) {
        UsualPlatformStrings(
            usualPlatform = { name, count, total -> resources.getQuantityString(R.plurals.reliability_usual_platform, count, name, count, total) },
            weekday = { day -> resources.getString(weekdaySubject(day)) },
            weekdayWorse = { day, count, total -> resources.getQuantityString(R.plurals.reliability_weekday_worse, count, day, count, total) },
            weekdayBetter = { day, count, total -> resources.getQuantityString(R.plurals.reliability_weekday_better, count, day, count, total) }
        )
    }
}

/** The resource of the subject of a weekday remark («Il lunedì» … «La domenica»). */
internal fun weekdaySubject(day: DayOfWeek): Int = when (day) {
    DayOfWeek.MONDAY -> R.string.reliability_weekday_monday
    DayOfWeek.TUESDAY -> R.string.reliability_weekday_tuesday
    DayOfWeek.WEDNESDAY -> R.string.reliability_weekday_wednesday
    DayOfWeek.THURSDAY -> R.string.reliability_weekday_thursday
    DayOfWeek.FRIDAY -> R.string.reliability_weekday_friday
    DayOfWeek.SATURDAY -> R.string.reliability_weekday_saturday
    DayOfWeek.SUNDAY -> R.string.reliability_weekday_sunday
}

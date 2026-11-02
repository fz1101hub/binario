package it.binario.app.ui.common

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Day-and-month date patterns in the order each language writes them. The Italian-style "d MMM" reads
 * "2 11月" in Japanese, so the CJK languages get their own patterns; everything else keeps the existing one.
 */
object LocalizedDates {
    fun dayMonth(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofPattern(dayMonthPattern(locale), locale)

    fun weekdayDayMonth(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(weekdayDayMonthPattern(locale), locale)

    fun dayMonthPattern(locale: Locale): String = when (locale.language) {
        "ja", "zh" -> "M月d日"
        "ko" -> "M월 d일"
        else -> "d MMM"
    }

    fun weekdayDayMonthPattern(locale: Locale): String = when (locale.language) {
        "ja" -> "M月d日(EEE)"
        "zh" -> "M月d日 EEE"
        "ko" -> "M월 d일 (EEE)"
        else -> "EEE d MMM"
    }
}

package it.binario.app.ui.common

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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

    /** "martedì 3 novembre", "11月3日 火曜日": the spoken date of the planner's date buttons. */
    fun weekdayDayMonthLong(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(weekdayDayMonthLongPattern(locale), locale)

    fun weekdayDayMonthLongPattern(locale: Locale): String = when (locale.language) {
        "ja", "zh" -> "M月d日 EEEE"
        "ko" -> "M월 d일 EEEE"
        else -> "EEEE d MMMM"
    }

    /** The short numeric date of the language ("03/11/26", "2026/11/03", "11/3/26"), with the year. */
    fun shortDate(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)

    /** "2 nov 2026, 17:32", "2026年11月2日 17:32": a moment with its date and year. */
    fun dateTime(locale: Locale): DateTimeFormatter = DateTimeFormatter.ofPattern(dateTimePattern(locale), locale)

    fun dateTimePattern(locale: Locale): String = when (locale.language) {
        "ja", "zh" -> "y年M月d日 HH:mm"
        "ko" -> "y년 M월 d일 HH:mm"
        else -> "d MMM yyyy, HH:mm"
    }

    /** "lun 2 nov, 17:32", "11月2日(月) 17:32": a moment of this year with its weekday. */
    fun weekdayDayMonthTime(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(weekdayDayMonthPattern(locale) + (if (locale.language in CJK) " " else ", ") + "HH:mm", locale)

    private val CJK = setOf("ja", "zh", "ko")
}

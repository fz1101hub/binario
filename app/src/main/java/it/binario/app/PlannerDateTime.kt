package it.binario.app

import it.binario.app.data.RailTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PlannerDateTime {
    fun dateLabel(value: String, locale: Locale, fallback: String): String =
        runCatching {
            LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
        }.getOrDefault(fallback)

    fun timeLabel(value: String, locale: Locale, fallback: String): String =
        runCatching {
            LocalTime.parse(value).format(DateTimeFormatter.ofPattern("HH:mm", locale))
        }.getOrDefault(fallback)

    fun dateOrToday(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now(RailTime.defaultZone))
    fun timeOrNow(value: String): LocalTime = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.now(RailTime.defaultZone))

    fun isReturnAfterDeparture(
        departureDate: LocalDate,
        departureTime: LocalTime,
        returnDate: LocalDate,
        returnTime: LocalTime
    ): Boolean = LocalDateTime.of(returnDate, returnTime)
        .isAfter(LocalDateTime.of(departureDate, departureTime))
}

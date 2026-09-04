package it.binario.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PlannerDateTime {
    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)

    fun dateLabel(value: String): String = runCatching { LocalDate.parse(value).format(dateFormatter) }.getOrDefault("Data da scegliere")
    fun timeLabel(value: String): String = runCatching { LocalTime.parse(value).format(timeFormatter) }.getOrDefault("Ora da scegliere")
    fun dateOrToday(value: String): LocalDate = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
    fun timeOrNow(value: String): LocalTime = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.now())

    fun isReturnAfterDeparture(
        departureDate: LocalDate,
        departureTime: LocalTime,
        returnDate: LocalDate,
        returnTime: LocalTime
    ): Boolean = LocalDateTime.of(returnDate, returnTime)
        .isAfter(LocalDateTime.of(departureDate, departureTime))
}

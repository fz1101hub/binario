package it.binario.app.data.weather

import it.binario.app.data.TrainService
import java.time.LocalDate
import java.time.LocalTime

/** The station and day a train detail's weather refers to: the last stop shown, on its arrival date. */
data class DestinationWeatherTarget(val stationName: String, val arrivalDate: LocalDate) {
    companion object {
        fun of(service: TrainService, today: LocalDate): DestinationWeatherTarget? {
            val last = service.stops.lastOrNull()
            val station = last?.station?.trim()?.takeIf { it.isNotEmpty() } ?: service.destination.trim().takeIf { it.isNotEmpty() } ?: return null
            val departure = service.scheduledDepartureDateTime
            val departureDate = departure?.toLocalDate() ?: today
            val departureTime = departure?.toLocalTime() ?: parse(service.scheduledDeparture)
            val arrivalTime = parse(last?.scheduled)
            // An arrival clock time earlier than the departure means the train arrives after midnight.
            val date = if (arrivalTime != null && departureTime != null && arrivalTime < departureTime) departureDate.plusDays(1) else departureDate
            return DestinationWeatherTarget(station, date)
        }

        private fun parse(value: String?): LocalTime? {
            val parts = value?.trim()?.split(':') ?: return null
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: return null
            return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
    }
}

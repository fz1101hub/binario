package it.binario.app.ui.common

import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import java.time.LocalTime
import java.util.Locale

/**
 * Category groups of the filter chips ("Regionali / Intercity / Frecce"). Italo high-speed trains are a group
 * of their own: they are not Frecce. Only groups present in the data are offered.
 */
internal enum class ServiceGroup {
    REGIONAL, INTERCITY, FRECCE, ITALO, OTHER;

    companion object {
        fun of(category: TrainCategory): ServiceGroup = when (category) {
            TrainCategory.REGIONALE, TrainCategory.REGIONALE_VELOCE, TrainCategory.TRENORD_REGIO, TrainCategory.METROPOLITANO -> REGIONAL
            TrainCategory.INTERCITY -> INTERCITY
            TrainCategory.FRECCE -> FRECCE
            TrainCategory.AV_ITALO -> ITALO
            TrainCategory.OTHER -> OTHER
        }

        /** The groups to offer as chips: none unless the services belong to at least two groups. */
        fun chipsFor(services: List<TrainService>): List<ServiceGroup> {
            val present = services.map { of(it.trainCategory) }.toSet()
            return if (present.size < 2) emptyList() else entries.filter { it in present }
        }
    }
}

/** Order of the route results: neutral departure order (the default) or shortest scheduled journey first. */
internal enum class ResultOrder { DEPARTURE, FASTEST }

/** Times of a journey as published by the timetable: departure, arrival at the last stop and duration. */
internal object JourneyTimes {
    private const val DAY_MINUTES = 24 * 60

    /**
     * Scheduled arrival at the journey's last stop. Route results carry the stops from the searched origin to
     * the searched destination, so this is the arrival where the traveller gets off.
     */
    fun arrival(service: TrainService): String? =
        service.stops.drop(1).lastOrNull()?.scheduled?.trim()?.takeIf { parse(it) != null }

    /** Minutes between scheduled departure and arrival; an arrival not after the departure is the next day. */
    fun durationMinutes(service: TrainService): Int? {
        val departure = parse(service.scheduledDeparture) ?: return null
        val arrival = arrival(service)?.let(::parse) ?: return null
        var minutes = arrival - departure
        if (minutes <= 0) minutes += DAY_MINUTES
        return minutes.takeIf { it in 1 until DAY_MINUTES }
    }

    /** "4 h 25 min", "45 min": hour and minute symbols read the same in the five app languages. */
    fun durationLabel(minutes: Int): String = when {
        minutes < 60 -> String.format(Locale.ROOT, "%d min", minutes)
        minutes % 60 == 0 -> String.format(Locale.ROOT, "%d h", minutes / 60)
        else -> String.format(Locale.ROOT, "%d h %d min", minutes / 60, minutes % 60)
    }

    fun ordered(services: List<TrainService>, order: ResultOrder): List<TrainService> = when (order) {
        ResultOrder.DEPARTURE -> services
        // Stable: equal durations keep the neutral departure order; unknown durations go last.
        ResultOrder.FASTEST -> services.sortedWith(compareBy(nullsLast()) { durationMinutes(it) })
    }

    fun filtered(services: List<TrainService>, group: ServiceGroup?): List<TrainService> =
        if (group == null) services else services.filter { ServiceGroup.of(it.trainCategory) == group }

    private fun parse(value: String): Int? = runCatching {
        LocalTime.parse(value.trim()).let { it.hour * 60 + it.minute }
    }.getOrNull()
}

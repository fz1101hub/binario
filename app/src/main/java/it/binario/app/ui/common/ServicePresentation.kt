package it.binario.app.ui.common

import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import it.binario.app.R
import java.time.LocalTime

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

    /**
     * The compact duration of the cards in the app language: "4h 25min" (Italian, as in the render), "4 ч 25 мин",
     * "4時間25分", "4 घं 25 मि", from the duration_* resources.
     */
    fun durationLabel(minutes: Int, resources: Resources): String = durationLabel(
        minutes,
        hoursMinutes = { hours, rest -> resources.getString(R.string.duration_hours_minutes, hours, rest) },
        hoursOnly = { hours -> resources.getString(R.string.duration_hours, hours) },
        minutesOnly = { rest -> resources.getString(R.string.duration_minutes, rest) }
    )

    /** [minutes] as hours and minutes, whole hours or minutes alone, with the given templates. */
    fun durationLabel(minutes: Int, hoursMinutes: (Int, Int) -> String, hoursOnly: (Int) -> String, minutesOnly: (Int) -> String): String = when {
        minutes < 60 -> minutesOnly(minutes)
        minutes % 60 == 0 -> hoursOnly(minutes / 60)
        else -> hoursMinutes(minutes / 60, minutes % 60)
    }

    fun filtered(services: List<TrainService>, group: ServiceGroup?): List<TrainService> =
        if (group == null) services else services.filter { ServiceGroup.of(it.trainCategory) == group }

    private fun parse(value: String): Int? = runCatching {
        LocalTime.parse(value.trim()).let { it.hour * 60 + it.minute }
    }.getOrNull()
}

/** [JourneyTimes.durationLabel] in the app language of the composition. */
@Composable
@ReadOnlyComposable
internal fun compactDurationLabel(minutes: Int): String = JourneyTimes.durationLabel(minutes, LocalResources.current)

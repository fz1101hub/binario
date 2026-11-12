package it.binario.app.journeys

import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.StaticDayDepartures
import it.binario.app.data.journey.StaticDeparture
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Ultimo di oggi" and "Non ci sono più treni oggi" on the boards, from the static timetable of the packs
 * ([EmbeddedGtfsJourneyProvider.staticDayDepartures]). A board row (live or timetable) is the last of the day when its
 * number and scheduled minute are those of a last departure; delays never move the mark.
 */
object LastOfDayMarks {
    /**
     * The day a board may mark, or null: today, when the board lists the trains from now or a search of today. A
     * search planned for another day lists that day's trains, and «Ultimo di oggi» or «Non ci sono più treni oggi:
     * primo domani alle …» would speak of a different day than the one on screen.
     */
    fun markedDay(today: LocalDate, followsCurrentTime: Boolean, searchDate: LocalDate): LocalDate? =
        today.takeIf { followsCurrentTime || searchDate == today }

    fun isLast(day: StaticDayDepartures?, publicNumber: String?, scheduledDeparture: LocalDateTime?): Boolean {
        if (day == null || scheduledDeparture == null) return false
        val number = publicNumber?.let(EmbeddedGtfsJourneyProvider::runNumberKey) ?: return false
        val minute = scheduledDeparture.withSecond(0).withNano(0)
        return day.lastByTerminus.any { last ->
            last.departure == minute && last.publicNumber?.let(EmbeddedGtfsJourneyProvider::runNumberKey) == number
        }
    }

    /**
     * The first departure of the next day when every last departure of [day] is before [now] (or the day has none):
     * "Non ci sono più treni oggi: primo domani alle …". Null while a train of the day is still to leave, or without data.
     */
    fun firstNextDay(day: StaticDayDepartures?, now: LocalDateTime): StaticDeparture? {
        val first = day?.firstNextDay ?: return null
        return first.takeIf { day.lastByTerminus.none { !it.departure.isBefore(now) } }
    }
}

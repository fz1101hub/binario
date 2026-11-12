package it.binario.app.ui.common

import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService

/**
 * What the time column of a row, its compact status, the result card and the service badge say about a run: one rule
 * for all of them. The minutes when there are some; «In ritardo» without a number when a source says late but the
 * expected time equals the timetable (the board reconciler can take the status from one board and the time from
 * another); «In orario» only when nothing says late. Before, the time column decided by the minutes and the status by
 * the status field, so the same train read «in orario» beside «+0 min» (audit2 android-ui-5).
 */
internal sealed interface ServiceRunLabel {
    /** No verified status: the timetable. */
    data object Scheduled : ServiceRunLabel

    /** The board shows the train late without minutes (RFI «RITARDO»), no verified status. */
    data object LateMinutesUnpublished : ServiceRunLabel

    data object Cancelled : ServiceRunLabel

    data class LateBy(val minutes: Int) : ServiceRunLabel

    /** A verified late status with no minutes to show. */
    data object Late : ServiceRunLabel

    data object OnTime : ServiceRunLabel

    companion object {
        fun of(service: TrainService): ServiceRunLabel = when {
            !service.operationalStatusVerified && service.delayMinutesUnpublished -> LateMinutesUnpublished
            !service.operationalStatusVerified -> Scheduled
            service.status == ServiceStatus.CANCELLED -> Cancelled
            service.delayMinutes > 0 -> LateBy(service.delayMinutes)
            service.status == ServiceStatus.DELAYED -> Late
            else -> OnTime
        }

        /** The status TalkBack names for a verified run: late whenever the row shows minutes of delay, as [of]. */
        fun spokenStatus(service: TrainService): ServiceStatus = when {
            service.status == ServiceStatus.CANCELLED -> ServiceStatus.CANCELLED
            service.delayMinutes > 0 -> ServiceStatus.DELAYED
            else -> service.status
        }
    }
}

package it.binario.app.data.notices

import it.binario.app.shared.SharedData
/**
 * Pages the app only opens in the browser on an explicit tap; the app never requests them itself.
 *
 * The MIT strike calendar page stays a link in the notices area. Its RSS feed is read separately
 * ([it.binario.app.data.live.MitStrikeRssAdapter], "Scioperi in programma"); when that feed fails or its format
 * changes, this link is all the area shows about strikes.
 */
object RailExternalLinks {
    val MIT_STRIKE_CALENDAR = SharedData.NoticesSources.LINKS.mitStrikeCalendar
}

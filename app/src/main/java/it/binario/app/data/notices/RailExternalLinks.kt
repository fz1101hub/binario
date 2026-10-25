package it.binario.app.data.notices

/**
 * Pages the app only opens in the browser on an explicit tap; the app never requests them itself.
 *
 * The MIT strike calendar has no machine-readable feed reachable from the app's test infrastructure (requests from
 * cloud runners time out and the format is unverified), so it is offered as a link instead of being parsed.
 */
object RailExternalLinks {
    const val MIT_STRIKE_CALENDAR = "https://scioperi.mit.gov.it/mit2/public/scioperi"
}

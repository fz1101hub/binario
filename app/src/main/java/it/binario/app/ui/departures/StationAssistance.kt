package it.binario.app.ui.departures

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import it.binario.app.R
import it.binario.app.SourceSectionCard
import it.binario.app.SourceSectionNote
import it.binario.app.data.Station
import it.binario.app.links.OfficialLinks
import it.binario.app.openExternalLink

/**
 * «Assistenza Sala Blu» for a station of the Italian network: RFI's assistance page and the two national Sala Blu
 * numbers. Links only: the page opens in the browser and a number in the dialer on a tap; nothing is requested.
 */
@Composable
internal fun SalaBluAssistanceSection(station: Station, modifier: Modifier = Modifier) {
    if (!OfficialLinks.SalaBlu.appliesTo(station)) return
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    SourceSectionCard(title = stringResource(R.string.station_sala_blu_title), modifier = modifier.testTag("station-sala-blu")) {
        SourceSectionNote(stringResource(R.string.station_sala_blu_description))
        TextButton(onClick = { openExternalLink(uriHandler, OfficialLinks.SalaBlu.PAGE) }) {
            Text(stringResource(R.string.station_sala_blu_page))
        }
        TextButton(onClick = { dial(context, OfficialLinks.SalaBlu.TOLL_FREE) }) {
            Text(stringResource(R.string.station_sala_blu_call_toll_free, OfficialLinks.SalaBlu.TOLL_FREE))
        }
        TextButton(onClick = { dial(context, OfficialLinks.SalaBlu.NATIONAL) }) {
            Text(stringResource(R.string.station_sala_blu_call_national, OfficialLinks.SalaBlu.NATIONAL))
        }
    }
}

/** Opens the dialer with [number] filled in (no call is placed, no permission needed); a device without one does nothing. */
private fun dial(context: Context, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(OfficialLinks.SalaBlu.dialUri(number))))
    } catch (_: ActivityNotFoundException) {
        // A tablet without telephony: the number stays readable on the button.
    }
}

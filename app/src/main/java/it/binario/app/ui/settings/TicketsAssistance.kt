package it.binario.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.links.OfficialLinks
import it.binario.app.links.OperatorLinkKind
import it.binario.app.openExternalLink
import it.binario.app.ui.common.NavigationRow
import it.binario.app.ui.common.RowDivider

/**
 * "Biglietti e assistenza": for every operator Binario shows timetables of, the official pages to buy, change,
 * subscribe, take a bike, ask for help and claim a refund. Links only: they open in the browser on a tap, and the
 * app sells nothing and sends nothing to these sites.
 */
@Composable
internal fun TicketsAssistanceSettings() {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.tickets_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OfficialLinks.ALL.forEach { operator ->
        RowDivider()
        Text(
            operator.name,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        operator.links.forEach { link ->
            NavigationRow(
                title = stringResource(link.kind.labelRes()),
                // The official domain the page opens on, so the destination is clear before the tap.
                detail = runCatching { java.net.URI(link.url).host }.getOrNull() ?: link.url,
                icon = link.kind.icon,
                onClick = { openExternalLink(uriHandler, link.url) },
                modifier = Modifier.testTag("tickets-${operator.id}-${link.kind.name.lowercase(java.util.Locale.ROOT)}"),
                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew
            )
        }
    }
}

private fun OperatorLinkKind.labelRes(): Int = when (this) {
    OperatorLinkKind.BUY -> R.string.tickets_link_buy
    OperatorLinkKind.CHANGE -> R.string.tickets_link_change
    OperatorLinkKind.SUBSCRIPTIONS -> R.string.tickets_link_subscriptions
    OperatorLinkKind.BIKES -> R.string.tickets_link_bikes
    OperatorLinkKind.CUSTOMER_CARE -> R.string.tickets_link_customer_care
    OperatorLinkKind.REFUNDS -> R.string.tickets_link_refunds
    OperatorLinkKind.STRIKES -> R.string.tickets_link_strikes
    OperatorLinkKind.WEBSITE -> R.string.tickets_link_website
}

private val OperatorLinkKind.icon: ImageVector
    get() = when (this) {
        OperatorLinkKind.BUY -> Icons.Default.ConfirmationNumber
        OperatorLinkKind.CHANGE -> Icons.Default.EditCalendar
        OperatorLinkKind.SUBSCRIPTIONS -> Icons.Default.CardMembership
        OperatorLinkKind.BIKES -> Icons.AutoMirrored.Filled.DirectionsBike
        OperatorLinkKind.CUSTOMER_CARE -> Icons.Default.SupportAgent
        OperatorLinkKind.REFUNDS -> Icons.Default.Payments
        OperatorLinkKind.STRIKES -> Icons.Default.EventBusy
        OperatorLinkKind.WEBSITE -> Icons.Default.Language
    }

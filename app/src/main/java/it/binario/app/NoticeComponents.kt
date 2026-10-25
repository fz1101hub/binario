package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import it.binario.app.data.notices.NoticePublisher
import it.binario.app.data.notices.RailNotice
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Card used by the public-source sections (notices, weather), in the style of the detail's trip card. */
@Composable
internal fun SourceSectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            content()
        }
    }
}

@Composable
internal fun SourceSectionNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val NOTICE_ZONE: ZoneId = ZoneId.of("Europe/Rome")

/** Short, locale-formatted publication time of a notice, in the Italian railway time zone. */
internal fun noticeTimestamp(instant: Instant, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(instant.atZone(NOTICE_ZONE))

/** Brand of a notice publisher; a proper name, therefore not translated. */
internal fun NoticePublisher.brand(): String = when (this) {
    NoticePublisher.RFI -> "RFI"
    NoticePublisher.TRENITALIA -> "Trenitalia"
    NoticePublisher.TRENORD -> "Trenord"
}

/** Opens an http(s) link in the browser chosen by the system; any other scheme is ignored. */
internal fun openExternalLink(uriHandler: UriHandler, url: String) {
    val link = url.trim().takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) } ?: return
    runCatching { uriHandler.openUri(link) }
}

/** One network notice: title, collapsible plain-text body, publisher and time, and the publisher's page. */
@Composable
internal fun RailNoticeItem(notice: RailNotice) {
    var expanded by rememberSaveable(notice.id) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(notice.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        notice.body?.let { body ->
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis
            )
            if (body.length > COLLAPSED_CHARACTERS || body.count { it == '\n' } >= COLLAPSED_LINES) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.notices_collapse else R.string.notices_expand))
                }
            }
        }
        SourceSectionNote(
            notice.publishedAt?.let { stringResource(R.string.notices_published_by_at, notice.publisher.brand(), noticeTimestamp(it, locale)) }
                ?: stringResource(R.string.notices_published_by, notice.publisher.brand())
        )
        notice.link?.let { link ->
            TextButton(onClick = { openExternalLink(uriHandler, link) }) { Text(stringResource(R.string.notices_open_source)) }
        }
    }
}

private const val COLLAPSED_LINES = 3
private const val COLLAPSED_CHARACTERS = 180

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
import androidx.compose.runtime.remember
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import it.binario.app.data.notices.NoticeLine
import it.binario.app.data.notices.NoticeLinks
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

/** Text whose http(s) URLs open in the browser on tap; everything else stays plain. */
@Composable
internal fun LinkifiedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    fontWeight: FontWeight? = null
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            NoticeLinks.segments(text).forEach { segment ->
                when (segment) {
                    is NoticeLinks.Segment.Plain -> append(segment.text)
                    is NoticeLinks.Segment.Link -> withLink(
                        LinkAnnotation.Url(
                            segment.url,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                            linkInteractionListener = { openExternalLink(uriHandler, segment.url) }
                        )
                    ) { append(segment.text) }
                }
            }
        }
    }
    Text(annotated, modifier = modifier, style = style, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** "D034 · LAVENO-VARESE-SARONNO-MILANO" for one line, "D034, D010, D004" for several. */
@Composable
internal fun noticeLinesLabel(lines: List<NoticeLine>, allLines: Boolean = false): String? = when {
    allLines -> stringResource(R.string.notices_trenord_all_lines)
    lines.isEmpty() -> null
    lines.size == 1 && lines.single().name.isBlank() -> stringResource(R.string.notices_trenord_line_code, lines.single().code)
    lines.size == 1 -> stringResource(R.string.notices_trenord_line, lines.single().code, lines.single().name)
    else -> stringResource(R.string.notices_trenord_lines, lines.take(MAX_LINE_CODES).joinToString(", ") { it.code } +
        if (lines.size > MAX_LINE_CODES) ", …" else "")
}

/** One network notice: title, collapsible plain-text body, publisher and time, and the publisher's page. */
@Composable
internal fun RailNoticeItem(notice: RailNotice, showLines: Boolean = true) {
    var expanded by rememberSaveable(notice.id) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinkifiedText(notice.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (showLines) noticeLinesLabel(notice.lines)?.let { SourceSectionNote(it) }
        notice.body?.let { body ->
            LinkifiedText(
                body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES
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
private const val MAX_LINE_CODES = 6

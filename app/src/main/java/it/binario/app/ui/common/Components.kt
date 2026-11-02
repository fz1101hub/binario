package it.binario.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.BinarioTheme
import it.binario.app.R

// Building blocks of the Binario 2.0 render: the deep green header that continues under the first card,
// white cards on the light page, segmented controls and tonal pills.

/**
 * True from 600 dp: the content sits beside the green side navigation on light panes, so the screen
 * headers are drawn flat (dark text on the page) instead of on the green header.
 */
internal val LocalWideLayout = staticCompositionLocalOf { false }

/** How far the first card or sheet of a screen reaches into the green header. */
internal val HeaderOverlap = 32.dp

/** Side margin of the render: cards and rows keep 16 dp from the window edges. */
internal val ScreenMargin = 16.dp

/**
 * The green header of a screen, below the top bar: optional overline, the title (a heading), an optional
 * subtitle with a [trailing] element at its end, then [content] (chips, tabs, segmented controls).
 * On wide windows the same header is drawn flat on the page.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    subtitle: String? = null,
    bottomPadding: Dp = 20.dp,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val wide = LocalWideLayout.current
    HeaderBlock(modifier, bottomPadding = bottomPadding) {
        overline?.let { HeaderOverline(it) }
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium
        )
        if (subtitle != null || trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    subtitle.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = headerMutedColor()
                )
                trailing?.invoke()
            }
        }
        content()
    }
}

/** The header background (green on phones, the page on wide windows) with its text colour, for custom header rows. */
@Composable
internal fun HeaderBlock(
    modifier: Modifier = Modifier,
    topPadding: Dp = 4.dp,
    bottomPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val wide = LocalWideLayout.current
    val colors = BinarioTheme.colors
    val background = if (wide) MaterialTheme.colorScheme.background else colors.header
    val foreground = if (wide) MaterialTheme.colorScheme.onBackground else colors.onHeader
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Column(
            modifier
                .fillMaxWidth()
                .background(background)
                .padding(start = ScreenMargin, end = ScreenMargin, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/** Overline of a header ("TRATTA ATTIVA", the train name): mint on the green header, primary when flat. */
@Composable
internal fun HeaderOverline(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (LocalWideLayout.current) MaterialTheme.colorScheme.primary else BinarioTheme.colors.onHeaderMuted,
        letterSpacing = 0.8.sp
    )
}

/** Secondary text on the header: mint on green, the muted text colour when flat. */
@Composable
internal fun headerMutedColor(): Color =
    if (LocalWideLayout.current) MaterialTheme.colorScheme.onSurfaceVariant else BinarioTheme.colors.onHeaderMuted

/**
 * Paints the header green behind the top [overlap] of its content, so that a card or a sheet appears to
 * overlap the bottom of the header as in the render. Flat on wide windows.
 */
@Composable
internal fun HeaderOverlapBox(
    modifier: Modifier = Modifier,
    overlap: Dp = HeaderOverlap,
    content: @Composable BoxScope.() -> Unit
) {
    val color = if (LocalWideLayout.current) Color.Transparent else BinarioTheme.colors.header
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                if (color != Color.Transparent) {
                    drawRect(color, size = Size(size.width, overlap.toPx().coerceAtMost(size.height)))
                }
            },
        content = content
    )
}

/** The top of a light sheet with rounded corners that continues the page under the green header. */
@Composable
internal fun HeaderSheetTop(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HeaderOverlapBox(overlap = 28.dp) {
        Column(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 16.dp),
            content = content
        )
    }
}

/** A white card of the render: 20 dp corners, a soft shadow, 16 dp from the window edges. */
@Composable
internal fun BinarioCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp
    ) {
        Column(content = content)
    }
}

/** Section title between cards ("Tratte recenti", "Altre corse"). */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = ScreenMargin, vertical = 10.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

/**
 * Segmented control with equal segments ("Viaggio | Treno | Stazione", "Chiaro | Scuro | Sistema"). The
 * selected segment is announced as selected and drawn filled; [onHeader] draws it on the green header.
 */
@Composable
internal fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    onHeader: Boolean = false,
    tagOf: (T) -> String? = { null }
) {
    val colors = BinarioTheme.colors
    val flatHeader = onHeader && LocalWideLayout.current
    val track = when {
        flatHeader -> colors.segmentTrack
        onHeader -> colors.headerTonal
        else -> colors.segmentTrack
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(track, RoundedCornerShape(50))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val (container, content) = when {
                isSelected && onHeader && !flatHeader -> colors.headerSelected to colors.onHeaderSelected
                isSelected -> colors.segmentSelected to colors.onSegmentSelected
                onHeader && !flatHeader -> Color.Transparent to colors.onHeaderTonal
                else -> Color.Transparent to colors.onSegmentTrack
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(container, RoundedCornerShape(50))
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .then(tagOf(option)?.let { Modifier.testTag(it) } ?: Modifier)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    color = content,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** A small rounded label (status, "Diretto", "Binario —"); the text always states what the colour means. */
@Composable
internal fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: ImageVector? = null
) {
    Surface(modifier = modifier, color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Tonal button of the render ("Dettagli →", "+ Nuova tratta", "Modifica"). */
@Composable
internal fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onHeader: Boolean = false
) {
    val colors = BinarioTheme.colors
    val headerStyle = onHeader && !LocalWideLayout.current
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
            containerColor = if (headerStyle) colors.headerTonal else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (headerStyle) colors.onHeaderTonal else MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        leadingIcon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold)
        trailingIcon?.let {
            Spacer(Modifier.width(8.dp))
            Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** The Binario train-front glyph and the wordmark, as in the planner header and the side navigation. */
@Composable
internal fun BinarioLogo(color: Color, modifier: Modifier = Modifier, showWordmark: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            BinarioGlyph,
            contentDescription = if (showWordmark) null else stringResource(R.string.app_brand),
            tint = color,
            modifier = Modifier.size(28.dp)
        )
        if (showWordmark) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.app_brand),
                color = color,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * A row of a settings or personal group: leading icon in a tonal circle, title, optional detail, chevron.
 * The whole row is one 56 dp target announced with its title and detail.
 */
@Composable
internal fun NavigationRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (trailing != null) trailing() else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thin divider between the rows of a card group, aligned with the row text. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The train-front glyph of the launcher icon (res/drawable/ic_binario.xml) without its square, tinted by the caller. */
private val BinarioGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "BinarioGlyph",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 48f,
        viewportHeight = 48f
    ).addPath(
        pathData = addPathNodes(
            "M12,8c-2.2,0 -4,1.8 -4,4v20c0,2.2 1.8,4 4,4h4l-3,4v2h5l3,-4h6l3,4h5v-2l-3,-4h4c2.2,0 4,-1.8 4,-4v-20" +
                "c0,-2.2 -1.8,-4 -4,-4zM16,13h7v7h-7zM25,13h7v7h-7zM14,25h20v4h-20zM16,32a3,3 0,1 1,0 -6a3,3 0,0 1,0 6z" +
                "M32,32a3,3 0,1 1,0 -6a3,3 0,0 1,0 6z"
        ),
        fill = SolidColor(Color.White)
    ).build()
}

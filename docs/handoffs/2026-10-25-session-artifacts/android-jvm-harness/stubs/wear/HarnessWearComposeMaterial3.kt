// Harness compile stubs for androidx.wear.compose.material3 (Wear Compose is Google-Maven-only, unreachable here).
// Text, MaterialTheme, Card, Button and the AppScaffold/ScreenScaffold/TimeText/ScrollIndicator scaffolding are
// modelled on top of the JetBrains Compose foundation jars; the scaffolds keep the real parameter shapes but do no
// round-screen layout (nothing is composed on the JVM).
@file:Suppress("unused", "PackageDirectoryMismatch", "UNUSED_PARAMETER")

package androidx.wear.compose.material3

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState

class Typography(
    val displayLarge: TextStyle = TextStyle(fontSize = 40.sp),
    val displayMedium: TextStyle = TextStyle(fontSize = 30.sp),
    val displaySmall: TextStyle = TextStyle(fontSize = 24.sp),
    val titleLarge: TextStyle = TextStyle(fontSize = 20.sp),
    val titleMedium: TextStyle = TextStyle(fontSize = 16.sp),
    val titleSmall: TextStyle = TextStyle(fontSize = 14.sp),
    val labelLarge: TextStyle = TextStyle(fontSize = 14.sp),
    val labelMedium: TextStyle = TextStyle(fontSize = 12.sp),
    val labelSmall: TextStyle = TextStyle(fontSize = 10.sp),
    val bodyLarge: TextStyle = TextStyle(fontSize = 16.sp),
    val bodyMedium: TextStyle = TextStyle(fontSize = 14.sp),
    val bodySmall: TextStyle = TextStyle(fontSize = 12.sp),
    val bodyExtraSmall: TextStyle = TextStyle(fontSize = 10.sp),
)

class ColorScheme(
    val primary: Color = Color(0xFFB8F3D7),
    val onPrimary: Color = Color.Black,
    val background: Color = Color.Black,
    val onBackground: Color = Color.White,
    val surfaceContainer: Color = Color(0xFF1C1B1F),
    val onSurface: Color = Color.White,
    val onSurfaceVariant: Color = Color(0xFFB3C8BE),
)

val LocalTypography = staticCompositionLocalOf { Typography() }
val LocalColorScheme = staticCompositionLocalOf { ColorScheme() }
val LocalTextStyle = staticCompositionLocalOf { TextStyle(fontSize = 14.sp) }

object MaterialTheme {
    val typography: Typography
        @Composable @ReadOnlyComposable get() = LocalTypography.current
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = LocalColorScheme.current
}

@Composable
fun MaterialTheme(
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    typography: Typography = MaterialTheme.typography,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalColorScheme provides colorScheme, LocalTypography provides typography, content = content)
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    BasicText(text = text, modifier = modifier, style = style, overflow = overflow, maxLines = maxLines)
}

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.clickable(enabled = enabled, onClick = onClick), content = content)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier.clickable(enabled = enabled, onClick = onClick), content = content)
}

@Composable
fun TimeText(modifier: Modifier = Modifier) {
    // The real one curves the clock along the top of a round screen.
}

@Composable
fun ScrollIndicator(state: ScalingLazyListState, modifier: Modifier = Modifier) {
    // The real one draws the position indicator at the end of the screen.
}

object ScreenScaffoldDefaults {
    /** The real value is a share of the screen size that keeps content clear of the bezel. */
    val contentPadding: PaddingValues
        @Composable get() = PaddingValues(horizontal = 10.dp, vertical = 32.dp)
    val EdgeButtonSpacing: Dp = 16.dp
}

@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    timeText: @Composable () -> Unit = { TimeText() },
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        timeText()
    }
}

@Composable
fun ScreenScaffold(
    scrollState: ScalingLazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ScreenScaffoldDefaults.contentPadding,
    timeText: (@Composable () -> Unit)? = null,
    scrollIndicator: (@Composable BoxScope.() -> Unit)? = {
        ScrollIndicator(scrollState, modifier = Modifier.align(Alignment.CenterEnd))
    },
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    edgeButtonSpacing: Dp = ScreenScaffoldDefaults.EdgeButtonSpacing,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier) {
        content(contentPadding)
        scrollIndicator?.invoke(this)
        edgeButton?.invoke(this)
        timeText?.invoke()
    }
}

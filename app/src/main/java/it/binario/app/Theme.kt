package it.binario.app

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit

enum class AppThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Chiaro"),
    DARK("Scuro")
}

fun appThemeModeFromStoredValue(value: String?): AppThemeMode = runCatching {
    AppThemeMode.valueOf(value.orEmpty())
}.getOrDefault(AppThemeMode.SYSTEM)

/** Local-only visual preference. It contains no travel, account or diagnostic data. */
class ThemeSettings(context: Context) {
    private val preferences = context.getSharedPreferences("binario_visual_settings", Context.MODE_PRIVATE)

    var mode by mutableStateOf(readMode())
        private set

    fun selectMode(value: AppThemeMode) {
        mode = value
        preferences.edit { putString(KEY_THEME_MODE, value.name) }
    }

    private fun readMode(): AppThemeMode = appThemeModeFromStoredValue(
        preferences.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
    )

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
    }
}

// Binario 2.0 palette measured on the approved render (design/BINARIO_2_DESIGN_SYSTEM.md): deep forest green
// header #003A28, filled actions #00703E (white text 6.2:1), tonal #D8F4EA with dark green text (8.1:1), cards
// #FCFCFC on #F4F7F6 and the #12785A timeline (5.3:1 on the cards). The dark scheme keeps the same hues: deep
// green surfaces and a mint primary. Every text pair listed here reaches WCAG AA (4.5:1) or better.
private val LightColors = lightColorScheme(
    primary = Color(0xFF00703E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8F4EA),
    onPrimaryContainer = Color(0xFF00513A),
    inversePrimary = Color(0xFF7FDBB5),
    secondary = Color(0xFF12785A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8F4EA),
    onSecondaryContainer = Color(0xFF00513A),
    tertiary = Color(0xFF99571B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE9D1),
    onTertiaryContainer = Color(0xFF492807),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF17241F),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF17241F),
    surfaceVariant = Color(0xFFE2EEEA),
    onSurfaceVariant = Color(0xFF3F5249),
    surfaceTint = Color(0xFF00703E),
    inverseSurface = Color(0xFF263530),
    inverseOnSurface = Color(0xFFE9F3EF),
    outline = Color(0xFF6F7F78),
    outlineVariant = Color(0xFFC9D8D2),
    scrim = Color(0xFF000000),
    // Container roles: green neutrals between the page and the chips, never the Material baseline violet.
    surfaceBright = Color(0xFFFCFCFC),
    surfaceDim = Color(0xFFD9E2DE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAF9),
    surfaceContainer = Color(0xFFF0F5F3),
    surfaceContainerHigh = Color(0xFFE9F1EE),
    surfaceContainerHighest = Color(0xFFE2EEEA)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FDBB5),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF1E4A3A),
    onPrimaryContainer = Color(0xFFC9F2E1),
    inversePrimary = Color(0xFF00703E),
    secondary = Color(0xFF6FD3A8),
    onSecondary = Color(0xFF003826),
    secondaryContainer = Color(0xFF1E4A3A),
    onSecondaryContainer = Color(0xFFC9F2E1),
    tertiary = Color(0xFFFFC489),
    onTertiary = Color(0xFF4C2604),
    tertiaryContainer = Color(0xFF71400E),
    onTertiaryContainer = Color(0xFFFFE9D1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B1511),
    onBackground = Color(0xFFE3F1EB),
    surface = Color(0xFF14221C),
    onSurface = Color(0xFFDCEAE4),
    surfaceVariant = Color(0xFF233630),
    onSurfaceVariant = Color(0xFFA9BDB4),
    surfaceTint = Color(0xFF7FDBB5),
    inverseSurface = Color(0xFFDCEAE4),
    inverseOnSurface = Color(0xFF1D2B25),
    outline = Color(0xFF8A9E95),
    outlineVariant = Color(0xFF34483F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3C35),
    surfaceDim = Color(0xFF0B1511),
    surfaceContainerLowest = Color(0xFF07100C),
    surfaceContainerLow = Color(0xFF101C17),
    surfaceContainer = Color(0xFF14221C),
    surfaceContainerHigh = Color(0xFF1B2B24),
    surfaceContainerHighest = Color(0xFF233630)
)

/**
 * Brand roles of the render that Material 3 has no slot for: the deep green header and side navigation with
 * their white and mint text, the segmented controls, the selected rail item and the stop timeline.
 */
@Immutable
data class BinarioColors(
    val header: Color,
    val onHeader: Color,
    /** Overlines and subtitles on the header (mint on deep green). */
    val onHeaderMuted: Color,
    /** Chips and unselected tabs drawn on the header. */
    val headerTonal: Color,
    val onHeaderTonal: Color,
    /** The selected tab or segment on the header ("Percorso", "Treni"): white in the render. */
    val headerSelected: Color,
    val onHeaderSelected: Color,
    val segmentSelected: Color,
    val onSegmentSelected: Color,
    /** Track of segmented controls and unselected chips on cards. */
    val segmentTrack: Color,
    val onSegmentTrack: Color,
    val railSelected: Color,
    val onRailSelected: Color,
    /** Unselected labels of the green side navigation. */
    val onRail: Color,
    val timeline: Color
)

private val LightBinarioColors = BinarioColors(
    header = Color(0xFF003A28),
    onHeader = Color(0xFFFFFFFF),
    onHeaderMuted = Color(0xFF8FE3C0),
    headerTonal = Color(0xFF1E5A44),
    onHeaderTonal = Color(0xFFFFFFFF),
    headerSelected = Color(0xFFFCFCFC),
    onHeaderSelected = Color(0xFF003A28),
    segmentSelected = Color(0xFF004A33),
    onSegmentSelected = Color(0xFFFFFFFF),
    segmentTrack = Color(0xFFE2EEEA),
    onSegmentTrack = Color(0xFF1A2E26),
    railSelected = Color(0xFF00523A),
    onRailSelected = Color(0xFFFFFFFF),
    onRail = Color(0xFFCFE3DA),
    timeline = Color(0xFF12785A)
)

private val DarkBinarioColors = BinarioColors(
    header = Color(0xFF0A1F17),
    onHeader = Color(0xFFE3F1EB),
    onHeaderMuted = Color(0xFF8FE3C0),
    headerTonal = Color(0xFF173F31),
    onHeaderTonal = Color(0xFFE3F1EB),
    headerSelected = Color(0xFFC9F2E1),
    onHeaderSelected = Color(0xFF0A1F17),
    segmentSelected = Color(0xFF7FDBB5),
    onSegmentSelected = Color(0xFF0A1F17),
    segmentTrack = Color(0xFF233630),
    onSegmentTrack = Color(0xFFDCEAE4),
    railSelected = Color(0xFF1E4A3A),
    onRailSelected = Color(0xFFE3F1EB),
    onRail = Color(0xFFBFD3CA),
    timeline = Color(0xFF6FD3A8)
)

private val LocalBinarioColors = staticCompositionLocalOf { LightBinarioColors }

/** Access to the brand roles of the current [BinarioTheme]. */
object BinarioTheme {
    val colors: BinarioColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBinarioColors.current
}

/** Clock times use tabular figures, so columns of departures line up without a monospaced font. */
internal val TabularFigures = "tnum"

private val BinarioTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, fontFeatureSettings = TabularFigures),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)
)

private val BinarioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BinarioTheme(mode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalBinarioColors provides if (useDark) DarkBinarioColors else LightBinarioColors) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColors else LightColors,
            typography = BinarioTypography,
            shapes = BinarioShapes,
            content = content
        )
    }
}

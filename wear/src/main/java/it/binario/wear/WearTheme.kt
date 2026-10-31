package it.binario.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Binario 2.0 colour tokens for the watch, defined once for the app, the Tile and future surfaces
 * (design/BINARIO_2_DESIGN_SYSTEM.md, "Scuro" column: watches are always dark).
 *
 * Values are ARGB longs so Compose ([BinarioWearColorScheme]) and ProtoLayout (the Tile, which
 * takes an ARGB int) read the same numbers. The background stays true black for OLED; every text
 * role below keeps WCAG AA (>= 4.5:1) on it and on the card containers, which
 * BinarioWearPaletteTest asserts.
 */
internal object BinarioWearPalette {
    /** Design token "Primary interattivo" (dark). */
    const val PRIMARY = 0xFF9BDD9E

    /** Design token "Sfondo" (dark): the text colour on primary fills. */
    const val ON_PRIMARY = 0xFF0D1710

    /** Design token "Primary container" (dark). */
    const val PRIMARY_CONTAINER = 0xFF205B2A

    /** Design token "Primary container" (light), used as text on the dark container. */
    const val ON_PRIMARY_CONTAINER = 0xFFDDF0DE

    /** Derived: a dimmer primary for secondary emphasis, same hue. */
    const val PRIMARY_DIM = 0xFF7FC283

    const val BACKGROUND = 0xFF000000
    const val ON_BACKGROUND = 0xFFFFFFFF

    /** Design token "Surface" (dark). */
    const val SURFACE_LOW = 0xFF142018

    /** Derived steps above the surface token, so cards stay visible on the true-black background. */
    const val SURFACE = 0xFF1D2C22
    const val SURFACE_HIGH = 0xFF273A2D

    const val ON_SURFACE = 0xFFFFFFFF

    /** Derived: secondary text (status, operator lines); replaces the former teal-grey. */
    const val ON_SURFACE_VARIANT = 0xFFC3D0C4

    const val OUTLINE = 0xFF8A9E8D
    const val OUTLINE_VARIANT = 0xFF3E5243

    /** Design token "Errore" (dark) with the Material error tones that pair with it. */
    const val ERROR = 0xFFFFB4AB
    const val ERROR_DIM = 0xFFE0968E
    const val ON_ERROR = 0xFF690005
    const val ERROR_CONTAINER = 0xFF93000A
    const val ON_ERROR_CONTAINER = 0xFFFFDAD6

    /** Design token "Attenzione" (dark); never the only signal of a state. */
    const val WARNING = 0xFFFFC489
}

/**
 * The Binario design system names one accent hue: the Wear secondary and tertiary roles reuse the
 * primary family so no default Wear hue leaks into components the app may adopt later.
 */
internal val BinarioWearColorScheme = ColorScheme(
    primary = Color(BinarioWearPalette.PRIMARY),
    primaryDim = Color(BinarioWearPalette.PRIMARY_DIM),
    primaryContainer = Color(BinarioWearPalette.PRIMARY_CONTAINER),
    onPrimary = Color(BinarioWearPalette.ON_PRIMARY),
    onPrimaryContainer = Color(BinarioWearPalette.ON_PRIMARY_CONTAINER),
    secondary = Color(BinarioWearPalette.PRIMARY),
    secondaryDim = Color(BinarioWearPalette.PRIMARY_DIM),
    secondaryContainer = Color(BinarioWearPalette.PRIMARY_CONTAINER),
    onSecondary = Color(BinarioWearPalette.ON_PRIMARY),
    onSecondaryContainer = Color(BinarioWearPalette.ON_PRIMARY_CONTAINER),
    tertiary = Color(BinarioWearPalette.PRIMARY),
    tertiaryDim = Color(BinarioWearPalette.PRIMARY_DIM),
    tertiaryContainer = Color(BinarioWearPalette.PRIMARY_CONTAINER),
    onTertiary = Color(BinarioWearPalette.ON_PRIMARY),
    onTertiaryContainer = Color(BinarioWearPalette.ON_PRIMARY_CONTAINER),
    surfaceContainerLow = Color(BinarioWearPalette.SURFACE_LOW),
    surfaceContainer = Color(BinarioWearPalette.SURFACE),
    surfaceContainerHigh = Color(BinarioWearPalette.SURFACE_HIGH),
    onSurface = Color(BinarioWearPalette.ON_SURFACE),
    onSurfaceVariant = Color(BinarioWearPalette.ON_SURFACE_VARIANT),
    outline = Color(BinarioWearPalette.OUTLINE),
    outlineVariant = Color(BinarioWearPalette.OUTLINE_VARIANT),
    background = Color(BinarioWearPalette.BACKGROUND),
    onBackground = Color(BinarioWearPalette.ON_BACKGROUND),
    error = Color(BinarioWearPalette.ERROR),
    errorDim = Color(BinarioWearPalette.ERROR_DIM),
    errorContainer = Color(BinarioWearPalette.ERROR_CONTAINER),
    onError = Color(BinarioWearPalette.ON_ERROR),
    onErrorContainer = Color(BinarioWearPalette.ON_ERROR_CONTAINER),
)

/** The single theme entry point of the watch app: Material 3 for Wear with the Binario colours. */
@Composable
internal fun BinarioWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BinarioWearColorScheme, content = content)
}

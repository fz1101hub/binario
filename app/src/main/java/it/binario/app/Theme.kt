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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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

private val LightColors = lightColorScheme(
    // Il verde guida si ispira al riferimento fornito, senza riutilizzarne layout o asset.
    // #2E7D32 (contrasto più alto) per azioni e testo bianco; #388E3C come riferimento cromatico.
    primary = Color(0xFF2E7D32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDF0DE),
    onPrimaryContainer = Color(0xFF143A1A),
    secondary = Color(0xFF256D47),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7F0DF),
    onSecondaryContainer = Color(0xFF143E29),
    tertiary = Color(0xFF99571B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE9D1),
    onTertiaryContainer = Color(0xFF492807),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8F5),
    onBackground = Color(0xFF17251A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17251A),
    surfaceVariant = Color(0xFFE8F0E9),
    onSurfaceVariant = Color(0xFF3E5243),
    outline = Color(0xFF617566),
    outlineVariant = Color(0xFFC5D7C8)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BDD9E),
    onPrimary = Color(0xFF103C19),
    primaryContainer = Color(0xFF205B2A),
    onPrimaryContainer = Color(0xFFDDF0DE),
    secondary = Color(0xFFA9DAB7),
    onSecondary = Color(0xFF113C27),
    secondaryContainer = Color(0xFF22563A),
    onSecondaryContainer = Color(0xFFD7F0DF),
    tertiary = Color(0xFFFFC489),
    onTertiary = Color(0xFF4C2604),
    tertiaryContainer = Color(0xFF71400E),
    onTertiaryContainer = Color(0xFFFFE9D1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1710),
    onBackground = Color(0xFFE2F1E3),
    surface = Color(0xFF142018),
    onSurface = Color(0xFFE2F1E3),
    surfaceVariant = Color(0xFF314637),
    onSurfaceVariant = Color(0xFFBDD3C1),
    outline = Color(0xFF92AC97),
    outlineVariant = Color(0xFF415746)
)

private val BinarioTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
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
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BinarioTheme(mode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = BinarioTypography,
        shapes = BinarioShapes,
        content = content
    )
}

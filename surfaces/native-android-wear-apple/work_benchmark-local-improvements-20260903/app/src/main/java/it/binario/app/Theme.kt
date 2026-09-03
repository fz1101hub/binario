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
    primary = Color(0xFF007F5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F4D1),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF006F96),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9EBFF),
    onSecondaryContainer = Color(0xFF001F2D),
    tertiary = Color(0xFFA55400),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC5),
    onTertiaryContainer = Color(0xFF351300),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8F5),
    onBackground = Color(0xFF15211E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15211E),
    surfaceVariant = Color(0xFFE1E7E1),
    onSurfaceVariant = Color(0xFF414943),
    outline = Color(0xFF717A73),
    outlineVariant = Color(0xFFC1C9C2)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FE0AE),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFFA9F4D1),
    secondary = Color(0xFF78D4FA),
    onSecondary = Color(0xFF00364A),
    secondaryContainer = Color(0xFF004E6B),
    onSecondaryContainer = Color(0xFFC9EBFF),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF592300),
    tertiaryContainer = Color(0xFF7D3500),
    onTertiaryContainer = Color(0xFFFFDCC5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101714),
    onBackground = Color(0xFFE1E8E2),
    surface = Color(0xFF151D18),
    onSurface = Color(0xFFE1E8E2),
    surfaceVariant = Color(0xFF414943),
    onSurfaceVariant = Color(0xFFC1C9C2),
    outline = Color(0xFF8B948C),
    outlineVariant = Color(0xFF414943)
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

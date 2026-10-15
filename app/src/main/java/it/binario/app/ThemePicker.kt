package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(
    currentMode: AppThemeMode,
    onModeSelected: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.theme_picker_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.theme_picker_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = currentMode == mode,
                    onClick = { onModeSelected(mode) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val icon = when (mode) {
                                AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                AppThemeMode.LIGHT -> Icons.Default.LightMode
                                AppThemeMode.DARK -> Icons.Default.DarkMode
                            }
                            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Column {
                                Text(mode.localizedLabel(), fontWeight = FontWeight.Bold)
                                Text(
                                    when (mode) {
                                        AppThemeMode.SYSTEM -> stringResource(R.string.theme_system_description)
                                        AppThemeMode.LIGHT -> stringResource(R.string.theme_light_description)
                                        AppThemeMode.DARK -> stringResource(R.string.theme_dark_description)
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AppThemeMode.localizedLabel(): String = when (this) {
    AppThemeMode.SYSTEM -> stringResource(R.string.theme_system_label)
    AppThemeMode.LIGHT -> stringResource(R.string.theme_light_label)
    AppThemeMode.DARK -> stringResource(R.string.theme_dark_label)
}

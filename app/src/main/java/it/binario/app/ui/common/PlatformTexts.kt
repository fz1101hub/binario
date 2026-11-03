package it.binario.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import it.binario.app.R
import it.binario.app.data.PlatformChange
import it.binario.app.data.PlatformLabelStrings
import it.binario.app.data.PlatformLabels

// Platform labels shared by Partenze, the results, the train detail and the station boards: the actual platform and,
// when the source moved the train, the timetable one ("Bin. 7 (era 5)"), in the attention colour and always in text.

/** Short form ("Bin. 7", "Bin. 7 (era 5)") for rows; the long form ("Binario 7 (era 5)") for the detail. */
@Composable
internal fun platformLabelStrings(long: Boolean = false): PlatformLabelStrings = PlatformLabelStrings(
    locale = LocalConfiguration.current.locales[0],
    short = stringResource(if (long) R.string.platform_label else R.string.platform_short),
    changedShort = stringResource(if (long) R.string.platform_changed_label else R.string.platform_changed_short),
    spoken = stringResource(R.string.platform_label),
    changedSpoken = stringResource(R.string.platform_changed_accessible),
    unavailable = stringResource(R.string.platform_unavailable)
)

/** "Bin. 7 (era 5)" / "Bin. 7", or null without a platform. */
@Composable
internal fun platformText(actual: String?, scheduled: String?, long: Boolean = false): String? =
    PlatformLabels.short(actual, scheduled, platformLabelStrings(long))

/** "Binario cambiato: 7, previsto 5" / "Binario 7" / "Binario non disponibile" for screen readers. */
@Composable
internal fun platformSpoken(actual: String?, scheduled: String?): String =
    PlatformLabels.spoken(actual, scheduled, platformLabelStrings())

/** The attention colour of the design system when the platform changed, otherwise [default]. */
@Composable
internal fun platformColor(actual: String?, scheduled: String?, default: Color): Color =
    if (PlatformChange.of(actual, scheduled) != null) MaterialTheme.colorScheme.tertiary else default

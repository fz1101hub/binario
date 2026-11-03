package it.binario.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.TravelPreferencesRepository
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneyRanking
import it.binario.app.data.provider.ModeChoice
import it.binario.app.data.provider.TravelMode
import it.binario.app.data.provider.WalkingSpeed
import it.binario.app.ui.common.RowDivider
import java.util.Locale

// Impostazioni › Preferenze di viaggio: what the offline journey planner takes into account.

/**
 * Walking speed and longest walk (they change the walks the planner offers), "Preferisci" (the chip the results open
 * on and the order of ties), the kinds of service to prefer or avoid (a tap prefers, a second avoids, a third clears)
 * and "Solo percorsi accessibili in sedia a rotelle". Saved on this device at every change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TravelPreferencesSettings() {
    val context = LocalContext.current.applicationContext
    val preferences by TravelPreferencesRepository.state(context).collectAsState()
    fun update(value: JourneyPreferences) = TravelPreferencesRepository.update(context, value)
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("travel-preferences"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.travel_preferences_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GroupTitle(stringResource(R.string.travel_walking_speed))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WalkingSpeed.entries.forEach { speed ->
                FilterChip(
                    selected = preferences.walkingSpeed == speed,
                    onClick = { update(preferences.copy(walkingSpeed = speed)) },
                    label = { Text(speed.label()) },
                    modifier = Modifier.testTag("travel-speed-${speed.name.lowercase(Locale.ROOT)}")
                )
            }
        }
        GroupTitle(stringResource(R.string.travel_max_walk))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JourneyPreferences.MAX_WALK_CHOICES.forEach { minutes ->
                FilterChip(
                    selected = preferences.maxWalkMinutes == minutes,
                    onClick = { update(preferences.copy(maxWalkMinutes = minutes)) },
                    label = { Text(pluralStringResource(R.plurals.minutes_short, minutes, minutes)) }
                )
            }
        }
        GroupTitle(stringResource(R.string.travel_ranking))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JourneyRanking.entries.forEach { ranking ->
                FilterChip(
                    selected = preferences.ranking == ranking,
                    onClick = { update(preferences.copy(ranking = ranking)) },
                    label = { Text(ranking.label()) },
                    modifier = Modifier.testTag("travel-ranking-${ranking.name.lowercase(Locale.ROOT)}")
                )
            }
        }
        GroupTitle(stringResource(R.string.travel_modes))
        Text(stringResource(R.string.travel_modes_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TravelMode.entries.forEach { mode ->
                val choice = preferences.choiceOf(mode)
                val state = choice.label()
                FilterChip(
                    selected = choice != ModeChoice.ANY,
                    onClick = { update(preferences.withMode(mode, choice.next())) },
                    label = { Text(mode.label()) },
                    leadingIcon = when (choice) {
                        ModeChoice.PREFER -> { { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) } }
                        ModeChoice.AVOID -> { { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp)) } }
                        ModeChoice.ANY -> null
                    },
                    modifier = Modifier
                        .semantics { stateDescription = state }
                        .testTag("travel-mode-${mode.name.lowercase(Locale.ROOT)}")
                )
            }
        }
    }
    RowDivider()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = preferences.wheelchairOnly,
                role = Role.Switch,
                onValueChange = { update(preferences.copy(wheelchairOnly = it)) }
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("travel-wheelchair-only"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.travel_wheelchair_only), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.travel_wheelchair_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = preferences.wheelchairOnly, onCheckedChange = null)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

/** Any → preferred → avoided → any: one chip for the three states. */
private fun ModeChoice.next(): ModeChoice = when (this) {
    ModeChoice.ANY -> ModeChoice.PREFER
    ModeChoice.PREFER -> ModeChoice.AVOID
    ModeChoice.AVOID -> ModeChoice.ANY
}

@Composable
private fun ModeChoice.label(): String = stringResource(
    when (this) {
        ModeChoice.ANY -> R.string.travel_mode_state_any
        ModeChoice.PREFER -> R.string.travel_mode_state_prefer
        ModeChoice.AVOID -> R.string.travel_mode_state_avoid
    }
)

@Composable
private fun WalkingSpeed.label(): String = stringResource(
    when (this) {
        WalkingSpeed.SLOW -> R.string.travel_walking_slow
        WalkingSpeed.NORMAL -> R.string.travel_walking_normal
        WalkingSpeed.FAST -> R.string.travel_walking_fast
    }
)

@Composable
private fun JourneyRanking.label(): String = stringResource(
    when (this) {
        JourneyRanking.FASTEST -> R.string.travel_ranking_fastest
        JourneyRanking.FEWEST_CHANGES -> R.string.journey_filter_fewest_changes
        JourneyRanking.LEAST_WALKING -> R.string.travel_ranking_least_walking
    }
)

/** The kinds of service with the app's existing labels where there is one ("Intercity", "Regionali", "Metro"). */
@Composable
private fun TravelMode.label(): String = stringResource(
    when (this) {
        TravelMode.HIGH_SPEED -> R.string.travel_mode_high_speed
        TravelMode.INTERCITY -> R.string.train_category_intercity
        TravelMode.REGIONAL -> R.string.service_group_regional
        TravelMode.ITALO -> R.string.service_group_italo
        TravelMode.METRO -> R.string.journey_leg_metro
        TravelMode.TRAM -> R.string.journey_leg_tram
        TravelMode.FUNICULAR -> R.string.travel_mode_funicular
        TravelMode.OPERATOR_BUS -> R.string.travel_mode_operator_bus
    }
)

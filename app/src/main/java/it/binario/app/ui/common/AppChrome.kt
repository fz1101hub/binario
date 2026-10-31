package it.binario.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DepartureBoard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.R
import it.binario.app.ui.TopDestination

// App chrome shared by every destination.

/**
 * App bar that grows with the text instead of clipping it: at 200 % font scale the title and the
 * subtitle wrap and the bar becomes taller, while the 64 dp minimum keeps the usual height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BinarioTopBar(
    title: String,
    subtitle: String?,
    containerColor: Color,
    contentColor: Color,
    brand: Boolean,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(color = containerColor, contentColor = contentColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (brand) 1.sp else 0.sp
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = if (brand) contentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Bottom navigation of phones: Pianifica, Partenze, Per te. */
@Composable
internal fun BinarioNavigationBar(selected: TopDestination, onSelect: (TopDestination) -> Unit, containerColor: Color) {
    NavigationBar(containerColor = containerColor, modifier = Modifier.testTag("bottom-navigation")) {
        TopDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label()) },
                modifier = Modifier.testTag(destination.testTag)
            )
        }
    }
}

@Composable
internal fun TopDestination.label(): String = stringResource(
    when (this) {
        TopDestination.PLAN -> R.string.nav_plan
        TopDestination.DEPARTURES -> R.string.nav_departures
        TopDestination.FOR_YOU -> R.string.nav_for_you
    }
)

private val TopDestination.icon: ImageVector
    get() = when (this) {
        TopDestination.PLAN -> Icons.Default.Search
        TopDestination.DEPARTURES -> Icons.Default.DepartureBoard
        TopDestination.FOR_YOU -> Icons.Default.Person
    }

/** Stable test tags of the destinations, shared by the bottom bar and the navigation rail. */
internal val TopDestination.testTag: String
    get() = when (this) {
        TopDestination.PLAN -> "nav-plan"
        TopDestination.DEPARTURES -> "nav-departures"
        TopDestination.FOR_YOU -> "nav-for-you"
    }

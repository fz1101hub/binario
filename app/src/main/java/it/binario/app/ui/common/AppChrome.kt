package it.binario.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DepartureBoard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DepartureBoard
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.ui.TopDestination

// App chrome shared by every destination: the green top bar, the bottom navigation and the green side
// navigation of wide windows.

/**
 * The row above every screen: Back (or the Binario logo on the planner) and the screen actions. On phones it
 * is green and reaches behind the status bar, continuing into the screen header; on wide windows it is a
 * flat row above the panes. It never clips: the 56 dp minimum grows with the font scale.
 */
@Composable
internal fun BinarioTopBar(
    brand: Boolean,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val wide = LocalWideLayout.current
    val colors = BinarioTheme.colors
    val background = if (wide) MaterialTheme.colorScheme.background else colors.header
    val foreground = if (wide) MaterialTheme.colorScheme.onBackground else colors.onHeader
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .then(
                    if (wide) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp)
                .testTag("top-bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon()
            if (brand && !wide) BinarioLogo(foreground, Modifier.padding(start = 12.dp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Bottom navigation of phones: Pianifica, Partenze, Per te. The selected item has a filled icon and a bold label. */
@Composable
internal fun BinarioNavigationBar(selected: TopDestination, onSelect: (TopDestination) -> Unit, containerColor: Color) {
    val colors = BinarioTheme.colors
    val selectedColor = if (MaterialTheme.colorScheme.surface.isLight()) colors.segmentSelected else MaterialTheme.colorScheme.primary
    NavigationBar(containerColor = containerColor, tonalElevation = 0.dp, modifier = Modifier.testTag("bottom-navigation")) {
        TopDestination.entries.forEach { destination ->
            val isSelected = selected == destination
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon(isSelected), contentDescription = null) },
                label = { Text(destination.label(), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selectedColor,
                    selectedTextColor = selectedColor,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.testTag(destination.testTag)
            )
        }
    }
}

private fun Color.isLight(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) > 0.5f

@Composable
internal fun TopDestination.label(): String = stringResource(
    when (this) {
        TopDestination.PLAN -> R.string.nav_plan
        TopDestination.DEPARTURES -> R.string.nav_departures
        TopDestination.FOR_YOU -> R.string.nav_for_you
    }
)

private fun TopDestination.icon(selected: Boolean): ImageVector = when (this) {
    TopDestination.PLAN -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
    TopDestination.DEPARTURES -> if (selected) Icons.Filled.DepartureBoard else Icons.Outlined.DepartureBoard
    TopDestination.FOR_YOU -> if (selected) Icons.Filled.Person else Icons.Outlined.Person
}

/** Stable test tags of the destinations, shared by the bottom bar and the side navigation. */
internal val TopDestination.testTag: String
    get() = when (this) {
        TopDestination.PLAN -> "nav-plan"
        TopDestination.DEPARTURES -> "nav-departures"
        TopDestination.FOR_YOU -> "nav-for-you"
    }

/**
 * From 600 dp the destinations move to a permanent deep green side navigation that reaches behind the status
 * bar: the Binario logo, then one item per destination. [expanded] (from 840 dp) puts the label beside the
 * icon as in the tablet render; the narrower rail stacks it below. The selected item is a rounded lighter green
 * with a filled icon and a bold label.
 */
@Composable
internal fun BinarioNavigationRail(
    selected: TopDestination,
    onSelect: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false
) {
    val colors = BinarioTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .width(if (expanded) 216.dp else 96.dp)
            .background(colors.header)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .selectableGroup()
            .testTag("navigation-rail"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BinarioLogo(
            colors.onHeader,
            Modifier.padding(start = if (expanded) 12.dp else 22.dp, bottom = 16.dp, top = 8.dp),
            showWordmark = expanded
        )
        TopDestination.entries.forEach { destination ->
            val isSelected = selected == destination
            val content = if (isSelected) colors.onRailSelected else colors.onRail
            val itemModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(if (isSelected) colors.railSelected else Color.Transparent, RoundedCornerShape(16.dp))
                .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(destination) })
                .testTag(destination.testTag)
                .padding(horizontal = 12.dp, vertical = 10.dp)
            val label: @Composable () -> Unit = {
                Text(
                    destination.label(),
                    color = content,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = if (expanded) TextAlign.Start else TextAlign.Center
                )
            }
            if (expanded) {
                Row(itemModifier, verticalAlignment = Alignment.CenterVertically) {
                    Icon(destination.icon(isSelected), contentDescription = null, tint = content)
                    Spacer(Modifier.width(14.dp))
                    label()
                }
            } else {
                Column(itemModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(destination.icon(isSelected), contentDescription = null, tint = content)
                    Spacer(Modifier.height(4.dp))
                    label()
                }
            }
        }
    }
}

/** Second pane of a wide window while no train is selected. */
@Composable
internal fun DetailPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp).testTag("detail-placeholder"), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.detail_pane_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

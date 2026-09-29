package com.example.trailblazer.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.trailblazer.ui.components.Backdrop
import com.example.trailblazer.ui.components.GlassChrome
import com.example.trailblazer.ui.components.LocalBottomInset
import com.example.trailblazer.ui.components.LocalHaze
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.now.NowScreen
import com.example.trailblazer.ui.settings.DocsScreen
import com.example.trailblazer.ui.settings.SettingsScreen
import com.example.trailblazer.ui.sky.SkyScreen
import com.example.trailblazer.ui.tools.AltimeterScreen
import com.example.trailblazer.ui.tools.DiagnosticsScreen
import com.example.trailblazer.ui.tools.LevelScreen
import com.example.trailblazer.ui.tools.SightingScreen
import com.example.trailblazer.ui.tools.SosScreen
import com.example.trailblazer.ui.tools.ToolsScreen
import com.example.trailblazer.ui.trips.TrackDetailScreen
import com.example.trailblazer.ui.trips.TripEditScreen
import com.example.trailblazer.ui.trips.TripsScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Navigation actions handed to screens, so screens never touch the back stack directly. */
class Navigator(private val stack: NavBackStack<NavKey>) {
    fun go(route: Route) {
        stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    fun tab(route: Route) {
        if (stack.size == 1 && stack[0] == route) return
        stack.clear()
        stack.add(route)
    }
}

private data class Tab(val route: Route, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(NowRoute, "Now", TrailIcons.Compass),
    Tab(SkyRoute, "Sky", TrailIcons.Sun),
    Tab(TripsRoute, "Trips", TrailIcons.Route),
    Tab(ToolsRoute, "Tools", TrailIcons.Tools),
)

@Composable
fun TrailNav() {
    val stack = rememberNavBackStack(NowRoute)
    val nav = remember(stack) { Navigator(stack) }
    val haze = rememberHazeState()
    val top = stack.lastOrNull() as? Route ?: NowRoute
    val showBar = stack.size == 1 && top in TopLevel
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // Back from another tab's root returns to Now before leaving the app.
    BackHandler(enabled = stack.size == 1 && top != NowRoute) { nav.tab(NowRoute) }

    Backdrop {
        CompositionLocalProvider(
            LocalBottomInset provides if (showBar) with(density) { barHeightPx.toDp() } else 0.dp,
        ) {
            NavDisplay(
                backStack = stack,
                modifier = Modifier.fillMaxSize().hazeSource(haze),
                onBack = { nav.back() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<NowRoute> { NowScreen(nav) }
                    entry<SkyRoute> { SkyScreen(nav) }
                    entry<TripsRoute> { TripsScreen(nav) }
                    entry<ToolsRoute> { ToolsScreen(nav) }
                    entry<SettingsRoute> { SettingsScreen(nav) }
                    entry<DocsRoute> { DocsScreen(nav, it.file) }
                    entry<TripEditRoute> { TripEditScreen(nav, it.tripId) }
                    entry<TrackDetailRoute> { TrackDetailScreen(nav, it.trackId) }
                    entry<LevelRoute> { LevelScreen(nav) }
                    entry<AltimeterRoute> { AltimeterScreen(nav) }
                    entry<SosRoute> { SosScreen(nav) }
                    entry<DiagnosticsRoute> { DiagnosticsScreen(nav) }
                    entry<SightingRoute> { SightingScreen(nav) }
                },
            )
        }
        if (showBar) {
            CompositionLocalProvider(LocalHaze provides haze) {
                GlassChrome(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { barHeightPx = it.height }) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                        for (t in tabs) {
                            NavigationBarItem(
                                selected = top == t.route,
                                onClick = { nav.tab(t.route) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

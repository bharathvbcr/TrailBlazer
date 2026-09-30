package com.example.trailblazer.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import com.example.trailblazer.ui.stars.StargazeScreen
import com.example.trailblazer.ui.tools.AltimeterScreen
import com.example.trailblazer.ui.tools.DiagnosticsScreen
import com.example.trailblazer.ui.tools.LevelScreen
import com.example.trailblazer.ui.tools.SightingScreen
import com.example.trailblazer.ui.tools.SosScreen
import com.example.trailblazer.ui.tools.ToolsScreen
import com.example.trailblazer.ui.trips.TrackDetailScreen
import com.example.trailblazer.ui.trips.TripEditScreen
import com.example.trailblazer.ui.trips.ShareScreen
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

private val PushTransition: ContentTransform =
    (slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(300)) { -it / 10 } + fadeOut(tween(200)))

private val PopTransition: ContentTransform =
    (slideInHorizontally(tween(300)) { -it / 10 } + fadeIn(tween(300))) togetherWith (slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(200)))

private val TabSwitch = NavDisplay.transitionSpec { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) } +
    NavDisplay.popTransitionSpec { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }

/**
 * Material predictive back: the leaving page scales down and drifts away from the edge the swipe started on, while
 * the page underneath fades up from slightly smaller. [edge] is a NavigationEvent swipe edge.
 */
private fun predictivePop(edge: Int): ContentTransform {
    val away = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
    return (fadeIn() + scaleIn(initialScale = 0.94f)) togetherWith
        (scaleOut(targetScale = 0.88f) + slideOutHorizontally { away * it / 10 } + fadeOut())
}

/** The pill's height plus its bottom margin, used only until it has been measured. */
private val PillEstimate = 84.dp

@Composable
fun TrailNav(sharedText: String? = null, onShareHandled: () -> Unit = {}) {
    val stack = rememberNavBackStack(NowRoute)
    val nav = remember(stack) { Navigator(stack) }
    // Something shared to the app opens "Add to a trip" on top of wherever the user was; consumed once.
    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            nav.go(ShareRoute(sharedText))
            onShareHandled()
        }
    }
    val haze = rememberHazeState()
    val top = stack.lastOrNull() as? Route ?: NowRoute
    val showBar = stack.size == 1 && top in TopLevel
    var barHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Back from another tab's root returns to Now before leaving the app.
    BackHandler(enabled = stack.size == 1 && top != NowRoute) { nav.tab(NowRoute) }

    Backdrop {
        CompositionLocalProvider(
            // Before the pill's first measurement, reserve an estimate rather than nothing.
            LocalBottomInset provides if (!showBar) navBarBottomInset
            else if (barHeightPx > 0) with(density) { barHeightPx.toDp() }
            else navBarBottomInset + PillEstimate,
        ) {
            NavDisplay(
                backStack = stack,
                modifier = Modifier.fillMaxSize().hazeSource(haze),
                onBack = { nav.back() },
                transitionSpec = { PushTransition },
                popTransitionSpec = { PopTransition },
                // The back gesture drives this frame by frame: the page shrinks and follows the finger, and the
                // screen underneath is already there, so releasing early (cancel) snaps back with nothing lost.
                predictivePopTransitionSpec = { edge -> predictivePop(edge) },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider {
                    // Tabs are siblings, not a hierarchy: switching crossfades instead of sliding.
                    entry<NowRoute>(metadata = TabSwitch) { NowScreen(nav) }
                    entry<SkyRoute>(metadata = TabSwitch) { SkyScreen(nav) }
                    entry<TripsRoute>(metadata = TabSwitch) { TripsScreen(nav) }
                    entry<ToolsRoute>(metadata = TabSwitch) { ToolsScreen(nav) }
                    entry<SettingsRoute> { SettingsScreen(nav) }
                    entry<DocsRoute> { DocsScreen(nav, it.file) }
                    entry<TripEditRoute> { TripEditScreen(nav, it.tripId, it.seed) }
                    entry<ShareRoute> { ShareScreen(nav, it.text) }
                    entry<TrackDetailRoute> { TrackDetailScreen(nav, it.trackId) }
                    entry<LevelRoute> { LevelScreen(nav) }
                    entry<AltimeterRoute> { AltimeterScreen(nav) }
                    entry<SosRoute> { SosScreen(nav) }
                    entry<DiagnosticsRoute> { DiagnosticsScreen(nav) }
                    entry<SightingRoute> { SightingScreen(nav) }
                    entry<StargazeRoute> { StargazeScreen(nav, it.lat, it.lon, it.label) }
                },
            )
        }
        if (showBar) {
            CompositionLocalProvider(LocalHaze provides haze) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Measured before the insets and margin are applied, so it is the pill's whole footprint:
                        // measured after them, it missed the gesture bar and the 12 dp gap, and lists ended under the pill.
                        .onSizeChanged { barHeightPx = it.height }
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
                        .padding(bottom = 12.dp, start = 20.dp, end = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val cs = MaterialTheme.colorScheme
                    GlassChrome(
                        modifier = Modifier
                            .testTag("bottomPill")
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .shadow(elevation = 10.dp, shape = CircleShape, spotColor = cs.primary.copy(alpha = 0.25f)),
                        shape = CircleShape,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for (t in tabs) {
                                val selected = top == t.route
                                BottomPillItem(
                                    tab = t,
                                    selected = selected,
                                    onClick = { nav.tab(t.route) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomPillItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) cs.secondaryContainer.copy(alpha = 0.85f) else Color.Transparent,
        animationSpec = spring(stiffness = 300f),
        label = "pillIndicator",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
        animationSpec = spring(stiffness = 300f),
        label = "pillContent",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 350f),
        label = "pillIconScale",
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(indicatorColor)
            .selectable(
                selected = selected,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                role = Role.Tab,
            )
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                tint = contentColor,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}


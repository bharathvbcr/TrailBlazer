package com.example.trailblazer.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * One back stack per tab, and which tab is showing.
 *
 * Switching tabs only changes [tab]; the stack of the tab left behind is kept, with its entries' saved UI state and
 * ViewModels, because [entries] keeps decorating every stack and a stack's entries are released only when they leave
 * *that stack* (a pop), not when they stop being displayed. Each stack is a [NavBackStack], so it restores after
 * process death, and the selected tab is restored with it.
 *
 * Tab switches happen at a tab root (the pill is hidden above one), so two stacks never hold the same pushed route at
 * once and their entries' content keys cannot collide.
 */
class NavigationState(
    val tabs: List<Route>,
    val stacks: Map<Route, NavBackStack<NavKey>>,
    selected: MutableIntState = mutableIntStateOf(0),
) {
    init {
        require(tabs.isNotEmpty() && tabs.toSet().size == tabs.size) { "tabs must be non-empty and distinct" }
        require(stacks.keys == tabs.toSet()) { "every tab needs exactly one stack" }
    }

    private var selectedIndex by selected

    /** The tab showing now. */
    var tab: Route
        get() = tabs[selectedIndex.coerceIn(tabs.indices)]
        set(value) {
            val i = tabs.indexOf(value)
            require(i >= 0) { "$value is not a tab" }
            selectedIndex = i
        }

    /** The back stack of the tab showing now; its first element is the tab's root. */
    val stack: NavBackStack<NavKey> get() = stacks.getValue(tab)

    /**
     * Every stack's entries, decorated so each keeps its own saved UI state and ViewModels while another tab shows,
     * and only the showing tab's are returned for display.
     */
    @Composable
    fun entries(entryProvider: (NavKey) -> NavEntry<NavKey>): List<NavEntry<NavKey>> {
        val decorated = tabs.associateWith { route ->
            rememberDecoratedNavEntries(
                backStack = stacks.getValue(route),
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = entryProvider,
            )
        }
        return decorated.getValue(tab)
    }

    companion object {
        /** A state over a single tab, for a screen shown on its own. */
        fun single(root: Route): NavigationState = NavigationState(listOf(root), mapOf(root to NavBackStack<NavKey>(root)))
    }
}

/** The navigation state of [tabs] (the first is the home tab), restored after process death. */
@Composable
fun rememberNavigationState(tabs: List<Route>): NavigationState {
    val selected = rememberSaveable { mutableIntStateOf(0) }
    val stacks = tabs.associateWith { rememberNavBackStack(it) }
    return remember(tabs, stacks.values.toList()) { NavigationState(tabs, stacks, selected) }
}

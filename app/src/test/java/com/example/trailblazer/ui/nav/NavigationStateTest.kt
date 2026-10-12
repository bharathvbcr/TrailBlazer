package com.example.trailblazer.ui.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationStateTest {
    private fun state() = NavigationState(TopLevel, TopLevel.associateWith { NavBackStack<NavKey>(it) })

    @Test
    fun startsOnTheFirstTab() {
        val s = state()
        assertEquals(NowRoute, s.tab)
        assertEquals(listOf<NavKey>(NowRoute), s.stack.toList())
    }

    @Test
    fun switchingTabsKeepsWhatWasOpenOnTheOneLeftBehind() {
        val s = state()
        val nav = Navigator(s)
        nav.tab(SkyRoute)
        nav.go(StargazeRoute())
        nav.tab(TripsRoute)
        assertEquals("the new tab shows its own root", listOf<NavKey>(TripsRoute), s.stack.toList())
        nav.tab(SkyRoute)
        assertEquals(listOf<NavKey>(SkyRoute, StargazeRoute()), s.stack.toList())
    }

    @Test
    fun backPopsOnlyTheShowingTab() {
        val s = state()
        val nav = Navigator(s)
        nav.go(SettingsRoute)
        nav.tab(SkyRoute)
        nav.go(StargazeRoute())
        nav.back()
        assertEquals(listOf<NavKey>(SkyRoute), s.stack.toList())
        nav.back() // at a root: nothing to pop
        assertEquals(listOf<NavKey>(SkyRoute), s.stack.toList())
        nav.tab(NowRoute)
        assertEquals(listOf<NavKey>(NowRoute, SettingsRoute), s.stack.toList())
    }

    @Test
    fun choosingTheTabAlreadyShowingPopsItToItsRoot() {
        val s = state()
        val nav = Navigator(s)
        nav.tab(ToolsRoute)
        nav.go(LevelRoute)
        nav.go(AltimeterRoute)
        nav.tab(ToolsRoute)
        assertEquals(ToolsRoute, s.tab)
        assertEquals(listOf<NavKey>(ToolsRoute), s.stack.toList())
    }

    @Test
    fun aRouteThatIsNotATabCannotBeSwitchedTo() {
        val nav = Navigator(state())
        assertThrows(IllegalArgumentException::class.java) { nav.tab(SettingsRoute) }
    }

    @Test
    fun aStateNeedsExactlyOneStackPerTab() {
        assertThrows(IllegalArgumentException::class.java) {
            NavigationState(TopLevel, mapOf(NowRoute to NavBackStack<NavKey>(NowRoute)))
        }
        assertThrows(IllegalArgumentException::class.java) { NavigationState(emptyList(), emptyMap()) }
    }
}

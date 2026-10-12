package com.example.trailblazer.zverify

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.ui.nav.NavigationState
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.NowRoute
import com.example.trailblazer.ui.nav.SettingsRoute
import com.example.trailblazer.ui.nav.SkyRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class Tracked : ViewModel() {
    val id = next++
    override fun onCleared() {
        cleared += id
    }

    companion object {
        var next = 0
        val cleared = mutableListOf<Int>()
        val created = mutableListOf<Int>()
    }
}

/** Verifier scratch: entries popped from a tab that is not showing release their ViewModel and saved state. */
@RunWith(AndroidJUnit4::class)
class VerifyNavStateTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun poppingAnEntryFromAHiddenTabClearsItsViewModelAndSavedState() {
        Tracked.next = 0
        Tracked.cleared.clear()
        Tracked.created.clear()
        val tabs = listOf(NowRoute, SkyRoute)
        val stacks = tabs.associateWith { NavBackStack<NavKey>(it) }
        val state = NavigationState(tabs, stacks)
        val nav = Navigator(state)
        rule.setContent {
            val provider = remember {
                entryProvider<NavKey> {
                    entry<NowRoute> { Text("now-root") }
                    entry<SkyRoute> { Text("sky-root") }
                    entry<SettingsRoute> {
                        val vm: Tracked = viewModel { Tracked().also { Tracked.created += it.id } }
                        var count by rememberSaveable { mutableIntStateOf(0) }
                        Column {
                            Text("pushed vm=${vm.id}")
                            Button(onClick = { count++ }) { Text("count=$count") }
                        }
                    }
                }
            }
            NavDisplay(entries = state.entries(provider), onBack = { nav.back() })
        }
        rule.waitForIdle()
        rule.runOnIdle { nav.go(SettingsRoute) } // push in tab A (Now)
        rule.waitForIdle()
        rule.onNodeWithText("pushed vm=0").assertExists()
        rule.onNodeWithText("count=0").performClick()
        rule.onNodeWithText("count=1").assertExists()

        rule.runOnIdle { nav.tab(SkyRoute) } // switch to tab B
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("sky-root").assertExists()
        assertTrue("switching tabs must not clear A's pushed ViewModel: ${Tracked.cleared}", Tracked.cleared.isEmpty())

        rule.runOnIdle { stacks.getValue(NowRoute).removeAt(1) } // pop in A while B shows
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals("A's popped ViewModel must be cleared even though A is not showing", listOf(0), Tracked.cleared.toList())

        // Its saved UI state must be gone too: pushing the same key again starts fresh.
        rule.runOnIdle { nav.tab(NowRoute) }
        rule.waitForIdle()
        rule.runOnIdle { nav.go(SettingsRoute) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        rule.onNodeWithText("pushed vm=1").assertExists()
        rule.onNodeWithText("count=0").assertExists()
    }

    @Test
    fun poppingTheShowingTabClearsItsViewModel() {
        Tracked.next = 0
        Tracked.cleared.clear()
        val tabs = listOf(NowRoute, SkyRoute)
        val stacks = tabs.associateWith { NavBackStack<NavKey>(it) }
        val state = NavigationState(tabs, stacks)
        val nav = Navigator(state)
        rule.setContent {
            val provider = remember {
                entryProvider<NavKey> {
                    entry<NowRoute> { Text("now-root") }
                    entry<SkyRoute> { Text("sky-root") }
                    entry<SettingsRoute> {
                        val vm: Tracked = viewModel { Tracked() }
                        Text("pushed vm=${vm.id}")
                    }
                }
            }
            NavDisplay(entries = state.entries(provider), onBack = { nav.back() })
        }
        rule.runOnIdle { nav.tab(SkyRoute) }
        rule.waitForIdle()
        rule.runOnIdle { nav.go(SettingsRoute) }
        rule.waitForIdle()
        rule.onNodeWithText("pushed vm=0").assertExists()
        rule.runOnIdle { nav.tab(SkyRoute) } // reselect pops to root
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertEquals(listOf(0), Tracked.cleared.toList())
    }
}

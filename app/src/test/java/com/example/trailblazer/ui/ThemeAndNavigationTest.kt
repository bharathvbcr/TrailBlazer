package com.example.trailblazer.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.ui.components.EmptyState
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.GlassChrome
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.ValueTile
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeAndNavigationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun darkModeContentColorIsNotBlack() {
        var contentColor = Color.Unspecified
        var onBgColor = Color.Unspecified
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                onBgColor = MaterialTheme.colorScheme.onBackground
                contentColor = LocalContentColor.current
                Text("Test")
            }
        }
        assertNotEquals("Content color in dark mode must not be Black", Color.Black, contentColor)
        assertEquals("Content color in dark mode should match onBackground", onBgColor, contentColor)
    }

    @Test
    fun nightRedContentColorIsNotBlack() {
        var contentColor = Color.Unspecified
        var onBgColor = Color.Unspecified
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = true) {
                onBgColor = MaterialTheme.colorScheme.onBackground
                contentColor = LocalContentColor.current
                Text("Night Test")
            }
        }
        assertNotEquals("Content color in night mode must not be Black", Color.Black, contentColor)
        assertEquals("Content color in night mode should match onBackground", onBgColor, contentColor)
    }

    @Test
    fun glassCardProvidesOnSurfaceContentColor() {
        var cardContentColor = Color.Unspecified
        var onSurfaceColor = Color.Unspecified
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                onSurfaceColor = MaterialTheme.colorScheme.onSurface
                GlassCard {
                    cardContentColor = LocalContentColor.current
                }
            }
        }
        assertNotEquals("Card content color in dark mode must not be Black", Color.Black, cardContentColor)
        assertEquals("Card content color should match onSurface", onSurfaceColor, cardContentColor)
    }

    @Test
    fun glassChromeProvidesOnSurfaceContentColor() {
        var chromeContentColor = Color.Unspecified
        var onSurfaceColor = Color.Unspecified
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                onSurfaceColor = MaterialTheme.colorScheme.onSurface
                GlassChrome {
                    chromeContentColor = LocalContentColor.current
                }
            }
        }
        assertNotEquals("Chrome content color in dark mode must not be Black", Color.Black, chromeContentColor)
        assertEquals("Chrome content color should match onSurface", onSurfaceColor, chromeContentColor)
    }

    @Test
    fun bottomPillRendersAllTabsAndNavigates() {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                com.example.trailblazer.ui.nav.TrailNav()
            }
        }
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Now").fetchSemanticsNodes().isNotEmpty())
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Sky").fetchSemanticsNodes().isNotEmpty())
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Trips").fetchSemanticsNodes().isNotEmpty())
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Tools").fetchSemanticsNodes().isNotEmpty())

        // Click Tools in the pill
        rule.onAllNodesWithText("Tools").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Level & tilt").assertIsDisplayed()
    }

    @Test
    fun emptyStateRendersIconAndText() {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                EmptyState("No trips yet", "Plan a route from A to B", icon = TrailIcons.Route)
            }
        }
        rule.onNodeWithText("No trips yet").assertIsDisplayed()
        rule.onNodeWithText("Plan a route from A to B").assertIsDisplayed()
    }

    @Test
    fun valueTileRendersContentAndIcon() {
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false) {
                ValueTile("Speed", Reading.Value(value = 12.5, timestampMs = 1000L), "GPS", icon = TrailIcons.Speed) { "${it} km/h" }
            }
        }
        rule.onNodeWithText("Speed").assertIsDisplayed()
        rule.onNodeWithText("12.5 km/h").assertIsDisplayed()
    }
}



package com.example.trailblazer.testing

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Waits until the launched app has drawn its first screen.
 *
 * The Now screen draws nothing until the saved settings have been read from DataStore, which happens on another
 * thread. Its Settings button, its cards and "Mark waypoint" appear only after that, so a test that reaches for
 * them straight after launch races the read, and loses it when the machine is busy. The bottom tab bar does not
 * wait for settings, so it is no sign of readiness; "Mark waypoint" is.
 */
fun ComposeTestRule.awaitAppReady(timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis) { onAllNodesWithText("Mark waypoint").fetchSemanticsNodes().isNotEmpty() }
}

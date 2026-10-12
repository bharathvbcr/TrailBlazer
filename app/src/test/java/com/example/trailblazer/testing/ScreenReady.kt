package com.example.trailblazer.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Waits until a node matching [matcher] is on screen. For screens that draw nothing until their first DataStore read
 * (which runs on Dispatchers.IO) lands on the main thread: `waitForIdle()` returns before that, so a test that acts at
 * once finds no node. Pass a node only that screen draws once its settings are loaded.
 */
fun ComposeTestRule.awaitScreenReady(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false, timeoutMs: Long = 20_000) {
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree).fetchSemanticsNodes().isNotEmpty() }
}

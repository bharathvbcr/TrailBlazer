package com.example.trailblazer.ui.now

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.TrailApp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowBleChipTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()

    @Test
    fun bleChipDisplaysStatusAndOpensSensorDialog() {
        rule.waitForIdle()

        // BLE status chip is visible in the chips area
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("BLE:", substring = true))
        val bleChips = rule.onAllNodesWithText("BLE:", substring = true).fetchSemanticsNodes()
        assertTrue("BLE chip is visible on Now tab", bleChips.isNotEmpty())

        // Click BLE chip to open sensor dialog
        rule.onNodeWithText("BLE:", substring = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()

        // Verify sensor configuration dialog is opened
        val dialogs = rule.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes()
        assertTrue("BLE Sensor Dialog opened", dialogs.isNotEmpty())

        // Dismiss dialog
        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()
        assertTrue("BLE Sensor Dialog dismissed", rule.onAllNodes(androidx.compose.ui.test.isDialog()).fetchSemanticsNodes().isEmpty())
    }
}

package com.example.trailblazer.ui.now

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedAlertEffectTest {
    @get:Rule
    val rule = createComposeRule()

    private val alerts = MutableSharedFlow<Double>(extraBufferCapacity = 8)
    private val texts = ArrayList<String>()

    private fun fire(mps: Double) {
        rule.waitUntil(5_000) { alerts.subscriptionCount.value > 0 }
        assertTrue(alerts.tryEmit(mps))
        rule.waitForIdle()
    }

    @Test
    fun anAlertAfterAUnitChangeIsWrittenInTheNewUnit() {
        var perSecond by mutableDoubleStateOf(1.0) // 1.0 = m/s, 3.6 = km/h
        rule.setContent {
            // Read here, as the screen reads its settings: the formatter closes over this composition's value.
            val factor = perSecond
            SpeedAlertEffect(alerts, format = { mps -> "${mps * factor}" }, onAlert = { texts += it })
        }

        fire(10.0)
        perSecond = 3.6 // the user switches to km/h while the screen stays open
        rule.waitForIdle()
        fire(10.0)

        assertEquals(listOf("10.0", "36.0"), texts)
    }

    @Test
    fun theCallbackInForceWhenTheAlertFiresIsTheOneCalled() {
        var tag by androidx.compose.runtime.mutableStateOf("first")
        rule.setContent {
            val current = tag
            SpeedAlertEffect(alerts, format = { "$it" }, onAlert = { texts += "$current:$it" })
        }

        fire(1.0)
        tag = "second"
        rule.waitForIdle()
        fire(2.0)

        assertEquals(listOf("first:1.0", "second:2.0"), texts)
    }
}

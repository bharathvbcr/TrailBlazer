package com.example.trailblazer.ui.sky

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.openUrl
import com.example.trailblazer.ui.theme.TrailTheme
import com.example.trailblazer.weather.ForecastResult
import com.example.trailblazer.weather.OpenMeteoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A phone with no browser must not crash the app when someone taps a link. */
@RunWith(AndroidJUnit4::class)
class ForecastLinkTest {
    @get:Rule
    val rule = createComposeRule()

    private class Phone(base: Context, private val hasBrowser: Boolean) : ContextWrapper(base) {
        val started = ArrayList<Intent>()
        override fun startActivity(intent: Intent) {
            started += intent
            if (!hasBrowser) throw ActivityNotFoundException("No Activity found to handle $intent")
        }
    }

    @Test
    fun openUrlReportsAMissingHandlerInsteadOfThrowing() {
        val ctx = Phone(ApplicationProvider.getApplicationContext(), hasBrowser = false)
        var message: String? = null
        openUrl(ctx, "https://open-meteo.com/") { message = it }
        assertEquals(Intent.ACTION_VIEW, ctx.started.single().action)
        assertTrue(message!!.isNotBlank())
    }

    @Test
    fun openUrlStartsTheViewIntentWhenAnAppCanHandleIt() {
        val ctx = Phone(ApplicationProvider.getApplicationContext(), hasBrowser = true)
        var message: String? = null
        openUrl(ctx, "https://open-meteo.com/") { message = it }
        assertEquals(null, message)
        assertEquals("https://open-meteo.com/", ctx.started.single().data.toString())
    }

    @Test
    fun theForecastAttributionLinkShowsAMessageWhenNothingCanOpenIt() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = false) {
                ForecastCard(
                    consented = true,
                    result = ForecastResult.Failed("offline"),
                    loading = false,
                    fmt = Fmt(LocalContext.current, Settings()),
                    hasPlace = true,
                    onEnable = {},
                    onRefresh = {},
                    onDisable = {},
                )
            }
        }
        rule.onNodeWithText(OpenMeteoClient.ATTRIBUTION).performClick()
        rule.onNodeWithText("No app on this phone can open this link.").assertExists()
    }
}

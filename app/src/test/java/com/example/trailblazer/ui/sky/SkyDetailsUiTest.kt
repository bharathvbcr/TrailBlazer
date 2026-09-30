package com.example.trailblazer.ui.sky

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.TrailApp
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.theme.TrailTheme
import com.example.trailblazer.weather.ForecastResult
import com.example.trailblazer.weather.HttpTransport
import com.example.trailblazer.weather.OpenMeteoClient
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Sun and Moon cards on the real Sky tab: times with compass directions, and the drawings. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class SkyDetailsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/reports/screens").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun has(text: String, substring: Boolean = true) = rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun sunAndMoonShowTimesWithCompassDirections() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(true)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onAllNodesWithText("Sky").onFirst().performClick()
        rule.waitForIdle()
        for (provider in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            shadowOf(manager).simulateLocation(Location(provider).apply { latitude = 33.19; longitude = -97.13; accuracy = 8f; time = System.currentTimeMillis() })
        }
        rule.waitForIdle()
        rule.waitUntil(20_000) { !has("Waiting for a position fix") }

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Your shadow", substring = true).or(hasText("Last light")))
        save("sky-sun-details")
        // Denton, TX: the Sun always rises and sets, so both directions are named.
        assertTrue("sunrise direction", has("from the "))
        assertTrue("sunset direction", has("to the "))
        assertTrue(has("First light") && has("Last light") && has("Day length"))

        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Moon phases"))
        save("sky-moon-compass")
        val moonFacts = listOf("Moonrise", "Moonset", "Highest").count { has(it, substring = false) }
        assertTrue("at least two of rise, peak and set are named ($moonFacts)", moonFacts >= 2)
        assertTrue("the moon's direction now", has("Now", substring = false))
    }
}

/** The forecast card from a real (trimmed) Open-Meteo response. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ForecastBodyUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val denver = """{"latitude":39.75,"longitude":-104.99,"utc_offset_seconds":-21600,"timezone":"America/Denver",
        "current":{"time":"2026-09-29T21:00","interval":900,"temperature_2m":16.9,"relative_humidity_2m":40,"apparent_temperature":15.2,"weather_code":3,
        "wind_speed_10m":12.0,"wind_direction_10m":315,"wind_gusts_10m":25.0,"pressure_msl":1012.0,"precipitation":0.0},
        "hourly":{"time":["2026-09-29T21:00","2026-09-29T22:00","2026-09-29T23:00","2026-09-30T00:00","2026-09-30T01:00","2026-09-30T02:00"],
        "temperature_2m":[16.9,15.8,14.9,14.1,13.3,12.8],"precipitation_probability":[0,0,5,10,10,5],"weather_code":[3,3,2,2,1,0]},
        "daily":{"time":["2026-09-29","2026-09-30","2026-10-01"],"weather_code":[3,61,0],"temperature_2m_max":[22.7,19.0,21.8],"temperature_2m_min":[9.1,8.0,7.4],
        "precipitation_probability_max":[5,60,0],"precipitation_sum":[0.0,4.1,0.0],"wind_speed_10m_max":[20,25,15],"uv_index_max":[5,3,6]}}"""

    @Test
    fun daysAreNamedAndHoursAreTimesNotIsoFragments() {
        val result = runBlocking { OpenMeteoClient(HttpTransport { denver }) { 1_790_000_000_000L }.forecast(LatLon(39.75, -104.99), consented = true) } as ForecastResult.Ok
        rule.setContent {
            TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = false) {
                val fmt = Fmt(LocalContext.current, Settings())
                Column(Modifier.width(380.dp).testTag("card")) { ForecastBody(result, fmt) }
            }
        }
        fun has(t: String, sub: Boolean = true) = rule.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
        assertTrue(has("Today", sub = false) && has("Tomorrow", sub = false))
        assertTrue("third day is a weekday name", has("Thu", sub = false))
        assertTrue("the old ISO fragments are gone", !has("09-29") && !has("09-30"))
        assertTrue("the chosen day reads as a date", has("29 Sep") || has("Sep 29"))
        assertTrue(has("Feels like") && has("from NW"))
        assertTrue(has("Next 6 hours") && has("Next 3 days"))
        val bmp = rule.onNodeWithTag("card").captureToImage().asAndroidBitmap()
        File(File("build/reports/screens").apply { mkdirs() }, "forecast-card.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

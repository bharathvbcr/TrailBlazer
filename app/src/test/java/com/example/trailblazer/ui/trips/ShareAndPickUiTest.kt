package com.example.trailblazer.ui.trips

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.MainActivity
import com.example.trailblazer.TrailApp
import com.example.trailblazer.container
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowGeocoder
import java.io.File

/** Adding stops without typing coordinates: sharing from a maps app, the clipboard, and search by name. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class ShareAndPickUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = ApplicationProvider.getApplicationContext<TrailApp>()
    private val jungfrau = "Jungfraujoch\nhttps://www.google.com/maps/place/Jungfraujoch/@46.54,7.98,15z/data=!3d46.547497!4d7.985275"

    private fun has(text: String, sub: Boolean = true) = rule.onAllNodesWithText(text, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(File("build/reports/screens").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before
    fun searchStartsOff() = runBlocking { app.container.prefs.update { it.copy(placeSearchConsent = false) } }

    @After
    fun resetGeocoder() = ShadowGeocoder.reset()

    private fun share(text: String) {
        rule.activityRule.scenario.onActivity { a ->
            val m = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            m.invoke(a, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
        }
        rule.waitForIdle()
    }

    @Test
    fun aPlaceSharedFromMapsStartsANewTrip() {
        share(jungfrau)
        assertTrue("the share opens Add to a trip", has("Add to a trip"))
        assertTrue(has("Jungfraujoch", sub = false))
        save("share-add-to-trip")
        rule.onNodeWithText("A new trip").performClick()
        rule.waitForIdle()
        assertTrue("the editor has the shared place as a stop", has("Jungfraujoch", sub = false) && has("Add the destination"))
    }

    @Test
    fun theClipboardIsOfferedAndReadOnlyWhenTapped() {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("map", jungfrau))
        rule.onAllNodesWithText("Trips").onFirst().performClick()
        rule.onNodeWithText("New trip").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Find a place"))
        rule.onNodeWithText("Find a place").performClick()
        rule.waitForIdle()
        save("place-picker")
        rule.onNodeWithText("Paste from clipboard").performClick()
        rule.waitForIdle()
        assertFalse("the picker closed after picking", rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty())
        assertTrue(has("Jungfraujoch", sub = false))
    }

    @Test
    fun searchByNameAsksFirstAndSendsNothingUntilAllowed() {
        rule.onAllNodesWithText("Trips").onFirst().performClick()
        rule.onNodeWithText("New trip").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Find a place"))
        rule.onNodeWithText("Find a place").performClick()
        rule.onAllNodes(hasSetTextAction()).onLast().performTextInput("Zermatt")
        rule.onNodeWithText("Go").performClick()
        rule.waitForIdle()
        assertTrue("asks before searching online", has("Search online for “Zermatt”?"))
        assertFalse("nothing searched yet", has("Nothing found") || has("Results for"))
        assertFalse(runBlocking { app.container.prefs.settings.first().placeSearchConsent })
        save("place-search-consent")

        rule.onNodeWithText("Turn on search").performClick()
        // Robolectric's geocoder answers with no places, so the search ran and reports that honestly.
        rule.waitUntil(5_000) { has("Nothing found for “Zermatt”") }
        assertTrue("the consent is remembered", runBlocking { app.container.prefs.settings.first().placeSearchConsent })
    }

    @Test
    fun yourOwnPlacesAreOfferedWithoutSearching() {
        runBlocking { app.container.waypoints.add("Base camp", com.trailblazer.core.geo.LatLon(46.0, 7.7), null) }
        rule.onAllNodesWithText("Trips").onFirst().performClick()
        rule.onNodeWithText("New trip").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Find a place"))
        rule.onNodeWithText("Find a place").performClick()
        rule.waitUntil(5_000) { has("Base camp") }
        assertTrue(has("Your places"))
        rule.onNodeWithText("Base camp").performClick()
        rule.waitForIdle()
        assertTrue(has("Add the destination"))
    }
}

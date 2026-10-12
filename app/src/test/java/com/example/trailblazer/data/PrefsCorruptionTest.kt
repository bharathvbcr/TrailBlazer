package com.example.trailblazer.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A settings file that no longer parses must reset to defaults, not crash every read and write.
 *
 * Each test opens its own store over a fresh garbage file. The app-wide store is a process singleton that other
 * tests have already read from, so it cannot be the first reader of a corrupt file here; these stores are built
 * with the same [settingsCorruptionHandler] the app-wide one is declared with.
 */
@RunWith(AndroidJUnit4::class)
class PrefsCorruptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun garbageFile(name: String): File {
        val file = File(context.cacheDir, "corrupt-$name/settings.preferences_pb")
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(64) { 0xFF.toByte() }) // a varint that never ends: not a valid protobuf
        return file
    }

    private fun store(file: File, handled: Boolean): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = if (handled) settingsCorruptionHandler else null,
        scope = scope,
        produceFile = { file },
    )

    @Test
    fun garbageBytesInTheSettingsFileFallBackToDefaultsAndStayWritable() = runBlocking {
        val prefs = PrefsRepository(context, store(garbageFile("handled"), handled = true))
        assertEquals(Settings(), prefs.settings.first())

        prefs.update { it.copy(nightRed = true, theme = ThemeMode.Dark) }
        val after = prefs.settings.first()
        assertTrue(after.nightRed)
        assertEquals(ThemeMode.Dark, after.theme)

        prefs.clear()
        assertEquals(Settings(), prefs.settings.first())
    }

    @Test
    fun theSameGarbageBreaksEveryEditWithoutTheHandler() = runBlocking {
        // Control: proves the input above really is corrupt, so the test above is not passing vacuously.
        val unhandled = store(garbageFile("unhandled"), handled = false)
        try {
            unhandled.edit { it.clear() }
            fail("editing a corrupt file without a handler should throw")
        } catch (_: CorruptionException) {
            // expected
        }
        try {
            PrefsRepository(context, unhandled).update { it.copy(nightRed = true) }
            fail("the repository's update should throw on the unhandled store")
        } catch (_: CorruptionException) {
            // expected
        }
    }
}

package com.example.trailblazer.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
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

/**
 * A settings file that no longer parses must reset to defaults, not crash every read and write.
 *
 * The stores here come from [createSettingsStore], the same function that builds the app-wide store, so taking the
 * corruption handler out of production configuration fails these tests. They use a file name of their own because
 * DataStore allows one active instance per file and the app-wide store is already open on "settings".
 */
@RunWith(AndroidJUnit4::class)
class PrefsCorruptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun writeGarbage(name: String) {
        val file = context.preferencesDataStoreFile(name)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(64) { 0xFF.toByte() }) // a varint that never ends: not a valid protobuf
    }

    @Test
    fun garbageBytesInTheSettingsFileFallBackToDefaultsAndStayWritable() = runBlocking {
        writeGarbage("corrupt-handled")
        val prefs = PrefsRepository(context, createSettingsStore(context, scope, "corrupt-handled"))
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
        writeGarbage("corrupt-unhandled")
        val unhandled = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("corrupt-unhandled") }
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

package com.example.trailblazer.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Runtime permissions the app asks for, each only when the feature that needs it is first used. */
enum class AppPermission(val manifest: Array<String>) {
    Location(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Camera(arrayOf(Manifest.permission.CAMERA)),
    Microphone(arrayOf(Manifest.permission.RECORD_AUDIO)),
    Notifications(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
    ActivityRecognition(if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()),
}

/**
 * Current grant state. Refreshed by the activity on resume and after every permission result, so
 * repositories that depend on a permission restart as soon as it is granted or revoked in Settings.
 */
class Permissions(private val context: Context) {
    private val state = MutableStateFlow(snapshot())
    val granted: StateFlow<Set<AppPermission>> get() = state

    /** Location counts as granted with either fine or coarse (approximate) access. */
    fun isGranted(p: AppPermission): Boolean = when (p) {
        AppPermission.Location -> p.manifest.any { has(it) }
        else -> p.manifest.all { has(it) }
    }

    fun hasFineLocation(): Boolean = has(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun has(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    private fun snapshot(): Set<AppPermission> = AppPermission.entries.filter { isGranted(it) }.toSet()

    fun refresh() {
        state.value = snapshot()
    }

    fun flowOf(p: AppPermission): Flow<Boolean> = state.map { p in it }.distinctUntilChanged()
}

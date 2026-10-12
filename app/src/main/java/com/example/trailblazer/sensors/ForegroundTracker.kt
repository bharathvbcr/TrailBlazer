package com.example.trailblazer.sensors

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether any of the app's activities is started (visible). Android 9+ does not deliver continuous sensor events to a
 * background app that has no foreground service, so the sensor logger marks these changes in its file. A configuration
 * change (rotation) stops one activity and starts the next; it is not reported as leaving the foreground.
 * Needs no library: it is a plain `ActivityLifecycleCallbacks`.
 */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    @Synchronized
    override fun onActivityStarted(activity: Activity) {
        started++
        _isForeground.value = true
    }

    @Synchronized
    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        if (started == 0 && !activity.isChangingConfigurations) _isForeground.value = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

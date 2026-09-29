package com.example.trailblazer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.ui.nav.TrailNav
import com.example.trailblazer.ui.theme.TrailTheme
import com.example.trailblazer.weather.PressureSampler
import kotlinx.coroutines.launch

/**
 * Single activity. Nothing is requested at launch: every permission is asked for by the screen that
 * needs it, when the user first opens that feature.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = container
        val sampler = PressureSampler(c.barometer.pressureHpa, c.location.fix, c.pressureHistory, c.clock)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                c.permissions.refresh()
                launch { c.tracking.resumeIfInterrupted() }
                sampler.run()
            }
        }
        setContent {
            val settings by c.prefs.settings.collectAsStateWithLifecycle(null)
            TrailTheme(
                mode = settings?.theme ?: ThemeMode.System,
                dynamicColor = settings?.dynamicColor ?: true,
                nightRed = settings?.nightRed ?: false,
            ) { TrailNav() }
        }
    }

    override fun onResume() {
        super.onResume()
        // A permission may have been changed in system settings while we were away.
        container.permissions.refresh()
    }
}

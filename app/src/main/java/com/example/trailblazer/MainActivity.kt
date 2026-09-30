package com.example.trailblazer

import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Single activity. Nothing is requested at launch: every permission is asked for by the screen that
 * needs it, when the user first opens that feature.
 */
class MainActivity : ComponentActivity() {
    /** Text another app shared to us (Maps' Share → TrailBlazer), waiting for the navigation to take it. */
    private val shared = MutableStateFlow<String?>(null)

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
        // Only on a fresh start: after a rotation or process restore the share was already taken.
        if (savedInstanceState == null) takeShare(intent)
        setContent {
            val settings by c.prefs.settings.collectAsStateWithLifecycle(null)
            val pending by shared.collectAsStateWithLifecycle()
            TrailTheme(
                mode = settings?.theme ?: ThemeMode.System,
                dynamicColor = settings?.dynamicColor ?: true,
                nightRed = settings?.nightRed ?: false,
            ) { TrailNav(pending) { shared.value = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    private fun takeShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        // Untrusted text from another app: bounded here, and only ever parsed (a short link is looked up on a tap).
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.take(MAX_SHARED_CHARS)
        if (!text.isNullOrEmpty()) shared.value = text
    }

    override fun onResume() {
        super.onResume()
        // A permission may have been changed in system settings while we were away.
        container.permissions.refresh()
    }
}

private const val MAX_SHARED_CHARS = 4_000

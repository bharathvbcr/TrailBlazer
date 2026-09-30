package com.example.trailblazer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.trailblazer.container
import com.example.trailblazer.links.LinkLookupResult
import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.RefusalReason
import com.trailblazer.core.geo.ShortLinks
import kotlinx.coroutines.launch

/** What to tell the user when pasted text gave no position. One wording for every coordinate or link field. */
fun parseProblem(r: CoordinateParse): String? = when (r) {
    is CoordinateParse.Found -> null
    is CoordinateParse.Refused -> when (r.reason) {
        RefusalReason.ShortLinkNeedsNetwork -> "This is a short link. It only says where it points once it is looked up online."
        RefusalReason.NoCoordinatesInLink ->
            "This link names a place but carries no coordinates. In your maps app, long-press the spot to drop a pin and share that, or copy its coordinates."
    }
    CoordinateParse.NotRecognized -> "Not recognised. Try 46.5582, 7.8352 or a map link."
}

/** True when [r] is a short link the user could look up with [ShortLinkLookup]. */
fun needsLookup(r: CoordinateParse?): Boolean = r is CoordinateParse.Refused && r.reason == RefusalReason.ShortLinkNeedsNetwork

/**
 * The opt-in lookup offered after a short link was refused. Nothing is sent until the button is tapped; then only the
 * link goes to the shortener that issued it, and the full link it points to is parsed on the phone. [onParsed] gets
 * that parse (which may still have no coordinates) and the full link; [onError] a message when the lookup itself failed.
 */
@Composable
fun ShortLinkLookup(text: String, onParsed: (CoordinateParse, String) -> Unit, onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val lookup = LocalContext.current.container.linkLookup
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val host = ShortLinks.find(text)?.let(ShortLinks::host) ?: return
    Column(modifier) {
        Spacer(Modifier.height(4.dp))
        FilledTonalButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    when (val r = lookup.resolve(text)) {
                        is LinkLookupResult.Parsed -> onParsed(r.parse, r.resolvedUrl)
                        is LinkLookupResult.Failed -> onError(r.message)
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Looking up…" else "Look up online") }
        Text(
            "Sends only this link to $host to see where it points. Your location is not sent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package com.example.trailblazer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space the global bottom bar occupies, so the last list item can scroll clear of it. */
val LocalBottomInset = compositionLocalOf { 0.dp }

private val TopBarHeight = 64.dp

/**
 * Standard screen: a seamless header over a scrolling column. The header has no bar, border or blur edge; once
 * content scrolls under it, a scrim in the backdrop's own top colour fades in and fades out downwards, so the title
 * stays readable without a visible seam.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val bottom = LocalBottomInset.current
    val hInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val layoutDir = LocalLayoutDirection.current
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(
                start = 16.dp + hInsets.calculateStartPadding(layoutDir),
                end = 16.dp + hInsets.calculateEndPadding(layoutDir),
                top = TopBarHeight + WindowInsets.statusBars.asPaddingTop() + 8.dp,
                // The keyboard covers the bottom of an edge-to-edge window; keep focused fields above it.
                bottom = maxOf(bottom, imeBottom) + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        TopBar(title, onBack, actions, scrolled = state.canScrollBackward)
    }
}

@Composable
private fun WindowInsets.asPaddingTop(): Dp = asPaddingValues().calculateTopPadding()

/** Extra fade below the header so the scrim ends softly instead of at a line. */
private val ScrimFade = 24.dp

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, scrolled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val backdrop = backdropBrush(endY = LocalWindowInfo.current.containerSize.height.toFloat())
    val scrim by animateFloatAsState(if (scrolled) 1f else 0f, label = "headerScrim")
    Box(
        Modifier
            .fillMaxWidth()
            // Offscreen so the fade mask below applies to the repainted backdrop only.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                if (scrim > 0f) {
                    // Repaint the backdrop exactly as it is behind us (same colours, same coordinates), fully
                    // opaque under the title, then fade it out over the last ScrimFade: no line, no colour step.
                    drawRect(cs.background)
                    drawRect(backdrop)
                    val solid = ((size.height - ScrimFade.toPx()) / size.height).coerceIn(0f, 1f)
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black.copy(alpha = scrim), solid to Color.Black.copy(alpha = scrim), 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .padding(bottom = ScrimFade),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
                .height(TopBarHeight)
                .padding(horizontal = 4.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(TrailIcons.Back, contentDescription = "Back")
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp)
                    .semantics { heading() },
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

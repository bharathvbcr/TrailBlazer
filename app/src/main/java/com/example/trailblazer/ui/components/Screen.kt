package com.example.trailblazer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Space the global bottom bar occupies, so the last list item can scroll clear of it. */
val LocalBottomInset = compositionLocalOf { 0.dp }

private val TopBarHeight = 64.dp

/**
 * Standard screen: a frosted top bar over a scrolling column. The column is the blur source for the bar;
 * items scroll underneath it.
 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val haze = rememberHazeState()
    val bottom = LocalBottomInset.current
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalHaze provides haze) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().hazeSource(haze),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = TopBarHeight + WindowInsets.statusBars.asPaddingTop() + 8.dp,
                    bottom = bottom + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            TopBar(title, onBack, actions)
        }
    }
}

@Composable
private fun WindowInsets.asPaddingTop(): Dp = asPaddingValues().calculateTopPadding()

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    GlassChrome(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(TopBarHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(TrailIcons.Back, contentDescription = "Back") }
            } else {
                Box(Modifier.padding(start = 12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            actions()
        }
    }
}

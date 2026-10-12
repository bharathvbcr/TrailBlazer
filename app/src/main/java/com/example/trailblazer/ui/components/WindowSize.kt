package com.example.trailblazer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The size of the window the app is drawing in, in dp (not the screen: split-screen and freeform windows are smaller
 * than the display). It changes with rotation, folding and window resizing. `material3-window-size-class` and
 * `material3-adaptive` are not dependencies of this app, so layouts read the window itself.
 */
@Composable
fun currentWindowSize(): DpSize {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) { DpSize(size.width.toDp(), size.height.toDp()) }
}

private val SkyChartMaxSide = 420.dp
private val SkyChartMinSide = 220.dp

/** Space a screen's header, the card around the chart and its margins take above and below the chart. */
private val SkyChartChrome = 160.dp

/**
 * The side of the square sky chart: 420 dp at most, and no taller than the window can show with its header, so in
 * landscape on a phone the chart fits the screen instead of running off the bottom of it.
 */
fun skyChartSide(windowHeight: Dp): Dp = (windowHeight - SkyChartChrome).coerceIn(SkyChartMinSide, SkyChartMaxSide)

package com.example.trailblazer.ui.trips

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trailblazer.data.DecodedTile
import com.example.trailblazer.data.OfflineTileSource
import com.example.trailblazer.data.Waypoint
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.stopLetter
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.plot.SeriesPlot
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun OfflineMapCanvas(
    tileSource: OfflineTileSource? = null,
    stops: List<Stop> = emptyList(),
    waypoints: List<Waypoint> = emptyList(),
    tracks: List<List<LatLon>> = emptyList(),
    currentLocation: LatLon? = null,
    accuracyM: Double? = null,
    modifier: Modifier = Modifier,
    initialCenter: LatLon? = null,
    initialZoom: Double? = null,
    onImportClick: (() -> Unit)? = null,
    distanceFormatter: ((Double) -> String)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Initial center and zoom based on content
        val allPoints = remember(stops, waypoints, tracks, currentLocation) {
            buildList {
                addAll(stops.map { it.position })
                addAll(waypoints.map { it.position })
                tracks.forEach { addAll(it) }
                currentLocation?.let { add(it) }
            }
        }

        val defaultViewport = remember(allPoints, widthPx, heightPx) {
            if (allPoints.isNotEmpty()) {
                TileUtils.fitViewport(allPoints, widthPx.toInt().coerceAtLeast(100), heightPx.toInt().coerceAtLeast(100), paddingPx = 48)
            } else {
                Pair(initialCenter ?: LatLon(46.0, 7.0), initialZoom ?: 12.0)
            }
        }

        var center by remember { mutableStateOf(initialCenter ?: defaultViewport.first) }
        var zoom by remember { mutableDoubleStateOf(initialZoom ?: defaultViewport.second) }

        // Layer toggles
        var showTrips by remember { mutableStateOf(true) }
        var showWaypoints by remember { mutableStateOf(true) }
        var showTracks by remember { mutableStateOf(true) }

        // LRU caches for tile bitmaps and vector tiles
        val bitmapCache = remember { LruCache<String, Bitmap>(64) }
        val vectorCache = remember { LruCache<String, TileUtils.VectorTile>(64) }
        var cacheRevision by remember { mutableIntStateOf(0) }
        val coroutineScope = rememberCoroutineScope()

        val measurer = rememberTextMeasurer()
        val cs = MaterialTheme.colorScheme

        val minAllowedZoom = (tileSource?.minZoom?.toDouble() ?: 1.0).coerceAtLeast(1.0)
        val maxAllowedZoom = (tileSource?.maxZoom?.toDouble() ?: 18.0).coerceAtMost(20.0)

        // Request missing tiles in background
        val intZoom = floor(zoom).toInt().coerceIn(minAllowedZoom.toInt(), maxAllowedZoom.toInt())
        val range = remember(center, zoom, widthPx, heightPx, intZoom) {
            TileUtils.visibleTileRange(center, zoom, widthPx, heightPx)
        }

        LaunchedEffect(range, tileSource) {
            if (tileSource == null) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                var loadedAny = false
                val z = range.zoom
                for (x in range.minX..range.maxX) {
                    for (y in range.minY..range.maxY) {
                        val key = "$z/$x/$y"
                        if (bitmapCache.get(key) == null && vectorCache.get(key) == null) {
                            val decoded = tileSource.getTile(z, x, y)
                            if (decoded != null) {
                                when (decoded) {
                                    is DecodedTile.Raster -> bitmapCache.put(key, decoded.bitmap)
                                    is DecodedTile.Vector -> vectorCache.put(key, decoded.vectorTile)
                                }
                                loadedAny = true
                            }
                        }
                    }
                }
                if (loadedAny) {
                    withContext(Dispatchers.Main) {
                        cacheRevision++
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(center, zoom) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val oldZoom = zoom
                        val newZoom = (zoom * zoomChange).coerceIn(1.0, 19.0)
                        val newCenter = TileUtils.screenToLatLon(
                            size.width / 2f - pan.x,
                            size.height / 2f - pan.y,
                            center,
                            oldZoom,
                            size.width.toFloat(),
                            size.height.toFloat(),
                        )
                        center = newCenter
                        zoom = newZoom
                    }
                }
                .pointerInput(center, zoom) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            val tappedPos = TileUtils.screenToLatLon(
                                tapOffset.x,
                                tapOffset.y,
                                center,
                                zoom,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            center = tappedPos
                            zoom = (zoom + 1.0).coerceAtMost(maxAllowedZoom)
                        },
                    )
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Offline topographical map canvas" },
            ) {
                // Read revision so cache updates trigger recomposition
                @Suppress("UNUSED_VARIABLE")
                val rev = cacheRevision

                // 1. Draw background
                drawRect(Color(0xFF161A1D))

                // 2. Draw subtle topo coordinate grid
                drawTopoGrid(center, zoom, size.width, size.height, cs.outlineVariant.copy(alpha = 0.25f))

                // 3. Render tiles (raster or vector)
                if (tileSource != null) {
                    drawTiles(
                        tileSource = tileSource,
                        range = range,
                        center = center,
                        zoom = zoom,
                        widthPx = size.width,
                        heightPx = size.height,
                        bitmapCache = bitmapCache,
                        vectorCache = vectorCache,
                    )
                }

                // 4. Draw overlays
                // Recorded GPS tracks
                if (showTracks) {
                    tracks.forEach { path ->
                        if (path.size >= 2) {
                            drawTrackPath(path, center, zoom, size.width, size.height, cs.primary)
                        }
                    }
                }

                // Planned trip routes and stops
                if (showTrips && stops.isNotEmpty()) {
                    drawTripRoute(stops, center, zoom, size.width, size.height, cs, measurer)
                }

                // Saved standalone waypoints
                if (showWaypoints && waypoints.isNotEmpty()) {
                    drawWaypoints(waypoints, center, zoom, size.width, size.height, cs, measurer)
                }

                // Current GPS location
                if (currentLocation != null) {
                    drawCurrentLocation(currentLocation, accuracyM, center, zoom, size.width, size.height, cs)
                }

                // 5. Draw Scale Bar and North Arrow
                drawScaleBar(center, zoom, size.width, size.height, cs, measurer, distanceFormatter)
                drawNorthArrow(size.width, cs)
            }

            // Top HUD: Map status badge and layer chips
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        tonalElevation = 3.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                if (tileSource != null) TrailIcons.Route else TrailIcons.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (tileSource != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (tileSource != null) "${tileSource.name} (z${"%.1f".format(zoom)})" else "No map loaded",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Layer filter chips
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (stops.isNotEmpty()) {
                        FilterChip(
                            selected = showTrips,
                            onClick = { showTrips = !showTrips },
                            label = { Text("Trip", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                        )
                    }
                    if (waypoints.isNotEmpty()) {
                        FilterChip(
                            selected = showWaypoints,
                            onClick = { showWaypoints = !showWaypoints },
                            label = { Text("Pins (${waypoints.size})", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                        )
                    }
                    if (tracks.isNotEmpty()) {
                        FilterChip(
                            selected = showTracks,
                            onClick = { showTracks = !showTracks },
                            label = { Text("Tracks", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                        )
                    }
                }
            }

            // Empty state notice if no map archive loaded
            if (tileSource == null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No Offline Topo Map Loaded",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Import local .mbtiles or .pmtiles contour or raster maps for 100% offline wilderness navigation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        if (onImportClick != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onImportClick,
                                shape = CircleShape,
                            ) {
                                Icon(TrailIcons.Import, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import map archive")
                            }
                        }
                    }
                }
            }

            // Bottom-right on-screen map action buttons
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentLocation != null) {
                    SmallFloatingActionButton(
                        onClick = { center = currentLocation },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        elevation = FloatingActionButtonDefaults.elevation(2.dp),
                    ) {
                        Icon(TrailIcons.MyLocation, contentDescription = "My location", modifier = Modifier.size(18.dp))
                    }
                }

                if (allPoints.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = {
                            val fit = TileUtils.fitViewport(allPoints, widthPx.toInt(), heightPx.toInt())
                            center = fit.first
                            zoom = fit.second
                        },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        elevation = FloatingActionButtonDefaults.elevation(2.dp),
                    ) {
                        Icon(TrailIcons.Route, contentDescription = "Fit to content", modifier = Modifier.size(18.dp))
                    }
                }

                SmallFloatingActionButton(
                    onClick = { zoom = (zoom + 1.0).coerceAtMost(maxAllowedZoom) },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp),
                ) {
                    Icon(TrailIcons.Add, contentDescription = "Zoom in", modifier = Modifier.size(18.dp))
                }

                SmallFloatingActionButton(
                    onClick = { zoom = (zoom - 1.0).coerceAtLeast(minAllowedZoom) },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp),
                ) {
                    Icon(TrailIcons.Close, contentDescription = "Zoom out", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun DrawScope.drawTopoGrid(center: LatLon, zoom: Double, widthPx: Float, heightPx: Float, gridColor: Color) {
    val step = 256f * (2.0.pow(zoom - floor(zoom))).toFloat()
    if (step <= 0f) return
    var x = (widthPx / 2f) % step
    while (x < widthPx) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, heightPx), strokeWidth = 1f)
        x += step
    }
    var y = (heightPx / 2f) % step
    while (y < heightPx) {
        drawLine(gridColor, Offset(0f, y), Offset(widthPx, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawTiles(
    tileSource: OfflineTileSource,
    range: TileUtils.TileRange,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    bitmapCache: LruCache<String, Bitmap>,
    vectorCache: LruCache<String, TileUtils.VectorTile>,
) {
    val z = range.zoom
    val scaleFactor = 2.0.pow(zoom - z)
    val tileSizeOnScreen = (TileUtils.TILE_SIZE * scaleFactor).toFloat()

    for (x in range.minX..range.maxX) {
        for (y in range.minY..range.maxY) {
            val key = "$z/$x/$y"
            val bounds = TileUtils.tileBounds(x, y, z)
            val topLeftScreen = TileUtils.latLonToScreen(LatLon(bounds.maxLat, bounds.minLon), center, zoom, widthPx, heightPx)

            // 1. Try drawing cached raster tile
            val bmp = bitmapCache.get(key)
            if (bmp != null) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(topLeftScreen.x.roundToInt(), topLeftScreen.y.roundToInt()),
                    dstSize = IntSize(tileSizeOnScreen.roundToInt(), tileSizeOnScreen.roundToInt()),
                )
                continue
            }

            // 2. Try drawing cached vector tile (contours / trails)
            val vt = vectorCache.get(key)
            if (vt != null) {
                drawVectorTile(vt, topLeftScreen.x, topLeftScreen.y, tileSizeOnScreen)
                continue
            }

            // 3. Fallback: try parent tile at z - 1 if available in cache
            if (z > 0) {
                val pZ = z - 1
                val pX = x / 2
                val pY = y / 2
                val pKey = "$pZ/$pX/$pY"
                val pBmp = bitmapCache.get(pKey)
                if (pBmp != null) {
                    val subX = (x % 2) * (pBmp.width / 2)
                    val subY = (y % 2) * (pBmp.height / 2)
                    val subW = pBmp.width / 2
                    val subH = pBmp.height / 2
                    drawImage(
                        image = pBmp.asImageBitmap(),
                        srcOffset = IntOffset(subX, subY),
                        srcSize = IntSize(subW, subH),
                        dstOffset = IntOffset(topLeftScreen.x.roundToInt(), topLeftScreen.y.roundToInt()),
                        dstSize = IntSize(tileSizeOnScreen.roundToInt(), tileSizeOnScreen.roundToInt()),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawVectorTile(
    tile: TileUtils.VectorTile,
    tileScreenX: Float,
    tileScreenY: Float,
    tileSize: Float,
) {
    val contourMajorColor = Color(0xFFC48A54)
    val contourMinorColor = Color(0xFF8C5E35).copy(alpha = 0.7f)
    val waterColor = Color(0xFF3884B8).copy(alpha = 0.8f)
    val trailColor = Color(0xFFE67E22)

    for (layer in tile.layers) {
        val isWater = layer.name.contains("water", ignoreCase = true)
        val isRoad = layer.name.contains("road", ignoreCase = true) || layer.name.contains("trail", ignoreCase = true)

        for (feature in layer.features) {
            val color = when {
                feature.isContour -> {
                    val ele = feature.elevationM
                    if (ele != null && ele.toInt() % 100 == 0) contourMajorColor else contourMinorColor
                }
                isWater -> waterColor
                isRoad -> trailColor
                else -> Color(0xFF7F8C8D)
            }
            val strokeW = when {
                feature.isContour && (feature.elevationM?.toInt()?.rem(100) == 0) -> 2.2f
                feature.isContour -> 1.2f
                isWater -> 2.5f
                isRoad -> 1.8f
                else -> 1.0f
            }

            for (line in feature.geometry) {
                if (line.size < 2) continue
                val path = Path()
                val firstX = tileScreenX + line[0].x * tileSize
                val firstY = tileScreenY + line[0].y * tileSize
                path.moveTo(firstX, firstY)
                for (i in 1 until line.size) {
                    val px = tileScreenX + line[i].x * tileSize
                    val py = tileScreenY + line[i].y * tileSize
                    path.lineTo(px, py)
                }
                drawPath(path, color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
            }
        }
    }
}

private fun DrawScope.drawTrackPath(
    path: List<LatLon>,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    primaryColor: Color,
) {
    val p = Path()
    val first = TileUtils.latLonToScreen(path.first(), center, zoom, widthPx, heightPx)
    p.moveTo(first.x, first.y)
    for (i in 1 until path.size) {
        val pt = TileUtils.latLonToScreen(path[i], center, zoom, widthPx, heightPx)
        p.lineTo(pt.x, pt.y)
    }
    // Draw track line with slight glow
    drawPath(p, primaryColor.copy(alpha = 0.35f), style = Stroke(width = 8f, cap = StrokeCap.Round))
    drawPath(p, primaryColor, style = Stroke(width = 4f, cap = StrokeCap.Round))

    // Start marker (Green)
    drawCircle(Color(0xFF2ECC71), 7f, Offset(first.x, first.y))
    // End marker (Red)
    val last = TileUtils.latLonToScreen(path.last(), center, zoom, widthPx, heightPx)
    drawCircle(Color(0xFFE74C3C), 7f, Offset(last.x, last.y))
}

private fun DrawScope.drawTripRoute(
    stops: List<Stop>,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    cs: androidx.compose.material3.ColorScheme,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val screenPts = stops.map { TileUtils.latLonToScreen(it.position, center, zoom, widthPx, heightPx) }

    // Connect stops with dashed line
    val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
    for (i in 0 until screenPts.size - 1) {
        drawLine(
            color = cs.primary,
            start = Offset(screenPts[i].x, screenPts[i].y),
            end = Offset(screenPts[i + 1].x, screenPts[i + 1].y),
            strokeWidth = 3f,
            pathEffect = dash,
        )
    }

    // Stop markers
    val letterStyle = TextStyle(fontSize = 11.sp, color = cs.onPrimary, fontWeight = FontWeight.Bold)
    screenPts.forEachIndexed { i, pt ->
        val color = when (stops[i].kind) {
            StopKind.Start -> cs.primary
            StopKind.End -> cs.error
            StopKind.Night -> cs.secondary
            StopKind.Visit -> cs.tertiary
        }
        val centerOffset = Offset(pt.x, pt.y)
        drawCircle(Color.Black.copy(alpha = 0.5f), 14f, centerOffset)
        drawCircle(color, 12f, centerOffset)

        val text = stopLetter(i)
        val layout = measurer.measure(text, letterStyle)
        drawText(layout, topLeft = Offset(pt.x - layout.size.width / 2f, pt.y - layout.size.height / 2f))
    }
}

private fun DrawScope.drawWaypoints(
    waypoints: List<Waypoint>,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    cs: androidx.compose.material3.ColorScheme,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val nameStyle = TextStyle(fontSize = 10.sp, color = cs.onSurface, fontWeight = FontWeight.Medium)
    for (w in waypoints) {
        val pt = TileUtils.latLonToScreen(w.position, center, zoom, widthPx, heightPx)
        if (pt.x < -50 || pt.x > widthPx + 50 || pt.y < -50 || pt.y > heightPx + 50) continue

        val centerOffset = Offset(pt.x, pt.y)
        drawCircle(cs.secondary, 6f, centerOffset)
        drawCircle(Color.White, 3f, centerOffset)

        val layout = measurer.measure(w.name, nameStyle)
        // Background chip for text readability
        drawRect(
            color = cs.surface.copy(alpha = 0.75f),
            topLeft = Offset(pt.x + 8f, pt.y - layout.size.height / 2f - 2f),
            size = Size(layout.size.width.toFloat() + 4f, layout.size.height.toFloat() + 4f),
        )
        drawText(layout, topLeft = Offset(pt.x + 10f, pt.y - layout.size.height / 2f))
    }
}

private fun DrawScope.drawCurrentLocation(
    pos: LatLon,
    accuracyM: Double?,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    cs: androidx.compose.material3.ColorScheme,
) {
    val pt = TileUtils.latLonToScreen(pos, center, zoom, widthPx, heightPx)
    val centerOffset = Offset(pt.x, pt.y)

    // Accuracy circle
    if (accuracyM != null && accuracyM > 0f) {
        val mpp = TileUtils.metersPerPixel(pos.lat, zoom)
        val radiusPx = (accuracyM / mpp).toFloat().coerceIn(8f, widthPx / 2f)
        drawCircle(Color(0xFF3498DB).copy(alpha = 0.18f), radiusPx, centerOffset)
        drawCircle(Color(0xFF3498DB).copy(alpha = 0.45f), radiusPx, centerOffset, style = Stroke(1.5f))
    }

    // Dot with white border
    drawCircle(Color.White, 9f, centerOffset)
    drawCircle(Color(0xFF2980B9), 7f, centerOffset)
}

private fun DrawScope.drawScaleBar(
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    cs: androidx.compose.material3.ColorScheme,
    measurer: androidx.compose.ui.text.TextMeasurer,
    distanceFormatter: ((Double) -> String)?,
) {
    val mpp = TileUtils.metersPerPixel(center.lat, zoom)
    val targetPx = (widthPx / 4f).coerceIn(60f, 200f)
    val targetDistM = targetPx * mpp
    val niceDistM = SeriesPlot.niceLength(targetDistM)
    val actualPx = (niceDistM / mpp).toFloat()

    val x0 = 16f
    val y = heightPx - 16f
    val h = 6f

    val barColor = cs.onSurface.copy(alpha = 0.85f)
    drawLine(barColor, Offset(x0, y), Offset(x0 + actualPx, y), strokeWidth = 2.5f)
    drawLine(barColor, Offset(x0, y - h), Offset(x0, y + h), strokeWidth = 2.5f)
    drawLine(barColor, Offset(x0 + actualPx, y - h), Offset(x0 + actualPx, y + h), strokeWidth = 2.5f)

    val label = distanceFormatter?.invoke(niceDistM) ?: if (niceDistM >= 1000.0) "${niceDistM / 1000.0} km" else "${niceDistM.toInt()} m"
    val layout = measurer.measure(label, TextStyle(fontSize = 10.sp, color = barColor, fontWeight = FontWeight.Medium))
    drawText(layout, topLeft = Offset(x0 + 4f, y - layout.size.height - 3f))
}

private fun DrawScope.drawNorthArrow(
    widthPx: Float,
    cs: androidx.compose.material3.ColorScheme,
) {
    val n = Offset(widthPx - 24f, 32f)
    val arrowColor = cs.onSurface.copy(alpha = 0.9f)
    val path = Path().apply {
        moveTo(n.x, n.y - 14f)
        lineTo(n.x - 6f, n.y + 6f)
        lineTo(n.x, n.y + 2f)
        lineTo(n.x + 6f, n.y + 6f)
        close()
    }
    drawPath(path, arrowColor)
}

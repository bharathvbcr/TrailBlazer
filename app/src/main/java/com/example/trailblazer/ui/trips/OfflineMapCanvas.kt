package com.example.trailblazer.ui.trips

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.example.trailblazer.data.OfflineTileSource
import com.example.trailblazer.data.Waypoint
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.stopLetter
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.TileUtils
import com.trailblazer.core.plot.SeriesPlot
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** The widest zoom out the gestures allow; overlays stay visible even where the archive has no tiles. */
private const val MIN_VIEW_ZOOM = 1.0

/**
 * Pans and zooms an offline map archive with the user's trips, waypoints, recorded tracks and live position drawn over
 * it. [routes] are separate trips: each is joined start to end on its own, never to the next one.
 */
@Composable
fun OfflineMapCanvas(
    modifier: Modifier = Modifier,
    tileSource: OfflineTileSource? = null,
    routes: List<List<Stop>> = emptyList(),
    waypoints: List<Waypoint> = emptyList(),
    tracks: List<List<LatLon>> = emptyList(),
    currentLocation: LatLon? = null,
    accuracyM: Double? = null,
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
        val allPoints = remember(routes, waypoints, tracks, currentLocation) {
            buildList {
                routes.forEach { r -> addAll(r.map { it.position }) }
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

        // The view may stretch the archive's deepest tiles a few levels ("overzoom") and no further.
        val maxAllowedZoom = tileSource?.let { TileUtils.maxViewZoom(it.maxZoom) } ?: 19.0

        var center by remember { mutableStateOf(initialCenter ?: defaultViewport.first) }
        var zoom by remember { mutableDoubleStateOf((initialZoom ?: defaultViewport.second).coerceIn(MIN_VIEW_ZOOM, maxAllowedZoom)) }
        LaunchedEffect(maxAllowedZoom) { if (zoom > maxAllowedZoom) zoom = maxAllowedZoom }

        // Layer toggles
        var showTrips by remember { mutableStateOf(true) }
        var showWaypoints by remember { mutableStateOf(true) }
        var showTracks by remember { mutableStateOf(true) }

        // Byte-bounded caches and the loader that fills them; a new archive (or screen size) starts from empty ones.
        // The budget holds a whole worst-case screen of tiles (see rasterCacheBytes) within a share of the heap limit.
        val loader = remember(tileSource, widthPx.toInt(), heightPx.toInt()) {
            tileSource?.let {
                val heap = Runtime.getRuntime().maxMemory()
                TileLoader(
                    it,
                    TileCache<Bitmap>(rasterCacheBytes(widthPx.toInt(), heightPx.toInt(), heap)) { b -> b.allocationByteCount.toLong() },
                    TileCache(vectorCacheBytes(widthPx.toInt(), heightPx.toInt(), heap)) { v -> v.bytes },
                )
            }
        }
        // Drawing reuses one projection buffer and one path for every track on every frame.
        val trackBuffer = remember { TileUtils.PolylineBuffer() }
        val trackPath = remember { Path() }
        var cacheRevision by remember { mutableIntStateOf(0) }

        val measurer = rememberTextMeasurer()
        val cs = MaterialTheme.colorScheme

        val maxZoomNow by rememberUpdatedState(maxAllowedZoom)
        val sizeNow by rememberUpdatedState(Pair(widthPx, heightPx))

        // Fetch the tiles the view needs. A new view cancels the old one's remaining tiles; decoding is off the main thread.
        LaunchedEffect(loader, tileSource) {
            val src = tileSource ?: return@LaunchedEffect
            val ld = loader ?: return@LaunchedEffect
            snapshotFlow {
                val (w, h) = sizeNow
                TileUtils.renderTileZoom(zoom, src.minZoom, src.maxZoom)?.let { tz ->
                    TileUtils.visibleTileRange(center, zoom, w, h, tz)
                }
            }.distinctUntilChanged().collectLatest { range ->
                if (range != null) {
                    withContext(Dispatchers.IO) { ld.load(range) { cacheRevision++ } }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val view = TileUtils.applyGesture(
                            center, zoom, centroid.x, centroid.y, pan.x, pan.y, zoomChange,
                            size.width.toFloat(), size.height.toFloat(), MIN_VIEW_ZOOM, maxZoomNow,
                        )
                        center = view.center
                        zoom = view.zoom
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            // Zoom in one level about the tapped point, which stays where it is.
                            val view = TileUtils.applyGesture(
                                center, zoom, tap.x, tap.y, 0f, 0f, 2f,
                                size.width.toFloat(), size.height.toFloat(), MIN_VIEW_ZOOM, maxZoomNow,
                            )
                            center = view.center
                            zoom = view.zoom
                        },
                    )
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Offline topographical map canvas" },
            ) {
                // Read revision so cache updates trigger a redraw
                @Suppress("UNUSED_VARIABLE")
                val rev = cacheRevision

                // 1. Draw background
                drawRect(Color(0xFF161A1D))

                // 2. Draw subtle topo coordinate grid
                drawTopoGrid(center, zoom, size.width, size.height, cs.outlineVariant.copy(alpha = 0.25f))

                // 3. Render tiles (raster or vector)
                if (tileSource != null && loader != null) {
                    val tz = TileUtils.renderTileZoom(zoom, tileSource.minZoom, tileSource.maxZoom)
                    if (tz != null) {
                        drawTiles(
                            range = TileUtils.visibleTileRange(center, zoom, size.width, size.height, tz),
                            center = center,
                            zoom = zoom,
                            widthPx = size.width,
                            heightPx = size.height,
                            loader = loader,
                        )
                    }
                }

                // 4. Draw overlays
                // Recorded GPS tracks
                if (showTracks) {
                    tracks.forEach { path -> drawTrackPath(path, center, zoom, size.width, size.height, cs.primary, trackBuffer, trackPath) }
                }

                // Planned trip routes and stops
                if (showTrips) {
                    routes.forEach { stops ->
                        if (stops.isNotEmpty()) drawTripRoute(stops, center, zoom, size.width, size.height, cs, measurer)
                    }
                }

                // Saved standalone waypoints
                if (showWaypoints && waypoints.isNotEmpty()) {
                    drawWaypoints(waypoints, center, zoom, size.width, size.height, cs, measurer)
                }

                // Current GPS location
                if (currentLocation != null) {
                    drawCurrentLocation(currentLocation, accuracyM, center, zoom, size.width, size.height)
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
                    if (routes.any { it.isNotEmpty() }) {
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
                            zoom = fit.second.coerceIn(MIN_VIEW_ZOOM, maxAllowedZoom)
                        },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        elevation = FloatingActionButtonDefaults.elevation(2.dp),
                    ) {
                        Icon(TrailIcons.Route, contentDescription = "Fit to content", modifier = Modifier.size(18.dp))
                    }
                }

                SmallFloatingActionButton(
                    onClick = { zoom = (zoom + 1.0).coerceIn(MIN_VIEW_ZOOM, maxAllowedZoom) },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp),
                ) {
                    Icon(TrailIcons.Add, contentDescription = "Zoom in", modifier = Modifier.size(18.dp))
                }

                SmallFloatingActionButton(
                    onClick = { zoom = (zoom - 1.0).coerceIn(MIN_VIEW_ZOOM, maxAllowedZoom) },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(2.dp),
                ) {
                    Icon(TrailIcons.Remove, contentDescription = "Zoom out", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Grid lines on the edges of the tiles at the current level, so they move with the map rather than the screen. */
private fun DrawScope.drawTopoGrid(center: LatLon, zoom: Double, widthPx: Float, heightPx: Float, gridColor: Color) {
    val tz = floor(zoom).toInt().coerceIn(TileUtils.MIN_ZOOM, TileUtils.MAX_ZOOM)
    val step = (TileUtils.TILE_SIZE * 2.0.pow(zoom - tz)).toFloat()
    if (!step.isFinite() || step < 8f) return
    val origin = TileUtils.tileOriginOnScreen(0, 0, tz, center, zoom, widthPx, heightPx)
    if (!origin.x.isFinite() || !origin.y.isFinite()) return
    var x = ((origin.x % step) + step) % step
    while (x < widthPx) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, heightPx), strokeWidth = 1f)
        x += step
    }
    var y = ((origin.y % step) + step) % step
    while (y < heightPx) {
        drawLine(gridColor, Offset(0f, y), Offset(widthPx, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawTiles(
    range: TileUtils.TileRange,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    loader: TileLoader,
) {
    val z = range.zoom
    val tileSize = (TileUtils.TILE_SIZE * 2.0.pow(zoom - z)).toFloat()

    for (x in range.minX..range.maxX) {
        val wx = TileUtils.wrapTileX(x, z)
        for (y in range.minY..range.maxY) {
            val origin = TileUtils.tileOriginOnScreen(x, y, z, center, zoom, widthPx, heightPx)
            val left = floor(origin.x).toInt()
            val top = floor(origin.y).toInt()
            // Neighbouring tiles share an edge pixel instead of leaving a hairline gap between rounded sizes.
            val dst = IntSize(ceil(origin.x + tileSize).toInt() - left, ceil(origin.y + tileSize).toInt() - top)

            // 1. The tile itself, raster or vector (contours / trails).
            val bmp = loader.raster(z, wx, y)
            if (bmp != null) {
                drawImage(image = bmp.asImageBitmap(), dstOffset = IntOffset(left, top), dstSize = dst)
                continue
            }
            val vt = loader.vector(z, wx, y)
            if (vt != null) {
                drawPreparedTile(vt, origin.x, origin.y, tileSize)
                continue
            }

            // 2. Fallback: the nearest cached ancestor, stretched to fill this tile.
            var ax = x
            var ay = y
            for (d in 1..TileLoader.ANCESTOR_DEPTH) {
                val az = z - d
                if (az < 0) break
                ax = ax shr 1
                ay = ay shr 1
                val pw = TileUtils.wrapTileX(ax, az)
                val span = 1 shl d
                val pBmp = loader.raster(az, pw, ay)
                if (pBmp != null) {
                    val subW = pBmp.width / span
                    val subH = pBmp.height / span
                    if (subW > 0 && subH > 0) {
                        drawImage(
                            image = pBmp.asImageBitmap(),
                            srcOffset = IntOffset((wx and (span - 1)) * subW, (y and (span - 1)) * subH),
                            srcSize = IntSize(subW, subH),
                            dstOffset = IntOffset(left, top),
                            dstSize = dst,
                        )
                    }
                    break
                }
                val pVec = loader.vector(az, pw, ay)
                if (pVec != null) {
                    val parent = TileUtils.tileOriginOnScreen(ax, ay, az, center, zoom, widthPx, heightPx)
                    clipRect(left.toFloat(), top.toFloat(), (left + dst.width).toFloat(), (top + dst.height).toFloat()) {
                        drawPreparedTile(pVec, parent.x, parent.y, tileSize * span)
                    }
                    break
                }
            }
        }
    }
}

private val ContourMajorColor = Color(0xFFC48A54)
private val ContourMinorColor = Color(0xFF8C5E35).copy(alpha = 0.7f)
private val WaterColor = Color(0xFF3884B8).copy(alpha = 0.8f)
private val TrailColor = Color(0xFFE67E22)
private val OtherColor = Color(0xFF7F8C8D)
private val PointColor = Color(0xFFE8E8E8)

private fun colorOf(kind: VectorKind): Color = when (kind) {
    VectorKind.ContourMajor -> ContourMajorColor
    VectorKind.ContourMinor -> ContourMinorColor
    VectorKind.Water -> WaterColor
    VectorKind.Road -> TrailColor
    VectorKind.Other -> OtherColor
}

/**
 * Draws a prepared tile with its top-left at ([x], [y]) and [size] pixels across. The paths are in tile-normalised
 * coordinates, so one scale transform places them and stroke widths are divided by the scale to stay in pixels.
 */
private fun DrawScope.drawPreparedTile(tile: PreparedVectorTile, x: Float, y: Float, size: Float) {
    if (size <= 0f || !size.isFinite()) return
    withTransform({
        translate(x, y)
        scale(size, size, pivot = Offset.Zero)
    }) {
        for (kp in tile.paths) {
            drawPath(kp.path, colorOf(kp.kind), style = Stroke(width = kp.kind.strokeWidth / size, cap = StrokeCap.Round))
        }
    }
    val pts = tile.points
    var i = 0
    while (i + 1 < pts.size) {
        val c = Offset(x + pts[i] * size, y + pts[i + 1] * size)
        drawCircle(Color.Black.copy(alpha = 0.5f), 4.5f, c)
        drawCircle(PointColor, 3f, c)
        i += 2
    }
}

private fun DrawScope.drawTrackPath(
    points: List<LatLon>,
    center: LatLon,
    zoom: Double,
    widthPx: Float,
    heightPx: Float,
    primaryColor: Color,
    buffer: TileUtils.PolylineBuffer,
    p: Path,
) {
    if (points.isEmpty()) return
    // Sub-pixel neighbours are dropped; first and last points are kept exactly.
    TileUtils.projectPolyline(points, center, zoom, widthPx, heightPx, buffer)
    val n = buffer.count
    if (n == 0) return
    val xy = buffer.xy
    if (n >= 2) {
        p.rewind()
        p.moveTo(xy[0], xy[1])
        for (i in 1 until n) p.lineTo(xy[i * 2], xy[i * 2 + 1])
        // Draw track line with slight glow
        drawPath(p, primaryColor.copy(alpha = 0.35f), style = Stroke(width = 8f, cap = StrokeCap.Round))
        drawPath(p, primaryColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
    // Start marker (Green) and end marker (Red); a one-fix track is just its start.
    drawCircle(Color(0xFF2ECC71), 7f, Offset(xy[0], xy[1]))
    if (n >= 2) drawCircle(Color(0xFFE74C3C), 7f, Offset(xy[n * 2 - 2], xy[n * 2 - 1]))
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
    if (!mpp.isFinite() || mpp <= 0.0) return
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

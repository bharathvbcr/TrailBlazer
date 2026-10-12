package com.trailblazer.core.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapViewportTest {
    private val w = 1080f
    private val h = 1920f

    // ---- gestures ----

    @Test
    fun pinchingByAFactorAddsItsLogarithmToTheZoom() {
        assertEquals(13.0, TileUtils.zoomAfterPinch(12.0, 2.0, 1.0, 20.0), 1e-9)
        assertEquals(11.0, TileUtils.zoomAfterPinch(12.0, 0.5, 1.0, 20.0), 1e-9)
        // 5% wider fingers is a twentieth of a level, not "12 x 1.05 = 12.6".
        assertEquals(12.0704, TileUtils.zoomAfterPinch(12.0, 1.05, 1.0, 20.0), 1e-3)
        assertEquals(20.0, TileUtils.zoomAfterPinch(19.9, 4.0, 1.0, 20.0), 1e-9)
        assertEquals(1.0, TileUtils.zoomAfterPinch(1.2, 0.1, 1.0, 20.0), 1e-9)
        // Nonsense scales leave the zoom alone.
        for (bad in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(12.0, TileUtils.zoomAfterPinch(12.0, bad, 1.0, 20.0), "scale $bad")
        }
    }

    @Test
    fun theWorldUnderTheFingersStaysUnderThem() {
        val center = LatLon(46.0, 7.0)
        val zoom = 12.0
        val anchor = TileUtils.screenToLatLon(300f, 500f, center, zoom, w, h)
        val view = TileUtils.applyGesture(center, zoom, 300f, 500f, 20f, -30f, 1.7f, w, h, 1.0, 20.0)
        val after = TileUtils.latLonToScreen(anchor, view.center, view.zoom, w, h)
        assertEquals(320f, after.x, 0.5f)
        assertEquals(470f, after.y, 0.5f)
        assertEquals(zoom + kotlin.math.log2(1.7), view.zoom, 1e-5)
    }

    @Test
    fun aPurePanMovesTheCentreTheOppositeWay() {
        val center = LatLon(46.0, 7.0)
        val view = TileUtils.applyGesture(center, 12.0, 540f, 960f, 100f, 0f, 1f, w, h, 1.0, 20.0)
        assertTrue(view.center.lon < center.lon, "dragging right reveals the west")
        assertEquals(center.lat, view.center.lat, 1e-9)
        assertEquals(12.0, view.zoom, 1e-12)
        val down = TileUtils.applyGesture(center, 12.0, 540f, 960f, 0f, 100f, 1f, w, h, 1.0, 20.0)
        assertTrue(down.center.lat > center.lat, "dragging down reveals the north")
    }

    @Test
    fun panningAcrossTheAntimeridianWrapsTheCentre() {
        var view = TileUtils.MapView(LatLon(10.0, 179.99), 12.0)
        repeat(20) {
            view = TileUtils.applyGesture(view.center, view.zoom, 540f, 960f, -300f, 0f, 1f, w, h, 1.0, 20.0)
            assertTrue(view.center.lon in -180.0..180.0, "lon ${view.center.lon}")
        }
        assertTrue(view.center.lon < 0.0, "went round to the western hemisphere: ${view.center.lon}")
    }

    @Test
    fun panningTowardThePolesStopsAtMercatorsLimit() {
        var view = TileUtils.MapView(LatLon(84.0, 0.0), 4.0)
        repeat(50) { view = TileUtils.applyGesture(view.center, view.zoom, 540f, 960f, 0f, 400f, 1f, w, h, 1.0, 20.0) }
        assertTrue(view.center.lat <= TileUtils.MAX_LAT + 1e-9, "lat ${view.center.lat}")
        var south = TileUtils.MapView(LatLon(-84.0, 0.0), 4.0)
        repeat(50) { south = TileUtils.applyGesture(south.center, south.zoom, 540f, 960f, 0f, -400f, 1f, w, h, 1.0, 20.0) }
        assertTrue(south.center.lat >= -TileUtils.MAX_LAT - 1e-9)
        val nan = TileUtils.applyGesture(LatLon(1.0, 1.0), 5.0, 1f, 1f, Float.NaN, Float.NaN, Float.NaN, w, h, 1.0, 20.0)
        assertEquals(5.0, nan.zoom, 1e-12)
        assertEquals(1.0, nan.center.lat, 1e-6)
    }

    // ---- projection ----

    @Test
    fun poleProjectionStaysFinite() {
        val c = LatLon(0.0, 0.0)
        for (lat in doubleArrayOf(90.0, -90.0, 89.999, 85.0511, -85.0511)) {
            val p = TileUtils.latLonToScreen(LatLon(lat, 0.0), c, 5.0, w, h)
            assertTrue(p.x.isFinite() && p.y.isFinite(), "lat $lat -> $p")
        }
        for (lat in doubleArrayOf(90.0, -90.0)) {
            val mpp = TileUtils.metersPerPixel(lat, 10.0)
            assertTrue(mpp.isFinite() && mpp > 0.0, "mpp at $lat = $mpp")
        }
        assertEquals(0.0, TileUtils.latToTileY(90.0, 3), 1e-6)
        assertEquals(8.0, TileUtils.latToTileY(-90.0, 3), 1e-6)
        assertEquals(7, TileUtils.latLonToTile(-90.0, 0.0, 3).y)
        assertEquals(7, TileUtils.latLonToTile(0.0, 180.0, 3).x)
        assertEquals(0, TileUtils.latLonToTile(0.0, -180.0, 3).x)
    }

    @Test
    fun theScaleBarMeasuresRealGroundDistance() {
        // 1000 screen px east-west at 46 N / zoom 12, against an independent haversine distance.
        for ((lat, zoom) in listOf(46.0 to 12.0, 0.0 to 10.0, 60.0 to 14.0, -70.0 to 9.0, 70.0 to 8.0)) {
            val c = LatLon(lat, 10.0)
            val left = TileUtils.screenToLatLon(w / 2 - 500f, h / 2, c, zoom, w, h)
            val right = TileUtils.screenToLatLon(w / 2 + 500f, h / 2, c, zoom, w, h)
            val truth = Geo.distanceM(left, right)
            val claimed = 1000.0 * TileUtils.metersPerPixel(lat, zoom)
            assertTrue(abs(claimed - truth) / truth < 0.01, "lat $lat z$zoom: scale bar says $claimed m, haversine says $truth m")
        }
        // 60 degrees is half the equator's metres per pixel.
        assertEquals(TileUtils.metersPerPixel(0.0, 5.0) / 2.0, TileUtils.metersPerPixel(60.0, 5.0), 1e-6)
    }

    @Test
    fun theWorldWrapsTheShortWayRoundForOverlays() {
        val c = LatLon(0.0, 179.9)
        val east = TileUtils.latLonToScreen(LatLon(0.0, -179.9), c, 10.0, w, h)
        val west = TileUtils.latLonToScreen(LatLon(0.0, 179.8), c, 10.0, w, h)
        assertTrue(east.x > w / 2 && east.x - w / 2 < 300f, "east neighbour ${east.x}")
        assertTrue(west.x < w / 2 && w / 2 - west.x < 200f, "west neighbour ${west.x}")
        assertEquals(179.0, TileUtils.wrapLon(-181.0), 1e-9)
        assertEquals(-180.0, TileUtils.wrapLon(180.0), 1e-9)
        assertEquals(-179.0, TileUtils.wrapLon(181.0), 1e-9)
        assertEquals(0.0, TileUtils.wrapLon(720.0), 1e-9)
        assertTrue(TileUtils.wrapLon(Double.NaN).isNaN())
    }

    // ---- polylines ----

    @Test
    fun anEmptyOrSinglePointTrackProjectsToWhatIsThere() {
        val c = LatLon(46.0, 7.0)
        assertEquals(0, TileUtils.projectPolyline(emptyList(), c, 12.0, w, h).size)
        val one = TileUtils.projectPolyline(listOf(c), c, 12.0, w, h)
        assertEquals(2, one.size)
        assertEquals(w / 2, one[0], 0.01f)
        assertEquals(h / 2, one[1], 0.01f)
    }

    @Test
    fun aPolylineDropsSubPixelPointsButKeepsBothEnds() {
        val c = LatLon(46.0, 7.0)
        val pts = ArrayList<LatLon>()
        for (i in 0..10_000) pts.add(LatLon(46.0 + i * 1e-7, 7.0)) // ~1 cm apart: invisible at z12
        pts.add(LatLon(46.01, 7.01))
        val out = TileUtils.projectPolyline(pts, c, 12.0, w, h)
        assertTrue(out.size / 2 < 10, "10,000 invisible points collapse, kept ${out.size / 2}")
        val end = TileUtils.latLonToScreen(pts.last(), c, 12.0, w, h)
        assertEquals(end.x, out[out.size - 2], 1e-3f)
        assertEquals(end.y, out[out.size - 1], 1e-3f)
        val start = TileUtils.latLonToScreen(pts.first(), c, 12.0, w, h)
        assertEquals(start.x, out[0], 1e-3f)
    }

    @Test
    fun theLastDroppedPointIsStillTheEndOfTheLine() {
        val c = LatLon(46.0, 7.0)
        // Two nearby points: the second is within a pixel of the first but it is the end of the track.
        val pts = listOf(LatLon(46.0, 7.0), LatLon(46.0, 7.0 + 1e-6))
        val out = TileUtils.projectPolyline(pts, c, 12.0, w, h)
        assertEquals(4, out.size)
        val end = TileUtils.latLonToScreen(pts[1], c, 12.0, w, h)
        assertEquals(end.x, out[2], 1e-4f)
    }

    @Test
    fun aTrackAcrossTheAntimeridianIsContinuousOnScreen() {
        val c = LatLon(0.0, 179.95)
        val pts = (0..20).map { LatLon(0.0, TileUtils.wrapLon(179.9 + it * 0.01)) }
        val out = TileUtils.projectPolyline(pts, c, 14.0, w, h, minStepPx = 0f)
        assertEquals(pts.size, out.size / 2)
        for (i in 1 until out.size / 2) {
            val dx = out[i * 2] - out[(i - 1) * 2]
            assertTrue(dx > 0f && dx < 300f, "step $i moved $dx px")
        }
    }

    @Test
    fun nonFinitePointsAreSkipped() {
        val c = LatLon(0.0, 0.0)
        val pts = listOf(LatLon(0.0, 0.0), LatLon(0.001, 0.001))
        val out = TileUtils.projectPolyline(pts, c, 10.0, Float.NaN, h)
        assertEquals(0, out.size, "a NaN viewport yields nothing rather than NaN coordinates")
    }

    // ---- fit & tile ranges ----

    @Test
    fun fittingATrackOverTheAntimeridianCentresOnIt() {
        val pts = listOf(LatLon(-17.0, 179.5), LatLon(-17.2, -179.6), LatLon(-17.1, 180.0))
        val (center, zoom) = TileUtils.fitViewport(pts, 1080, 1920)
        assertTrue(abs(center.lon) > 179.0, "centre ${center.lon} sits on the dateline, not at 0")
        assertTrue(zoom > 6.0, "a 1 degree route fits at high zoom, not $zoom")
    }

    @Test
    fun fittingDegenerateInputsIsSafe() {
        assertEquals(2.0, TileUtils.fitViewport(emptyList(), 100, 100).second)
        val same = LatLon(10.0, 10.0)
        val (c, z) = TileUtils.fitViewport(listOf(same, same, same), 100, 100)
        assertEquals(10.0, c.lat, 1e-9)
        assertTrue(z in 1.0..18.0)
        val (_, tiny) = TileUtils.fitViewport(listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.0)), 10, 10, paddingPx = 500)
        assertTrue(tiny in 1.0..18.0)
    }

    @Test
    fun visibleTilesAreFewAndWrapAcrossTheAntimeridian() {
        val r = TileUtils.visibleTileRange(LatLon(0.0, 179.999), 12.0, w, h)
        assertEquals(12, r.zoom)
        assertTrue(r.maxX - r.minX + 1 <= 6, "columns ${r.maxX - r.minX + 1}")
        assertTrue(r.maxX >= (1 shl 12), "the view reaches past the east edge: ${r.maxX}")
        assertEquals(0, TileUtils.wrapTileX(1 shl 12, 12))
        assertEquals((1 shl 12) - 1, TileUtils.wrapTileX(-1, 12))
        assertEquals(7, TileUtils.wrapTileX(-1, 3))
        // A viewport can never ask for more than the cap, whatever its size or zoom.
        for ((zoom, width) in listOf(2.0 to 5_000_000f, 22.0 to 100_000f, 0.5 to 1e9f)) {
            val big = TileUtils.visibleTileRange(LatLon(50.0, 3.0), zoom, width, width)
            assertTrue(big.maxX - big.minX + 1 <= TileUtils.MAX_TILE_SPAN, "cols at z$zoom")
            assertTrue(big.maxY - big.minY + 1 <= TileUtils.MAX_TILE_SPAN, "rows at z$zoom")
        }
        val pole = TileUtils.visibleTileRange(LatLon(85.0, 0.0), 2.0, w, h)
        assertTrue(pole.minY >= 0 && pole.maxY <= 3, "rows clamp to the world: ${pole.minY}..${pole.maxY}")
    }

    @Test
    fun tilesAreAnchoredToTheWorldNotTheScreenCentre() {
        val c = LatLon(46.0, 7.0)
        val t = TileUtils.latLonToTile(c, 12)
        val origin = TileUtils.tileOriginOnScreen(t.x, t.y, 12, c, 12.0, w, h)
        assertTrue(origin.x <= w / 2 && origin.x > w / 2 - 256f, "origin x ${origin.x}")
        assertTrue(origin.y <= h / 2 && origin.y > h / 2 - 256f, "origin y ${origin.y}")
        // At a deeper view the same tile is drawn twice the size: its far corner moves.
        val near = TileUtils.tileOriginOnScreen(t.x + 1, t.y, 12, c, 13.0, w, h)
        val base = TileUtils.tileOriginOnScreen(t.x, t.y, 12, c, 13.0, w, h)
        assertEquals(512f, near.x - base.x, 0.01f)
        // Column -1 is a real, drawable column west of the antimeridian.
        val west = TileUtils.tileOriginOnScreen(-1, 0, 3, LatLon(0.0, -179.0), 3.0, w, h)
        assertTrue(west.x.isFinite())
    }

    @Test
    fun theTileLevelFollowsTheViewAndTheArchive() {
        assertEquals(12, TileUtils.renderTileZoom(12.7, 10, 15))
        assertEquals(15, TileUtils.renderTileZoom(16.4, 10, 15))
        assertEquals(15, TileUtils.renderTileZoom(21.9, 10, 15), "deep zoom stretches the deepest level")
        assertEquals(10, TileUtils.renderTileZoom(9.5, 10, 15), "one level out still shows the shallowest tiles")
        assertNull(TileUtils.renderTileZoom(8.9, 10, 15), "further out there is nothing to draw")
        assertEquals(18.0, TileUtils.maxViewZoom(15), 1e-9)
        assertEquals(22.0, TileUtils.maxViewZoom(30), 1e-9)
        assertNotNull(TileUtils.renderTileZoom(1.0, 0, 0))
    }

    // ---- tile coordinates ----

    @Test
    fun mbtilesRowsAreAMirrorOfXyzRows() {
        // z2: four rows. XYZ y=0 is the northernmost row and TMS row 3.
        assertEquals(3, TileUtils.xyzYToTmsY(0, 2))
        assertEquals(0, TileUtils.xyzYToTmsY(3, 2))
        for (z in 0..20) {
            val y = (1 shl z) / 3
            assertEquals(y, TileUtils.tmsYToXyzY(TileUtils.xyzYToTmsY(y, z), z))
        }
        assertTrue(TileUtils.tileBounds(0, 0, 1).maxLat > 85.0, "XYZ row 0 touches the north limit")
        assertTrue(TileUtils.tileBounds(0, 1, 1).minLat < -85.0)
        assertEquals((1 shl 30) - 1, TileUtils.xyzYToTmsY(0, 30))
        assertTrue(TileUtils.isValidTile(30, (1 shl 30) - 1, 0))
        assertTrue(!TileUtils.isValidTile(31, 0, 0))
        assertTrue(!TileUtils.isValidTile(3, 8, 0))
        assertTrue(!TileUtils.isValidTile(3, 0, -1))
    }

    @Test
    fun tileCornersWrapAndClampInsteadOfThrowing() {
        val past = TileUtils.tileToLatLon((1 shl 5) + 3.0, -2.0, 5)
        assertTrue(past.lon in -180.0..180.0 && past.lat <= TileUtils.MAX_LAT)
        val coord = TileUtils.TileCoord(7, 7, 3)
        assertTrue(coord.bounds().contains(coord.bounds().center()))
        assertTrue(TileUtils.lonToTileX(0.0, 99) > 0.0)
    }
}

package com.trailblazer.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemTest {
    private fun at(lat: Double, lon: Double) = LatLon(lat, lon)

    // ---- file names and sizes -------------------------------------------------------------------------------

    @Test
    fun fileNamesParseInAllFourHemisphereCombinations() {
        assertEquals(DemTileId(36, -119), DemTileId.parse("N36W119.hgt"))
        assertEquals(DemTileId(-33, -71), DemTileId.parse("S33W071.hgt"))
        assertEquals(DemTileId(27, 86), DemTileId.parse("N27E086.hgt"))
        assertEquals(DemTileId(-4, 37), DemTileId.parse("S04E037.hgt"))
    }

    @Test
    fun fileNamesRoundTripAndAcceptRealDownloadSpellings() {
        assertEquals("N36W119.hgt", DemTileId(36, -119).fileName)
        assertEquals("S33W071.hgt", DemTileId(-33, -71).fileName)
        assertEquals("N27E086.hgt", DemTileId(27, 86).fileName)
        assertEquals("S04E037.hgt", DemTileId(-4, 37).fileName)
        assertEquals("N00E000.hgt", DemTileId(0, 0).fileName)
        assertEquals("S01W001.hgt", DemTileId(-1, -1).fileName)
        assertEquals(DemTileId(37, -105), DemTileId.parse("N37W105.SRTMGL1.hgt"))
        assertEquals(DemTileId(37, -105), DemTileId.parse("n37w105.HGT"))
        for (lat in listOf(-90, -45, -1, 0, 1, 45, 89)) {
            for (lon in listOf(-180, -90, -1, 0, 1, 90, 179)) {
                val id = DemTileId(lat, lon)
                assertEquals(id, DemTileId.parse(id.fileName), id.fileName)
            }
        }
    }

    @Test
    fun namesThatCannotBePlacedAreRejected() {
        for (bad in listOf(
            "", "N36W119", "N36W119.hgt.zip", "N36W119.txt", "36N119W.hgt", "N3W119.hgt", "N36W19.hgt", "N36 W119.hgt",
            "X36W119.hgt", "N36Q119.hgt", "S00E010.hgt", "N10W000.hgt", "N90E010.hgt", "S91E010.hgt", "N10E180.hgt",
            "N10W181.hgt", "../N36W119.hgt", "N36W119.hgt\n", "N36W119..hgt", "N36W119.a b.hgt",
        )) {
            assertNull(DemTileId.parse(bad), "should reject '$bad'")
        }
    }

    @Test
    fun theSizeMustBeExactlyOneOfTheTwoGrids() {
        val oneSecond = 3601L * 3601 * 2
        val threeSecond = 1201L * 1201 * 2
        assertEquals(25_934_402L, oneSecond)
        assertEquals(2_884_802L, threeSecond)
        assertEquals(DemInspection.Valid(DemTileId(36, -119), DemResolution.OneArcSecond), Hgt.inspect("N36W119.hgt", oneSecond))
        assertEquals(DemInspection.Valid(DemTileId(36, -119), DemResolution.ThreeArcSecond), Hgt.inspect("N36W119.hgt", threeSecond))
        for (size in listOf(0L, 1L, threeSecond - 1, threeSecond + 1, threeSecond + 2, oneSecond - 2, oneSecond + 2, 1201L * 1201, 3601L * 3601, 3601L * 3601 * 4)) {
            assertEquals(DemInspection.Rejected(DemRejection.BadSize), Hgt.inspect("N36W119.hgt", size), "size $size")
        }
    }

    @Test
    fun compressedArchivesGetTheirOwnReason() {
        assertEquals(DemInspection.Rejected(DemRejection.CompressedArchive), Hgt.inspect("N36W119.hgt.zip", 1_000_000))
        assertEquals(DemInspection.Rejected(DemRejection.CompressedArchive), Hgt.inspect("N36W119.SRTMGL1.hgt.zip", 2_884_802))
        assertEquals(DemInspection.Rejected(DemRejection.CompressedArchive), Hgt.inspect("N36W119.HGT.GZ", 2_884_802))
        assertEquals(DemInspection.Rejected(DemRejection.BadName), Hgt.inspect("elevation.hgt", 2_884_802))
    }

    @Test
    fun openRefusesWhatInspectRefuses() {
        val zero = { _: Long, n: Int -> ByteArray(n) }
        assertNull(DemTile.open("N36W119.hgt", 2_884_801, zero))
        assertNull(DemTile.open("nonsense.hgt", 2_884_802, zero))
        assertNotNull(DemTile.open("N36W119.hgt", 2_884_802, zero))
    }

    // ---- which tile holds a point ---------------------------------------------------------------------------

    @Test
    fun aPointIsInTheTileOfItsFloorCorner() {
        assertEquals(DemTileId(36, -119), DemTileId.candidates(at(36.5785, -118.2923)).first())
        assertEquals(DemTileId(-33, -71), DemTileId.candidates(at(-32.6532, -70.0109)).first())
        assertEquals(DemTileId(27, 86), DemTileId.candidates(at(27.9881, 86.9250)).first())
        assertEquals(DemTileId(-4, 37), DemTileId.candidates(at(-3.0758, 37.3533)).first())
        assertEquals(listOf(DemTileId(36, -119)), DemTileId.candidates(at(36.5785, -118.2923)))
    }

    @Test
    fun wholeDegreesBelongToBothNeighbours() {
        val ids = DemTileId.candidates(at(36.0, -119.0)).toSet()
        assertEquals(setOf(DemTileId(36, -119), DemTileId(35, -119), DemTileId(36, -120), DemTileId(35, -120)), ids)
        assertEquals(DemTileId(0, 0), DemTileId.candidates(at(0.0, 0.0)).first())
        assertTrue(DemTileId(-1, -1) in DemTileId.candidates(at(0.0, 0.0)))
    }

    @Test
    fun theNorthPoleTheSouthPoleAndTheAntimeridianLandInTheLastTiles() {
        assertEquals(listOf(DemTileId(89, 10)), DemTileId.candidates(at(90.0, 10.5)))
        assertEquals(listOf(DemTileId(-90, 10)), DemTileId.candidates(at(-90.0, 10.5)))
        assertEquals(listOf(DemTileId(10, 179), DemTileId(10, -180)), DemTileId.candidates(at(10.5, 180.0)))
        assertEquals(listOf(DemTileId(10, -180), DemTileId(10, 179)), DemTileId.candidates(at(10.5, -180.0)))
        assertEquals(DemTileId(89, 179), DemTileId.candidates(at(90.0, 180.0)).first())
    }

    // ---- known summits at their real coordinates ------------------------------------------------------------

    /**
     * The four posts around the summit are set in the "file" at hard-coded rows and columns worked out by hand from the
     * tile's corner (rows count down from the north edge, columns across from the west edge). If the sampler mixed up
     * rows and columns, forgot that row 0 is the north edge, or took the wrong corner for a southern or western tile,
     * it would read other posts and not return the summit.
     */
    private fun assertSummit(id: DemTileId, resolution: DemResolution, row: Int, col: Int, lat: Double, lon: Double, elevationM: Int) {
        val fake = FakeHgt(resolution, summit(row, col, elevationM))
        val tile = fake.tile(id)
        val sampled = assertNotNull(tile.elevationM(at(lat, lon)), "no elevation at $lat, $lon")
        assertEquals(elevationM.toDouble(), sampled, 1e-9, "summit in ${id.fileName}")
        // Away from the summit the ground really is lower, so the match above is not a flat tile.
        val inwardLat = if (lat - id.southLat > 0.5) -0.05 else 0.05
        val inwardLon = if (lon - id.westLon > 0.5) -0.05 else 0.05
        val away = assertNotNull(tile.elevationM(at(lat + inwardLat, lon + inwardLon)))
        assertTrue(away < elevationM - 100, "$away")
    }

    @Test
    fun mountWhitneyInTheNorthWesternTile() {
        // (37 - 36.5785) x 1200 = 505.8 rows from the north edge; (-118.2923 + 119) x 1200 = 849.2 columns from the west.
        assertSummit(DemTileId(36, -119), DemResolution.ThreeArcSecond, 505, 849, 36.5785, -118.2923, 4421)
    }

    @Test
    fun mountWhitneyAtOneArcSecond() {
        // The same corner at three times the density: 1517.4 rows and 2547.7 columns.
        assertSummit(DemTileId(36, -119), DemResolution.OneArcSecond, 1517, 2547, 36.5785, -118.2923, 4421)
    }

    @Test
    fun aconcaguaInTheSouthWesternTile() {
        // Tile S33W071 spans 33-32 S: (-32 + 32.6532) x 1200 = 783.8 rows down from -32; (-70.0109 + 71) x 1200 = 1186.9 across.
        assertSummit(DemTileId(-33, -71), DemResolution.ThreeArcSecond, 783, 1186, -32.6532, -70.0109, 6961)
    }

    @Test
    fun everestInTheNorthEasternTile() {
        // (28 - 27.9881) x 1200 = 14.3 rows; (86.9250 - 86) x 1200 = 1110 columns.
        assertSummit(DemTileId(27, 86), DemResolution.ThreeArcSecond, 14, 1110, 27.9881, 86.9250, 8849)
    }

    @Test
    fun kilimanjaroInTheSouthEasternTile() {
        // Tile S04E037 spans 4-3 S: (-3 + 3.0758) x 1200 = 91.0 rows... 90.96, so the post pair is 90 and 91; 423.96 columns.
        assertSummit(DemTileId(-4, 37), DemResolution.ThreeArcSecond, 90, 423, -3.0758, 37.3533, 5895)
    }

    // ---- interpolation, voids and edges ---------------------------------------------------------------------

    /** Posts: row 0 = [100, 200], row 1 = [300, 400] at rows/cols 10-11, everything else 0 (and -32768 where [voidAt]). */
    private fun smallGrid(voidAt: Pair<Int, Int>? = null): DemTile {
        val fake = FakeHgt(DemResolution.ThreeArcSecond) { r, c ->
            when {
                voidAt != null && r == voidAt.first && c == voidAt.second -> Hgt.VOID
                r == 10 && c == 10 -> 100
                r == 10 && c == 11 -> 200
                r == 11 && c == 10 -> 300
                r == 11 && c == 11 -> 400
                else -> 0
            }
        }
        return fake.tile(DemTileId(10, 20))
    }

    private fun postLat(row: Int) = 11.0 - row / 1200.0
    private fun postLon(col: Int) = 20.0 + col / 1200.0

    @Test
    fun bilinearInterpolationBetweenFourKnownPosts() {
        val tile = smallGrid()
        fun e(fr: Double, fc: Double) = tile.elevationM(at(postLat(10) - fr / 1200.0, postLon(10) + fc / 1200.0))
        assertEquals(100.0, e(0.0, 0.0)!!, 1e-6)
        assertEquals(200.0, e(0.0, 1.0)!!, 1e-6)
        assertEquals(300.0, e(1.0, 0.0)!!, 1e-6)
        assertEquals(400.0, e(1.0, 1.0)!!, 1e-6)
        assertEquals(150.0, e(0.0, 0.5)!!, 1e-6)
        assertEquals(200.0, e(0.5, 0.0)!!, 1e-6)
        assertEquals(250.0, e(0.5, 0.5)!!, 1e-6)
        // 0.25 down, 0.75 across: 0.75*0.75*100 + 0.25*0.75*200... = (1-.75)(1-.25)100 + .75(1-.25)200 + (1-.75).25*300 + .75*.25*400
        assertEquals(0.1875 * 100 + 0.5625 * 200 + 0.0625 * 300 + 0.1875 * 400, e(0.25, 0.75)!!, 1e-6)
    }

    @Test
    fun everyPostIsReadBackExactlyAtItsOwnCoordinates() {
        val fake = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled)
        val tile = fake.tile(DemTileId(-12, 130))
        for ((r, c) in listOf(0 to 0, 0 to 1200, 1200 to 0, 1200 to 1200, 0 to 600, 600 to 0, 1200 to 600, 600 to 1200, 1 to 1, 599 to 733)) {
            val lat = -11.0 - r / 1200.0
            val lon = 130.0 + c / 1200.0
            assertEquals(scrambled(r, c).toDouble(), tile.elevationM(at(lat, lon)), "post row $r col $c")
        }
    }

    @Test
    fun negativeElevationsKeepTheirSign() {
        // Badwater Basin, Death Valley, 36.2461 N 116.8258 W, about -86 m: 0xFFAA on disk.
        val fake = FakeHgt(DemResolution.ThreeArcSecond) { _, _ -> -86 }
        val tile = fake.tile(DemTileId(36, -117))
        assertEquals(-86.0, tile.elevationM(at(36.2461, -116.8258)))
        val extremes = FakeHgt(DemResolution.ThreeArcSecond) { r, _ -> if (r < 600) 32767 else -32767 }.tile(DemTileId(0, 0))
        assertEquals(32767.0, extremes.elevationM(at(0.9, 0.5)))
        assertEquals(-32767.0, extremes.elevationM(at(0.1, 0.5)))
    }

    @Test
    fun aVoidPostMakesTheAnswerUnknownNeverZero() {
        val tile = smallGrid(voidAt = 11 to 11)
        val inside = at(postLat(10) - 0.5 / 1200.0, postLon(10) + 0.5 / 1200.0)
        assertNull(tile.elevationM(inside), "must not interpolate through -32768")
        // A point that leans on the three good posts only is still unknown: the void post has weight.
        assertNull(tile.elevationM(at(postLat(10) - 0.1 / 1200.0, postLon(10) + 0.1 / 1200.0)))
        // Exactly on a good post, the void neighbours carry no weight, so the post itself is the answer.
        assertEquals(100.0, tile.elevationM(at(postLat(10), postLon(10))))
        assertEquals(200.0, tile.elevationM(at(postLat(10), postLon(11))))
        assertEquals(300.0, tile.elevationM(at(postLat(11), postLon(10))))
        // On the void post itself: unknown.
        assertNull(tile.elevationM(at(postLat(11), postLon(11))))
    }

    @Test
    fun aTileOfNothingButVoidsAnswersNull() {
        val tile = FakeHgt(DemResolution.ThreeArcSecond) { _, _ -> Hgt.VOID }.tile(DemTileId(10, 20))
        for ((lat, lon) in listOf(10.5 to 20.5, 10.0 to 20.0, 11.0 to 21.0, 10.25 to 20.75)) assertNull(tile.elevationM(at(lat, lon)))
    }

    @Test
    fun edgesAreInsideAndJustPastThemIsOutside() {
        val fake = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled)
        val tile = fake.tile(DemTileId(10, 20))
        assertEquals(scrambled(0, 0).toDouble(), tile.elevationM(at(11.0, 20.0)), "north-west corner")
        assertEquals(scrambled(0, 1200).toDouble(), tile.elevationM(at(11.0, 21.0)), "north-east corner")
        assertEquals(scrambled(1200, 0).toDouble(), tile.elevationM(at(10.0, 20.0)), "south-west corner")
        assertEquals(scrambled(1200, 1200).toDouble(), tile.elevationM(at(10.0, 21.0)), "south-east corner")
        assertNull(tile.elevationM(at(9.999, 20.5)))
        assertNull(tile.elevationM(at(11.001, 20.5)))
        assertNull(tile.elevationM(at(10.5, 19.999)))
        assertNull(tile.elevationM(at(10.5, 21.001)))
        assertFalse(tile.covers(at(-10.5, 20.5)))
        assertTrue(tile.covers(at(10.5, 20.5)))
    }

    @Test
    fun theAntimeridianAndThePoleAreTheEdgesOfTheLastTiles() {
        val east = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).tile(DemTileId(10, 179))
        assertEquals(scrambled(600, 1200).toDouble(), east.elevationM(at(10.5, 180.0)), "east edge of E179")
        assertNull(east.elevationM(at(10.5, -179.5)), "the far side of the antimeridian is another tile")
        val west = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).tile(DemTileId(10, -180))
        assertEquals(scrambled(600, 0).toDouble(), west.elevationM(at(10.5, 180.0)), "180 E is the west edge of W180")
        assertEquals(scrambled(600, 0).toDouble(), west.elevationM(at(10.5, -180.0)))
        val polar = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).tile(DemTileId(89, 10))
        assertEquals(scrambled(0, 600).toDouble(), polar.elevationM(at(90.0, 10.5)), "north pole row")
        val south = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).tile(DemTileId(-90, 10))
        assertEquals(scrambled(1200, 600).toDouble(), south.elevationM(at(-90.0, 10.5)), "south pole row")
    }

    @Test
    fun adjacentTilesAgreeOnTheirSharedEdge() {
        val a = FakeHgt(DemResolution.ThreeArcSecond) { r, c -> 500 + c }.tile(DemTileId(10, 20))
        // The next tile east repeats the column at 21 E as its column 0 (the same heights down the whole edge).
        val b = FakeHgt(DemResolution.ThreeArcSecond) { r, c -> 500 + 1200 + c }.tile(DemTileId(10, 21))
        for (lat in listOf(10.0, 10.3, 10.77, 11.0)) assertEquals(a.elevationM(at(lat, 21.0)), b.elevationM(at(lat, 21.0)), "lat $lat")
    }

    // ---- laziness and failure -------------------------------------------------------------------------------

    @Test
    fun onlyTheRowsNeededAreReadAndTheyAreReused() {
        val fake = FakeHgt(DemResolution.OneArcSecond, ::scrambled)
        val tile = fake.tile(DemTileId(36, -119))
        // Walk 2,000 samples along one row of a 26 MB tile.
        for (i in 0 until 2000) tile.elevationM(at(36.5, -118.9 + i * 0.00005))
        val rowBytes = 3601 * 2
        assertTrue(fake.reads.all { it.second == rowBytes }, "never more than one row per read")
        assertTrue(fake.reads.size <= 2, "two rows serve the whole walk, read ${fake.reads.size} times")
        // Walking north reads each row about once.
        fake.reads.clear()
        for (i in 0 until 100) tile.elevationM(at(36.5 + i * 0.00001, -118.5))
        assertTrue(fake.reads.size <= 6, "${fake.reads.size} reads for 100 northward samples")
    }

    @Test
    fun aFailingOrShortReadIsUnknownNotACrash() {
        val throwing = DemTile.open("N36W119.hgt", DemResolution.ThreeArcSecond.fileBytes, { _, _ -> throw RuntimeException("disk gone") })!!
        assertNull(throwing.elevationM(at(36.5, -118.5)))
        val short = DemTile.open("N36W119.hgt", DemResolution.ThreeArcSecond.fileBytes, { _, n -> ByteArray(n - 2) })!!
        assertNull(short.elevationM(at(36.5, -118.5)))
    }

    // ---- the tile set ---------------------------------------------------------------------------------------

    @Test
    fun theTileSetOpensTilesOnceRemembersAbsentOnesAndClosesAll() {
        val opened = ArrayList<DemTileId>()
        val fakes = HashMap<DemTileId, FakeHgt>()
        val set = DemTileSet({ id ->
            opened += id
            if (id == DemTileId(36, -119)) FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).also { fakes[id] = it }.tile(id) else null
        })
        assertNotNull(set.elevationM(at(36.5, -118.5)))
        assertNotNull(set.elevationM(at(36.6, -118.6)))
        assertNull(set.elevationM(at(50.5, 8.5)))
        assertNull(set.elevationM(at(50.6, 8.6)))
        assertEquals(listOf(DemTileId(36, -119), DemTileId(50, 8)), opened)
        set.close()
        assertTrue(fakes.getValue(DemTileId(36, -119)).closed)
    }

    @Test
    fun theTileSetKeepsAtMostMaxOpenTilesOpen() {
        val made = HashMap<DemTileId, FakeHgt>()
        val set = DemTileSet({ id -> FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).also { made[id] = it }.tile(id) }, maxOpen = 2)
        for (lon in listOf(10.5, 11.5, 12.5)) assertNotNull(set.elevationM(at(5.5, lon)))
        assertTrue(made.getValue(DemTileId(5, 10)).closed, "oldest tile closed on eviction")
        assertFalse(made.getValue(DemTileId(5, 12)).closed)
        set.close()
        assertTrue(made.values.all { it.closed })
    }

    @Test
    fun evictionClosesTheLeastRecentlyUsedTileNotTheFirstOpened() {
        val made = HashMap<DemTileId, FakeHgt>()
        val set = DemTileSet({ id -> FakeHgt(DemResolution.ThreeArcSecond, ::scrambled).also { made[id] = it }.tile(id) }, maxOpen = 2)
        assertNotNull(set.elevationM(at(5.5, 10.5))) // A
        assertNotNull(set.elevationM(at(5.5, 11.5))) // B
        assertNotNull(set.elevationM(at(5.5, 10.6))) // A again: now B is the least recently used
        assertNotNull(set.elevationM(at(5.5, 12.5))) // C evicts B, not A
        assertTrue(made.getValue(DemTileId(5, 11)).closed, "B was least recently used")
        assertFalse(made.getValue(DemTileId(5, 10)).closed, "A was just used")
        set.close()
    }

    @Test
    fun aOneArcSecondTileBetweenThreeArcSecondStopsSetsTheSpacing() {
        val oneSecond = FakeHgt(DemResolution.OneArcSecond, ::scrambled)
        val threeSecond = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled)
        val set = DemTileSet({ id ->
            when (id) {
                DemTileId(10, 21) -> oneSecond.tile(id)
                DemTileId(10, 20), DemTileId(10, 22) -> threeSecond.tile(id)
                else -> null
            }
        })
        val a = at(10.5, 20.5)
        val b = at(10.5, 22.5)
        // The 1 arc-second tile is under neither stop, only under the leg between them.
        assertEquals(30.0, set.finestSpacingM(listOf(a, b)))
        assertEquals(90.0, set.finestSpacingM(listOf(a, at(10.5, 20.9))), "a route that stays on the 3 arc-second tile")
    }

    @Test
    fun aVoidOnOneTilesEdgeFallsBackToTheNeighbourThatHasTheSamePost() {
        val voidTile = FakeHgt(DemResolution.ThreeArcSecond) { _, _ -> Hgt.VOID }
        val good = FakeHgt(DemResolution.ThreeArcSecond) { _, _ -> 700 }
        val set = DemTileSet({ id ->
            when (id) {
                DemTileId(10, 21) -> voidTile.tile(id)
                DemTileId(10, 20) -> good.tile(id)
                else -> null
            }
        })
        assertEquals(700.0, set.elevationM(at(10.5, 21.0)))
    }

    @Test
    fun theFinestSpacingComesFromTheInstalledTilesAndDefaultsToNinetyMetres() {
        val oneSecond = FakeHgt(DemResolution.OneArcSecond, ::scrambled)
        val threeSecond = FakeHgt(DemResolution.ThreeArcSecond, ::scrambled)
        val set = DemTileSet({ id ->
            when (id) {
                DemTileId(36, -119) -> oneSecond.tile(id)
                DemTileId(40, 10) -> threeSecond.tile(id)
                else -> null
            }
        })
        assertEquals(30.0, set.finestSpacingM(listOf(at(36.5, -118.5), at(40.5, 10.5))))
        assertEquals(90.0, set.finestSpacingM(listOf(at(40.5, 10.5))))
        assertEquals(90.0, set.finestSpacingM(listOf(at(0.5, 0.5))))
        assertEquals(30.0, set.finestSpacingM(listOf(at(0.5, 0.5)), fallbackM = 30.0))
    }

    @Test
    fun aRealByteArrayFileDecodesBigEndian() {
        // A complete 1201 x 1201 file in memory, written the way the format says: high byte first.
        val n = 1201
        val bytes = ByteArray(n * n * 2)
        for (r in 0 until n) for (c in 0 until n) {
            val v = if (r == 505 && c in 849..850 || r == 506 && c in 849..850) 4421 else -17 - c
            bytes[(r * n + c) * 2] = (v shr 8).toByte()
            bytes[(r * n + c) * 2 + 1] = v.toByte()
        }
        val tile = DemTile.open("N36W119.hgt", bytes.size.toLong(), { off, len -> bytes.copyOfRange(off.toInt(), off.toInt() + len) })!!
        assertEquals(4421.0, tile.elevationM(at(36.5785, -118.2923)))
        assertEquals(-17.0, tile.elevationM(at(36.0, -119.0)))
        assertEquals(-17.0 - 1200, tile.elevationM(at(36.0, -118.0)))
        assertFailsWith<IllegalArgumentException> { DemTileId(90, 0) }
    }
}

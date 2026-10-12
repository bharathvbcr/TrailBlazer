package com.example.trailblazer.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.example.trailblazer.tracking.TrackRecorder
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.track.ElevationDatum
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room schema v1 to v2 (segment and elevation datum on `track_points`). `MigrationTestHelper` builds a real v1 database
 * from the exported `1.json`, the test fills it with raw SQL (the DAOs only speak the latest schema), then it is migrated
 * and validated against `2.json`. The schemas reach this Robolectric test through the `test` assets in `app/build.gradle.kts`.
 */
@RunWith(AndroidJUnit4::class)
class TrailDbMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TrailDb::class.java)

    private val origin = LatLon(46.0, 7.0)

    private fun SupportSQLiteDatabase.insertV1Track(id: String, state: String, points: Int) {
        execSQL("INSERT INTO tracks (id, name, startedMs, endedMs, state, distanceM, gainM, lossM, movingMs, maxSpeedMps, pointCount) VALUES ('$id', 'Col du Pillon', 1700000000000, ${if (state == "Finished") "1700000600000" else "NULL"}, '$state', ${points * 20.0}, 12.5, 3.0, 540000, 2.5, $points)")
        for (i in 0 until points) {
            // A realistic mix: an elevation on most points, none on one, and a point with every optional field null.
            val ele = if (i == 1) "NULL" else "${2000.0 + i}"
            val acc = if (i == 2) "NULL" else "6.0"
            val spd = if (i == 2) "NULL" else "1.4"
            val p = Geo.destination(origin, 0.0, i * 20.0)
            execSQL("INSERT INTO track_points (trackId, seq, timeMs, lat, lon, elevationM, accuracyM, speedMps) VALUES ('$id', $i, ${1700000000000L + i * 10_000L}, ${p.lat}, ${p.lon}, $ele, $acc, $spd)")
        }
    }

    private data class V2Row(val seq: Int, val timeMs: Long, val ele: Double?, val acc: Double?, val spd: Double?, val segment: Int, val datum: String?)

    private fun SupportSQLiteDatabase.rows(trackId: String): List<V2Row> =
        query("SELECT seq, timeMs, elevationM, accuracyM, speedMps, segment, elevationDatum FROM track_points WHERE trackId = '$trackId' ORDER BY seq").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(V2Row(c.getInt(0), c.getLong(1), if (c.isNull(2)) null else c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4), c.getInt(5), if (c.isNull(6)) null else c.getString(6)))
                }
            }
        }

    @Test
    fun existingPointsKeepTheirDataTheSegmentIsZeroAndTheDatumIsUnknown() {
        helper.createDatabase(DB, 1).apply {
            insertV1Track("t1", "Finished", 4)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true, *TrailDb.MIGRATIONS)

        val rows = db.rows("t1")
        assertEquals(4, rows.size)
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.seq })
        assertEquals(listOf(2000.0, null, 2002.0, 2003.0), rows.map { it.ele })
        assertEquals(listOf(6.0, 6.0, null, 6.0), rows.map { it.acc })
        assertEquals(listOf(1.4, 1.4, null, 1.4), rows.map { it.spd })
        assertEquals(1700000000000L + 30_000L, rows[3].timeMs)
        assertTrue("every legacy point is segment 0", rows.all { it.segment == 0 })
        // The datum of a pre-v2 point is not known, so it is NULL: never labelled sea level.
        assertTrue("no legacy point may claim a datum", rows.all { it.datum == null })

        db.query("SELECT name, state, distanceM, gainM, lossM, movingMs, maxSpeedMps, pointCount FROM tracks WHERE id = 't1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Col du Pillon", c.getString(0))
            assertEquals("Finished", c.getString(1))
            assertEquals(80.0, c.getDouble(2), 0.0)
            assertEquals(12.5, c.getDouble(3), 0.0)
            assertEquals(540000L, c.getLong(5))
            assertEquals(4, c.getInt(7))
        }
    }

    @Test
    fun anEmptyDatabaseMigrates() {
        helper.createDatabase(DB, 1).close()
        val db = helper.runMigrationsAndValidate(DB, 2, true, *TrailDb.MIGRATIONS)
        db.query("SELECT COUNT(*) FROM track_points").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun aTrackWithNoPointsMigrates() {
        helper.createDatabase(DB, 1).apply {
            insertV1Track("empty", "Finished", 0)
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true, *TrailDb.MIGRATIONS)
        assertTrue(db.rows("empty").isEmpty())
    }

    @Test
    fun aTrackStillRecordingResumesAfterTheMigrationInItsOwnSegment() = runBlocking {
        helper.createDatabase(DB, 1).apply {
            insertV1Track("live", "Recording", 3)
            close()
        }
        // The app's own builder, with the app's own migration list.
        val db = TrailDb.builder(context, DB).allowMainThreadQueries().build()
        try {
            val clock = Clock { 1_700_000_100_000L }
            val recorder = TrackRecorder(db.tracks(), clock)
            val track = recorder.openOrStart("unused")
            assertEquals("live", track.id)
            assertEquals(3, recorder.pointCount)
            // The three legacy points are replayed into the statistics; the next fix continues the same segment.
            val next = Geo.destination(origin, 0.0, 3 * 20.0)
            assertTrue(recorder.offer(Fix(next, 5.0, 2004.0, AltitudeDatum.SeaLevel, null, 1.4, null, 1_700_000_040_000L, "gps")))
            recorder.flush()

            val stored = db.tracks().pointsAfter("live", -1, 10)
            assertEquals(listOf(0, 1, 2, 3), stored.map { it.seq })
            assertEquals(listOf(0, 0, 0, 0), stored.map { it.segment })
            assertEquals(listOf(null, null, null, ElevationDatum.Msl), stored.map { it.elevationDatum })
            assertEquals(4, db.tracks().get("live")!!.pointCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun theProductionBuilderOpensAV1DatabaseThroughItsMigrations() = runBlocking {
        helper.createDatabase(DB, 1).apply {
            insertV1Track("old", "Finished", 5)
            close()
        }
        val db = TrailDb.builder(context, DB).allowMainThreadQueries().build()
        try {
            val points = db.tracks().pointsAfter("old", -1, 100)
            assertEquals(5, points.size)
            assertTrue(points.all { it.segment == 0 && it.elevationDatum == null })
            assertNull(points[1].elevationM)
        } finally {
            db.close()
        }
    }

    @Test
    fun theProductionBuilderCreatesAndReopensAFreshV2Database() = runBlocking {
        val name = "fresh-v2.db"
        context.deleteDatabase(name)
        TrailDb.builder(context, name).allowMainThreadQueries().build().apply {
            tracks().insert(TrackEntity("a", "A", 1, null, TrackState.Recording, 0.0, 0.0, 0.0, 0, null, 2))
            tracks().insertPoints(
                listOf(
                    TrackPointEntity("a", 0, 1, 46.0, 7.0, 100.0, 5.0, null, 0, ElevationDatum.Msl),
                    TrackPointEntity("a", 1, 2, 46.1, 7.1, 150.0, 5.0, null, 1, ElevationDatum.Ellipsoid),
                ),
            )
            close()
        }
        val reopened = TrailDb.builder(context, name).allowMainThreadQueries().build()
        try {
            val points = reopened.tracks().pointsAfter("a", -1, 10)
            assertEquals(listOf(0, 1), points.map { it.segment })
            assertEquals(listOf(ElevationDatum.Msl, ElevationDatum.Ellipsoid), points.map { it.elevationDatum })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aV1DatabaseWithoutTheMigrationFailsOnOpenInsteadOfLosingData() {
        helper.createDatabase(DB, 1).apply {
            insertV1Track("old", "Finished", 2)
            close()
        }
        // What the app would do if MIGRATIONS were empty: Room refuses, and there is no destructive fallback to hide it.
        val bare = Room.databaseBuilder(context, TrailDb::class.java, DB).allowMainThreadQueries().build()
        val e = assertThrows(IllegalStateException::class.java) { bare.openHelper.writableDatabase }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("migration"))
        bare.close()
    }

    @Test
    fun migrationsFormAnUnbrokenChainToTheCurrentVersion() {
        val versions = TrailDb.MIGRATIONS.sortedBy { it.startVersion }
        assertEquals(1, versions.first().startVersion)
        versions.zipWithNext { a, b -> assertEquals(a.endVersion, b.startVersion) }
        assertEquals(2, versions.last().endVersion)
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}

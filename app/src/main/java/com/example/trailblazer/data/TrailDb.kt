package com.example.trailblazer.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.trailblazer.core.track.ElevationDatum
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val createdMs: Long,
    val note: String?,
)

enum class TrackState { Recording, Paused, Finished }

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startedMs: Long,
    val endedMs: Long?,
    val state: TrackState,
    val distanceM: Double,
    val gainM: Double,
    val lossM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double?,
    val pointCount: Int,
)

@Entity(
    tableName = "track_points",
    primaryKeys = ["trackId", "seq"],
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackPointEntity(
    val trackId: String,
    val seq: Int,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val accuracyM: Double?,
    val speedMps: Double?,
    /** The continuous stretch the fix belongs to. It changes after a pause and at each `trkseg` of an imported file. */
    @ColumnInfo(defaultValue = "0") val segment: Int = 0,
    /** The datum of [elevationM]; null when unknown, as for every point recorded before schema v2. */
    val elevationDatum: ElevationDatum? = null,
)

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdMs: Long,
    val updatedMs: Long,
)

@Entity(
    tableName = "stops",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class StopEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val ordinal: Int,
    val name: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val plannedDay: Long?,
    val note: String?,
)

data class TripWithStops(
    @Embedded val trip: TripEntity,
    @Relation(parentColumn = "id", entityColumn = "tripId") val stops: List<StopEntity>,
)

@Entity(tableName = "pressure_samples")
data class PressureSampleEntity(
    @PrimaryKey val timeMs: Long,
    val stationHpa: Double,
    val elevationM: Double?,
)

@Dao
interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdMs DESC")
    fun observeAll(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun get(id: String): WaypointEntity?

    @Query("SELECT * FROM waypoints ORDER BY createdMs")
    suspend fun all(): List<WaypointEntity>

    @Upsert
    suspend fun upsert(w: WaypointEntity)

    @Upsert
    suspend fun upsertAll(w: List<WaypointEntity>)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM waypoints")
    suspend fun deleteAll()
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY startedMs DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE state != 'Finished' ORDER BY startedMs DESC LIMIT 1")
    suspend fun open(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE state != 'Finished' ORDER BY startedMs DESC LIMIT 1")
    fun observeOpen(): Flow<TrackEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(t: TrackEntity)

    @androidx.room.Update
    suspend fun update(t: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPoints(points: List<TrackPointEntity>)

    @Query("SELECT COALESCE(MAX(seq), -1) FROM track_points WHERE trackId = :trackId")
    suspend fun maxSeq(trackId: String): Int

    /** Keyset paging for streaming export and charts; never loads a whole track at once. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun pointsAfter(trackId: String, afterSeq: Int, limit: Int): List<TrackPointEntity>

    @Transaction
    suspend fun appendPoints(track: TrackEntity, points: List<TrackPointEntity>) {
        insertPoints(points)
        update(track)
    }
}

@Dao
interface TripDao {
    @Transaction
    @Query("SELECT * FROM trips ORDER BY updatedMs DESC")
    fun observeAll(): Flow<List<TripWithStops>>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: String): Flow<TripWithStops?>

    @Upsert
    suspend fun upsertTrip(t: TripEntity)

    @Query("DELETE FROM stops WHERE tripId = :tripId")
    suspend fun deleteStops(tripId: String)

    @Insert
    suspend fun insertStops(stops: List<StopEntity>)

    @Query("SELECT createdMs FROM trips WHERE id = :id")
    suspend fun createdMs(id: String): Long?

    /** Saves the trip and its stops. A trip that is already stored keeps the `createdMs` it was first saved with. */
    @Transaction
    suspend fun save(trip: TripEntity, stops: List<StopEntity>) {
        upsertTrip(createdMs(trip.id)?.let { trip.copy(createdMs = it) } ?: trip)
        deleteStops(trip.id)
        insertStops(stops)
    }

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}

@Dao
interface PressureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: PressureSampleEntity)

    @Query("SELECT * FROM pressure_samples WHERE timeMs >= :sinceMs ORDER BY timeMs")
    fun observeSince(sinceMs: Long): Flow<List<PressureSampleEntity>>

    @Query("SELECT MAX(timeMs) FROM pressure_samples")
    suspend fun latestTime(): Long?

    @Query("DELETE FROM pressure_samples WHERE timeMs < :beforeMs")
    suspend fun prune(beforeMs: Long)

    @Query("DELETE FROM pressure_samples")
    suspend fun deleteAll()
}

@Database(
    entities = [WaypointEntity::class, TrackEntity::class, TrackPointEntity::class, TripEntity::class, StopEntity::class, PressureSampleEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TrailDb : RoomDatabase() {
    abstract fun waypoints(): WaypointDao
    abstract fun tracks(): TrackDao
    abstract fun trips(): TripDao
    abstract fun pressure(): PressureDao

    companion object {
        const val FILE_NAME = "trail.db"

        /**
         * v1 to v2: every track point gets a segment index (0, so a recorded track is one segment as it always was) and
         * an elevation datum. The datum of a stored point is not known (before v2 it could be sea level or the ellipsoid,
         * depending on the Android version and on whether the geoid lookup worked), so the column is NULL, never a guess.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_points ADD COLUMN segment INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE track_points ADD COLUMN elevationDatum TEXT")
            }
        }

        /** Every migration the app ships. There is no destructive fallback: a version without a path here crashes on open. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** The one place the production database is configured; the migration tests open it through here too. */
        fun builder(context: Context, name: String = FILE_NAME): RoomDatabase.Builder<TrailDb> =
            Room.databaseBuilder(context, TrailDb::class.java, name).addMigrations(*MIGRATIONS)

        fun create(context: Context): TrailDb = builder(context).build()
    }
}

package com.example.trailblazer.tracking

import com.example.trailblazer.data.TrackDao
import com.example.trailblazer.data.TrackEntity
import com.example.trailblazer.data.TrackPointEntity
import com.example.trailblazer.data.TrackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [TrackDao] for tests that need to control when and whether the database answers, which a real Room
 * database on its own threads cannot do deterministically. [failWrites] makes every write throw, as a full disk does;
 * [openGate] holds [open] until completed, which parks a caller inside whatever lock it holds.
 */
class FakeTrackDao : TrackDao {
    private val rows = MutableStateFlow<Map<String, TrackEntity>>(emptyMap())
    private val points = mutableListOf<TrackPointEntity>()

    var failWrites: Throwable? = null
    var openGate: CompletableDeferred<Unit>? = null

    /** How many times [update] and [appendPoints] were called directly, to show a state change is one write. */
    var updateCalls = 0
        private set
    var appendCalls = 0
        private set

    val allPoints: List<TrackPointEntity> get() = points.toList()
    fun row(id: String): TrackEntity? = rows.value[id]
    fun allRows(): List<TrackEntity> = rows.value.values.toList()

    private fun checkWritable() {
        failWrites?.let { throw it }
    }

    override fun observeAll(): Flow<List<TrackEntity>> = rows.map { it.values.sortedByDescending { t -> t.startedMs } }

    override fun observe(id: String): Flow<TrackEntity?> = rows.map { it[id] }

    override suspend fun get(id: String): TrackEntity? = rows.value[id]

    override suspend fun open(): TrackEntity? {
        openGate?.await()
        return rows.value.values.filter { it.state != TrackState.Finished }.maxByOrNull { it.startedMs }
    }

    override fun observeOpen(): Flow<TrackEntity?> =
        rows.map { m -> m.values.filter { it.state != TrackState.Finished }.maxByOrNull { it.startedMs } }

    override suspend fun insert(t: TrackEntity) {
        checkWritable()
        rows.value = rows.value + (t.id to t)
    }

    override suspend fun update(t: TrackEntity) {
        checkWritable()
        updateCalls++
        if (t.id in rows.value) rows.value = rows.value + (t.id to t)
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value - id
        points.removeAll { it.trackId == id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyMap()
        points.clear()
    }

    override suspend fun insertPoints(points: List<TrackPointEntity>) {
        checkWritable()
        for (p in points) if (this.points.none { it.trackId == p.trackId && it.seq == p.seq }) this.points += p
    }

    override suspend fun maxSeq(trackId: String): Int = points.filter { it.trackId == trackId }.maxOfOrNull { it.seq } ?: -1

    override suspend fun pointsAfter(trackId: String, afterSeq: Int, limit: Int): List<TrackPointEntity> =
        points.filter { it.trackId == trackId && it.seq > afterSeq }.sortedBy { it.seq }.take(limit)

    /** All or nothing, like the real @Transaction: a failure leaves neither the points nor the row changed. */
    override suspend fun appendPoints(track: TrackEntity, points: List<TrackPointEntity>) {
        appendCalls++
        checkWritable()
        insertPoints(points)
        if (track.id in rows.value) rows.value = rows.value + (track.id to track)
    }
}

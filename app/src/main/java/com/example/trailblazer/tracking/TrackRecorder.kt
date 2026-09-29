package com.example.trailblazer.tracking

import com.example.trailblazer.data.TrackDao
import com.example.trailblazer.data.TrackEntity
import com.example.trailblazer.data.TrackPointEntity
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.newId
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.track.TrackPoint
import com.trailblazer.core.track.TrackStats
import kotlin.math.max

/**
 * Recording logic without Android service plumbing, so it can be tested directly.
 * Fixes worse than [MAX_ACCURACY_M] are dropped, as are fixes closer than max(3 m, accuracy/2) to the
 * last kept point (GPS jitter while standing still). Points are written in batches of [BATCH_SIZE] or
 * every [FLUSH_MS], and the track row's statistics are updated in the same transaction.
 */
class TrackRecorder(private val dao: TrackDao, private val clock: Clock) {
    var track: TrackEntity? = null
        private set
    private var stats = TrackStats()
    private var nextSeq = 0
    private var lastKept: TrackPoint? = null
    private val buffer = ArrayList<TrackPointEntity>()
    private var lastFlushMs = 0L

    /** Continues the open track if there is one (e.g. after the process was killed), else starts a new one. */
    suspend fun openOrStart(defaultName: String): TrackEntity {
        val open = dao.open()
        if (open != null) {
            restore(open)
            val t = open.copy(state = TrackState.Recording)
            dao.update(t)
            track = t
            return t
        }
        val now = clock.nowMs()
        val t = TrackEntity(newId(), defaultName, now, null, TrackState.Recording, 0.0, 0.0, 0.0, 0L, null, 0)
        dao.insert(t)
        track = t
        stats = TrackStats()
        nextSeq = 0
        lastKept = null
        lastFlushMs = now
        return t
    }

    private suspend fun restore(t: TrackEntity) {
        stats = TrackStats()
        var after = -1
        var last: TrackPoint? = null
        while (true) {
            val page = dao.pointsAfter(t.id, after, 5_000)
            if (page.isEmpty()) break
            for (p in page) {
                val pos = LatLon.of(p.lat, p.lon) ?: continue
                val tp = TrackPoint(p.timeMs, pos, p.elevationM, p.accuracyM, p.speedMps)
                stats.add(tp)
                last = tp
            }
            after = page.last().seq
        }
        nextSeq = after + 1
        lastKept = last
        lastFlushMs = clock.nowMs()
    }

    /** Returns true if the fix was kept. */
    fun offer(fix: Fix): Boolean {
        val t = track ?: return false
        if (t.state != TrackState.Recording) return false
        val acc = fix.accuracyM ?: return false
        if (!acc.isFinite() || acc > MAX_ACCURACY_M) return false
        val prev = lastKept
        if (prev != null) {
            if (fix.timeMs <= prev.epochMs) return false
            if (Geo.distanceM(prev.position, fix.position) < max(MIN_STEP_M, acc / 2)) return false
        }
        val p = TrackPoint(fix.timeMs, fix.position, fix.altitudeM, acc, fix.speedMps)
        stats.add(p)
        lastKept = p
        buffer += TrackPointEntity(t.id, nextSeq++, p.epochMs, p.position.lat, p.position.lon, p.elevationM, acc, p.speedMps)
        return true
    }

    fun flushDue(): Boolean = buffer.size >= BATCH_SIZE || (buffer.isNotEmpty() && clock.nowMs() - lastFlushMs >= FLUSH_MS)

    suspend fun flush() {
        val t = track ?: return
        val updated = t.copy(
            distanceM = stats.distanceM,
            gainM = stats.gainM,
            lossM = stats.lossM,
            movingMs = stats.movingMs,
            maxSpeedMps = stats.maxSpeedMps,
            pointCount = nextSeq,
        )
        val batch = ArrayList(buffer)
        dao.appendPoints(updated, batch)
        buffer.clear()
        track = updated
        lastFlushMs = clock.nowMs()
    }

    suspend fun setState(state: TrackState) {
        flush()
        val t = track ?: return
        val updated = t.copy(state = state, endedMs = if (state == TrackState.Finished) clock.nowMs() else t.endedMs)
        dao.update(updated)
        track = updated
    }

    val distanceM: Double get() = stats.distanceM
    val elapsedMs: Long get() = track?.let { clock.nowMs() - it.startedMs } ?: 0L
    val pointCount: Int get() = nextSeq

    companion object {
        const val MAX_ACCURACY_M = 30.0
        const val MIN_STEP_M = 3.0
        const val BATCH_SIZE = 10
        const val FLUSH_MS = 5_000L
    }
}

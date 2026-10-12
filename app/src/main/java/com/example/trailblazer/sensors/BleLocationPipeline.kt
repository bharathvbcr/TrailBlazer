package com.example.trailblazer.sensors

import com.example.trailblazer.location.Fix

/**
 * Turns the raw location data of a BLE peripheral into [Fix]es: NMEA text (reassembled from notifications of any size and
 * merged per receiver epoch) and LNS Location and Speed frames (refined by the latest Position Quality). One lock guards
 * all state, because notifications arrive on binder threads while the owner resets it from the main thread.
 */
class BleLocationPipeline(private val clock: Clock) {
    private val lock = Any()
    private val lines = NmeaStreamBuffer()
    private val merger = NmeaEpochMerger(clock)
    private var quality: BlePositionQuality? = null
    private var qualityAtMs = 0L
    private var positionStatusSupported = false

    /** True while a received NMEA epoch is waiting for its other sentences; the owner flushes it after a short delay. */
    val hasPendingNmea: Boolean get() = synchronized(lock) { merger.hasPending }

    /** Feeds NMEA text as received (any chunking) and returns the fixes completed by it. */
    fun onNmeaChunk(chunk: String): List<Fix> = synchronized(lock) {
        val fixes = ArrayList<Fix>(1)
        for (line in lines.appendAndExtractLines(chunk)) {
            val sentence = NmeaParser.parseSentence(line) ?: continue
            fixes += merger.accept(sentence)
        }
        fixes
    }

    /** Emits the NMEA epoch still waiting for sentences, if any. */
    fun flushNmea(): Fix? = synchronized(lock) { merger.flush() }

    fun onPositionQuality(data: ByteArray) {
        val q = BlePositionQualityParser.parse(data) ?: return
        synchronized(lock) {
            quality = q
            qualityAtMs = clock.nowMs()
        }
    }

    /**
     * The LN Feature value read from the device. Until it arrives (or if the read fails or is malformed) the device is
     * assumed not to report Position Status, so no fix is dropped for want of it.
     */
    fun onLnFeature(data: ByteArray) {
        val supported = BleLnFeatureParser.positionStatusSupported(data) ?: return
        synchronized(lock) { positionStatusSupported = supported }
    }

    /** An LNS frame as a fix, using the Position Quality received at most [BleFixPolicy.MAX_QUALITY_AGE_MS] ago. */
    fun onLocationAndSpeed(data: ByteArray): Fix? = synchronized(lock) {
        val fresh = quality?.takeIf { clock.nowMs() - qualityAtMs in 0..BleFixPolicy.MAX_QUALITY_AGE_MS }
        BleLnsParser.parse(data, clock, fresh, positionStatusSupported)
    }

    /** Drops partial text, the open epoch, the quality sample and the feature bits (on disconnect or error). */
    fun reset() = synchronized(lock) {
        lines.clear()
        merger.reset()
        quality = null
        positionStatusSupported = false
    }
}

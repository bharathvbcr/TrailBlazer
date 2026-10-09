package com.trailblazer.core.time

expect class PlatformTimeZone {
    fun offsetAtMs(epochMs: Long): Long
    fun localToEpochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long

    companion object {
        fun current(): PlatformTimeZone
        fun of(zoneId: String): PlatformTimeZone
    }
}

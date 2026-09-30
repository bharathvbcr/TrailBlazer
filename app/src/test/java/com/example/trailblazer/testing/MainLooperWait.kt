package com.example.trailblazer.testing

import android.os.Looper
import org.robolectric.Shadows.shadowOf

/**
 * Runs the Robolectric main looper until [condition] holds or [timeoutMs] passes, for work that finishes on another
 * thread (Room, DataStore, Dispatchers.Default) and posts back. Returns quietly on timeout: the caller's assertion
 * then fails with its own message. Use instead of a fixed sleep, which is flaky under load.
 */
fun awaitMainLooper(timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
}

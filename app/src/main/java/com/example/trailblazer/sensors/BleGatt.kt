package com.example.trailblazer.sensors

import java.util.UUID

/** The kinds of GATT operation the app issues. Android allows exactly one in flight per connection. */
enum class GattOpKind { RequestMtu, EnableNotify, Read }

/** One queued GATT operation. [characteristic] identifies the one an operation's completion callback reports on. */
sealed interface GattOp {
    val kind: GattOpKind
    val characteristic: UUID?

    data class RequestMtu(val mtu: Int) : GattOp {
        override val kind get() = GattOpKind.RequestMtu
        override val characteristic: UUID? get() = null
    }

    /** setCharacteristicNotification plus the Client Characteristic Configuration Descriptor write. */
    data class EnableNotify(val service: UUID, override val characteristic: UUID) : GattOp {
        override val kind get() = GattOpKind.EnableNotify
    }

    data class Read(val service: UUID, override val characteristic: UUID) : GattOp {
        override val kind get() = GattOpKind.Read
    }
}

enum class GattOpResult { Success, Failed, TimedOut }

/** Schedules [task] after [delayMs] and returns a function that cancels it. Android: a main-looper Handler; tests: a fake. */
fun interface GattTimeoutScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * Runs GATT operations strictly one at a time, in order. The next operation starts only when the previous one reports
 * completion (descriptor write, MTU change, characteristic read), fails to start, or times out.
 *
 *  - An operation that fails to start, completes with an error status, or times out is reported through [onFinished] and the
 *    queue moves on to the next: one broken characteristic never blocks the rest.
 *  - A completion is accepted only if its kind and characteristic match the operation in flight, so a late callback from an
 *    operation that already timed out cannot be mistaken for the completion of the next one.
 *  - [onDrained] runs once after the last queued operation finished (or an empty batch was submitted).
 *  - [clear] (disconnect) drops everything silently and cancels the pending timeout.
 *
 * Thread-safe: GATT callbacks arrive on binder threads, timeouts on the main thread. Callbacks run on the calling thread
 * with the queue's lock held, so they must be quick and must not wait on another thread.
 */
class GattOpQueue(
    private val timeoutMs: Long,
    private val scheduler: GattTimeoutScheduler,
    private val execute: (GattOp) -> Boolean,
    private val onFinished: (GattOp, GattOpResult) -> Unit,
    private val onDrained: () -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<GattOp>()
    private var current: GattOp? = null
    private var cancelTimeout: (() -> Unit)? = null
    private var generation = 0L
    private var drainDue = false

    /** True when nothing is queued or in flight. */
    val isIdle: Boolean get() = synchronized(lock) { current == null && pending.isEmpty() }

    fun enqueue(ops: List<GattOp>) = synchronized(lock) {
        if (ops.isEmpty() && current == null && pending.isEmpty()) {
            onDrained()
            return@synchronized
        }
        pending.addAll(ops)
        drainDue = true
        pump()
    }

    /** Reports a completion callback. Returns false (and does nothing) if it does not belong to the operation in flight. */
    fun complete(kind: GattOpKind, characteristic: UUID?, success: Boolean): Boolean = synchronized(lock) {
        val op = current ?: return@synchronized false
        if (op.kind != kind || (characteristic != null && op.characteristic != characteristic)) return@synchronized false
        finishCurrent(op, if (success) GattOpResult.Success else GattOpResult.Failed)
        pump()
        true
    }

    /** Disconnect: forget every queued and in-flight operation without reporting any of them. */
    fun clear() = synchronized(lock) {
        pending.clear()
        current = null
        drainDue = false
        generation++
        cancelTimeout?.invoke()
        cancelTimeout = null
    }

    private fun onTimeout(forGeneration: Long) = synchronized(lock) {
        val op = current
        if (op == null || forGeneration != generation) return@synchronized
        finishCurrent(op, GattOpResult.TimedOut)
        pump()
    }

    private fun finishCurrent(op: GattOp, result: GattOpResult) {
        current = null
        cancelTimeout?.invoke()
        cancelTimeout = null
        generation++
        onFinished(op, result)
    }

    private fun pump() {
        while (current == null) {
            val next = pending.removeFirstOrNull()
            if (next == null) {
                if (drainDue) {
                    drainDue = false
                    onDrained()
                }
                return
            }
            // The operation is current *before* it starts, so a completion delivered synchronously from inside [execute]
            // (a test double, or a stack that calls back on the caller's thread) is matched instead of lost.
            val g = ++generation
            current = next
            cancelTimeout = scheduler.schedule(timeoutMs) { onTimeout(g) }
            val started = try {
                execute(next)
            } catch (_: RuntimeException) {
                false
            }
            if (!started && generation == g && current === next) finishCurrent(next, GattOpResult.Failed)
        }
    }
}

/** A characteristic as the planner sees it: its UUID and the GATT properties that matter. */
data class GattCharacteristicInfo(val uuid: UUID, val canNotify: Boolean, val canRead: Boolean)

data class GattServiceInfo(val uuid: UUID, val characteristics: List<GattCharacteristicInfo>)

/** Which characteristic a notification or read belongs to, decided by service and characteristic together. */
enum class BleChannel { LocationAndSpeed, PositionQuality, LnFeature, Pressure, Temperature, Humidity, NmeaSerial }

/**
 * The operations to run after service discovery, and the capability each of them provides. A peripheral is only reported as
 * having a capability if the operation that enables it succeeded; [servicesAfter] applies that rule.
 */
data class BleGattPlan(val ops: List<GattOp>, val provides: Map<GattOp, BleServiceType>) {
    /** Capabilities that survive the operations in [failed]. Environmental is derived: any ESS channel left makes it true. */
    fun servicesAfter(failed: Set<GattOp>): Set<BleServiceType> {
        val caps = provides.filterKeys { it !in failed }.values.toMutableSet()
        if (caps.any { it == BleServiceType.Pressure || it == BleServiceType.Temperature || it == BleServiceType.Humidity }) {
            caps += BleServiceType.Environmental
        }
        return caps
    }
}

/** Pure decisions about a peripheral's GATT table, kept out of the Android transport so they can be tested. */
object BleGattPlanner {
    /** Requested ATT MTU. A whole NMEA sentence (at most 84 bytes with CR LF) then fits in one notification. */
    const val PREFERRED_MTU = 247

    private val channels: Map<Pair<java.util.UUID, java.util.UUID>, BleChannel> = mapOf(
        (BleUuids.LNS_SERVICE to BleUuids.LOCATION_AND_SPEED) to BleChannel.LocationAndSpeed,
        (BleUuids.LNS_SERVICE to BleUuids.POSITION_QUALITY) to BleChannel.PositionQuality,
        (BleUuids.LNS_SERVICE to BleUuids.LN_FEATURE) to BleChannel.LnFeature,
        (BleUuids.ESS_SERVICE to BleUuids.PRESSURE) to BleChannel.Pressure,
        (BleUuids.ESS_SERVICE to BleUuids.TEMPERATURE) to BleChannel.Temperature,
        (BleUuids.ESS_SERVICE to BleUuids.HUMIDITY) to BleChannel.Humidity,
        (BleUuids.NUS_SERVICE to BleUuids.NUS_TX) to BleChannel.NmeaSerial,
    )

    /** The channel a characteristic of [service] feeds, or null for anything the app does not understand. */
    fun channelOf(service: UUID, characteristic: UUID): BleChannel? = channels[service to characteristic]

    private fun capabilityOf(channel: BleChannel): BleServiceType? = when (channel) {
        BleChannel.LocationAndSpeed -> BleServiceType.Location
        BleChannel.PositionQuality, BleChannel.LnFeature -> null // refine the location fix, provide nothing on their own
        BleChannel.Pressure -> BleServiceType.Pressure
        BleChannel.Temperature -> BleServiceType.Temperature
        BleChannel.Humidity -> BleServiceType.Humidity
        BleChannel.NmeaSerial -> BleServiceType.Nmea
    }

    /**
     * Subscribes to exactly the known characteristics a peripheral offers: nothing else is touched, and in particular an
     * unknown vendor service is never treated as an NMEA source. Position Quality is subscribed to when it notifies and read
     * once when it only supports reads. LN Feature (0x2A6A) is read once, before the location notifications are enabled, so
     * the parser knows whether the device reports Position Status. The MTU request comes first so it is settled before the
     * notification traffic starts.
     */
    fun plan(services: List<GattServiceInfo>): BleGattPlan {
        val reads = ArrayList<GattOp>()
        val ops = ArrayList<GattOp>()
        val provides = LinkedHashMap<GattOp, BleServiceType>()
        for (service in services) {
            for (ch in service.characteristics) {
                val channel = channelOf(service.uuid, ch.uuid) ?: continue
                if (channel == BleChannel.LnFeature) {
                    val read = GattOp.Read(service.uuid, ch.uuid)
                    if (ch.canRead && read !in reads) reads += read
                    continue
                }
                val op: GattOp = when {
                    ch.canNotify -> GattOp.EnableNotify(service.uuid, ch.uuid)
                    channel == BleChannel.PositionQuality && ch.canRead -> GattOp.Read(service.uuid, ch.uuid)
                    else -> continue // the data characteristics are useless without notifications
                }
                if (op in ops) continue
                ops += op
                capabilityOf(channel)?.let { provides[op] = it }
            }
        }
        if (provides.isEmpty()) return BleGattPlan(emptyList(), emptyMap())
        return BleGattPlan(listOf<GattOp>(GattOp.RequestMtu(PREFERRED_MTU)) + reads + ops, provides)
    }
}

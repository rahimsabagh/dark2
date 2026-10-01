package com.v2ray.helper.engine

import com.v2ray.helper.aidl.IMiningCallback
import com.v2ray.helper.aidl.MiningStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic mock engine designed to verify the Android Service and Binder/AIDL architecture.
 *
 * Hashrate is strictly proportional to CPU limit:
 * - 20% CPU limit -> 250 H/s
 * - 50% CPU limit -> 625 H/s
 * - 80% CPU limit -> 1000 H/s
 * Zero random numbers are used.
 */
class MockMiningEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    companion object {
        const val MIN_CPU_LIMIT = 20
        const val MAX_CPU_LIMIT = 80
        const val DEFAULT_CPU_LIMIT = 50
        private const val MAX_BASE_HASHRATE = 1250L // Hashrate at 100% compute
    }

    private val callbacks = CopyOnWriteArrayList<IMiningCallback>()

    private var isRunning: Boolean = false
    private var cpuLimitPercent: Int = DEFAULT_CPU_LIMIT
    private var uptimeSeconds: Long = 0L

    // Constraint flags for resource-aware mining policy
    var requireChargingOnly: Boolean = false
    var requireWifiOnly: Boolean = false
    var batteryProtectionEnabled: Boolean = true
    var temperatureProtectionEnabled: Boolean = true

    // Real device state tracking / simulated telemetry
    var isCharging: Boolean = true
    var isWifiConnected: Boolean = true
    var temperatureCelsius: Float = 36.5f

    private var tickerJob: Job? = null

    private val _statusFlow = MutableStateFlow(createCurrentStatus())
    val statusFlow: StateFlow<MiningStatus> = _statusFlow.asStateFlow()

    @Synchronized
    fun startMining(): Boolean {
        if (isRunning) return true

        // Validate policy constraints before starting
        if (requireChargingOnly && !isCharging) {
            broadcastError("Cannot start: Device is not plugged in / charging")
            updateAndBroadcastStatus("PAUSED_NOT_CHARGING")
            return false
        }
        if (requireWifiOnly && !isWifiConnected) {
            broadcastError("Cannot start: Device is not connected to Wi-Fi")
            updateAndBroadcastStatus("PAUSED_NO_WIFI")
            return false
        }

        isRunning = true
        uptimeSeconds = 0L
        updateAndBroadcastStatus("ACTIVE")
        startTelemetryTicker()
        return true
    }

    @Synchronized
    fun stopMining(): Boolean {
        if (!isRunning) return true

        isRunning = false
        stopTelemetryTicker()
        updateAndBroadcastStatus("STOPPED")
        return true
    }

    @Synchronized
    fun setCpuLimit(percent: Int): Boolean {
        val clamped = percent.coerceIn(MIN_CPU_LIMIT, MAX_CPU_LIMIT)
        val changed = clamped != cpuLimitPercent
        cpuLimitPercent = clamped

        if (changed) {
            updateAndBroadcastStatus(if (isRunning) "ACTIVE" else "STOPPED")
        }
        return true
    }

    @Synchronized
    fun setWifiOnly(enabled: Boolean): Boolean {
        requireWifiOnly = enabled
        if (enabled && isRunning && !isWifiConnected) {
            // Immediately enforce constraint and pause/stop execution
            stopMining()
            updateAndBroadcastStatus("PAUSED_NO_WIFI")
        } else {
            updateAndBroadcastStatus()
        }
        return true
    }

    @Synchronized
    fun setChargingOnly(enabled: Boolean): Boolean {
        requireChargingOnly = enabled
        if (enabled && isRunning && !isCharging) {
            // Immediately enforce constraint and pause/stop execution
            stopMining()
            updateAndBroadcastStatus("PAUSED_NOT_CHARGING")
        } else {
            updateAndBroadcastStatus()
        }
        return true
    }

    fun getStatus(): MiningStatus = createCurrentStatus()

    fun getHashrate(): Long {
        return if (isRunning) {
            calculateDeterministicHashrate(cpuLimitPercent)
        } else {
            0L
        }
    }

    fun registerCallback(callback: IMiningCallback) {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
            try {
                callback.onStatusUpdate(createCurrentStatus())
            } catch (_: Exception) {
                callbacks.remove(callback)
            }
        }
    }

    fun unregisterCallback(callback: IMiningCallback) {
        callbacks.remove(callback)
    }

    private fun calculateDeterministicHashrate(cpuPercent: Int): Long {
        return (cpuPercent.toLong() * MAX_BASE_HASHRATE) / 100L
    }

    private fun createCurrentStatus(overrideMessage: String? = null): MiningStatus {
        val statusMsg = overrideMessage ?: if (isRunning) "ACTIVE" else "STOPPED"
        val currentHashrate = if (isRunning) calculateDeterministicHashrate(cpuLimitPercent) else 0L

        return MiningStatus(
            isRunning = isRunning,
            cpuLimitPercent = cpuLimitPercent,
            hashrateHps = currentHashrate,
            statusMessage = statusMsg,
            uptimeSeconds = uptimeSeconds,
            isCharging = isCharging,
            isWifiConnected = isWifiConnected,
            isWifiOnly = requireWifiOnly,
            isChargingOnly = requireChargingOnly,
            isThrottled = false
        )
    }

    private fun updateAndBroadcastStatus(statusMessage: String? = null) {
        val status = createCurrentStatus(statusMessage)
        _statusFlow.value = status

        val iterator = callbacks.iterator()
        while (iterator.hasNext()) {
            val cb = iterator.next()
            try {
                cb.onStatusUpdate(status)
            } catch (_: Exception) {
                callbacks.remove(cb)
            }
        }
    }

    private fun broadcastError(errorMessage: String) {
        val iterator = callbacks.iterator()
        while (iterator.hasNext()) {
            val cb = iterator.next()
            try {
                cb.onError(errorMessage)
            } catch (_: Exception) {
                callbacks.remove(cb)
            }
        }
    }

    private fun startTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && isRunning) {
                delay(1000L)
                uptimeSeconds += 1L
                updateAndBroadcastStatus()
            }
        }
    }

    private fun stopTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}

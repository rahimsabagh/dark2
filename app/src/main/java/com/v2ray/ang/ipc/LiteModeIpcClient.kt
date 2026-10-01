package com.v2ray.ang.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.v2ray.helper.aidl.IMiningCallback
import com.v2ray.helper.aidl.IMiningService
import com.v2ray.helper.aidl.MiningStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LiteModeState(
    val isBound: Boolean = false,
    val isInstalled: Boolean = true,
    val isMining: Boolean = false,
    val cpuLimitPercent: Int = 50,
    val hashrateHps: Long = 0L,
    val statusMessage: String = "NOT_CONNECTED",
    val uptimeSeconds: Long = 0L,
    val isCharging: Boolean = false,
    val isWifiConnected: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Manages the real Android ServiceConnection and Binder IPC to the Helper service.
 */
class LiteModeIpcClient(private val context: Context) {

    companion object {
        private const val TAG = "LiteModeIpcClient"
        const val HELPER_PACKAGE = "com.v2ray.helper"
        const val HELPER_ACTION = "com.v2ray.helper.ACTION_BIND_MINING"
    }

    private var miningService: IMiningService? = null
    private var isBinding = false

    private val _state = MutableStateFlow(LiteModeState())
    val state: StateFlow<LiteModeState> = _state.asStateFlow()

    private val callback = object : IMiningCallback.Stub() {
        override fun onStatusUpdate(status: MiningStatus?) {
            if (status == null) return
            _state.update {
                it.copy(
                    isMining = status.isRunning,
                    cpuLimitPercent = status.cpuLimitPercent,
                    hashrateHps = status.hashrateHps,
                    statusMessage = status.statusMessage,
                    uptimeSeconds = status.uptimeSeconds,
                    isCharging = status.isCharging,
                    isWifiConnected = status.isWifiConnected,
                    errorMessage = null
                )
            }
        }

        override fun onError(message: String?) {
            Log.e(TAG, "Received error from Helper: $message")
            _state.update { it.copy(errorMessage = message) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to Helper service: $name")
            val aidl = IMiningService.Stub.asInterface(service)
            miningService = aidl

            try {
                aidl.registerCallback(callback)
                val initialStatus = aidl.status
                _state.update {
                    it.copy(
                        isBound = true,
                        isInstalled = true,
                        isMining = initialStatus.isRunning,
                        cpuLimitPercent = initialStatus.cpuLimitPercent,
                        hashrateHps = initialStatus.hashrateHps,
                        statusMessage = initialStatus.statusMessage,
                        uptimeSeconds = initialStatus.uptimeSeconds,
                        isCharging = initialStatus.isCharging,
                        isWifiConnected = initialStatus.isWifiConnected,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing state from Helper: ${e.message}", e)
                _state.update { it.copy(errorMessage = "Communication error: ${e.message}") }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Helper service disconnected: $name")
            miningService = null
            _state.update {
                it.copy(
                    isBound = false,
                    isMining = false,
                    hashrateHps = 0L,
                    statusMessage = "DISCONNECTED"
                )
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.e(TAG, "Helper service binding died: $name")
            disconnect()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.e(TAG, "Helper service returned null binding. Caller authorization failed or service disabled.")
            _state.update {
                it.copy(
                    isBound = false,
                    statusMessage = "UNAUTHORIZED_OR_REJECTED",
                    errorMessage = "Helper service rejected connection. Security check failed."
                )
            }
        }
    }

    fun connect() {
        if (_state.value.isBound || isBinding) return
        isBinding = true

        val intent = Intent(HELPER_ACTION).apply {
            setPackage(HELPER_PACKAGE)
        }

        try {
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Log.w(TAG, "Failed to bind to Helper. APK might not be installed.")
                _state.update {
                    it.copy(
                        isBound = false,
                        isInstalled = false,
                        statusMessage = "NOT_INSTALLED",
                        errorMessage = "Helper APK is not installed on device."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during bindService: ${e.message}", e)
            _state.update {
                it.copy(
                    isBound = false,
                    statusMessage = "BIND_FAILED",
                    errorMessage = e.message
                )
            }
        } finally {
            isBinding = false
        }
    }

    fun disconnect() {
        if (_state.value.isBound) {
            try {
                miningService?.unregisterCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister callback: ${e.message}")
            }
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unbind service: ${e.message}")
            }
        }
        miningService = null
        _state.update {
            it.copy(
                isBound = false,
                isMining = false,
                hashrateHps = 0L,
                statusMessage = "DISCONNECTED"
            )
        }
    }

    fun startMining(): Boolean {
        val service = miningService
        if (service == null) {
            _state.update { it.copy(errorMessage = "Cannot start: Helper service not connected") }
            return false
        }
        return try {
            service.startMining()
        } catch (e: Exception) {
            Log.e(TAG, "startMining failed: ${e.message}", e)
            _state.update { it.copy(errorMessage = "IPC call failed: ${e.message}") }
            false
        }
    }

    fun stopMining(): Boolean {
        val service = miningService
        if (service == null) {
            _state.update { it.copy(errorMessage = "Cannot stop: Helper service not connected") }
            return false
        }
        return try {
            service.stopMining()
        } catch (e: Exception) {
            Log.e(TAG, "stopMining failed: ${e.message}", e)
            _state.update { it.copy(errorMessage = "IPC call failed: ${e.message}") }
            false
        }
    }

    fun setCpuLimit(percent: Int): Boolean {
        val clamped = percent.coerceIn(20, 80)
        val service = miningService ?: return false
        return try {
            service.setCpuLimit(clamped)
        } catch (e: Exception) {
            Log.e(TAG, "setCpuLimit failed: ${e.message}", e)
            false
        }
    }
}

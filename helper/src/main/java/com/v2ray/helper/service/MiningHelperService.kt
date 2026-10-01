package com.v2ray.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.v2ray.helper.aidl.IMiningCallback
import com.v2ray.helper.aidl.IMiningService
import com.v2ray.helper.aidl.MiningStatus
import com.v2ray.helper.engine.MockMiningEngine
import com.v2ray.helper.security.SignatureValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the mining execution engine.
 *
 * Security & Lifecycle Guarantees:
 * - Only starts foreground execution when explicitly instructed via startMining()
 * - Strictly validates caller UID and signing certificate
 * - Provides an ongoing notification with real-time telemetry and a "Stop" action
 * - No auto-start or boot persistence
 */
class MiningHelperService : Service() {

    companion object {
        private const val TAG = "MiningHelperService"
        const val ACTION_BIND_MINING = "com.v2ray.helper.ACTION_BIND_MINING"
        const val ACTION_STOP_MINING = "com.v2ray.helper.ACTION_STOP_MINING"

        private const val CHANNEL_ID = "mining_service_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine = MockMiningEngine()
    private var statusObserverJob: Job? = null
    private var isForegroundActive = false

    private val binder = object : IMiningService.Stub() {
        override fun startMining(): Boolean {
            enforceSecurity()
            Log.i(TAG, "IPC startMining() invoked by UID ${Binder.getCallingUid()}")
            val success = engine.startMining()
            if (success) {
                startServiceForeground()
            }
            return success
        }

        override fun stopMining(): Boolean {
            enforceSecurity()
            Log.i(TAG, "IPC stopMining() invoked by UID ${Binder.getCallingUid()}")
            val success = engine.stopMining()
            stopServiceForeground()
            return success
        }

        override fun setCpuLimit(percent: Int): Boolean {
            enforceSecurity()
            Log.i(TAG, "IPC setCpuLimit($percent%) invoked by UID ${Binder.getCallingUid()}")
            val result = engine.setCpuLimit(percent)
            if (isForegroundActive) {
                updateNotification(engine.getStatus())
            }
            return result
        }

        override fun getStatus(): MiningStatus {
            enforceSecurity()
            return engine.getStatus()
        }

        override fun getHashrate(): Long {
            enforceSecurity()
            return engine.getHashrate()
        }

        override fun registerCallback(callback: IMiningCallback) {
            enforceSecurity()
            engine.registerCallback(callback)
        }

        override fun unregisterCallback(callback: IMiningCallback) {
            enforceSecurity()
            engine.unregisterCallback(callback)
        }

        private fun enforceSecurity() {
            SignatureValidator.verifyCaller(this@MiningHelperService, Binder.getCallingUid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MiningHelperService created (Idle state, not mining)")
        createNotificationChannel()

        // Observe telemetry to keep foreground notification updated
        statusObserverJob = serviceScope.launch {
            engine.statusFlow.collectLatest { status ->
                if (isForegroundActive && status.isRunning) {
                    updateNotification(status)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MINING) {
            Log.i(TAG, "Stop mining requested via notification action")
            engine.stopMining()
            stopServiceForeground()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        val callingUid = Binder.getCallingUid()
        Log.d(TAG, "Client binding with intent: ${intent?.action}, calling UID: $callingUid")

        return try {
            SignatureValidator.verifyCaller(this, callingUid)
            binder
        } catch (e: SecurityException) {
            Log.e(TAG, "Rejecting unauthorized binder connection: ${e.message}")
            null
        }
    }

    private fun startServiceForeground() {
        if (isForegroundActive) return
        isForegroundActive = true

        val notification = buildNotification(engine.getStatus())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Promoted service to Foreground Service")
    }

    private fun stopServiceForeground() {
        if (!isForegroundActive) return
        isForegroundActive = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "Demoted service from Foreground Service")
    }

    private fun updateNotification(status: MiningStatus) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: MiningStatus): Notification {
        val stopIntent = Intent(this, MiningHelperService::class.java).apply {
            action = ACTION_STOP_MINING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (status.isRunning) {
            "Hashrate: ${status.hashrateHps} H/s | CPU Limit: ${status.cpuLimitPercent}%"
        } else {
            "Mining stopped"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lite Mode Mining Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(status.isRunning)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Mining",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mining Execution Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time telemetry while user-authorized mining is active"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MiningHelperService destroyed")
        engine.stopMining()
        stopServiceForeground()
        statusObserverJob?.cancel()
        serviceScope.cancel()
    }
}

package com.galaxyairpods.data.bluetooth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.service.AirPodsMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Receives BLE results after Android wakes the process through PendingIntent. */
class AirPodsScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCAN_RESULT) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (AirPodsDataStore(appContext).backgroundDetection.first()) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            appContext,
                            Intent(appContext, AirPodsMonitorService::class.java),
                        )
                    }
                }
                AirPodsScannerHub.dispatchPendingScanIntent(appContext, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCAN_RESULT = "com.galaxyairpods.BLE_SCAN_RESULT"
    }
}

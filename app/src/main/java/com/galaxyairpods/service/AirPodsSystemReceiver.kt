package com.galaxyairpods.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.galaxyairpods.data.persistence.AirPodsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the monitor when the app is not currently open. */
class AirPodsSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in START_ACTIONS) return

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
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothDeviceAction.ACL_CONNECTED,
        )
    }

    private object BluetoothDeviceAction {
        const val ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
    }
}

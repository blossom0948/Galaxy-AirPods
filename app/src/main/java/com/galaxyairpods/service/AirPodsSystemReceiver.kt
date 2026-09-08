package com.galaxyairpods.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
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

/**
 * Wakes the monitor for lifecycle/connection events only.
 *
 * V4 deliberately keeps Android hidden battery metadata and HFP vendor events
 * out of the primary acquisition path. Connection broadcasts prove only that
 * a profile changed; they never create a battery sample.
 */
class AirPodsSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in START_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = AirPodsDataStore(appContext)
                val monitorRequired = store.backgroundDetection.first() ||
                    (store.wearDetectionEnabled.first() && store.automaticMediaControlEnabled.first())
                if (monitorRequired) {
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

    companion object {
        val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothDeviceAction.ACL_CONNECTED,
            BluetoothDeviceAction.ACL_DISCONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
        )
    }

    private object BluetoothDeviceAction {
        const val ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
        const val ACL_DISCONNECTED = "android.bluetooth.device.action.ACL_DISCONNECTED"
    }
}

package com.galaxyairpods.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.galaxyairpods.data.bluetooth.BluetoothBatteryReader
import com.galaxyairpods.data.bluetooth.parseIphoneAccessoryBattery
import com.galaxyairpods.data.bluetooth.parseXEventBattery
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.domain.model.AirPodsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/** Starts the monitor when the app is not currently open. */
class AirPodsSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                handleHfpBatteryEvent(context, intent)
                return
            }

            HF_INDICATORS_VALUE_CHANGED_ACTION -> {
                handleHfIndicatorBatteryEvent(context, intent)
                return
            }

            BATTERY_LEVEL_CHANGED_ACTION -> {
                handleSystemBatteryEvent(context, intent)
                return
            }

            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                if (intent.getIntExtra(
                        BluetoothHeadset.EXTRA_STATE,
                        BluetoothHeadset.STATE_DISCONNECTED,
                    ) == BluetoothHeadset.STATE_CONNECTED
                ) {
                    persistCachedBattery(context, intent)
                }
            }

            in START_ACTIONS -> Unit
            else -> return
        }

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

    private fun handleHfpBatteryEvent(context: Context, intent: Intent) {
        val command = intent.getStringExtra(
            BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD,
        ) ?: return

        val device = intent.airPodsDevice(context) ?: return
        val name = device.airPodsName()
        if (!name.looksLikeAirPods()) return
        val raw = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS)
        val battery = when {
            command.contains("IPHONEACCEV", ignoreCase = true) -> parseIphoneAccessoryBattery(raw)
            command.contains("XEVENT", ignoreCase = true) -> parseXEventBattery(raw)
            else -> null
        } ?: BluetoothBatteryReader.read(device) ?: return
        persistClassicBattery(context, device, name, battery)
    }

    /** Standard HFP 1.7 indicator broadcast used by newer Android stacks. */
    private fun handleHfIndicatorBatteryEvent(context: Context, intent: Intent) {
        val indicatorId = intent.getIntExtra(HF_INDICATORS_IND_ID_EXTRA, -1)
        if (indicatorId != HFP_BATTERY_INDICATOR_ID) return

        val device = intent.airPodsDevice(context) ?: return
        val name = device.airPodsName()
        if (!name.looksLikeAirPods()) return

        val battery = intent.getIntExtra(HF_INDICATORS_IND_VALUE_EXTRA, -1)
            .takeIf { it in 0..100 }
            ?: BluetoothBatteryReader.read(device)
            ?: return
        persistClassicBattery(context, device, name, battery)
    }

    private fun handleSystemBatteryEvent(context: Context, intent: Intent) {
        val device = intent.airPodsDevice(context) ?: return
        val name = device.airPodsName()
        if (!name.looksLikeAirPods()) return
        val broadcastBattery = intent.getIntExtra(BATTERY_LEVEL_EXTRA, -1)
        val battery = broadcastBattery.takeIf { it in 0..100 }
            ?: BluetoothBatteryReader.readSnapshot(device).bestAvailable
            ?: return
        persistClassicBattery(context, device, name, battery)
    }

    private fun persistCachedBattery(context: Context, intent: Intent) {
        val device = intent.airPodsDevice(context) ?: return
        val name = device.airPodsName()
        if (!name.looksLikeAirPods()) return
        BluetoothBatteryReader.readSnapshot(device).let { snapshot ->
            if (snapshot.hasValue) {
                persistClassicBatterySnapshot(context, device, name, snapshot)
            }
        }
    }

    private fun persistClassicBattery(
        context: Context,
        device: BluetoothDevice,
        name: String,
        battery: Int,
    ) {
        persistClassicBatterySnapshot(
            context = context,
            device = device,
            name = name,
            snapshot = BluetoothBatteryReader.Snapshot(main = battery),
        )
    }

    private fun persistClassicBatterySnapshot(
        context: Context,
        device: BluetoothDevice,
        name: String,
        snapshot: BluetoothBatteryReader.Snapshot,
    ) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AirPodsDataStore(appContext).applyClassicBatterySnapshot(
                    deviceId = device.address,
                    deviceName = name,
                    model = AirPodsModel.fromBluetoothName(name),
                    mainBattery = snapshot.main,
                    leftBattery = snapshot.left,
                    rightBattery = snapshot.right,
                    caseBattery = snapshot.caseBattery,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.airPodsName(): String {
        val remoteName = runCatching { name }.getOrNull().orEmpty()
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { alias }.getOrNull().orEmpty()
        } else {
            ""
        }
        return alias.ifBlank { remoteName }
    }

    private fun String.looksLikeAirPods(): Boolean =
        lowercase(Locale.US).replace("-", " ").contains("airpod") ||
            lowercase(Locale.US).contains("apple")

    @SuppressLint("MissingPermission")
    private fun Intent.airPodsDevice(context: Context): BluetoothDevice? {
        val explicit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (explicit != null) return explicit

        return runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.getConnectedDevices(BluetoothProfile.HEADSET)
                ?.firstOrNull { it.airPodsName().looksLikeAirPods() }
        }.getOrNull()
    }

    companion object {
        val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            BluetoothDeviceAction.ACL_CONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
        )
        const val BATTERY_LEVEL_CHANGED_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val HF_INDICATORS_VALUE_CHANGED_ACTION =
            "android.bluetooth.headset.action.HF_INDICATORS_VALUE_CHANGED"
        private const val HF_INDICATORS_IND_ID_EXTRA =
            "android.bluetooth.headset.extra.HF_INDICATORS_IND_ID"
        private const val HF_INDICATORS_IND_VALUE_EXTRA =
            "android.bluetooth.headset.extra.HF_INDICATORS_IND_VALUE"
        private const val HFP_BATTERY_INDICATOR_ID = 2
        private const val BATTERY_LEVEL_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }

    private object BluetoothDeviceAction {
        const val ACL_CONNECTED = "android.bluetooth.device.action.ACL_CONNECTED"
    }
}

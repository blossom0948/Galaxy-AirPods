package com.galaxyairpods.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class ValidatedPacketEvent(
    val deviceId: String,
    val packet: ParsedAirPodsPacket,
    val seenAt: Long,
)

data class BluetoothAirPodsEvent(
    val deviceId: String,
    val deviceName: String,
    val model: AirPodsModel,
    val connected: Boolean,
    val seenAt: Long,
)

class AirPodsBleScanner(private val context: Context) {
    private val _status = MutableStateFlow("스캔 대기")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _validatedPackets = MutableSharedFlow<ValidatedPacketEvent>(replay = 1, extraBufferCapacity = 16)
    val validatedPackets: SharedFlow<ValidatedPacketEvent> = _validatedPackets.asSharedFlow()

    private val _bluetoothEvents = MutableSharedFlow<BluetoothAirPodsEvent>(replay = 1, extraBufferCapacity = 8)
    val bluetoothEvents: SharedFlow<BluetoothAirPodsEvent> = _bluetoothEvents.asSharedFlow()

    private val parserRegistry = AirPodsParserRegistry()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var scanning = false
    private var connectionMonitorJob: Job? = null
    private var lastBluetoothEvent: BluetoothAirPodsEvent? = null
    private var profileProxiesRequested = false
    private var pendingScanIntent: PendingIntent? = null
    @Volatile
    private var pendingScanActive = false
    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }
    private val adapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            synchronized(profileProxies) {
                if (connectionMonitorJob?.isActive == true) {
                    profileProxies[profile] = proxy
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            synchronized(profileProxies) {
                profileProxies.remove(profile)
            }
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            append(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::append)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _status.value = "스캔 실패: error $errorCode"
        }
    }

    @Synchronized
    fun start(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY) {
        if (!hasConnectPermission()) {
            _status.value = "Bluetooth/Nearby devices 권한이 필요합니다"
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            _status.value = "이 기기에서 Bluetooth를 사용할 수 없습니다"
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _status.value = "Bluetooth가 꺼져 있습니다"
            return
        }

        startConnectionMonitor()
        if (scanning) return
        if (!hasScanPermission()) {
            _status.value = "BLE 검색 권한이 없어 연결 상태만 확인합니다"
            return
        }

        try {
            val scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                _status.value = "BLE 스캐너를 사용할 수 없어 Bluetooth 연결만 확인합니다"
                return
            }
            if (startBleScan(scanner, scanMode)) {
                startPendingIntentScan(scanner, scanMode)
                return
            }
            _status.value = "Bluetooth 스캔을 시작하지 못했습니다"
        } catch (_: SecurityException) {
            _status.value = "Bluetooth scan 권한이 없어 시작하지 못했습니다"
        } catch (_: IllegalStateException) {
            _status.value = "Bluetooth 스캔을 시작하지 못했습니다"
        }
    }

    @Synchronized
    fun stop() {
        if (scanning && hasScanPermission()) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(callback)
                if (pendingScanActive) {
                    pendingScanIntent?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
                }
            } catch (_: SecurityException) {
                _status.value = "권한이 없어 BLE 검색을 중지하지 못했습니다"
            }
        }
        scanning = false
        pendingScanActive = false
        pendingScanIntent = null
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        lastBluetoothEvent = null
        closeProfileProxies()
        _status.value = "검색 중지"
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        scanMode: Int,
    ): Boolean {
        // Do not use an offloaded manufacturer filter here. Several Samsung
        // Bluetooth stacks accept the filtered scan but silently deliver no
        // callbacks for Apple's rotating advertisements. We scan all BLE
        // advertisements and apply the strict Apple parser in process instead.
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(
                if (scanMode == ScanSettings.SCAN_MODE_LOW_LATENCY) {
                    ScanSettings.MATCH_MODE_AGGRESSIVE
                } else {
                    ScanSettings.MATCH_MODE_STICKY
                },
            )
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        return try {
            scanner.startScan(null, settings, callback)
            scanning = true
            _status.value = "AirPods 신호 검색 중"
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // A few OEM stacks reject one of the optional settings. Retry with
            // the minimal platform-supported configuration before giving up.
            runCatching {
                scanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(scanMode).build(),
                    callback,
                )
                scanning = true
                _status.value = "AirPods 신호 검색 중"
                true
            }.getOrDefault(false)
        } catch (_: IllegalStateException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPendingIntentScan(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        scanMode: Int,
    ) {
        if (pendingScanActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val intent = Intent(context, AirPodsScanReceiver::class.java)
            .setAction(AirPodsScanReceiver.ACTION_SCAN_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PENDING_SCAN_REQUEST_CODE,
            intent,
            flags,
        )
        val filters = listOf(
            // Match the protocol byte only: pairing mode is 0x0E and paired
            // mode is 0x19, so filtering on the old length drops valid cases.
            ScanFilter.Builder()
                .setManufacturerData(
                    AppleAirPodsParser.APPLE_COMPANY_ID,
                    byteArrayOf(0x07),
                    byteArrayOf(0xFF.toByte()),
                )
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()

        runCatching {
            scanner.startScan(filters, settings, pendingIntent)
            pendingScanIntent = pendingIntent
            pendingScanActive = true
        }
    }

    /** Called by [AirPodsScanReceiver] for a PendingIntent-delivered result. */
    internal fun dispatchPendingScanIntent(intent: Intent) {
        val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            ).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(
                android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            ).orEmpty()
        }
        results.forEach(::append)
    }

    private fun append(result: ScanResult) {
        parserRegistry.parse(result)?.let { parsed ->
            _status.value = "AirPods 신호 감지됨"
            _validatedPackets.tryEmit(
                ValidatedPacketEvent(
                    deviceId = runCatching { result.device.address }.getOrDefault("unknown"),
                    packet = parsed,
                    seenAt = System.currentTimeMillis(),
                ),
            )
        } ?: run {
            if (lastBluetoothEvent == null) _status.value = "AirPods 연결 상태 확인 중"
        }
    }

    private fun startConnectionMonitor() {
        if (connectionMonitorJob?.isActive == true) return
        connectionMonitorJob = monitorScope.launch {
            while (isActive) {
                refreshBluetoothConnection()
                delay(CONNECTION_POLL_MS)
            }
        }
        requestProfileProxies()
    }

    @SuppressLint("MissingPermission")
    private fun requestProfileProxies() {
        if (profileProxiesRequested || !hasConnectPermission()) return
        val bluetoothAdapter = adapter ?: return
        profileProxiesRequested = true
        PROFILE_PROXY_IDS.forEach { profile ->
            runCatching {
                bluetoothAdapter.getProfileProxy(context, profileListener, profile)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetoothConnection() {
        if (!hasConnectPermission()) return
        val bluetoothAdapter = adapter ?: return
        if (!bluetoothAdapter.isEnabled) {
            publishDisconnectedEvent()
            _status.value = "Bluetooth가 꺼져 있습니다"
            return
        }

        val connectedDevices = buildList {
            runCatching {
                addAll(bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty())
            }
            val proxies = synchronized(profileProxies) { profileProxies.values.toList() }
            proxies.forEach { profile ->
                runCatching { addAll(profile.connectedDevices) }
            }
        }
            .associateBy { it.address }
        val bondedDevices = runCatching { bluetoothAdapter.bondedDevices }
            .getOrDefault(emptySet())

        val candidates = (bondedDevices + connectedDevices.values)
            .distinctBy { it.address }
            .mapNotNull { device ->
                val remoteName = device.remoteName()
                val alias = device.localAlias()
                val names = listOf(remoteName, alias).filter { it.isNotBlank() }
                if (names.isEmpty() || !looksLikeAirPods(device, names)) {
                    return@mapNotNull null
                }
                BluetoothAirPodsEvent(
                    deviceId = device.address,
                    deviceName = alias.ifBlank { remoteName },
                    model = modelFromName(names.joinToString(" ")),
                    connected = connectedDevices.containsKey(device.address),
                    seenAt = System.currentTimeMillis(),
                )
            }
            .sortedWith(compareByDescending<BluetoothAirPodsEvent> { it.connected }.thenBy { it.deviceId })

        val current = candidates.firstOrNull()
        val previous = lastBluetoothEvent
        if (current == null) {
            publishDisconnectedEvent()
            _status.value = "AirPods 연결 대기"
            return
        }
        lastBluetoothEvent = current

        _status.value = if (current.connected) {
            "AirPods Bluetooth 연결됨"
        } else {
            "AirPods 페어링됨 · 연결 대기"
        }

        val changed = previous == null ||
            previous.deviceId != current.deviceId ||
            previous.connected != current.connected ||
            previous.model != current.model ||
            current.seenAt - previous.seenAt >= EVENT_REFRESH_MS
        if (changed) _bluetoothEvents.tryEmit(current)
    }

    private fun publishDisconnectedEvent() {
        val previous = lastBluetoothEvent ?: return
        if (!previous.connected) return
        val disconnected = previous.copy(
            connected = false,
            seenAt = System.currentTimeMillis(),
        )
        lastBluetoothEvent = disconnected
        _bluetoothEvents.tryEmit(disconnected)
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.localAlias(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { alias }.getOrNull().orEmpty()
    } else {
        ""
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.remoteName(): String = runCatching { name }.getOrNull().orEmpty()

    @SuppressLint("MissingPermission")
    private fun looksLikeAirPods(device: BluetoothDevice, names: List<String>): Boolean {
        if (names.any { it.lowercase(Locale.US).replace("-", " ").contains("airpod") }) return true
        return device.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO &&
            names.any { it.lowercase(Locale.US).contains("apple") }
    }

    private fun modelFromName(name: String): AirPodsModel {
        return AirPodsModel.fromBluetoothName(name)
    }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    companion object {
        private const val PENDING_SCAN_REQUEST_CODE = 1002
        private const val CONNECTION_POLL_MS = 1_500L
        private const val EVENT_REFRESH_MS = 30_000L
        private val PROFILE_PROXY_IDS = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeProfileProxies() {
        val proxies = synchronized(profileProxies) {
            val copy = profileProxies.toMap()
            profileProxies.clear()
            copy
        }
        proxies.forEach { (profile, proxy) ->
            runCatching { adapter?.closeProfileProxy(profile, proxy) }
        }
        profileProxiesRequested = false
    }
}

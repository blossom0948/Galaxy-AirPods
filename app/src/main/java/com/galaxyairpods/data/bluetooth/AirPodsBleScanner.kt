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
import com.galaxyairpods.domain.model.AirPodsConnectionState
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import com.galaxyairpods.domain.model.isCompatibleWith
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
    val connectionState: AirPodsConnectionState = if (connected) {
        AirPodsConnectionState.ANDROID_CONNECTED
    } else {
        AirPodsConnectionState.UNKNOWN
    },
    val a2dpConnected: Boolean = false,
    val headsetConnected: Boolean = false,
    val aapReady: Boolean = false,
    val nearbySeenAt: Long? = null,
)

class AirPodsBleScanner(private val context: Context) {
    private val _status = MutableStateFlow("스캔 대기")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _validatedPackets = MutableSharedFlow<ValidatedPacketEvent>(replay = 1, extraBufferCapacity = 16)
    val validatedPackets: SharedFlow<ValidatedPacketEvent> = _validatedPackets.asSharedFlow()

    private val _bluetoothEvents = MutableSharedFlow<BluetoothAirPodsEvent>(replay = 1, extraBufferCapacity = 8)
    val bluetoothEvents: SharedFlow<BluetoothAirPodsEvent> = _bluetoothEvents.asSharedFlow()

    private val _classicBatteryEvents = MutableSharedFlow<ClassicAapBatteryEvent>(replay = 1, extraBufferCapacity = 16)
    internal val classicBatteryEvents: SharedFlow<ClassicAapBatteryEvent> = _classicBatteryEvents.asSharedFlow()

    private val _classicWearEvents = MutableSharedFlow<ClassicAapWearEvent>(replay = 1, extraBufferCapacity = 16)
    internal val classicWearEvents: SharedFlow<ClassicAapWearEvent> = _classicWearEvents.asSharedFlow()

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
    private var directScanFallbackJob: Job? = null
    private var legacyScanFallbackJob: Job? = null
    private var aapSessionJob: Job? = null
    private var aapSessionDeviceId: String? = null
    private var legacyScanning = false
    private var directScanIsFiltered = false
    @Volatile
    private var lastValidatedAt = 0L
    @Volatile
    private var lastValidatedDeviceId: String? = null
    @Volatile
    private var lastValidatedModel: AirPodsModel = AirPodsModel.UNKNOWN
    @Volatile
    private var lastPrimaryPodIsLeft: Boolean? = null
    @Volatile
    private var aapReadyDeviceId: String? = null
    private val aapWearStabilizer = AapWearStabilizer()
    private val publicWearStabilizer = WearStateStabilizer()
    private val profileProxies = mutableMapOf<Int, BluetoothProfile>()
    private val aapBatteryClient = ClassicAapBatteryClient()
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
            append(result, delivery = "DIRECT")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { append(it, delivery = "BATCH") }
        }

        override fun onScanFailed(errorCode: Int) {
            BleScanDiagnostics.logScanFailed(errorCode)
            if (directScanIsFiltered && restartUnfilteredScanAfterFailure()) return
            scanning = false
            directScanIsFiltered = false
            _status.value = "스캔 실패: error $errorCode"
        }
    }

    private val legacyScanCallback = object : BluetoothAdapter.LeScanCallback {
        override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray) {
            parserRegistry.parseRawScanRecord(scanRecord)?.let { parsed ->
                emitValidatedPacket(device.address, parsed)
            }
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
            lastValidatedAt = 0L
            // Samsung firmware has shipped BLE offload filters that accept an
            // AirPods scan but never deliver the rotating Apple payload to the
            // app. Receive the raw advertisements first and apply the strict
            // parser in-process instead. This is the same compatibility shape
            // used by CAPod's unfiltered troubleshooting path.
            if (startBleScan(scanner, scanMode, filtered = false)) {
                startPendingIntentScan(scanner, scanMode)
                scheduleLegacyScanFallback()
                return
            }
            _status.value = "Bluetooth 스캔을 시작하지 못했습니다"
        } catch (_: SecurityException) {
            _status.value = "Bluetooth scan 권한이 없어 시작하지 못했습니다"
        } catch (_: IllegalStateException) {
            _status.value = "Bluetooth 스캔을 시작하지 못했습니다"
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stop() {
        aapBatteryClient.close()
        aapSessionJob?.cancel()
        aapSessionJob = null
        aapSessionDeviceId = null
        aapReadyDeviceId = null
        aapWearStabilizer.reset()
        publicWearStabilizer.reset()
        directScanFallbackJob?.cancel()
        directScanFallbackJob = null
        legacyScanFallbackJob?.cancel()
        legacyScanFallbackJob = null
        if (hasScanPermission()) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(callback)
                if (pendingScanActive) {
                    pendingScanIntent?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
                }
            } catch (_: SecurityException) {
                _status.value = "권한이 없어 BLE 검색을 중지하지 못했습니다"
            }
        }
        if (legacyScanning && hasScanPermission()) {
            runCatching { adapter?.stopLeScan(legacyScanCallback) }
        }
        scanning = false
        directScanIsFiltered = false
        pendingScanActive = false
        pendingScanIntent = null
        legacyScanning = false
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        lastBluetoothEvent = null
        lastValidatedDeviceId = null
        lastValidatedModel = AirPodsModel.UNKNOWN
        lastPrimaryPodIsLeft = null
        closeProfileProxies()
        _status.value = "검색 중지"
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startBleScan(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        scanMode: Int,
        filtered: Boolean,
        scheduleFallback: Boolean = filtered,
    ): Boolean {
        val filters = AIRPODS_SCAN_FILTERS.takeIf { filtered }
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
            scanner.startScan(filters, settings, callback)
            scanning = true
            directScanIsFiltered = filtered
            _status.value = "AirPods 신호 검색 중"
            adapter?.let { BleScanDiagnostics.logScannerStarted(it, scanMode, filtered) }
            if (scheduleFallback) scheduleUnfilteredFallback(scanner, scanMode)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // Some OEM stacks reject an optional setting or cannot offload the
            // manufacturer filter. Retry with the minimal configuration and
            // then use the unfiltered path if necessary.
            runCatching {
                scanner.startScan(
                    filters,
                    ScanSettings.Builder().setScanMode(scanMode).build(),
                    callback,
                )
                scanning = true
                directScanIsFiltered = filtered
                _status.value = "AirPods 신호 검색 중"
                if (scheduleFallback) scheduleUnfilteredFallback(scanner, scanMode)
                true
            }.getOrElse {
                if (filtered) {
                    startBleScan(scanner, scanMode, filtered = false, scheduleFallback = false)
                } else {
                    false
                }
            }
        } catch (_: IllegalStateException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleUnfilteredFallback(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        scanMode: Int,
    ) {
        directScanFallbackJob?.cancel()
        directScanFallbackJob = monitorScope.launch {
            delay(DIRECT_FILTER_FALLBACK_DELAY_MS)
            if (!scanning || !directScanIsFiltered || lastValidatedAt != 0L) return@launch

            runCatching { scanner.stopScan(callback) }
            startBleScan(scanner, scanMode, filtered = false, scheduleFallback = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleLegacyScanFallback() {
        legacyScanFallbackJob?.cancel()
        legacyScanFallbackJob = monitorScope.launch {
            delay(LEGACY_SCAN_FALLBACK_DELAY_MS)
            if (lastValidatedAt != 0L || legacyScanning) return@launch
            val bluetoothAdapter = adapter ?: return@launch
            legacyScanning = runCatching {
                bluetoothAdapter.startLeScan(legacyScanCallback)
            }.getOrDefault(false)
        }
    }

    private fun emitValidatedPacket(deviceId: String, parsed: ParsedAirPodsPacket) {
        val seenAt = System.currentTimeMillis()
        lastValidatedAt = seenAt
        lastValidatedDeviceId = deviceId
        lastValidatedModel = parsed.model
        parsed.primaryPodIsLeft?.let { lastPrimaryPodIsLeft = it }
        val stabilizedWear = parsed.wearState?.let {
            publicWearStabilizer.accept(it, seenAt)
        }
        directScanFallbackJob?.cancel()
        _status.value = "AirPods 신호 감지됨"
        _validatedPackets.tryEmit(
            ValidatedPacketEvent(
                deviceId = deviceId,
                packet = parsed.copy(wearState = stabilizedWear),
                seenAt = seenAt,
            ),
        )
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun restartUnfilteredScanAfterFailure(): Boolean {
        val bluetoothScanner = adapter?.bluetoothLeScanner ?: return false
        return runCatching {
            bluetoothScanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0L)
                    .build(),
                callback,
            )
            scanning = true
            directScanIsFiltered = false
            _status.value = "AirPods 신호 검색 중"
            true
        }.getOrDefault(false)
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
        // Do not use an offloaded manufacturer filter here. Several Samsung
        // Bluetooth stacks accept the filter but silently omit Apple's
        // rotating advertisements from PendingIntent delivery. The receiver
        // parses and validates the raw records itself.
        val filters: List<ScanFilter>? = null
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
        results.forEach { append(it, delivery = "PENDING") }
    }

    private fun append(result: ScanResult, delivery: String) {
        val parsed = parserRegistry.parse(result)
        BleScanDiagnostics.logScanResult(result, delivery, parsed)
        parsed?.let {
            emitValidatedPacket(
                deviceId = runCatching { result.device.address }.getOrDefault("unknown"),
                parsed = it,
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
            stopAapSession()
            publishDisconnectedEvent()
            _status.value = "Bluetooth가 꺼져 있습니다"
            return
        }

        val knownDevices = linkedMapOf<String, BluetoothDevice>()
        val a2dpAddresses = mutableSetOf<String>()
        val headsetAddresses = mutableSetOf<String>()
        runCatching {
            // GATT is useful for identity/candidate discovery but is not an
            // Android audio connection. Counting it as connected caused the
            // exact false positive shown in the user's screenshot.
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                .orEmpty()
                .forEach { knownDevices[it.address] = it }
        }
        val proxies = synchronized(profileProxies) { profileProxies.toMap() }
        proxies.forEach { (profileId, profile) ->
            runCatching {
                profile.connectedDevices.forEach { device ->
                    knownDevices[device.address] = device
                    when (profileId) {
                        BluetoothProfile.A2DP -> a2dpAddresses += device.address
                        BluetoothProfile.HEADSET -> headsetAddresses += device.address
                    }
                }
            }
        }
        val bondedDevices = runCatching { bluetoothAdapter.bondedDevices }
            .getOrDefault(emptySet())

        val candidates = (bondedDevices + knownDevices.values)
            .distinctBy { it.address }
            .mapNotNull { device ->
                val remoteName = device.remoteName()
                val alias = device.localAlias()
                val names = listOf(remoteName, alias).filter { it.isNotBlank() }
                if (names.isEmpty() || !looksLikeAirPods(device, names)) {
                    return@mapNotNull null
                }
                val now = System.currentTimeMillis()
                val a2dpConnected = a2dpAddresses.contains(device.address)
                val headsetConnected = headsetAddresses.contains(device.address)
                val aapReady = aapReadyDeviceId == device.address
                val nearbyFresh = lastValidatedAt > 0L &&
                    now - lastValidatedAt <= NEARBY_FRESHNESS_MS &&
                    (lastValidatedModel == AirPodsModel.UNKNOWN ||
                        modelFromName(names.joinToString(" ")).isCompatibleWith(lastValidatedModel))
                val paired = bondedDevices.any { it.address == device.address }
                val connectionState = resolveAirPodsConnectionState(
                    BluetoothConnectionEvidence(
                        a2dpConnected = a2dpConnected,
                        headsetConnected = headsetConnected,
                        aapReady = aapReady,
                        nearbyFresh = nearbyFresh,
                        paired = paired,
                        knownBluetoothDevice = knownDevices.containsKey(device.address),
                    ),
                )
                BluetoothAirPodsEvent(
                    deviceId = device.address,
                    deviceName = alias.ifBlank { remoteName },
                    model = modelFromName(names.joinToString(" ")),
                    connected = connectionState == AirPodsConnectionState.ANDROID_CONNECTED,
                    seenAt = now,
                    connectionState = connectionState,
                    a2dpConnected = a2dpConnected,
                    headsetConnected = headsetConnected,
                    aapReady = aapReady,
                    nearbySeenAt = lastValidatedAt.takeIf { nearbyFresh },
                )
            }
            .sortedWith(
                compareByDescending<BluetoothAirPodsEvent> {
                    it.connectionState == AirPodsConnectionState.ANDROID_CONNECTED
                }.thenByDescending {
                    it.connectionState == AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING
                }.thenBy { it.deviceId },
            )

        val current = candidates.firstOrNull() ?: lastValidatedDeviceId?.let { nearbyDeviceId ->
            if (lastValidatedAt > 0L &&
                System.currentTimeMillis() - lastValidatedAt <= NEARBY_FRESHNESS_MS
            ) {
                BluetoothAirPodsEvent(
                    deviceId = nearbyDeviceId,
                    deviceName = "AirPods",
                    model = lastValidatedModel,
                    connected = false,
                    seenAt = System.currentTimeMillis(),
                    connectionState = AirPodsConnectionState.NEARBY_ONLY,
                    nearbySeenAt = lastValidatedAt,
                )
            } else {
                null
            }
        }
        val previous = lastBluetoothEvent
        if (current == null) {
            stopAapSession()
            publishDisconnectedEvent()
            _status.value = "AirPods 연결 대기"
            return
        }
        lastBluetoothEvent = current

        _status.value = when (current.connectionState) {
            AirPodsConnectionState.ANDROID_CONNECTED -> "AirPods Galaxy 연결됨"
            AirPodsConnectionState.NEARBY_ONLY -> "AirPods 주변 감지됨"
            AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING -> "AirPods 다른 기기/연결 대기"
            AirPodsConnectionState.DISCONNECTED -> "AirPods 연결 끊김"
            AirPodsConnectionState.UNKNOWN -> "AirPods 연결 상태 확인 중"
        }

        val changed = previous == null ||
            previous.deviceId != current.deviceId ||
            previous.connectionState != current.connectionState ||
            previous.a2dpConnected != current.a2dpConnected ||
            previous.headsetConnected != current.headsetConnected ||
            previous.aapReady != current.aapReady ||
            previous.model != current.model ||
            current.seenAt - previous.seenAt >= EVENT_REFRESH_MS
        if (changed) {
            BleScanDiagnostics.logBluetoothEvent(current)
            _bluetoothEvents.tryEmit(current)
        }
        if (current.a2dpConnected || current.headsetConnected || current.aapReady) {
            startAapSession(current)
        } else if (aapSessionDeviceId == current.deviceId) {
            stopAapSession()
        }
    }

    private fun startAapSession(event: BluetoothAirPodsEvent) {
        if (aapSessionJob?.isActive == true && aapSessionDeviceId == event.deviceId) return
        stopAapSession()
        val bluetoothAdapter = adapter ?: return
        val device = runCatching { bluetoothAdapter.getRemoteDevice(event.deviceId) }.getOrNull() ?: return
        aapSessionDeviceId = event.deviceId
        aapWearStabilizer.reset()
        aapSessionJob = monitorScope.launch {
            // Keep one bounded session alive while the classic profile remains
            // connected. If Samsung rejects the public socket or the AirPods
            // close it, retry slowly without flooding the accessory.
            while (isActive && aapSessionDeviceId == event.deviceId) {
                aapBatteryClient.runSession(
                    device = device,
                    deviceId = event.deviceId,
                    model = event.model,
                    onBattery = { snapshot ->
                    _classicBatteryEvents.emit(
                        ClassicAapBatteryEvent(
                            deviceId = event.deviceId,
                            model = event.model,
                            snapshot = snapshot,
                            seenAt = System.currentTimeMillis(),
                        ),
                    )
                    },
                    onWear = { snapshot ->
                    val seenAt = System.currentTimeMillis()
                    val primaryPodIsLeft = lastPrimaryPodIsLeft
                    val stableWearState = aapWearStabilizer.accept(
                        snapshot = snapshot,
                        primaryPodIsLeft = primaryPodIsLeft,
                        seenAt = seenAt,
                    )
                    _classicWearEvents.emit(
                        ClassicAapWearEvent(
                            deviceId = event.deviceId,
                            model = event.model,
                            primaryPodIsLeft = primaryPodIsLeft,
                            snapshot = snapshot,
                            wearState = stableWearState,
                            seenAt = seenAt,
                        ),
                    )
                    },
                    onReady = {
                        aapReadyDeviceId = event.deviceId
                        refreshBluetoothConnection()
                    },
                    onClosed = {
                        if (aapReadyDeviceId == event.deviceId) {
                            aapReadyDeviceId = null
                            refreshBluetoothConnection()
                        }
                    },
                )
                if (isActive) delay(AAP_RETRY_DELAY_MS)
            }
        }
    }

    private fun stopAapSession() {
        aapSessionDeviceId = null
        aapReadyDeviceId = null
        aapWearStabilizer.reset()
        aapBatteryClient.close()
        aapSessionJob?.cancel()
        aapSessionJob = null
    }

    private fun publishDisconnectedEvent() {
        val previous = lastBluetoothEvent ?: return
        if (previous.connectionState == AirPodsConnectionState.DISCONNECTED) return
        val disconnected = previous.copy(
            connected = false,
            seenAt = System.currentTimeMillis(),
            connectionState = AirPodsConnectionState.DISCONNECTED,
            a2dpConnected = false,
            headsetConnected = false,
            aapReady = false,
            nearbySeenAt = null,
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
        private val AIRPODS_SCAN_FILTERS = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    AppleAirPodsParser.APPLE_COMPANY_ID,
                    byteArrayOf(0x07),
                    byteArrayOf(0xFF.toByte()),
                )
                .build(),
        )
        private const val PENDING_SCAN_REQUEST_CODE = 1002
        private const val DIRECT_FILTER_FALLBACK_DELAY_MS = 6_000L
        private const val LEGACY_SCAN_FALLBACK_DELAY_MS = 12_000L
        private const val CONNECTION_POLL_MS = 1_500L
        private const val EVENT_REFRESH_MS = 30_000L
        private const val NEARBY_FRESHNESS_MS = 30_000L
        private const val AAP_RETRY_DELAY_MS = 30_000L
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

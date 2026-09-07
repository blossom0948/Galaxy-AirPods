package com.galaxyairpods.data.bluetooth

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

data class BleDiagnosticRecord(
    val timestamp: Long,
    val name: String,
    val address: String,
    val rssi: Int,
    val manufacturerId: Int?,
    val manufacturerHex: String,
    val serviceUuids: List<String>,
    val rawAdvertisingHex: String,
    val parserName: String,
    val parserResult: String,
) {
    fun toDiagnosticJson(): String = buildString {
        append("{")
        append("\"timestamp\":").append(timestamp).append(",")
        append("\"name\":\"").append(name.jsonEscape()).append("\",")
        append("\"address\":\"").append(address.jsonEscape()).append("\",")
        append("\"rssi\":").append(rssi).append(",")
        append("\"manufacturerId\":").append(manufacturerId ?: "null").append(",")
        append("\"manufacturerHex\":\"").append(manufacturerHex).append("\",")
        append("\"serviceUuids\":[")
        append(serviceUuids.joinToString(",") { "\"${it.jsonEscape()}\"" })
        append("],")
        append("\"rawAdvertisingHex\":\"").append(rawAdvertisingHex).append("\",")
        append("\"parserName\":\"").append(parserName.jsonEscape()).append("\",")
        append("\"parserResult\":\"").append(parserResult.jsonEscape()).append("\"")
        append("}")
    }
}

data class ValidatedPacketEvent(
    val deviceId: String,
    val packet: ParsedAirPodsPacket,
    val seenAt: Long,
)

class AirPodsBleScanner(private val context: Context) {
    private val _records = MutableStateFlow<List<BleDiagnosticRecord>>(emptyList())
    val records: StateFlow<List<BleDiagnosticRecord>> = _records.asStateFlow()

    private val _status = MutableStateFlow("스캔 대기")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _validatedPackets = MutableSharedFlow<ValidatedPacketEvent>(extraBufferCapacity = 16)
    val validatedPackets: SharedFlow<ValidatedPacketEvent> = _validatedPackets.asSharedFlow()

    private val parserRegistry = AirPodsParserRegistry()
    private val adapter: BluetoothAdapter? by lazy {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            append(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::append)
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = "스캔 실패: error $errorCode"
        }
    }

    fun start(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY) {
        if (!hasScanPermission()) {
            _status.value = "Bluetooth/Nearby devices 권한이 필요합니다"
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            _status.value = "이 기기는 Bluetooth LE를 지원하지 않습니다"
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _status.value = "Bluetooth가 꺼져 있습니다"
            return
        }
        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(scanMode)
                    .build(),
                callback,
            )
            _status.value = "스캔 중 · raw packet 기록 중"
        } catch (_: SecurityException) {
            _status.value = "Bluetooth scan 권한이 없어 시작하지 못했습니다"
        }
    }

    fun stop() {
        if (!hasScanPermission()) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
            _status.value = "스캔 중지"
        } catch (_: SecurityException) {
            _status.value = "권한이 없어 스캔을 중지하지 못했습니다"
        }
    }

    fun clear() {
        _records.value = emptyList()
        _status.value = "기록 삭제됨"
    }

    fun maskedJson(): String = _records.value.joinToString(prefix = "[", postfix = "]") {
        it.toDiagnosticJson()
    }

    private fun append(result: ScanResult) {
        val record = result.toDiagnosticRecord(parserRegistry.match(result))
        _records.value = (_records.value + record).takeLast(MAX_RECORDS)
        _status.value = "${_records.value.size}개 packet 기록됨"
        parserRegistry.parse(result)?.let { parsed ->
            _validatedPackets.tryEmit(
                ValidatedPacketEvent(
                    deviceId = runCatching { result.device.address }.getOrDefault("unknown"),
                    packet = parsed,
                    seenAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun hasScanPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    companion object {
        private const val MAX_RECORDS = 250
    }
}

@SuppressLint("MissingPermission")
private fun ScanResult.toDiagnosticRecord(parserMatch: ParserMatch): BleDiagnosticRecord {
    val scanRecord = scanRecord
    val manufacturers = scanRecord?.manufacturerSpecificData
    val firstManufacturerIndex = manufacturers?.let { if (it.size() > 0) 0 else null }
    val manufacturerId = firstManufacturerIndex?.let { manufacturers.keyAt(it) }
    val manufacturerBytes = firstManufacturerIndex?.let { manufacturers.valueAt(it) }
    val serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty()
    val raw = scanRecord?.bytes?.toHex().orEmpty()
    return BleDiagnosticRecord(
        timestamp = System.currentTimeMillis(),
        name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Unnamed device" },
        address = device.address.maskAddress(),
        rssi = rssi,
        manufacturerId = manufacturerId,
        manufacturerHex = manufacturerBytes?.toHex().orEmpty(),
        serviceUuids = serviceUuids,
        rawAdvertisingHex = raw,
        parserName = parserMatch.parserName,
        parserResult = parserMatch.resultLabel,
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }

private fun String.maskAddress(): String =
    if (count { it == ':' } == 5) {
        split(":").take(3).joinToString(":") + ":**:**:**"
    } else {
        "masked"
    }

private fun String.jsonEscape(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

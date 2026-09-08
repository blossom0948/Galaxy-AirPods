package com.galaxyairpods.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.util.Log
import com.galaxyairpods.BuildConfig
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import java.security.MessageDigest
import java.lang.reflect.Constructor

/**
 * Debug-only, redacted evidence logger for the V4 Samsung gates.
 *
 * It records delivery metadata and digests, never a full Bluetooth address or
 * raw manufacturer/key payload. Release builds do not emit these records.
 */
internal object BleScanDiagnostics {
    private const val TAG = "AirPodsV4"
    private const val MANUFACTURER_AD_TYPE = 0xFF

    fun logScannerStarted(adapter: BluetoothAdapter, scanMode: Int, filtered: Boolean) {
        if (!BuildConfig.DEBUG) return
        val filtering = runCatching { adapter.isOffloadedFilteringSupported }.getOrNull()
        val batching = runCatching { adapter.isOffloadedScanBatchingSupported }.getOrNull()
        Log.i(
            TAG,
            "scan_start mode=$scanMode filtered=$filtered " +
                "offloadFiltering=$filtering offloadBatching=$batching reportDelay=0",
        )
    }

    fun logScanFailed(errorCode: Int) {
        if (BuildConfig.DEBUG) Log.e(TAG, "scan_failed errorCode=$errorCode")
    }

    fun logBluetoothEvent(event: BluetoothAirPodsEvent) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
                "classic_event deviceHash=${digest(event.deviceId.toByteArray())} " +
                "nameHash=${digest(event.deviceName.toByteArray())} " +
                "model=${event.model.name} connected=${event.connected} " +
                "connectionState=${event.connectionState.name} " +
                "a2dp=${event.a2dpConnected} headset=${event.headsetConnected} " +
                "aapReady=${event.aapReady} nearby=${event.nearbySeenAt != null} " +
                "seenAt=${event.seenAt}",
        )
    }

    fun logScanResult(result: ScanResult, delivery: String, parsed: ParsedAirPodsPacket?) {
        if (!BuildConfig.DEBUG) return

        val record = result.scanRecord
        val recordBytes = record?.bytes ?: byteArrayOf()
        val manufacturerMap = record?.manufacturerSpecificData
        val mapCompanyIds = buildList {
            if (manufacturerMap != null) {
                for (index in 0 until manufacturerMap.size()) {
                    add(manufacturerMap.keyAt(index).toString(16).padStart(4, '0'))
                }
            }
        }
        val appleData = manufacturerMap?.get(AppleAirPodsParser.APPLE_COMPANY_ID)
            ?: extractAppleManufacturerData(recordBytes)
        val fields = inspectAppleData(appleData)
        val companyIds = if (fields != null && mapCompanyIds.none { it.equals("004c", true) }) {
            (mapCompanyIds + "004c(raw)").joinToString(",")
        } else {
            mapCompanyIds.joinToString(",")
        }

        Log.d(
            TAG,
            "scan delivery=$delivery addressHash=${digest(result.device.address.toByteArray())} " +
                "rssi=${result.rssi} timestampNanos=${result.timestampNanos} " +
                "recordLength=${recordBytes.size} recordDigest=${digest(recordBytes)} " +
                "companyIds=${companyIds.ifBlank { "none" }} " +
                "appleLength=${appleData?.size ?: -1} type=${fields?.type ?: -1} " +
                "valueLength=${fields?.valueLength ?: -1} prefix=${fields?.prefix ?: -1} " +
                "parser=${if (parsed == null) "REJECTED" else "VALID"} " +
                "model=${parsed?.model?.name ?: "none"} " +
                "battery=${parsed?.leftBattery ?: "?"}/${parsed?.rightBattery ?: "?"}/" +
                "${parsed?.caseBattery ?: "?"} caseOpen=${parsed?.caseOpen ?: "?"}",
        )
    }

    fun logAapState(deviceId: String, state: String, detail: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "aap_state deviceHash=${digest(deviceId.toByteArray())} state=$state" +
                detail.takeIf { it.isNotBlank() }?.let { " detail=$it" }.orEmpty(),
        )
    }

    fun logAapFailure(deviceId: String, stage: String, reason: String) {
        if (!BuildConfig.DEBUG) return
        Log.w(
            TAG,
            "aap_failure deviceHash=${digest(deviceId.toByteArray())} stage=$stage reason=$reason",
        )
    }

    fun logAapFailureCause(deviceId: String, stage: String, cause: Throwable) {
        if (!BuildConfig.DEBUG) return
        val message = cause.message.orEmpty()
            .replace(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"), "<redacted>")
            .take(96)
        Log.w(
            TAG,
            "aap_failure_cause deviceHash=${digest(deviceId.toByteArray())} " +
                "stage=$stage cause=${cause::class.simpleName ?: "EXCEPTION"} " +
                "message=${message.ifBlank { "<empty>" }}",
        )
    }

    fun logAapTx(deviceId: String, kind: String, bytes: ByteArray) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "aap_tx deviceHash=${digest(deviceId.toByteArray())} kind=$kind " +
                "length=${bytes.size} digest=${digest(bytes)}",
        )
    }

    /**
     * Records only the shape of an AAP read record.  The payload is never
     * logged: a key response can arrive on this channel and must remain
     * private even in a debug build.
     */
    fun logAapReadRecord(
        deviceId: String,
        bytes: ByteArray,
        emittedFrames: Int,
        malformedRecords: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        val packetType = littleEndian16(bytes, 0)
        val service = littleEndian16(bytes, 2)
        val command = if (packetType == AapBatteryProtocol.MESSAGE_PACKET_TYPE && bytes.size >= 6) {
            littleEndian16(bytes, 4)
        } else {
            -1
        }
        Log.d(
            TAG,
            "aap_read deviceHash=${digest(deviceId.toByteArray())} length=${bytes.size} " +
                "digest=${digest(bytes)} packetType=0x${packetType.toString(16).padStart(4, '0')} " +
                "service=0x${service.toString(16).padStart(4, '0')} " +
                "command=${if (command < 0) "none" else "0x${command.toString(16).padStart(4, '0')}"} " +
                "emittedFrames=$emittedFrames malformedRecords=$malformedRecords",
        )
    }

    fun logAapRxConnectResponse(deviceId: String, frame: AapFrame.ConnectResponse) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "aap_rx deviceHash=${digest(deviceId.toByteArray())} type=CONNECT_RESPONSE " +
                "length=${frame.length} service=0x${frame.service.toString(16).padStart(4, '0')} " +
                "status=0x${frame.status.toString(16).padStart(4, '0')}",
        )
    }

    fun logAapRxMessage(deviceId: String, frame: AapFrame.Message) {
        if (!BuildConfig.DEBUG) return
        val componentCount = if (frame.command == AapBatteryProtocol.BATTERY_COMMAND) {
            frame.payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        } else {
            -1
        }
        Log.d(
            TAG,
            "aap_rx deviceHash=${digest(deviceId.toByteArray())} type=MESSAGE " +
                "command=0x${frame.command.toString(16).padStart(4, '0')} " +
                "length=${frame.length} componentCount=$componentCount",
        )
    }

    fun logAapBattery(deviceId: String, snapshot: AapBatterySnapshot) {
        if (!BuildConfig.DEBUG) return
        val components = buildList {
            if (snapshot.left != null) add("LEFT")
            if (snapshot.right != null) add("RIGHT")
            if (snapshot.case != null) add("CASE")
        }
        Log.i(
            TAG,
            "aap_battery deviceHash=${digest(deviceId.toByteArray())} " +
                "components=${components.joinToString(",")} valuesPresent=${snapshot.valuesPresent}",
        )
    }

    fun logAapEarDetection(deviceId: String, snapshot: AapEarDetectionSnapshot) {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "aap_ear deviceHash=${digest(deviceId.toByteArray())} " +
                "primary=${snapshot.primary.name} secondary=${snapshot.secondary.name}",
        )
    }

    fun logAapSocketConstructors(constructors: Array<Constructor<*>>) {
        if (!BuildConfig.DEBUG) return
        val signatures = constructors.joinToString(";") { constructor ->
            constructor.parameterTypes.joinToString(",") { it.name.substringAfterLast('.') }
        }
        Log.i(TAG, "aap_transport_constructors count=${constructors.size} signatures=$signatures")
    }

    private data class AppleFields(
        val type: Int,
        val valueLength: Int,
        val prefix: Int,
    )

    private fun inspectAppleData(data: ByteArray?): AppleFields? {
        if (data == null || data.isEmpty()) return null
        var offset = 0
        if (data.size >= 2 &&
            ((data[0].u8() == 0x4C && data[1].u8() == 0x00) ||
                (data[0].u8() == 0x00 && data[1].u8() == 0x4C))
        ) {
            offset = 2
        }
        if (offset + 1 >= data.size) return null
        return AppleFields(
            type = data[offset].u8(),
            valueLength = data[offset + 1].u8(),
            prefix = data.getOrNull(offset + 2)?.u8() ?: -1,
        )
    }

    private fun extractAppleManufacturerData(scanRecord: ByteArray): ByteArray? {
        var offset = 0
        while (offset < scanRecord.size) {
            val fieldLength = scanRecord[offset].u8()
            if (fieldLength == 0) break
            val fieldStart = offset + 1
            val fieldEnd = fieldStart + fieldLength
            if (fieldEnd > scanRecord.size) break
            if (scanRecord[fieldStart].u8() == MANUFACTURER_AD_TYPE && fieldLength >= 3) {
                val companyId = scanRecord[fieldStart + 1].u8() or
                    (scanRecord[fieldStart + 2].u8() shl 8)
                if (companyId == AppleAirPodsParser.APPLE_COMPANY_ID) {
                    return scanRecord.copyOfRange(fieldStart + 3, fieldEnd)
                }
            }
            offset = fieldEnd
        }
        return null
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun littleEndian16(bytes: ByteArray, offset: Int): Int {
        if (bytes.size < offset + 2) return -1
        return bytes[offset].u8() or (bytes[offset + 1].u8() shl 8)
    }
}

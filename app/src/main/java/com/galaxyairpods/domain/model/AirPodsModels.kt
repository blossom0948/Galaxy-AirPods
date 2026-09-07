package com.galaxyairpods.domain.model

import java.util.Locale

enum class AirPodsModel(val label: String) {
    AIRPODS_GEN1("AirPods (1세대)"),
    AIRPODS_GEN2("AirPods (2세대)"),
    AIRPODS_GEN3("AirPods (3세대)"),
    AIRPODS_GEN4("AirPods (4세대)"),
    AIRPODS_GEN4_ANC("AirPods (4세대 ANC)"),
    AIRPODS_PRO("AirPods Pro (1세대)"),
    AIRPODS_PRO2("AirPods Pro (2세대)"),
    AIRPODS_PRO2_USBC("AirPods Pro (2세대 USB-C)"),
    AIRPODS_PRO3("AirPods Pro (3세대)"),
    AIRPODS_MAX("AirPods Max"),
    AIRPODS_MAX_USBC("AirPods Max (USB-C)"),
    AIRPODS_MAX2("AirPods Max (2세대)"),
    AIRPODS("AirPods"),
    UNKNOWN("AirPods");

    companion object {
        fun fromBluetoothName(name: String): AirPodsModel {
            val normalized = name.lowercase(Locale.US).replace("-", " ")
            return when {
                normalized.contains("max") && normalized.contains("2") -> AIRPODS_MAX2
                normalized.contains("max") && (normalized.contains("usb") || normalized.contains("usbc")) ->
                    AIRPODS_MAX_USBC
                normalized.contains("max") -> AIRPODS_MAX
                normalized.contains("pro") && normalized.contains("3") -> AIRPODS_PRO3
                normalized.contains("pro") && normalized.contains("2") &&
                    (normalized.contains("usb") || normalized.contains("usbc")) -> AIRPODS_PRO2_USBC
                normalized.contains("pro") && normalized.contains("2") -> AIRPODS_PRO2
                normalized.contains("pro") -> AIRPODS_PRO
                normalized.contains("4") && normalized.contains("anc") -> AIRPODS_GEN4_ANC
                normalized.contains("4") -> AIRPODS_GEN4
                normalized.contains("3") -> AIRPODS_GEN3
                normalized.contains("2") -> AIRPODS_GEN2
                normalized.contains("1") -> AIRPODS_GEN1
                else -> AIRPODS
            }
        }
    }
}

val AirPodsModel.isMax: Boolean
    get() = this == AirPodsModel.AIRPODS_MAX ||
        this == AirPodsModel.AIRPODS_MAX_USBC ||
        this == AirPodsModel.AIRPODS_MAX2

val AirPodsModel.isPro: Boolean
    get() = this == AirPodsModel.AIRPODS_PRO ||
        this == AirPodsModel.AIRPODS_PRO2 ||
        this == AirPodsModel.AIRPODS_PRO2_USBC ||
        this == AirPodsModel.AIRPODS_PRO3

enum class DataConfidence(val label: String) {
    LIVE("실시간"),
    RECENT("최근 확인"),
    STALE("오래됨"),
    UNKNOWN("확인 불가"),
}

/**
 * `null` battery means "not currently known". It must never be rendered as 0%.
 */
data class AirPodsState(
    val deviceId: String? = null,
    val model: AirPodsModel = AirPodsModel.UNKNOWN,
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val caseBattery: Int? = null,
    val leftCharging: Boolean? = null,
    val rightCharging: Boolean? = null,
    val caseCharging: Boolean? = null,
    val leftInCase: Boolean? = null,
    val rightInCase: Boolean? = null,
    val caseOpen: Boolean? = null,
    val connected: Boolean = false,
    val detected: Boolean = false,
    val deviceName: String? = null,
    val lastSeenAt: Long? = null,
    val confidence: DataConfidence = DataConfidence.UNKNOWN,
) {
    val hasAnyBattery: Boolean
        get() = leftBattery != null || rightBattery != null || caseBattery != null

    val hasLiveData: Boolean
        get() = confidence == DataConfidence.LIVE

    val connectionLabel: String
        get() = when {
            connected -> "연결됨"
            detected -> "페어링됨 · 연결 대기"
            else -> "연결 대기"
        }

    fun batteryFor(slot: BatterySlot): Int? = when (slot) {
        BatterySlot.LEFT -> leftBattery
        BatterySlot.RIGHT -> rightBattery
        BatterySlot.CASE -> caseBattery
    }

    fun chargingFor(slot: BatterySlot): Boolean? = when (slot) {
        BatterySlot.LEFT -> leftCharging
        BatterySlot.RIGHT -> rightCharging
        BatterySlot.CASE -> caseCharging
    }

    fun withResolvedConfidence(
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = 15 * 60 * 1000L,
    ): AirPodsState {
        val seen = lastSeenAt ?: return copy(confidence = DataConfidence.UNKNOWN)
        val resolved = when {
            confidence == DataConfidence.UNKNOWN -> DataConfidence.UNKNOWN
            now - seen > staleAfterMs -> DataConfidence.STALE
            else -> confidence
        }
        val isFresh = now - seen <= staleAfterMs
        return copy(
            confidence = resolved,
            connected = if (isFresh) connected else false,
            detected = if (isFresh) detected else false,
        )
    }

    companion object {
        fun empty() = AirPodsState()
    }
}

enum class BatterySlot(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
    CASE("Case"),
}

data class ParsedAirPodsPacket(
    val model: AirPodsModel,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val leftCharging: Boolean?,
    val rightCharging: Boolean?,
    val caseCharging: Boolean?,
    val leftInCase: Boolean?,
    val rightInCase: Boolean?,
    val caseOpen: Boolean?,
    val parserVersion: String,
    val confidence: DataConfidence,
)

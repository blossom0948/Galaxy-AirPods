package com.galaxyairpods.domain.model

enum class AirPodsModel(val label: String) {
    AIRPODS_PRO("AirPods Pro"),
    AIRPODS("AirPods"),
    AIRPODS_MAX("AirPods Max"),
    UNKNOWN("Unknown AirPods"),
}

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
            detected -> "감지됨"
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
        return copy(confidence = resolved)
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

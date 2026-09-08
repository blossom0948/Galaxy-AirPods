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

/**
 * BLE advertisements and classic Bluetooth profiles do not expose the same
 * address or always use the same product name. Treat a generic name, or two
 * generations in the same product family, as the same logical AirPods set so
 * a profile update cannot erase a battery packet received from BLE.
 */
fun AirPodsModel.isCompatibleWith(other: AirPodsModel): Boolean = when {
    this == other -> true
    this == AirPodsModel.AIRPODS || this == AirPodsModel.UNKNOWN -> true
    other == AirPodsModel.AIRPODS || other == AirPodsModel.UNKNOWN -> true
    this.isPro && other.isPro -> true
    this.isMax && other.isMax -> true
    else -> false
}

enum class DataConfidence(val label: String) {
    LIVE("실시간"),
    RECENT("최근 확인"),
    STALE("오래됨"),
    UNKNOWN("확인 불가"),
}

/**
 * Connection is deliberately independent from BLE proximity and persisted
 * telemetry. A public AirPods advertisement can be visible while the set is
 * connected to an iPad, or while it is only waiting for an Android profile.
 */
enum class AirPodsConnectionState(val label: String) {
    ANDROID_CONNECTED("Galaxy에 연결됨"),
    NEARBY_ONLY("주변에서 감지됨"),
    OTHER_DEVICE_OR_CONNECTION_PENDING("다른 기기에 연결되었거나 연결 대기 중"),
    DISCONNECTED("연결 끊김"),
    UNKNOWN("상태 확인 중"),
}

enum class ChargingState {
    CHARGING,
    NOT_CHARGING,
    UNKNOWN,
}

/**
 * Charging is evidence with a shorter lifetime than the battery percentage.
 * In particular, an old `true` value must never survive a process restart and
 * appear as a current charging indicator.
 */
data class ChargingEvidence(
    val state: ChargingState = ChargingState.UNKNOWN,
    val source: String? = null,
    val capturedAt: Long? = null,
    val expiresAt: Long? = null,
    val proof: String? = null,
) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        state != ChargingState.UNKNOWN &&
            source != null &&
            capturedAt != null &&
            expiresAt != null &&
            proof != null &&
            now >= capturedAt &&
            now <= expiresAt

    fun resolved(now: Long = System.currentTimeMillis()): ChargingEvidence =
        if (isFresh(now)) this else ChargingEvidence()
}

enum class AirPodsWearState {
    LEFT_IN_EAR,
    RIGHT_IN_EAR,
    BOTH_IN_EAR,
    NONE_IN_EAR,
    IN_CASE,
    UNKNOWN,
    CONFLICT,
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
    val connectionState: AirPodsConnectionState = when {
        connected -> AirPodsConnectionState.ANDROID_CONNECTED
        detected -> AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING
        else -> AirPodsConnectionState.UNKNOWN
    },
    val deviceName: String? = null,
    val lastSeenAt: Long? = null,
    val confidence: DataConfidence = DataConfidence.UNKNOWN,
    val batterySource: String? = null,
    val batteryCapturedAt: Long? = null,
    val primaryPodIsLeft: Boolean? = null,
    val leftChargingEvidence: ChargingEvidence = ChargingEvidence(),
    val rightChargingEvidence: ChargingEvidence = ChargingEvidence(),
    val caseChargingEvidence: ChargingEvidence = ChargingEvidence(),
    val wearState: AirPodsWearState = AirPodsWearState.UNKNOWN,
    val wearSource: String? = null,
    val wearCapturedAt: Long? = null,
    val wearExpiresAt: Long? = null,
) {
    val hasAnyBattery: Boolean
        get() = leftBattery != null || rightBattery != null || caseBattery != null

    val hasLiveData: Boolean
        get() = confidence == DataConfidence.LIVE

    val isAndroidConnected: Boolean
        get() = connectionState == AirPodsConnectionState.ANDROID_CONNECTED

    val connectionLabel: String
        get() = connectionState.label

    fun batteryFor(slot: BatterySlot): Int? = when (slot) {
        BatterySlot.LEFT -> leftBattery
        BatterySlot.RIGHT -> rightBattery
        BatterySlot.CASE -> caseBattery
    }

    fun chargingEvidenceFor(slot: BatterySlot): ChargingEvidence = when (slot) {
        BatterySlot.LEFT -> leftChargingEvidence
        BatterySlot.RIGHT -> rightChargingEvidence
        BatterySlot.CASE -> caseChargingEvidence
    }

    fun chargingFor(
        slot: BatterySlot,
        now: Long = System.currentTimeMillis(),
    ): Boolean? = when (chargingEvidenceFor(slot).resolved(now).state) {
        ChargingState.CHARGING -> true
        ChargingState.NOT_CHARGING -> false
        ChargingState.UNKNOWN -> null
    }

    fun chargingStatusUnknownFor(
        slot: BatterySlot,
        now: Long = System.currentTimeMillis(),
    ): Boolean = chargingEvidenceFor(slot).state != ChargingState.UNKNOWN &&
        !chargingEvidenceFor(slot).isFresh(now)

    fun withResolvedConfidence(
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = 15 * 60 * 1000L,
    ): AirPodsState {
        // A profile poll updates lastSeenAt, but it is not a battery sample.
        // Once a battery exists, only its own capture timestamp can make the
        // battery confidence LIVE/RECENT/STALE.
        val seen = when {
            batteryCapturedAt != null -> batteryCapturedAt
            hasAnyBattery -> return copy(confidence = DataConfidence.UNKNOWN)
                .withFreshCharging(now)
                .withFreshWear(now)
            else -> lastSeenAt
        } ?: return copy(confidence = DataConfidence.UNKNOWN).withFreshCharging(now)
        val resolved = when {
            confidence == DataConfidence.UNKNOWN -> DataConfidence.UNKNOWN
            now - seen > staleAfterMs -> DataConfidence.STALE
            else -> confidence
        }
        return copy(
            confidence = resolved,
        ).withFreshCharging(now).withFreshWear(now)
    }

    fun withFreshCharging(now: Long = System.currentTimeMillis()): AirPodsState {
        fun normalize(raw: Boolean?, evidence: ChargingEvidence): Pair<Boolean?, ChargingEvidence> {
            val resolved = evidence.resolved(now)
            return if (resolved.state == ChargingState.UNKNOWN) {
                null to ChargingEvidence()
            } else {
                (resolved.state == ChargingState.CHARGING) to resolved
            }
        }

        val (left, leftEvidence) = normalize(leftCharging, leftChargingEvidence)
        val (right, rightEvidence) = normalize(rightCharging, rightChargingEvidence)
        val (caseValue, caseEvidence) = normalize(caseCharging, caseChargingEvidence)
        return copy(
            leftCharging = left,
            rightCharging = right,
            caseCharging = caseValue,
            leftChargingEvidence = leftEvidence,
            rightChargingEvidence = rightEvidence,
            caseChargingEvidence = caseEvidence,
        )
    }

    fun withFreshWear(now: Long = System.currentTimeMillis()): AirPodsState =
        if (wearState == AirPodsWearState.UNKNOWN ||
            (wearCapturedAt != null && wearExpiresAt != null && now in wearCapturedAt..wearExpiresAt)
        ) {
            this
        } else {
            copy(
                wearState = AirPodsWearState.UNKNOWN,
                wearSource = null,
                wearCapturedAt = null,
                wearExpiresAt = null,
            )
        }

    /** Hide wear telemetry immediately when the user disables the feature. */
    fun withoutWearDetection(): AirPodsState = copy(
        wearState = AirPodsWearState.UNKNOWN,
        wearSource = null,
        wearCapturedAt = null,
        wearExpiresAt = null,
    )

    companion object {
        fun empty() = AirPodsState()
    }
}

enum class BatterySlot(val label: String) {
    LEFT("왼쪽"),
    RIGHT("오른쪽"),
    CASE("케이스"),
}

/** Keeps persisted fallback battery values visible while a live profile event
 * is still being merged in. Live non-null fields always win. */
fun AirPodsState.mergeKnownValuesFrom(fallback: AirPodsState?): AirPodsState {
    if (fallback == null) return this
    val sameDevice = deviceId == null || fallback.deviceId == null ||
        deviceId == fallback.deviceId || model.isCompatibleWith(fallback.model)
    if (!sameDevice) return this

    return copy(
        model = when {
            model == AirPodsModel.UNKNOWN || model == AirPodsModel.AIRPODS -> fallback.model
            else -> model
        },
        leftBattery = leftBattery ?: fallback.leftBattery,
        rightBattery = rightBattery ?: fallback.rightBattery,
        caseBattery = caseBattery ?: fallback.caseBattery,
        leftCharging = leftCharging ?: fallback.leftCharging,
        rightCharging = rightCharging ?: fallback.rightCharging,
        caseCharging = caseCharging ?: fallback.caseCharging,
        leftInCase = leftInCase ?: fallback.leftInCase,
        rightInCase = rightInCase ?: fallback.rightInCase,
        caseOpen = caseOpen ?: fallback.caseOpen,
        detected = detected || fallback.detected,
        deviceName = deviceName ?: fallback.deviceName,
        lastSeenAt = maxOf(lastSeenAt ?: 0L, fallback.lastSeenAt ?: 0L).takeIf { it > 0L },
        batterySource = batterySource ?: fallback.batterySource,
        batteryCapturedAt = maxOf(batteryCapturedAt ?: 0L, fallback.batteryCapturedAt ?: 0L)
            .takeIf { it > 0L },
        primaryPodIsLeft = primaryPodIsLeft ?: fallback.primaryPodIsLeft,
        leftChargingEvidence = leftChargingEvidence.takeIf { it.state != ChargingState.UNKNOWN }
            ?: fallback.leftChargingEvidence,
        rightChargingEvidence = rightChargingEvidence.takeIf { it.state != ChargingState.UNKNOWN }
            ?: fallback.rightChargingEvidence,
        caseChargingEvidence = caseChargingEvidence.takeIf { it.state != ChargingState.UNKNOWN }
            ?: fallback.caseChargingEvidence,
        wearState = if (wearState == AirPodsWearState.UNKNOWN) fallback.wearState else wearState,
        wearSource = wearSource ?: fallback.wearSource,
        wearCapturedAt = wearCapturedAt ?: fallback.wearCapturedAt,
        wearExpiresAt = wearExpiresAt ?: fallback.wearExpiresAt,
    )
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
    /** Public BLE status bit used to map AAP primary/secondary to physical L/R. */
    val primaryPodIsLeft: Boolean? = null,
    /** Candidate wear state; the scanner publishes it only after stabilization. */
    val wearState: AirPodsWearState? = null,
)

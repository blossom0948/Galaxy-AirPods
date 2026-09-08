package com.galaxyairpods.data

import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.data.bluetooth.ClassicAapBatteryEvent
import com.galaxyairpods.data.bluetooth.ClassicAapWearEvent
import com.galaxyairpods.data.bluetooth.BluetoothAirPodsEvent
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsConnectionState
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.AirPodsWearState
import com.galaxyairpods.domain.model.ChargingEvidence
import com.galaxyairpods.domain.model.ChargingState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import com.galaxyairpods.domain.model.isCompatibleWith
import com.galaxyairpods.domain.model.isMax
import com.galaxyairpods.domain.repository.AirPodsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Production repository path used once a validated parser returns a packet. */
class BleAirPodsRepository(
    private val dataStore: AirPodsDataStore,
) : AirPodsRepository {
    private val _state = MutableStateFlow(AirPodsState.empty())
    override val state: StateFlow<AirPodsState> = _state.asStateFlow()

    override suspend fun applyParsedPacket(
        deviceId: String,
        packet: ParsedAirPodsPacket,
        seenAt: Long,
        wearDetectionEnabled: Boolean,
        deviceProfileId: String?,
        capturedAtElapsedMs: Long?,
    ) {
        val newState = mergeParsedState(
            current = _state.value,
            deviceId = deviceId,
            deviceProfileId = deviceProfileId,
            packet = packet,
            seenAt = seenAt,
            wearDetectionEnabled = wearDetectionEnabled,
            capturedAtElapsedMs = capturedAtElapsedMs,
        )
        _state.value = newState
        val source = newState.batterySource?.let {
            runCatching { AirPodsDataStore.BatterySource.valueOf(it) }.getOrNull()
        }
        dataStore.saveState(
            newState,
            batterySource = source,
        )
    }

    suspend fun applyBluetoothConnection(event: BluetoothAirPodsEvent) {
        val current = _state.value
        val sameDevice = sameLogicalAirPods(
            current = current,
            incomingDeviceId = event.deviceId,
            incomingModel = event.model,
            incomingProfileId = event.deviceProfileId,
            allowModelFallback = event.connectionState == AirPodsConnectionState.ANDROID_CONNECTED,
        )
        val clearCharging = event.connectionState != AirPodsConnectionState.ANDROID_CONNECTED
        // A connection transition away from Android audio invalidates the
        // previous wear sample. A fresh BLE/AAP wear event may populate it
        // again, but an iPad/nearby-only snapshot must not keep old wear live.
        val clearWear = event.connectionState != AirPodsConnectionState.ANDROID_CONNECTED
        val newState = if (sameDevice) {
            current.copy(
                // Once Android confirms the audio profile, its Classic
                // address becomes the route anchor. BLE may have arrived on a
                // rotating address before this event.
                deviceId = if (event.connectionState == AirPodsConnectionState.ANDROID_CONNECTED) {
                    event.deviceId
                } else {
                    current.deviceId ?: event.deviceId
                },
                deviceProfileId = if (event.connectionState == AirPodsConnectionState.ANDROID_CONNECTED) {
                    event.deviceProfileId
                } else {
                    current.deviceProfileId ?: event.deviceProfileId
                },
                model = when {
                    current.model == AirPodsModel.UNKNOWN || current.model == AirPodsModel.AIRPODS -> event.model
                    event.model == AirPodsModel.UNKNOWN || event.model == AirPodsModel.AIRPODS -> current.model
                    current.hasAnyBattery && current.model.isCompatibleWith(event.model) -> current.model
                    else -> event.model
                },
                connected = event.connectionState == AirPodsConnectionState.ANDROID_CONNECTED,
                detected = event.connectionState != AirPodsConnectionState.UNKNOWN,
                connectionState = event.connectionState,
                deviceName = event.deviceName,
                a2dpConnected = event.a2dpConnected,
                headsetConnected = event.headsetConnected,
                aapReady = event.aapReady,
                lastSeenAt = event.seenAt,
                confidence = DataConfidence.LIVE,
            ).clearChargingIf(clearCharging)
                .clearWearIf(clearWear)
                .withFreshCharging(event.seenAt)
        } else {
            AirPodsState(
                deviceId = event.deviceId,
                deviceProfileId = event.deviceProfileId,
                model = event.model,
                connected = event.connectionState == AirPodsConnectionState.ANDROID_CONNECTED,
                detected = event.connectionState != AirPodsConnectionState.UNKNOWN,
                connectionState = event.connectionState,
                deviceName = event.deviceName,
                a2dpConnected = event.a2dpConnected,
                headsetConnected = event.headsetConnected,
                aapReady = event.aapReady,
                lastSeenAt = event.seenAt,
                confidence = DataConfidence.LIVE,
            )
        }
        _state.value = newState
        dataStore.saveState(newState)
    }

    internal suspend fun applyClassicAapBattery(event: ClassicAapBatteryEvent) {
        val current = _state.value
        val sameDevice = sameLogicalAirPods(
            current = current,
            incomingDeviceId = event.deviceId,
            incomingModel = event.model,
            incomingProfileId = event.deviceProfileId,
        )
        val base = if (sameDevice) {
            current
        } else {
            AirPodsState(
                deviceId = event.deviceId,
                deviceProfileId = event.deviceProfileId,
                model = event.model,
                connected = false,
                detected = true,
                connectionState = AirPodsConnectionState.UNKNOWN,
            )
        }
        val snapshot = event.snapshot
        val leftEvidence = snapshot.left?.let {
            chargingEvidence(it.charging, "AAP_CLASSIC_EXACT", event.seenAt, "AAP_0x0004")
        } ?: base.leftChargingEvidence
        val rightEvidence = snapshot.right?.let {
            chargingEvidence(it.charging, "AAP_CLASSIC_EXACT", event.seenAt, "AAP_0x0004")
        } ?: base.rightChargingEvidence
        val caseEvidence = snapshot.case?.let {
            chargingEvidence(it.charging, "AAP_CLASSIC_EXACT", event.seenAt, "AAP_0x0004")
        } ?: base.caseChargingEvidence
        val newState = base.copy(
            deviceId = base.deviceId ?: event.deviceId,
            deviceProfileId = base.deviceProfileId ?: event.deviceProfileId,
            model = when {
                base.model == AirPodsModel.UNKNOWN || base.model == AirPodsModel.AIRPODS -> event.model
                else -> base.model
            },
            leftBattery = snapshot.left?.percent ?: base.leftBattery,
            rightBattery = snapshot.right?.percent ?: base.rightBattery,
            caseBattery = snapshot.case?.percent ?: base.caseBattery,
            leftCharging = snapshot.left?.charging ?: base.leftCharging,
            rightCharging = snapshot.right?.charging ?: base.rightCharging,
            caseCharging = snapshot.case?.charging ?: base.caseCharging,
            connected = base.connectionState == AirPodsConnectionState.ANDROID_CONNECTED,
            detected = true,
            lastSeenAt = event.seenAt,
            confidence = DataConfidence.LIVE,
            batterySource = AirPodsDataStore.BatterySource.AAP_CLASSIC_EXACT.name,
            batteryCapturedAt = event.seenAt,
            leftChargingEvidence = leftEvidence,
            rightChargingEvidence = rightEvidence,
            caseChargingEvidence = caseEvidence,
        ).withFreshCharging(event.seenAt)
        _state.value = newState
        dataStore.saveState(
            newState,
            batterySource = AirPodsDataStore.BatterySource.AAP_CLASSIC_EXACT,
        )
    }

    internal suspend fun applyClassicAapWear(event: ClassicAapWearEvent) {
        val stableWearState = event.wearState ?: return
        val current = _state.value
        val sameDevice = sameLogicalAirPods(
            current = current,
            incomingDeviceId = event.deviceId,
            incomingModel = event.model,
            incomingProfileId = event.deviceProfileId,
        )
        val base = if (sameDevice) current else AirPodsState(
            deviceId = event.deviceId,
            deviceProfileId = event.deviceProfileId,
            model = event.model,
            detected = true,
            connectionState = AirPodsConnectionState.UNKNOWN,
        )
        val newState = base.copy(
            deviceId = base.deviceId ?: event.deviceId,
            deviceProfileId = base.deviceProfileId ?: event.deviceProfileId,
            model = if (base.model == AirPodsModel.UNKNOWN) event.model else base.model,
            primaryPodIsLeft = event.primaryPodIsLeft ?: base.primaryPodIsLeft,
            wearState = stableWearState,
            wearSource = "AAP_CLASSIC_0x0006",
            wearCapturedAt = event.seenAt,
            wearExpiresAt = event.seenAt + WEAR_TTL_MS,
            wearCapturedAtElapsedMs = event.capturedAtElapsedMs,
            wearExpiresAtElapsedMs = event.capturedAtElapsedMs + WEAR_TTL_MS,
            wearDeviceProfileId = event.deviceProfileId,
            lastSeenAt = maxOf(base.lastSeenAt ?: 0L, event.seenAt),
        ).clearPodChargingWhenOutOfCase(stableWearState).withFreshWear(event.seenAt)
        _state.value = newState
        dataStore.saveState(newState)
    }
}

internal fun mergeParsedState(
    current: AirPodsState,
    deviceId: String,
    packet: ParsedAirPodsPacket,
    seenAt: Long,
    wearDetectionEnabled: Boolean = true,
    deviceProfileId: String? = null,
    capturedAtElapsedMs: Long? = null,
): AirPodsState {
    val sameDevice = sameLogicalAirPods(
        current = current,
        incomingDeviceId = deviceId,
        incomingModel = packet.model,
        incomingProfileId = deviceProfileId,
    )
    val model = if (sameDevice && packet.model == AirPodsModel.AIRPODS &&
        current.model != AirPodsModel.UNKNOWN && current.model != AirPodsModel.AIRPODS
    ) {
        current.model
    } else {
        packet.model
    }

    val leftEvidence = when {
        packet.leftInCase == false && !packet.model.isMax -> ChargingEvidence()
        packet.leftCharging != null -> chargingEvidence(
            packet.leftCharging,
            "BLE_PUBLIC_COARSE",
            seenAt,
            packet.parserVersion,
        )
        else -> current.leftChargingEvidence.takeIf { sameDevice } ?: ChargingEvidence()
    }
    val rightEvidence = when {
        packet.rightInCase == false && !packet.model.isMax -> ChargingEvidence()
        packet.rightCharging != null -> chargingEvidence(
            packet.rightCharging,
            "BLE_PUBLIC_COARSE",
            seenAt,
            packet.parserVersion,
        )
        else -> current.rightChargingEvidence.takeIf { sameDevice } ?: ChargingEvidence()
    }
    val caseEvidence = when {
        packet.caseCharging != null -> chargingEvidence(
            packet.caseCharging,
            "BLE_PUBLIC_COARSE",
            seenAt,
            packet.parserVersion,
        )
        else -> current.caseChargingEvidence.takeIf { sameDevice } ?: ChargingEvidence()
    }
    val connectionState = when {
        !sameDevice -> AirPodsConnectionState.NEARBY_ONLY
        current.connectionState == AirPodsConnectionState.ANDROID_CONNECTED ->
            AirPodsConnectionState.ANDROID_CONNECTED
        current.connectionState == AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING ->
            AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING
        else -> AirPodsConnectionState.NEARBY_ONLY
    }
    val leftBattery = retainLastKnownPodBattery(
        current = current.leftBattery,
        incoming = packet.leftBattery,
        inCase = packet.leftInCase,
        caseOpen = packet.caseOpen,
        sameDevice = sameDevice,
        parserVersion = packet.parserVersion,
    )
    val rightBattery = retainLastKnownPodBattery(
        current = current.rightBattery,
        incoming = packet.rightBattery,
        inCase = packet.rightInCase,
        caseOpen = packet.caseOpen,
        sameDevice = sameDevice,
        parserVersion = packet.parserVersion,
    )
    val leftClosedCaseZero = isClosedCaseZero(
        incoming = packet.leftBattery,
        inCase = packet.leftInCase,
        caseOpen = packet.caseOpen,
        parserVersion = packet.parserVersion,
    )
    val rightClosedCaseZero = isClosedCaseZero(
        incoming = packet.rightBattery,
        inCase = packet.rightInCase,
        caseOpen = packet.caseOpen,
        parserVersion = packet.parserVersion,
    )
    val hasFreshBatterySample =
        (packet.leftBattery != null && !leftClosedCaseZero) ||
            (packet.rightBattery != null && !rightClosedCaseZero) ||
            packet.caseBattery != null

    return AirPodsState(
        deviceId = if (sameDevice) current.deviceId ?: deviceId else deviceId,
        deviceProfileId = if (sameDevice) current.deviceProfileId ?: deviceProfileId else deviceProfileId,
        model = model,
        // A valid AirPods broadcast can omit a battery/lid field (0xF or an
        // out-of-case frame). Do not erase the last real value when a later
        // packet only contains the other side's status.
        leftBattery = leftBattery,
        rightBattery = rightBattery,
        caseBattery = packet.caseBattery ?: current.caseBattery.takeIf { sameDevice },
        leftCharging = packet.leftCharging ?: current.leftCharging.takeIf {
            sameDevice && leftEvidence.state != ChargingState.UNKNOWN
        },
        rightCharging = packet.rightCharging ?: current.rightCharging.takeIf {
            sameDevice && rightEvidence.state != ChargingState.UNKNOWN
        },
        caseCharging = packet.caseCharging ?: current.caseCharging.takeIf {
            sameDevice && caseEvidence.state != ChargingState.UNKNOWN
        },
        leftInCase = packet.leftInCase ?: current.leftInCase.takeIf { sameDevice },
        rightInCase = packet.rightInCase ?: current.rightInCase.takeIf { sameDevice },
        caseOpen = packet.caseOpen ?: current.caseOpen.takeIf { sameDevice },
        connected = connectionState == AirPodsConnectionState.ANDROID_CONNECTED,
        detected = true,
        connectionState = connectionState,
        deviceName = current.deviceName.takeIf { sameDevice },
        a2dpConnected = current.a2dpConnected.takeIf { sameDevice } ?: false,
        headsetConnected = current.headsetConnected.takeIf { sameDevice } ?: false,
        aapReady = current.aapReady.takeIf { sameDevice } ?: false,
        lastSeenAt = seenAt,
        confidence = packet.confidence,
        batterySource = if (hasFreshBatterySample) AirPodsDataStore.BatterySource.BLE_PUBLIC_COARSE.name
        else current.batterySource.takeIf { sameDevice },
        batteryCapturedAt = if (hasFreshBatterySample) seenAt
        else current.batteryCapturedAt.takeIf { sameDevice },
        primaryPodIsLeft = packet.primaryPodIsLeft ?: current.primaryPodIsLeft.takeIf { sameDevice },
        wearState = if (wearDetectionEnabled) {
            packet.wearState ?: current.wearState.takeIf { sameDevice }
                ?: AirPodsWearState.UNKNOWN
        } else {
            AirPodsWearState.UNKNOWN
        },
        wearSource = if (!wearDetectionEnabled) null else if (packet.wearState != null) "BLE_PUBLIC_EAR_STATE"
        else current.wearSource.takeIf { sameDevice },
        wearCapturedAt = if (!wearDetectionEnabled) null else if (packet.wearState != null) seenAt
        else current.wearCapturedAt.takeIf { sameDevice },
        wearExpiresAt = if (!wearDetectionEnabled) null else if (packet.wearState != null) seenAt + WEAR_TTL_MS
        else current.wearExpiresAt.takeIf { sameDevice },
        wearCapturedAtElapsedMs = if (!wearDetectionEnabled) null else if (packet.wearState != null) {
            capturedAtElapsedMs
        } else {
            current.wearCapturedAtElapsedMs.takeIf { sameDevice }
        },
        wearExpiresAtElapsedMs = if (!wearDetectionEnabled) null else if (packet.wearState != null) {
            capturedAtElapsedMs?.plus(WEAR_TTL_MS)
        } else {
            current.wearExpiresAtElapsedMs.takeIf { sameDevice }
        },
        wearDeviceProfileId = if (!wearDetectionEnabled) null else if (packet.wearState != null) {
            deviceProfileId
        } else {
            current.wearDeviceProfileId.takeIf { sameDevice }
        },
        leftChargingEvidence = leftEvidence,
        rightChargingEvidence = rightEvidence,
        caseChargingEvidence = caseEvidence,
    ).withFreshCharging(seenAt).withFreshWear(seenAt)
}

/**
 * Public Apple proximity frames often report a pod that is in a closed case
 * as 0 even though no new pod battery sample was delivered. That is not proof
 * that the pod reached 0%. Keep the last real pod sample until a later frame
 * contains a usable value or an exact AAP snapshot replaces it.
 */
private fun retainLastKnownPodBattery(
    current: Int?,
    incoming: Int?,
    inCase: Boolean?,
    caseOpen: Boolean?,
    sameDevice: Boolean,
    parserVersion: String,
): Int? = if (isClosedCaseZero(incoming, inCase, caseOpen, parserVersion)) {
    current.takeIf { sameDevice }
} else {
    incoming ?: current.takeIf { sameDevice }
}

private fun isClosedCaseZero(
    incoming: Int?,
    inCase: Boolean?,
    caseOpen: Boolean?,
    parserVersion: String,
): Boolean = incoming == 0 && inCase == true && caseOpen != true &&
    parserVersion.startsWith("apple-proximity-public")

private fun chargingEvidence(
    charging: Boolean?,
    source: String,
    capturedAt: Long,
    proof: String,
): ChargingEvidence = ChargingEvidence(
    state = when (charging) {
        true -> ChargingState.CHARGING
        false -> ChargingState.NOT_CHARGING
        null -> ChargingState.UNKNOWN
    },
    source = source,
    capturedAt = capturedAt,
    expiresAt = capturedAt + CHARGING_TTL_MS,
    proof = proof,
)

private fun AirPodsState.clearChargingIf(clear: Boolean): AirPodsState = if (!clear) {
    this
} else {
    copy(
        leftCharging = null,
        rightCharging = null,
        caseCharging = null,
        leftChargingEvidence = ChargingEvidence(),
        rightChargingEvidence = ChargingEvidence(),
        caseChargingEvidence = ChargingEvidence(),
    )
}

private fun AirPodsState.clearWearIf(clear: Boolean): AirPodsState = if (!clear) {
    this
} else {
    copy(
        wearState = AirPodsWearState.UNKNOWN,
        wearSource = null,
        wearCapturedAt = null,
        wearExpiresAt = null,
        wearCapturedAtElapsedMs = null,
        wearExpiresAtElapsedMs = null,
        wearDeviceProfileId = null,
    )
}

/**
 * A fresh wear frame is also fresh evidence that an earbud is not charging
 * while it is out of the case. It invalidates a previous charging=true sample;
 * an IN_CASE frame is left to the component battery message because it does
 * not say whether the case is actively charging.
 */
private fun AirPodsState.clearPodChargingWhenOutOfCase(
    wearState: AirPodsWearState,
): AirPodsState = when (wearState) {
    AirPodsWearState.LEFT_IN_EAR,
    AirPodsWearState.RIGHT_IN_EAR,
    AirPodsWearState.BOTH_IN_EAR,
    AirPodsWearState.PARTIAL_IN_EAR,
    AirPodsWearState.NONE_IN_EAR,
    AirPodsWearState.CONFLICT,
    -> copy(
        leftCharging = null,
        rightCharging = null,
        leftChargingEvidence = ChargingEvidence(),
        rightChargingEvidence = ChargingEvidence(),
    )
    AirPodsWearState.IN_CASE,
    AirPodsWearState.UNKNOWN,
    -> this
}

internal fun sameLogicalAirPods(
    current: AirPodsState,
    incomingDeviceId: String,
    incomingModel: AirPodsModel,
    incomingProfileId: String? = null,
    allowModelFallback: Boolean = incomingProfileId == null,
): Boolean = current.deviceId == incomingDeviceId ||
    (incomingProfileId != null && current.deviceProfileId == incomingProfileId) ||
    (allowModelFallback && current.detected && current.model.isCompatibleWith(incomingModel))

private const val CHARGING_TTL_MS = 20_000L
private const val WEAR_TTL_MS = 15_000L

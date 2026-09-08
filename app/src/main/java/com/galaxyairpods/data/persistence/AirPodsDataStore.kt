package com.galaxyairpods.data.persistence

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.isCompatibleWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.airPodsPreferences by preferencesDataStore(name = "airpods_state")

/** A persisted Bluetooth connection is never live across a process restart. */
private val PROCESS_SESSION_ID: String = UUID.randomUUID().toString()

class AirPodsDataStore(private val context: Context) {
    /** Source labels are capability metadata, not a precision claim. */
    enum class BatterySource { BLE_PUBLIC_COARSE, AAP_CLASSIC_EXACT, LEGACY_COARSE }

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val model = stringPreferencesKey("model")
        val modelOverride = stringPreferencesKey("model_override")
        val leftBattery = intPreferencesKey("left_battery")
        val rightBattery = intPreferencesKey("right_battery")
        val caseBattery = intPreferencesKey("case_battery")
        val leftCharging = booleanPreferencesKey("left_charging")
        val rightCharging = booleanPreferencesKey("right_charging")
        val caseCharging = booleanPreferencesKey("case_charging")
        val leftInCase = booleanPreferencesKey("left_in_case")
        val rightInCase = booleanPreferencesKey("right_in_case")
        val caseOpen = booleanPreferencesKey("case_open")
        val connected = booleanPreferencesKey("connected")
        val detected = booleanPreferencesKey("detected")
        val connectionState = stringPreferencesKey("connection_state")
        val liveSessionId = stringPreferencesKey("live_session_id")
        val deviceName = stringPreferencesKey("device_name")
        val lastSeenAt = longPreferencesKey("last_seen_at")
        val confidence = stringPreferencesKey("confidence")
        val batterySource = stringPreferencesKey("battery_source")
        val batteryUpdatedAt = longPreferencesKey("battery_updated_at")
        val primaryPodIsLeft = booleanPreferencesKey("primary_pod_is_left")
        val leftChargingState = stringPreferencesKey("left_charging_state")
        val rightChargingState = stringPreferencesKey("right_charging_state")
        val caseChargingState = stringPreferencesKey("case_charging_state")
        val leftChargingSource = stringPreferencesKey("left_charging_source")
        val rightChargingSource = stringPreferencesKey("right_charging_source")
        val caseChargingSource = stringPreferencesKey("case_charging_source")
        val leftChargingCapturedAt = longPreferencesKey("left_charging_captured_at")
        val rightChargingCapturedAt = longPreferencesKey("right_charging_captured_at")
        val caseChargingCapturedAt = longPreferencesKey("case_charging_captured_at")
        val leftChargingExpiresAt = longPreferencesKey("left_charging_expires_at")
        val rightChargingExpiresAt = longPreferencesKey("right_charging_expires_at")
        val caseChargingExpiresAt = longPreferencesKey("case_charging_expires_at")
        val leftChargingProof = stringPreferencesKey("left_charging_proof")
        val rightChargingProof = stringPreferencesKey("right_charging_proof")
        val caseChargingProof = stringPreferencesKey("case_charging_proof")
        val wearState = stringPreferencesKey("wear_state")
        val wearSource = stringPreferencesKey("wear_source")
        val wearCapturedAt = longPreferencesKey("wear_captured_at")
        val wearExpiresAt = longPreferencesKey("wear_expires_at")
        val autoPopup = booleanPreferencesKey("auto_popup")
        val showOnCaseOpen = booleanPreferencesKey("show_on_case_open")
        val backgroundDetection = booleanPreferencesKey("background_detection")
        val popupDuration = intPreferencesKey("popup_duration")
    }

    val latestState: Flow<AirPodsState?> = context.airPodsPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            if (preferences[Keys.deviceId] == null && preferences[Keys.lastSeenAt] == null) {
                null
            } else {
                val liveInThisProcess = preferences[Keys.liveSessionId] == PROCESS_SESSION_ID
                AirPodsState(
                    deviceId = preferences[Keys.deviceId],
                    model = preferences[Keys.model]?.let { value ->
                        AirPodsModel.entries.firstOrNull { it.name == value }
                    } ?: AirPodsModel.UNKNOWN,
                    leftBattery = preferences[Keys.leftBattery],
                    rightBattery = preferences[Keys.rightBattery],
                    caseBattery = preferences[Keys.caseBattery],
                    leftCharging = preferences[Keys.leftCharging],
                    rightCharging = preferences[Keys.rightCharging],
                    caseCharging = preferences[Keys.caseCharging],
                    leftInCase = preferences[Keys.leftInCase],
                    rightInCase = preferences[Keys.rightInCase],
                    caseOpen = preferences[Keys.caseOpen],
                    // A previous process may have persisted true here. It is
                    // cache metadata only; the scanner must re-query profiles.
                    connected = if (liveInThisProcess) preferences[Keys.connected] == true else false,
                    detected = if (liveInThisProcess) preferences[Keys.detected] == true else false,
                    connectionState = if (liveInThisProcess) {
                        preferences[Keys.connectionState]?.let { value ->
                            com.galaxyairpods.domain.model.AirPodsConnectionState.entries
                                .firstOrNull { it.name == value }
                        }
                    } else {
                        null
                    } ?: com.galaxyairpods.domain.model.AirPodsConnectionState.UNKNOWN,
                    deviceName = preferences[Keys.deviceName],
                    lastSeenAt = preferences[Keys.lastSeenAt],
                    confidence = preferences[Keys.confidence]?.let { value ->
                        DataConfidence.entries.firstOrNull { it.name == value }
                    } ?: DataConfidence.UNKNOWN,
                    batterySource = preferences[Keys.batterySource],
                    batteryCapturedAt = preferences[Keys.batteryUpdatedAt],
                    primaryPodIsLeft = preferences[Keys.primaryPodIsLeft],
                    leftChargingEvidence = readChargingEvidence(
                        preferences,
                        Keys.leftChargingState,
                        Keys.leftChargingSource,
                        Keys.leftChargingCapturedAt,
                        Keys.leftChargingExpiresAt,
                        Keys.leftChargingProof,
                    ),
                    rightChargingEvidence = readChargingEvidence(
                        preferences,
                        Keys.rightChargingState,
                        Keys.rightChargingSource,
                        Keys.rightChargingCapturedAt,
                        Keys.rightChargingExpiresAt,
                        Keys.rightChargingProof,
                    ),
                    caseChargingEvidence = readChargingEvidence(
                        preferences,
                        Keys.caseChargingState,
                        Keys.caseChargingSource,
                        Keys.caseChargingCapturedAt,
                        Keys.caseChargingExpiresAt,
                        Keys.caseChargingProof,
                    ),
                    wearState = preferences[Keys.wearState]?.let { value ->
                        com.galaxyairpods.domain.model.AirPodsWearState.entries
                            .firstOrNull { it.name == value }
                    } ?: com.galaxyairpods.domain.model.AirPodsWearState.UNKNOWN,
                    wearSource = preferences[Keys.wearSource],
                    wearCapturedAt = preferences[Keys.wearCapturedAt],
                    wearExpiresAt = preferences[Keys.wearExpiresAt],
                ).withResolvedConfidence()
            }
        }

    val autoPopup: Flow<Boolean> = context.airPodsPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.autoPopup] ?: true }

    val showOnCaseOpen: Flow<Boolean> = context.airPodsPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.showOnCaseOpen] ?: true }

    val popupDuration: Flow<Int> = context.airPodsPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.popupDuration] ?: 5 }

    val backgroundDetection: Flow<Boolean> = context.airPodsPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.backgroundDetection] ?: true }

    val modelOverride: Flow<AirPodsModel?> = context.airPodsPreferences.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[Keys.modelOverride]?.let { value ->
                AirPodsModel.entries.firstOrNull { it.name == value }
            }
        }

    val latestDisplayState: Flow<AirPodsState?> = combine(latestState, modelOverride) { state, override ->
        state?.copy(model = override ?: state.model)
    }

    suspend fun saveState(state: AirPodsState, batterySource: BatterySource? = null) {
        context.airPodsPreferences.edit { preferences ->
            val storedModel = preferences[Keys.model]?.let { value ->
                AirPodsModel.entries.firstOrNull { it.name == value }
            }
            val preserveKnownFields = storedModel != null && storedModel.isCompatibleWith(state.model)

            preferences.putNullable(Keys.deviceId, state.deviceId)
            preferences[Keys.model] = state.model.name
            preferences.putNullablePreserving(Keys.leftBattery, state.leftBattery, preserveKnownFields)
            preferences.putNullablePreserving(Keys.rightBattery, state.rightBattery, preserveKnownFields)
            preferences.putNullablePreserving(Keys.caseBattery, state.caseBattery, preserveKnownFields)
            preferences.putNullablePreserving(Keys.leftCharging, state.leftCharging, preserveKnownFields)
            preferences.putNullablePreserving(Keys.rightCharging, state.rightCharging, preserveKnownFields)
            preferences.putNullablePreserving(Keys.caseCharging, state.caseCharging, preserveKnownFields)
            preferences.putNullablePreserving(Keys.leftInCase, state.leftInCase, preserveKnownFields)
            preferences.putNullablePreserving(Keys.rightInCase, state.rightInCase, preserveKnownFields)
            preferences.putNullablePreserving(Keys.caseOpen, state.caseOpen, preserveKnownFields)
            preferences[Keys.connected] = state.isAndroidConnected
            preferences[Keys.detected] = state.connectionState !=
                com.galaxyairpods.domain.model.AirPodsConnectionState.UNKNOWN
            preferences[Keys.connectionState] = state.connectionState.name
            preferences[Keys.liveSessionId] = PROCESS_SESSION_ID
            preferences.putNullablePreserving(Keys.deviceName, state.deviceName, preserveKnownFields)
            preferences.putNullable(Keys.lastSeenAt, state.lastSeenAt)
            preferences[Keys.confidence] = state.confidence.name
            preferences.putNullable(Keys.primaryPodIsLeft, state.primaryPodIsLeft)
            if (state.hasAnyBattery) {
                val source = batterySource?.name ?: state.batterySource
                if (source != null) {
                    preferences[Keys.batterySource] = source
                    preferences[Keys.batteryUpdatedAt] = state.batteryCapturedAt
                        ?: state.lastSeenAt
                        ?: System.currentTimeMillis()
                } else {
                    // Never leave a source/timestamp claiming a battery
                    // sample that the current state does not identify.
                    preferences.remove(Keys.batterySource)
                    preferences.remove(Keys.batteryUpdatedAt)
                }
            } else {
                preferences.remove(Keys.batterySource)
                preferences.remove(Keys.batteryUpdatedAt)
            }

            writeChargingEvidence(
                preferences,
                state.leftChargingEvidence,
                Keys.leftCharging,
                Keys.leftChargingState,
                Keys.leftChargingSource,
                Keys.leftChargingCapturedAt,
                Keys.leftChargingExpiresAt,
                Keys.leftChargingProof,
            )
            writeChargingEvidence(
                preferences,
                state.rightChargingEvidence,
                Keys.rightCharging,
                Keys.rightChargingState,
                Keys.rightChargingSource,
                Keys.rightChargingCapturedAt,
                Keys.rightChargingExpiresAt,
                Keys.rightChargingProof,
            )
            writeChargingEvidence(
                preferences,
                state.caseChargingEvidence,
                Keys.caseCharging,
                Keys.caseChargingState,
                Keys.caseChargingSource,
                Keys.caseChargingCapturedAt,
                Keys.caseChargingExpiresAt,
                Keys.caseChargingProof,
            )
            preferences.putNullable(Keys.wearState, state.wearState.name.takeIf {
                state.wearState != com.galaxyairpods.domain.model.AirPodsWearState.UNKNOWN
            })
            preferences.putNullable(Keys.wearSource, state.wearSource)
            preferences.putNullable(Keys.wearCapturedAt, state.wearCapturedAt)
            preferences.putNullable(Keys.wearExpiresAt, state.wearExpiresAt)
        }
    }

    suspend fun setAutoPopup(enabled: Boolean) {
        context.airPodsPreferences.edit { it[Keys.autoPopup] = enabled }
    }

    suspend fun setShowOnCaseOpen(enabled: Boolean) {
        context.airPodsPreferences.edit { it[Keys.showOnCaseOpen] = enabled }
    }

    suspend fun setPopupDuration(seconds: Int) {
        context.airPodsPreferences.edit { it[Keys.popupDuration] = seconds.coerceIn(2, 15) }
    }

    suspend fun setBackgroundDetection(enabled: Boolean) {
        context.airPodsPreferences.edit { it[Keys.backgroundDetection] = enabled }
    }

    suspend fun setModelOverride(model: AirPodsModel?) {
        context.airPodsPreferences.edit { preferences ->
            if (model == null) preferences.remove(Keys.modelOverride)
            else preferences[Keys.modelOverride] = model.name
        }
    }
}

private fun readChargingEvidence(
    preferences: Preferences,
    stateKey: Preferences.Key<String>,
    sourceKey: Preferences.Key<String>,
    capturedAtKey: Preferences.Key<Long>,
    expiresAtKey: Preferences.Key<Long>,
    proofKey: Preferences.Key<String>,
): com.galaxyairpods.domain.model.ChargingEvidence =
    com.galaxyairpods.domain.model.ChargingEvidence(
        state = preferences[stateKey]?.let { value ->
            com.galaxyairpods.domain.model.ChargingState.entries.firstOrNull { it.name == value }
        } ?: com.galaxyairpods.domain.model.ChargingState.UNKNOWN,
        source = preferences[sourceKey],
        capturedAt = preferences[capturedAtKey],
        expiresAt = preferences[expiresAtKey],
        proof = preferences[proofKey],
    )

private fun writeChargingEvidence(
    preferences: MutablePreferences,
    evidence: com.galaxyairpods.domain.model.ChargingEvidence,
    rawChargingKey: Preferences.Key<Boolean>,
    stateKey: Preferences.Key<String>,
    sourceKey: Preferences.Key<String>,
    capturedAtKey: Preferences.Key<Long>,
    expiresAtKey: Preferences.Key<Long>,
    proofKey: Preferences.Key<String>,
) {
    // Do not preserve an old true value when a new sample does not contain
    // charging proof. Clearing all fields makes stale state impossible to
    // resurrect on the next process start.
    if (evidence.state == com.galaxyairpods.domain.model.ChargingState.UNKNOWN ||
        evidence.source == null || evidence.capturedAt == null ||
        evidence.expiresAt == null || evidence.proof == null
    ) {
        preferences.remove(rawChargingKey)
        preferences.remove(stateKey)
        preferences.remove(sourceKey)
        preferences.remove(capturedAtKey)
        preferences.remove(expiresAtKey)
        preferences.remove(proofKey)
        return
    }

    preferences[rawChargingKey] = evidence.state == com.galaxyairpods.domain.model.ChargingState.CHARGING
    preferences[stateKey] = evidence.state.name
    preferences[sourceKey] = evidence.source
    preferences[capturedAtKey] = evidence.capturedAt
    preferences[expiresAtKey] = evidence.expiresAt
    preferences[proofKey] = evidence.proof
}

private fun <T> MutablePreferences.putNullable(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}

private fun <T> MutablePreferences.putNullablePreserving(
    key: Preferences.Key<T>,
    value: T?,
    preserveExisting: Boolean,
) {
    if (value != null || !preserveExisting) putNullable(key, value)
}

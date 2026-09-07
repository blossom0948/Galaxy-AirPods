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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.airPodsPreferences by preferencesDataStore(name = "airpods_state")

class AirPodsDataStore(private val context: Context) {
    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val model = stringPreferencesKey("model")
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
        val lastSeenAt = longPreferencesKey("last_seen_at")
        val confidence = stringPreferencesKey("confidence")
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
                    connected = preferences[Keys.connected] ?: false,
                    detected = preferences[Keys.detected] ?: false,
                    lastSeenAt = preferences[Keys.lastSeenAt],
                    confidence = preferences[Keys.confidence]?.let { value ->
                        DataConfidence.entries.firstOrNull { it.name == value }
                    } ?: DataConfidence.UNKNOWN,
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
        .map { it[Keys.backgroundDetection] ?: false }

    suspend fun saveState(state: AirPodsState) {
        context.airPodsPreferences.edit { preferences ->
            preferences.putNullable(Keys.deviceId, state.deviceId)
            preferences[Keys.model] = state.model.name
            preferences.putNullable(Keys.leftBattery, state.leftBattery)
            preferences.putNullable(Keys.rightBattery, state.rightBattery)
            preferences.putNullable(Keys.caseBattery, state.caseBattery)
            preferences.putNullable(Keys.leftCharging, state.leftCharging)
            preferences.putNullable(Keys.rightCharging, state.rightCharging)
            preferences.putNullable(Keys.caseCharging, state.caseCharging)
            preferences.putNullable(Keys.leftInCase, state.leftInCase)
            preferences.putNullable(Keys.rightInCase, state.rightInCase)
            preferences.putNullable(Keys.caseOpen, state.caseOpen)
            preferences[Keys.connected] = state.connected
            preferences[Keys.detected] = state.detected
            preferences.putNullable(Keys.lastSeenAt, state.lastSeenAt)
            preferences[Keys.confidence] = state.confidence.name
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
}

private fun <T> MutablePreferences.putNullable(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}

package com.galaxyairpods.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galaxyairpods.data.PreviewFixtures
import com.galaxyairpods.domain.model.PreviewPreset
import com.galaxyairpods.domain.motion.MotionLabSettings
import com.galaxyairpods.ui.components.BatteryGrid
import com.galaxyairpods.ui.components.ProductRenderer

@Composable
fun PreviewLabScreen(
    settings: MotionLabSettings,
    onSettingsChange: ((MotionLabSettings) -> MotionLabSettings) -> Unit,
    onReset: () -> Unit,
    onTrigger: (PreviewPreset) -> Unit,
    onSaveCandidate: () -> String,
) {
    var selectedPreset by rememberSaveable { mutableStateOf(PreviewPreset.KNOWN_OPEN.name) }
    var savedFile by rememberSaveable { mutableStateOf<String?>(null) }
    val currentPreset = PreviewPreset.valueOf(selectedPreset)

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Popup Preview Lab", style = MaterialTheme.typography.headlineMedium)
        Text(
            "실제 AirPods 없이 V3 상태와 interruption을 검증합니다. Preview 값은 BLE 데이터 경로와 분리되어 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("Scene preview", style = MaterialTheme.typography.titleMedium)
                ProductRenderer(
                    state = when (currentPreset) {
                        PreviewPreset.LOW_BATTERY -> PreviewFixtures.lowBattery
                        PreviewPreset.CHARGING -> PreviewFixtures.charging
                        else -> PreviewFixtures.knownOpen
                    },
                    openProgress = 1f,
                    modifier = Modifier.fillMaxWidth(),
                )
                BatteryGrid(
                    state = when (currentPreset) {
                        PreviewPreset.LOW_BATTERY -> PreviewFixtures.lowBattery
                        PreviewPreset.CHARGING -> PreviewFixtures.charging
                        else -> PreviewFixtures.knownOpen
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text("Presets", style = MaterialTheme.typography.titleMedium)
        presetRows().forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    FilterChip(
                        selected = preset.name == selectedPreset,
                        onClick = {
                            selectedPreset = preset.name
                            onTrigger(preset)
                        },
                        label = { Text(preset.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1f, 0.5f, 0.25f).forEach { speed ->
                FilterChip(
                    selected = settings.playbackSpeed == speed,
                    onClick = { onSettingsChange { it.copy(playbackSpeed = speed) } },
                    label = { Text("${speed}x") },
                )
            }
        }

        Text("Live controls", style = MaterialTheme.typography.titleMedium)
        MotionSlider("Card damping", settings.cardDamping, 0.82f..1f) { value -> onSettingsChange { it.copy(cardDamping = value) } }
        MotionSlider("Card stiffness", settings.cardStiffness, 320f..520f) { value -> onSettingsChange { it.copy(cardStiffness = value) } }
        MotionSlider("Card initial Y", settings.cardInitialY, 0.15f..0.28f) { value -> onSettingsChange { it.copy(cardInitialY = value) } }
        MotionSlider("Card initial scale", settings.cardInitialScale, 0.95f..0.99f) { value -> onSettingsChange { it.copy(cardInitialScale = value) } }
        MotionSlider("Product delay", settings.productDelayMs.toFloat(), 0f..120f) { value -> onSettingsChange { it.copy(productDelayMs = value.toLong()) } }
        MotionSlider("Product damping", settings.productDamping, 0.80f..1f) { value -> onSettingsChange { it.copy(productDamping = value) } }
        MotionSlider("Product stiffness", settings.productStiffness, 280f..480f) { value -> onSettingsChange { it.copy(productStiffness = value) } }
        MotionSlider("Battery delay", settings.batteryDelayMs.toFloat(), 100f..360f) { value -> onSettingsChange { it.copy(batteryDelayMs = value.toLong()) } }
        MotionSlider("Battery stagger", settings.batteryStaggerMs.toFloat(), 0f..80f) { value -> onSettingsChange { it.copy(batteryStaggerMs = value.toLong()) } }
        MotionSlider("Exit Y", settings.exitY, 0.08f..0.24f) { value -> onSettingsChange { it.copy(exitY = value) } }
        MotionSlider("Exit scale", settings.exitScale, 0.98f..1f) { value -> onSettingsChange { it.copy(exitScale = value) } }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onTrigger(currentPreset) }, modifier = Modifier.weight(1f)) { Text("Replay") }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Reset") }
        }
        OutlinedButton(
            onClick = { savedFile = onSaveCandidate() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save as candidate")
        }
        savedFile?.let {
            Text("저장됨: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Baseline 숫자는 Apple 내부 상수가 아닙니다. Preview Lab에서 실제 iOS 레퍼런스와 Galaxy 60/120Hz 결과를 비교해 튜닝하세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MotionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("${"%.3f".format(value)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

private fun presetRows(): List<List<PreviewPreset>> = listOf(
    listOf(PreviewPreset.FIRST_PAIRING, PreviewPreset.KNOWN_OPEN),
    listOf(PreviewPreset.CONNECTED, PreviewPreset.LEFT_REMOVED),
    listOf(PreviewPreset.RIGHT_REMOVED, PreviewPreset.BOTH_REMOVED),
    listOf(PreviewPreset.CHARGING, PreviewPreset.LOW_BATTERY),
    listOf(PreviewPreset.EXIT, PreviewPreset.INTERRUPT_ENTER_TO_EXIT),
    listOf(PreviewPreset.INTERRUPT_EXIT_TO_ENTER),
)

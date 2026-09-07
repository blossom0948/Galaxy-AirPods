package com.galaxyairpods.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.permissions.PermissionManager
import com.galaxyairpods.update.UpdateInfo
import com.galaxyairpods.update.UpdateState

@Composable
fun SettingsScreen(
    autoPopup: Boolean,
    showOnCaseOpen: Boolean,
    popupDuration: Int,
    backgroundDetection: Boolean,
    modelOverride: AirPodsModel?,
    reducedMotion: Boolean,
    onAutoPopupChange: (Boolean) -> Unit,
    onCaseOpenChange: (Boolean) -> Unit,
    onPopupDurationChange: (Int) -> Unit,
    onBackgroundDetectionChange: (Boolean) -> Unit,
    onModelOverrideChange: (AirPodsModel?) -> Unit,
    onTestOverlay: () -> Unit,
    onReducedMotionChange: (Boolean) -> Unit,
    onStartScanning: () -> Unit,
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onInstallReady: (UpdateState.Ready) -> Unit,
) {
    val context = LocalContext.current
    val overlayGranted = Settings.canDrawOverlays(context)
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onStartScanning() }

    Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)

        ModelSelector(modelOverride, onModelOverrideChange)

        UpdateCard(updateState, onCheckForUpdates, onInstallUpdate, onInstallReady)

        SettingsToggle("자동 팝업", autoPopup, onAutoPopupChange)
        SettingsToggle("케이스를 열 때 표시", showOnCaseOpen, onCaseOpenChange)
        SettingsToggle("백그라운드 감지", backgroundDetection, onBackgroundDetectionChange)
        SettingsToggle("동작 줄이기", reducedMotion, onReducedMotionChange)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("팝업 표시 시간", style = MaterialTheme.typography.titleMedium)
                Text("${popupDuration}초", color = MaterialTheme.colorScheme.primary)
                Slider(
                    value = popupDuration.toFloat(),
                    onValueChange = { onPopupDurationChange(it.toInt()) },
                    valueRange = 2f..15f,
                    steps = 12,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("권한", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (PermissionManager.allGranted(context, PermissionManager.runtimePermissions())) {
                        "허용됨"
                    } else {
                        "Bluetooth와 알림 권한이 필요합니다"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    permissionsLauncher.launch(PermissionManager.runtimePermissions())
                }) { Text("권한 허용") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("다른 앱 위 팝업", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (overlayGranted) "허용됨" else "오버레이 권한이 필요합니다",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text("오버레이 권한 설정") }
                Button(onClick = onTestOverlay, enabled = overlayGranted) { Text("팝업 테스트") }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateState,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: (UpdateInfo) -> Unit,
    onInstallReady: (UpdateState.Ready) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("업데이트", style = MaterialTheme.typography.titleMedium)
            when (state) {
                UpdateState.Idle -> Text("업데이트 확인 전")
                UpdateState.Checking -> Text("최신 버전 확인 중…")
                UpdateState.UpToDate -> Text("최신 버전입니다")
                is UpdateState.Available -> {
                    Text("새 버전 ${state.info.versionName} 사용 가능")
                    Button(onClick = { onInstallUpdate(state.info) }) {
                        Text("업데이트 다운로드")
                    }
                }
                is UpdateState.Downloading -> {
                    Text("다운로드 중 ${state.progress}%")
                }
                is UpdateState.Ready -> {
                    Text("다운로드 완료")
                    Text(
                        "파일을 모두 받은 뒤 설치를 시작합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { onInstallReady(state) }) {
                        Text("설치")
                    }
                }
                is UpdateState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onCheckForUpdates) { Text("다시 확인") }
                }
            }
            if (state is UpdateState.UpToDate || state is UpdateState.Error) {
                Button(onClick = onCheckForUpdates) { Text("업데이트 확인") }
            }
        }
    }
}

@Composable
private fun ModelSelector(
    modelOverride: AirPodsModel?,
    onModelOverrideChange: (AirPodsModel?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options: List<AirPodsModel?> = listOf(null) + AirPodsModel.entries.filter { it != AirPodsModel.UNKNOWN }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("제품 모양", style = MaterialTheme.typography.titleMedium)
            Text(
                "자동 감지하거나 원하는 모델 모양을 선택합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { expanded = true }) {
                Text(modelOverride?.label ?: "자동 감지")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model?.label ?: "자동 감지") },
                        onClick = {
                            onModelOverrideChange(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

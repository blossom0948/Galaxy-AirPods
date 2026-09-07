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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.galaxyairpods.permissions.PermissionManager

@Composable
fun SettingsScreen(
    autoPopup: Boolean,
    showOnCaseOpen: Boolean,
    popupDuration: Int,
    backgroundDetection: Boolean,
    reducedMotion: Boolean,
    onAutoPopupChange: (Boolean) -> Unit,
    onCaseOpenChange: (Boolean) -> Unit,
    onPopupDurationChange: (Int) -> Unit,
    onBackgroundDetectionChange: (Boolean) -> Unit,
    onTestOverlay: () -> Unit,
    onReducedMotionChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val overlayGranted = Settings.canDrawOverlays(context)
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    Column(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("권한, 자동 팝업, 접근성 동작을 조정합니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        SettingsToggle("Auto popup", "케이스/연결 이벤트에서 팝업 surface를 표시", autoPopup, onAutoPopupChange)
        SettingsToggle("Show when case opens", "케이스 열림 상태를 감지했을 때 자동 표시", showOnCaseOpen, onCaseOpenChange)
        SettingsToggle("Background detection", "저전력 foreground notification으로 주변 상태 감지", backgroundDetection, onBackgroundDetectionChange)
        SettingsToggle("Reduce motion", "정보를 즉시 보여주고 장식적인 이동을 줄임", reducedMotion, onReducedMotionChange)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Popup duration", style = MaterialTheme.typography.titleMedium)
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
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bluetooth/Nearby devices: ${if (PermissionManager.allGranted(context, PermissionManager.bluetoothPermissions())) "허용됨" else "필요함"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { permissionsLauncher.launch(PermissionManager.bluetoothPermissions()) }) {
                    Text("Bluetooth 권한 요청")
                }
                Text(
                    "알림 권한은 백그라운드 fallback notification에 사용할 수 있습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (PermissionManager.notificationPermissions().isNotEmpty()) {
                    Button(onClick = { permissionsLauncher.launch(PermissionManager.notificationPermissions()) }) {
                        Text("알림 권한 요청")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Other apps overlay", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (overlayGranted) "허용됨 · 자동 팝업 사용 가능" else "꺼짐 · 앱 내부 팝업과 위젯은 계속 사용 가능",
                    color = if (overlayGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }) { Text("오버레이 권한 설정") }
                Button(onClick = onTestOverlay, enabled = overlayGranted) {
                    Text("오버레이 테스트")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Platform limitations", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Android와 Samsung One UI는 백그라운드 BLE/overlay를 제한할 수 있습니다. 지원되는 공식 API와 fallback notification 범위 안에서 동작하며, 실기기 결과는 DEVICE_TEST_MATRIX.md에 기록합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

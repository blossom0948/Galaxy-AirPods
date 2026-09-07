package com.galaxyairpods.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import com.galaxyairpods.permissions.PermissionManager

@Composable
fun DiagnosticsScreen(scanner: AirPodsBleScanner) {
    val records by scanner.records.collectAsStateWithLifecycle()
    val status by scanner.status.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) scanner.start()
    }

    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text("BLE Debug", style = MaterialTheme.typography.headlineMedium)
        Text(
            "실제 패킷을 수집하고 parser 검증에 사용할 masked diagnostic을 확인합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(status, modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val permissions = PermissionManager.bluetoothPermissions()
                if (PermissionManager.allGranted(context, permissions)) scanner.start()
                else permissionLauncher.launch(permissions)
            }) { Text("Start scan") }
            OutlinedButton(onClick = scanner::stop) { Text("Stop") }
            OutlinedButton(onClick = scanner::clear) { Text("Clear") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(scanner.maskedJson()))
            }) { Text("Copy masked diagnostic data") }
            OutlinedButton(onClick = { saveDiagnostics(context, scanner.maskedJson()) }) { Text("Save JSON") }
        }
        Text(
            "Apple manufacturer packet은 발견해도 아직 byte offset을 추측하지 않습니다. 결과에 NEEDS_DEVICE_VALIDATION이 표시됩니다.",
            modifier = Modifier.padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(records.reversed()) { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${record.name} · RSSI ${record.rssi}", style = MaterialTheme.typography.titleSmall)
                        Text("address: ${record.address}", style = MaterialTheme.typography.labelSmall)
                        Text("manufacturer: ${record.manufacturerId ?: "-"} / ${record.manufacturerHex.ifBlank { "-" }}", style = MaterialTheme.typography.labelSmall)
                        Text("parser: ${record.parserName}", style = MaterialTheme.typography.labelSmall)
                        Text(record.parserResult, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("raw: ${record.rawAdvertisingHex.take(160)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun saveDiagnostics(context: Context, json: String) {
    val fileName = "airpods-diagnostics-${System.currentTimeMillis()}.json"
    context.openFileOutput(fileName, Context.MODE_PRIVATE).use { it.write(json.toByteArray()) }
}

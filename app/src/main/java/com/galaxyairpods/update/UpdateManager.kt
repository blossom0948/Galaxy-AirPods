package com.galaxyairpods.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.galaxyairpods.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
)

class UpdateManager(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    suspend fun check() {
        _state.value = UpdateState.Checking
        runCatching {
            withContext(Dispatchers.IO) {
                val manifest = readText(MANIFEST_URL)
                parseManifest(manifest)
            }
        }.onSuccess { info ->
            _state.value = if (info.versionCode > BuildConfig.VERSION_CODE) {
                UpdateState.Available(info)
            } else {
                UpdateState.UpToDate
            }
        }.onFailure {
            _state.value = UpdateState.Error("업데이트 확인 실패")
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo) {
        runCatching {
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "AirPodsGalaxy-${info.versionCode}.apk")
                download(info, target)
                target
            }
            _state.value = UpdateState.Ready(info, file)
            install(file)
        }.onFailure {
            _state.value = UpdateState.Error("업데이트 다운로드 실패")
        }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(installIntent)
    }

    private fun readText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return connection.useConnection { inputStream.reader().use { it.readText() } }
    }

    private fun download(info: UpdateInfo, target: File) {
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "AirPodsGalaxy/${BuildConfig.VERSION_NAME}")
        connection.connect()
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")

        val total = connection.contentLengthLong
        var copied = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    val progress = if (total > 0) (copied * 100 / total).toInt() else 0
                    _state.value = UpdateState.Downloading(info, progress.coerceIn(0, 99))
                }
            }
        }
    }

    private fun parseManifest(json: String): UpdateInfo {
        val versionCode = json.matchInt("versionCode") ?: error("versionCode 없음")
        val versionName = json.matchString("versionName") ?: error("versionName 없음")
        val apkUrl = json.matchString("apkUrl") ?: error("apkUrl 없음")
        return UpdateInfo(versionCode, versionName, apkUrl)
    }

    private fun String.matchInt(key: String): Int? =
        Regex("""["]$key["]\s*:\s*(\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.matchString(key: String): String? =
        Regex("""["]$key["]\s*:\s*["]([^"]+)["]""").find(this)?.groupValues?.getOrNull(1)

    private fun HttpURLConnection.useConnection(block: HttpURLConnection.() -> String): String {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "AirPodsGalaxy/${BuildConfig.VERSION_NAME}")
        connect()
        if (responseCode !in 200..299) error("HTTP $responseCode")
        return block()
    }

    companion object {
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/blossom0948/Galaxy-AirPods/main/update.json"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}

package com.galaxyairpods.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.galaxyairpods.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
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

    fun installReady(file: File) {
        runCatching { install(file) }
            .onFailure { _state.value = UpdateState.Error("업데이트 설치를 시작하지 못했습니다") }
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

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = packageInstaller.createSession(params)

        try {
            packageInstaller.openSession(sessionId).use { session ->
                FileInputStream(file).use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val callbackIntent = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val statusIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    pendingIntentFlags,
                )
                session.commit(statusIntent.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw error
        }
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
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (total <= 0L || copied < total) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val remaining = if (total > 0L) {
                            (total - copied).toInt().coerceAtMost(count)
                        } else {
                            count
                        }
                        output.write(buffer, 0, remaining)
                        copied += remaining
                        val progress = if (total > 0) (copied * 100 / total).toInt() else 0
                        _state.value = UpdateState.Downloading(info, progress.coerceIn(0, 99))
                    }
                }
            }
            if (total > 0L && copied != total) {
                error("APK 다운로드가 끝나기 전에 연결이 종료되었습니다")
            }
            _state.value = UpdateState.Downloading(info, 100)
        } finally {
            connection.disconnect()
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
    }
}

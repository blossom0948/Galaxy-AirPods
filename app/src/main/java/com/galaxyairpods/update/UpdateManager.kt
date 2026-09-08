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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
)

class UpdateManager(context: Context) {
    private val context = context.applicationContext
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var lastAutomaticCheckAt = 0L

    suspend fun check(automatic: Boolean = false) {
        if (automatic) {
            val now = System.currentTimeMillis()
            if (now - lastAutomaticCheckAt < AUTO_CHECK_INTERVAL_MS) return
            lastAutomaticCheckAt = now
        }

        mutex.withLock {
            if (context.packageManager.canRequestPackageInstalls()) {
                // A blocked update becomes eligible again after the user grants
                // this permission. Keep the attempt timestamp so an install
                // session that is already waiting for confirmation is not started
                // again on every automatic check.
                preferences.edit().remove(BLOCKED_VERSION_KEY).apply()
            }

            _state.value = UpdateState.Checking
            runCatching {
                withContext(Dispatchers.IO) {
                    val manifest = readText(manifestUrl())
                    parseManifest(manifest)
                }
            }.onSuccess { info ->
                if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    preferences.edit()
                        .remove(ATTEMPTED_VERSION_KEY)
                        .remove(ATTEMPTED_AT_KEY)
                        .remove(BLOCKED_VERSION_KEY)
                        .apply()
                    _state.value = UpdateState.UpToDate
                } else {
                    _state.value = UpdateState.Available(info)
                    val attempted = preferences.getInt(ATTEMPTED_VERSION_KEY, -1)
                    val attemptedAt = preferences.getLong(ATTEMPTED_AT_KEY, 0L)
                    val blocked = preferences.getInt(BLOCKED_VERSION_KEY, -1)
                    val retryAllowed = attempted != info.versionCode ||
                        attemptedAt == 0L ||
                        System.currentTimeMillis() - attemptedAt >= AUTO_RETRY_COOLDOWN_MS
                    if (automatic && blocked != info.versionCode && retryAllowed) {
                        downloadAndInstallLocked(info)
                    }
                }
            }.onFailure {
                _state.value = UpdateState.Error("업데이트 확인 실패")
            }
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo) {
        mutex.withLock {
            // Manual retry must always be available, even after an interrupted
            // automatic attempt left a persisted guard behind.
            preferences.edit()
                .remove(ATTEMPTED_VERSION_KEY)
                .remove(ATTEMPTED_AT_KEY)
                .remove(BLOCKED_VERSION_KEY)
                .apply()
            downloadAndInstallLocked(info)
        }
    }

    fun installReady(file: File) {
        val ready = _state.value as? UpdateState.Ready ?: return
        runCatching { install(ready.info, file) }
            .onFailure { _state.value = UpdateState.Error("업데이트 설치를 시작하지 못했습니다") }
    }

    fun markInstallFailure(message: String) {
        preferences.edit()
            .remove(ATTEMPTED_VERSION_KEY)
            .remove(ATTEMPTED_AT_KEY)
            .remove(BLOCKED_VERSION_KEY)
            .apply()
        _state.value = UpdateState.Error(message)
    }

    fun markInstallSuccess() {
        preferences.edit()
            .remove(ATTEMPTED_VERSION_KEY)
            .remove(ATTEMPTED_AT_KEY)
            .remove(BLOCKED_VERSION_KEY)
            .apply()
        _state.value = UpdateState.UpToDate
    }

    private suspend fun downloadAndInstallLocked(info: UpdateInfo) {
        runCatching {
            val file = downloadAndPrepare(info)
            _state.value = UpdateState.Ready(info, file)
            install(info, file)
        }.onFailure {
            _state.value = UpdateState.Error("업데이트 파일을 받을 수 없습니다")
        }
    }

    private suspend fun downloadAndPrepare(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "AirPodsGalaxy-${info.versionCode}.apk")
        download(info, target)
        verifyApk(target, info)
        target
    }

    private fun install(info: UpdateInfo, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            preferences.edit().putInt(BLOCKED_VERSION_KEY, info.versionCode).apply()
            runCatching { context.startActivity(settingsIntent) }
                .onFailure { UpdateNotifications.notifyInstallPermission(context) }
            return
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Ask Android to complete without a prompt when policy allows
                // it. Ordinary APK installers may still receive
                // STATUS_PENDING_USER_ACTION, which the receiver handles.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
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
                session.setStagingProgress(1f)

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
                preferences.edit()
                    .putInt(ATTEMPTED_VERSION_KEY, info.versionCode)
                    .putLong(ATTEMPTED_AT_KEY, System.currentTimeMillis())
                    .remove(BLOCKED_VERSION_KEY)
                    .apply()
                _state.value = UpdateState.Installing(info)
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
        val partial = File(target.parentFile, "${target.name}.part")
        if (partial.exists() && !partial.delete()) {
            error("이전 다운로드 임시 파일을 정리하지 못했습니다")
        }

        var total = -1L
        var attempts = 0
        while (true) {
            val offset = partial.length()
            if (total > 0L && offset >= total) break

            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "AirPodsGalaxy/${BuildConfig.VERSION_NAME}")
            // GitHub release assets are served through a redirect. Do not let a
            // proxy negotiate gzip/chunked content while we validate the APK bytes.
            connection.setRequestProperty("Accept-Encoding", "identity")
            // If a mobile connection stalls after most of the file, continue
            // from the exact byte already saved instead of restarting at zero.
            connection.setRequestProperty("Range", "bytes=$offset-")

            var responseCompleted = false
            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (offset > 0L && responseCode == HttpURLConnection.HTTP_OK) {
                    // The server ignored Range. Restart once from a clean file so
                    // the full response is not appended to a partial APK.
                    if (!partial.delete()) error("다운로드를 처음부터 다시 시작하지 못했습니다")
                    total = -1L
                    continue
                }
                if (responseCode !in 200..299) error("HTTP $responseCode")

                val range = parseContentRange(connection.getHeaderField("Content-Range"))
                if (responseCode == HttpURLConnection.HTTP_PARTIAL &&
                    range != null && range.first != offset
                ) {
                    error("APK 다운로드 위치가 일치하지 않습니다")
                }
                val responseLength = connection.contentLengthLong
                total = range?.third
                    ?: total.takeIf { it > 0L }
                    ?: responseLength.takeIf { it > 0L }?.let { offset + it }
                    ?: -1L
                val expectedBytes = responseLength.takeIf { it > 0L }
                    ?: total.takeIf { it > 0L }?.minus(offset)?.takeIf { it > 0L }

                connection.inputStream.buffered().use { input ->
                    FileOutputStream(partial, true).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var received = 0L
                        while (expectedBytes == null || received < expectedBytes) {
                            val requested = if (expectedBytes != null) {
                                (expectedBytes - received).toInt().coerceAtMost(buffer.size)
                            } else {
                                buffer.size
                            }
                            val count = input.read(buffer, 0, requested)
                            if (count < 0) {
                                if (expectedBytes != null && received < expectedBytes) {
                                    throw EOFException("APK 다운로드가 끝나기 전에 연결이 종료되었습니다")
                                }
                                break
                            }
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            received += count
                            val copied = offset + received
                            val progress = if (total > 0L) {
                                (copied * 100 / total).toInt()
                            } else {
                                0
                            }
                            _state.value = UpdateState.Downloading(
                                info,
                                progress.coerceIn(0, 99),
                            )
                        }
                        output.flush()
                    }
                }

                attempts = 0
                responseCompleted = true
            } catch (error: IOException) {
                attempts += 1
                if (attempts > MAX_DOWNLOAD_ATTEMPTS) throw error
                Thread.sleep(DOWNLOAD_RETRY_DELAY_MS * attempts)
            } finally {
                connection.disconnect()
            }

            if (responseCompleted && (total <= 0L || partial.length() >= total)) break
        }

        if (total > 0L && partial.length() != total) {
            error("APK 다운로드 크기가 일치하지 않습니다")
        }
        if (target.exists() && !target.delete()) {
            error("이전 APK 파일을 교체하지 못했습니다")
        }
        if (!partial.renameTo(target)) error("APK 파일을 저장하지 못했습니다")
        _state.value = UpdateState.Downloading(info, 100)
    }

    private fun parseContentRange(value: String?): Triple<Long, Long, Long?>? {
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(value.orEmpty())
            ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull()
        return Triple(start, end, total)
    }

    private fun verifyApk(file: File, info: UpdateInfo) {
        if (!file.isFile || file.length() <= 0L) error("APK 파일이 비어 있습니다")
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: error("APK 파일 형식을 읽을 수 없습니다")
        if (packageInfo.packageName != context.packageName) {
            error("다른 앱의 APK가 다운로드되었습니다")
        }
        val downloadedVersionCode = packageInfo.longVersionCode
        if (downloadedVersionCode != info.versionCode.toLong()) {
            error("다운로드된 APK 버전이 업데이트 정보와 다릅니다")
        }
        info.sha256?.let { expected ->
            val actual = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
            if (!actual.equals(expected, ignoreCase = true)) {
                error("다운로드된 APK 검증에 실패했습니다")
            }
        }
    }

    private fun parseManifest(json: String): UpdateInfo {
        val versionCode = json.matchInt("versionCode") ?: error("versionCode 없음")
        val versionName = json.matchString("versionName") ?: error("versionName 없음")
        val apkUrl = json.matchString("apkUrl") ?: error("apkUrl 없음")
        val sha256 = json.matchString("sha256")
        return UpdateInfo(versionCode, versionName, apkUrl, sha256)
    }

    private fun String.matchInt(key: String): Int? =
        Regex("""["]$key["]\s*:\s*(\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.matchString(key: String): String? =
        Regex("""["]$key["]\s*:\s*["]([^"]+)["]""").find(this)?.groupValues?.getOrNull(1)

    private fun manifestUrl(): String = "$MANIFEST_URL?cache=${System.currentTimeMillis() / MANIFEST_CACHE_BUCKET_MS}"

    private fun HttpURLConnection.useConnection(block: HttpURLConnection.() -> String): String {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "AirPodsGalaxy/${BuildConfig.VERSION_NAME}")
        return try {
            connect()
            if (responseCode !in 200..299) error("HTTP $responseCode")
            block()
        } finally {
            disconnect()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "update_state"
        private const val ATTEMPTED_VERSION_KEY = "attempted_version"
        private const val ATTEMPTED_AT_KEY = "attempted_at"
        private const val BLOCKED_VERSION_KEY = "blocked_version"
        private const val MANIFEST_CACHE_BUCKET_MS = 5 * 60 * 1000L
        private const val AUTO_CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private const val AUTO_RETRY_COOLDOWN_MS = 60 * 60 * 1000L
        private const val MAX_DOWNLOAD_ATTEMPTS = 5
        private const val DOWNLOAD_RETRY_DELAY_MS = 500L
        private const val MANIFEST_URL =
            "https://raw.githubusercontent.com/blossom0948/Galaxy-AirPods/main/update.json"

        @Volatile
        private var shared: UpdateManager? = null

        fun shared(context: Context): UpdateManager = synchronized(this) {
            shared ?: UpdateManager(context.applicationContext).also { shared = it }
        }
    }
}

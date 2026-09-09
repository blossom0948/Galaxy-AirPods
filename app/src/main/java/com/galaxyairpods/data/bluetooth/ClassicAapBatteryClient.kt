package com.galaxyairpods.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.ParcelUuid
import com.galaxyairpods.domain.model.AirPodsModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Reads the exact 1% AAP battery message over classic BR/EDR L2CAP.
 *
 * The public Android 36+ BluetoothSocketSettings API is attempted first,
 * reached reflectively so this app still compiles against the local Android
 * 35 SDK. A narrowly allowlisted Samsung tuple may use an explicitly marked
 * experimental hidden-API probe; every path still requires a real AAP response
 * and valid battery frame before it can produce a value.
 */
@SuppressLint("MissingPermission")
internal class ClassicAapBatteryClient {
    private val socketFactory = PublicClassicL2capSocketFactory()
    private val outputMutex = Mutex()

    @Volatile
    private var activeSocket: BluetoothSocket? = null

    suspend fun runSession(
        device: BluetoothDevice,
        deviceId: String,
        model: AirPodsModel,
        onBattery: suspend (AapBatterySnapshot) -> Unit,
        onWear: suspend (AapEarDetectionSnapshot) -> Unit = {},
        onReady: suspend () -> Unit = {},
        onClosed: suspend () -> Unit = {},
    ) {
        BleScanDiagnostics.logAapState(deviceId, "SESSION_START", model.name)
        val socketHandle = try {
            socketFactory.create(device, AapBatteryProtocol.CLASSIC_PSM)
        } catch (error: SecurityException) {
            BleScanDiagnostics.logAapFailure(deviceId, "socket_create", "SECURITY_EXCEPTION")
            return
        } catch (error: Exception) {
            BleScanDiagnostics.logAapFailure(
                deviceId,
                "socket_create",
                "${error::class.simpleName ?: "EXCEPTION"}",
            )
            return
        }

        if (socketHandle == null) {
            BleScanDiagnostics.logAapFailure(deviceId, "socket_create", "AAP_TRANSPORT_UNAVAILABLE")
            return
        }

        val socket = socketHandle.socket
        activeSocket = socket
        try {
            connectWithTimeout(socket, deviceId, socketHandle.strategy)
            send(socket, deviceId, "HANDSHAKE", AapBatteryProtocol.handshake)
            // A few firmware versions only enable the full component battery
            // notification after feature negotiation. Keep this immediately
            // after the handshake in the classic AACP startup sequence.
            send(socket, deviceId, "SET_FEATURE_FLAGS", AapBatteryProtocol.featureFlags)
            // Preserve the working v0.3.8 ordering for AirPods firmware that
            // only applies the first registration during session setup. The
            // response-gated registration below still sends every known mask.
            AapBatteryProtocol.legacyPreflightNotificationProfiles.forEach { (profile, bytes) ->
                send(socket, deviceId, "NOTIFICATION_PREFLIGHT_$profile", bytes)
            }
            BleScanDiagnostics.logAapState(deviceId, "READING", "psm=0x1001")
            readLoop(socket, deviceId, onBattery, onWear, onReady)
        } catch (_: CancellationException) {
            throw CancellationException("AAP session cancelled")
        } catch (error: IOException) {
            BleScanDiagnostics.logAapFailure(
                deviceId,
                "io",
                error::class.simpleName ?: "IO_EXCEPTION",
            )
        } catch (error: Exception) {
            BleScanDiagnostics.logAapFailure(
                deviceId,
                "session",
                error::class.simpleName ?: "EXCEPTION",
            )
        } finally {
            closeQuietly(socket)
            if (activeSocket === socket) activeSocket = null
            onClosed()
            BleScanDiagnostics.logAapState(deviceId, "SESSION_END", "")
        }
    }

    fun close() {
        activeSocket?.let(::closeQuietly)
        activeSocket = null
    }

    private suspend fun connectWithTimeout(
        socket: BluetoothSocket,
        deviceId: String,
        strategy: String,
    ) {
        val result = CompletableDeferred<Result<Unit>>()
        val cancelled = AtomicBoolean(false)
        val connectThread = Thread(
            {
                val outcome = runCatching { socket.connect() }
                result.complete(outcome)
                if (cancelled.get() && outcome.isSuccess) closeQuietly(socket)
            },
            "AirPods-AAP-connect",
        ).apply {
            isDaemon = true
            start()
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                result.await().getOrThrow()
            }
            BleScanDiagnostics.logAapState(deviceId, "CONNECTED", "transport=$strategy psm=0x1001")
        } catch (error: Exception) {
            cancelled.set(true)
            closeQuietly(socket)
            connectThread.interrupt()
            BleScanDiagnostics.logAapFailure(
                deviceId,
                "socket_connect",
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    "TIMEOUT"
                } else {
                    error::class.simpleName ?: "CONNECT_EXCEPTION"
                },
            )
            throw error
        }
    }

    private suspend fun send(
        socket: BluetoothSocket,
        deviceId: String,
        kind: String,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        outputMutex.withLock {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
            BleScanDiagnostics.logAapTx(deviceId, kind, bytes)
        }
    }

    private suspend fun readLoop(
        socket: BluetoothSocket,
        deviceId: String,
        onBattery: suspend (AapBatterySnapshot) -> Unit,
        onWear: suspend (AapEarDetectionSnapshot) -> Unit,
        onReady: suspend () -> Unit,
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        val accumulator = AapFrameAccumulator()
        var startupComplete = false
        val batteryObserved = AtomicBoolean(false)
        var batteryRetryJob: Job? = null
        val startupWatchdog = launch {
            delay(STARTUP_RESPONSE_TIMEOUT_MS)
            if (!startupComplete) {
                BleScanDiagnostics.logAapFailure(deviceId, "handshake", "CONNECT_RESPONSE_TIMEOUT")
                closeQuietly(socket)
            }
        }
        try {
            while (coroutineContext.isActive) {
                coroutineContext.ensureActive()
                val length = socket.inputStream.read(buffer)
                if (length < 0) return@withContext
                if (length == 0) continue

                val accumulated = accumulator.append(buffer.copyOf(length))
                BleScanDiagnostics.logAapReadRecord(
                    deviceId = deviceId,
                    bytes = buffer.copyOf(length),
                    emittedFrames = accumulated.frames.size,
                    malformedRecords = accumulated.malformedRecords,
                )
                repeat(accumulated.malformedRecords) {
                    BleScanDiagnostics.logAapFailure(deviceId, "read", "MALFORMED_FRAME")
                }
                accumulated.frames.forEach frameLoop@{ rawFrame ->
                    val frame = AapBatteryProtocol.parseFrame(rawFrame)
                    if (frame == null) {
                        BleScanDiagnostics.logAapFailure(deviceId, "read", "MALFORMED_FRAME")
                        return@frameLoop
                    }
                    when (frame) {
                    is AapFrame.ConnectResponse -> {
                        BleScanDiagnostics.logAapRxConnectResponse(deviceId, frame)
                        if (frame.status != 0) {
                            BleScanDiagnostics.logAapFailure(deviceId, "handshake", "STATUS_${frame.status}")
                        } else if (!startupComplete) {
                            // Registration is ordered after the handshake response
                            // and is performed once per session.
                            send(socket, deviceId, "SET_FEATURE_FLAGS_RESPONSE", AapBatteryProtocol.featureFlags)
                            AapBatteryProtocol.notificationProfiles.forEach { (profile, bytes) ->
                                send(socket, deviceId, "NOTIFICATION_$profile", bytes)
                            }
                            send(socket, deviceId, "KEY_REQUEST", AapBatteryProtocol.keyRequest)
                            startupComplete = true
                            batteryRetryJob = launch {
                                delay(BATTERY_REQUEST_RETRY_DELAY_MS)
                                if (coroutineContext.isActive && !batteryObserved.get()) {
                                    BleScanDiagnostics.logAapState(
                                        deviceId,
                                        "BATTERY_REQUEST_RETRY",
                                        "no_0x0004_sample",
                                    )
                                    send(
                                        socket,
                                        deviceId,
                                        "SET_FEATURE_FLAGS_RETRY",
                                        AapBatteryProtocol.featureFlags,
                                    )
                                    AapBatteryProtocol.notificationProfiles.forEach { (profile, bytes) ->
                                        send(socket, deviceId, "NOTIFICATION_RETRY_$profile", bytes)
                                    }
                                }
                            }
                            onReady()
                        }
                    }
                    is AapFrame.Message -> {
                        BleScanDiagnostics.logAapRxMessage(deviceId, frame)
                        val battery = AapBatteryProtocol.parseBattery(frame)
                        if (battery != null) {
                            batteryObserved.set(true)
                            batteryRetryJob?.cancel()
                            BleScanDiagnostics.logAapBattery(deviceId, battery)
                            onBattery(battery)
                        }
                        val wear = AapBatteryProtocol.parseEarDetection(frame)
                        if (wear != null) {
                            BleScanDiagnostics.logAapEarDetection(deviceId, wear)
                            onWear(wear)
                        } else if (frame.command == AapBatteryProtocol.EAR_DETECTION_COMMAND) {
                            BleScanDiagnostics.logAapEarFrameShape(deviceId, frame)
                            BleScanDiagnostics.logAapFailure(deviceId, "ear_detection", "MALFORMED_OR_UNKNOWN_STATUS")
                        }
                    }
                    is AapFrame.Other -> {
                        BleScanDiagnostics.logAapFailure(
                            deviceId,
                            "read",
                            "UNKNOWN_PACKET_0x${frame.packetType.toString(16)}",
                        )
                    }
                }
                }
            }
        } finally {
            startupWatchdog.cancel()
            batteryRetryJob?.cancel()
        }
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        runCatching { socket.close() }
    }

    private class PublicClassicL2capSocketFactory {
        fun create(device: BluetoothDevice, psm: Int): AapSocketHandle? {
            val publicSocket = tryPublicApi(device, psm)
            if (publicSocket != null) return AapSocketHandle(publicSocket, "PUBLIC_CLASSIC_PROBE")

            if (!AapTransportFeatureFlags.hiddenClassicL2capAllowed()) {
                BleScanDiagnostics.logAapFailure(
                    device.address,
                    "hidden_socket_api",
                    "DISABLED_OR_TUPLE_NOT_ALLOWLISTED",
                )
                return null
            }

            return tryHiddenApi(device, psm)?.let { AapSocketHandle(it, "HIDDEN_EXPERIMENTAL") }
        }

        private fun tryPublicApi(device: BluetoothDevice, psm: Int): BluetoothSocket? {
            if (Build.VERSION.SDK_INT < PUBLIC_SOCKET_SETTINGS_API) return null
            return try {
                val settingsClass = Class.forName("android.bluetooth.BluetoothSocketSettings")
                val builderClass = Class.forName("android.bluetooth.BluetoothSocketSettings\$Builder")
                val builder = builderClass.getDeclaredConstructor().newInstance()
                builderClass.getMethod("setSocketType", Int::class.javaPrimitiveType)
                    .invoke(builder, SOCKET_TYPE_L2CAP)
                builderClass.getMethod("setL2capPsm", Int::class.javaPrimitiveType)
                    .invoke(builder, psm)
                builderClass.getMethod("setAuthenticationRequired", Boolean::class.javaPrimitiveType)
                    .invoke(builder, false)
                builderClass.getMethod("setEncryptionRequired", Boolean::class.javaPrimitiveType)
                    .invoke(builder, false)
                val settings = builderClass.getMethod("build").invoke(builder)
                val createMethod = BluetoothDevice::class.java.getMethod(
                    "createUsingSocketSettings",
                    settingsClass,
                )
                createMethod.invoke(device, settings) as BluetoothSocket
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                // Android's public API currently rejects TYPE_L2CAP on this
                // device. Keep the evidence, then try only the allowlisted
                // experimental path below.
                val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
                BleScanDiagnostics.logAapFailure(
                    device.address,
                    "public_socket_api",
                    cause::class.simpleName ?: "REFLECTION_EXCEPTION",
                )
                BleScanDiagnostics.logAapFailureCause(device.address, "public_socket_api", cause)
                null
            }
        }

        @SuppressLint("SoonBlockedPrivateApi")
        private fun tryHiddenApi(device: BluetoothDevice, psm: Int): BluetoothSocket? {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
            val methodResult = runCatching {
                val method = BluetoothDevice::class.java.getDeclaredMethod(
                    "createInsecureL2capSocket",
                    Int::class.javaPrimitiveType,
                )
                method.isAccessible = true
                method.invoke(device, psm) as BluetoothSocket
            }
            methodResult.getOrNull()?.let { socket ->
                BleScanDiagnostics.logAapState(device.address, "TRANSPORT_HIDDEN_EXPERIMENTAL", "psm=0x1001")
                return socket
            }

            methodResult.exceptionOrNull()?.let { error ->
                val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
                BleScanDiagnostics.logAapFailure(
                    device.address,
                    "hidden_socket_api",
                    cause::class.simpleName ?: "REFLECTION_EXCEPTION",
                )
                BleScanDiagnostics.logAapFailureCause(device.address, "hidden_socket_api", cause)
            }

            return tryHiddenConstructors(device, psm)
        }

        private fun tryHiddenConstructors(device: BluetoothDevice, psm: Int): BluetoothSocket? {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            BleScanDiagnostics.logAapSocketConstructors(BluetoothSocket::class.java.declaredConstructors)
            val uuid = ParcelUuid.fromString(AAP_SERVICE_UUID)
            val candidates = listOf(
                arrayOf<Any>(adapter, device, SOCKET_TYPE_L2CAP, true, true, psm, uuid),
                arrayOf<Any>(device, SOCKET_TYPE_L2CAP, true, true, psm, uuid),
                arrayOf<Any>(device, SOCKET_TYPE_L2CAP, 1, true, true, psm, uuid),
                arrayOf<Any>(SOCKET_TYPE_L2CAP, 1, true, true, device, psm, uuid),
                arrayOf<Any>(SOCKET_TYPE_L2CAP, true, true, device, psm, uuid),
            )

            candidates.forEachIndexed { index, values ->
                val result = runCatching {
                    val parameterTypes = values.map(::parameterType).toTypedArray()
                    val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*parameterTypes)
                    constructor.isAccessible = true
                    constructor.newInstance(*values) as BluetoothSocket
                }
                result.getOrNull()?.let { socket ->
                    BleScanDiagnostics.logAapState(
                        device.address,
                        "TRANSPORT_HIDDEN_EXPERIMENTAL",
                        "constructor=${index + 1} psm=0x1001",
                    )
                    return socket
                }
                result.exceptionOrNull()?.let { error ->
                    val cause = (error as? java.lang.reflect.InvocationTargetException)?.cause ?: error
                    BleScanDiagnostics.logAapFailure(
                        device.address,
                        "hidden_constructor_${index + 1}",
                        cause::class.simpleName ?: "REFLECTION_EXCEPTION",
                    )
                }
            }
            return null
        }

        private fun parameterType(value: Any): Class<*> = when (value) {
            is Int -> Int::class.javaPrimitiveType!!
            is Boolean -> Boolean::class.javaPrimitiveType!!
            else -> value.javaClass
        }

        private companion object {
            const val PUBLIC_SOCKET_SETTINGS_API = 36
            const val SOCKET_TYPE_L2CAP = 3
            const val AAP_SERVICE_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
        }
    }

    private data class AapSocketHandle(
        val socket: BluetoothSocket,
        val strategy: String,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val STARTUP_RESPONSE_TIMEOUT_MS = 5_000L
        const val BATTERY_REQUEST_RETRY_DELAY_MS = 2_000L
    }
}

internal data class ClassicAapBatteryEvent(
    val deviceId: String,
    val deviceProfileId: String,
    val model: AirPodsModel,
    val snapshot: AapBatterySnapshot,
    val seenAt: Long,
)

internal data class ClassicAapWearEvent(
    val deviceId: String,
    val deviceProfileId: String,
    val model: AirPodsModel,
    val primaryPodIsLeft: Boolean?,
    val snapshot: AapEarDetectionSnapshot,
    val wearState: com.galaxyairpods.domain.model.AirPodsWearState?,
    val seenAt: Long,
    val capturedAtElapsedMs: Long,
)

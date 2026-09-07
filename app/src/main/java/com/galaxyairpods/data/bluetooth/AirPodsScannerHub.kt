package com.galaxyairpods.data.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.bluetooth.le.ScanSettings
import android.content.Intent

/**
 * There must be one BLE scan per process on Samsung devices. The activity and
 * the foreground monitor service both need the same stream, so they share one
 * scanner instead of starting two independent controller scans.
 */
@SuppressLint("StaticFieldLeak")
object AirPodsScannerHub {
    private val lock = Any()
    private var sharedScanner: AirPodsBleScanner? = null
    private val owners = mutableSetOf<Any>()

    fun acquire(context: Context): AirPodsScannerLease {
        val owner = Any()
        val scanner = synchronized(lock) {
            owners += owner
            sharedScanner ?: AirPodsBleScanner(context.applicationContext).also { sharedScanner = it }
        }
        return AirPodsScannerLease(scanner) {
            release(owner, scanner)
        }
    }

    fun dispatchPendingScanIntent(context: Context, intent: Intent) {
        val scanner = synchronized(lock) {
            sharedScanner ?: AirPodsBleScanner(context.applicationContext).also { sharedScanner = it }
        }
        scanner.dispatchPendingScanIntent(intent)
    }

    private fun release(owner: Any, scanner: AirPodsBleScanner) {
        val shouldStop = synchronized(lock) {
            owners.remove(owner)
            owners.isEmpty() && sharedScanner === scanner
        }
        if (shouldStop) {
            scanner.stop()
            synchronized(lock) {
                if (owners.isEmpty() && sharedScanner === scanner) sharedScanner = null
            }
        }
    }
}

class AirPodsScannerLease internal constructor(
    val scanner: AirPodsBleScanner,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private var closed = false

    fun start(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY) {
        if (!closed) scanner.start(scanMode)
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

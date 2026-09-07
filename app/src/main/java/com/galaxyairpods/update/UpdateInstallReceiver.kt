package com.galaxyairpods.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Bridges PackageInstaller's asynchronous result back to the system installer.
 * Android may require user confirmation even when the app requested package
 * installs; in that case PackageInstaller supplies the confirmation Intent here.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "AirPods Galaxy 업데이트가 완료되었습니다", Toast.LENGTH_LONG).show()
                return
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "설치를 완료하지 못했습니다"
                Toast.makeText(context, "업데이트 실패: $message", Toast.LENGTH_LONG).show()
                return
            }
        }

        val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                Intent.EXTRA_INTENT,
                Intent::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return

        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(confirmationIntent)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.galaxyairpods.UPDATE_INSTALL_STATUS"
    }
}

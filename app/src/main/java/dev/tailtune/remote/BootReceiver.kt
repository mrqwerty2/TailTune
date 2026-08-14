package dev.tailtune.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the user-enabled headless remote after reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS) return
        if (!RemoteServicePreferences.isEnabled(context)) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RemoteServerService::class.java).apply {
                    action = RemoteServerService.ACTION_START_OR_RELOAD
                }
            )
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}

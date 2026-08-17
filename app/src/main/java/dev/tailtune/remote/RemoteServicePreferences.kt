package dev.tailtune.remote

import android.content.Context

/** Persists the user's explicit choice to keep the headless remote running. */
object RemoteServicePreferences {
    private const val PREFS = "tailtune_remote_service"
    private const val KEY_ENABLED = "enabled_v1"

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        // apply() avoids synchronous disk I/O on the service/activity main thread.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

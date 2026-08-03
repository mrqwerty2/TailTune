package dev.tailtune.remote

import android.content.Context

data class ServerSettings(
    val baseUrl: String,
    val username: String,
    val password: String
) {
    val configured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    fun normalizedBaseUrl(): String {
        var value = baseUrl.trim().trimEnd('/')
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        if (value.endsWith("/rest", ignoreCase = true)) {
            value = value.dropLast(5)
        }
        return value.trimEnd('/')
    }

    companion object {
        private const val PREFS = "tailtune_server"
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"

        fun load(context: Context): ServerSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ServerSettings(
                prefs.getString(KEY_URL, "") ?: "",
                prefs.getString(KEY_USER, "") ?: "",
                prefs.getString(KEY_PASSWORD, "") ?: ""
            )
        }

        fun save(context: Context, settings: ServerSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, settings.normalizedBaseUrl())
                .putString(KEY_USER, settings.username.trim())
                .putString(KEY_PASSWORD, settings.password)
                .apply()
        }
    }
}

package dev.tailtune.remote

import android.content.Context
import java.net.URI
import java.util.Locale

data class ServerSettings(
    val baseUrl: String,
    val username: String,
    val password: String
) {
    val configured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val hasAnyValue: Boolean
        get() = baseUrl.isNotBlank() || username.isNotBlank() || password.isNotBlank()

    fun normalizedBaseUrl(): String {
        var value = baseUrl.trim().trimEnd('/')

        val explicitScheme = runCatching {
            URI(value).scheme
        }.getOrNull()

        // Add http:// only when the user supplied no scheme at all.
        if (explicitScheme.isNullOrBlank()) {
            value = "http://$value"
        }

        if (value.endsWith("/rest", ignoreCase = true)) {
            value = value.dropLast(5)
        }

        return value.trimEnd('/')
    }

    fun validationError(): String? {
        if (baseUrl.isBlank()) return "Enter the Navidrome URL."
        if (username.isBlank()) return "Enter the Navidrome username."
        if (password.isBlank()) return "Enter the Navidrome password."
        if (baseUrl.length > MAX_URL_LENGTH) return "The Navidrome URL is too long."
        if (username.length > MAX_USERNAME_LENGTH) return "The Navidrome username is too long."
        if (password.length > MAX_PASSWORD_LENGTH) return "The Navidrome password is too long."
        if (baseUrl.any { it.isISOControl() } || username.any { it.isISOControl() }) {
            return "The Navidrome URL and username cannot contain control characters."
        }

        val normalized = normalizedBaseUrl()
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return "The Navidrome URL is invalid."
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
            return "The Navidrome URL must use http:// or https://."
        }
        if (uri.host.isNullOrBlank()) {
            return "The Navidrome URL must contain a host name or IP address."
        }
        if (uri.userInfo != null) {
            return "Do not put a username or password inside the Navidrome URL."
        }
        if (uri.query != null || uri.fragment != null) {
            return "Do not put a query string or fragment in the Navidrome URL."
        }
        if (uri.port != -1 && uri.port !in 1..65535) return "The Navidrome port is invalid."
        return null
    }

    companion object {
        private const val PREFS = "tailtune_server"
        private const val KEY_URL = "url"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD_ENCRYPTED = "password_encrypted"
        private const val KEY_PASSWORD_LEGACY = "password"
        private const val MAX_URL_LENGTH = 2_048
        private const val MAX_USERNAME_LENGTH = 256
        private const val MAX_PASSWORD_LENGTH = 4_096

        fun load(context: Context): ServerSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encrypted = prefs.getString(KEY_PASSWORD_ENCRYPTED, null)
            val legacy = prefs.getString(KEY_PASSWORD_LEGACY, "").orEmpty()
            val password = when {
                !encrypted.isNullOrBlank() -> runCatching {
                    SecretStore.decrypt(encrypted)
                }.getOrElse { legacy }
                else -> legacy
            }

            val settings = ServerSettings(
                baseUrl = prefs.getString(KEY_URL, "").orEmpty(),
                username = prefs.getString(KEY_USER, "").orEmpty(),
                password = password
            )

            // One-way migration from the old plaintext preference.
            if (legacy.isNotEmpty() && encrypted.isNullOrBlank()) {
                runCatching { save(context, settings) }
            }
            return settings
        }

        fun clear(context: Context) {
            val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_URL)
                .remove(KEY_USER)
                .remove(KEY_PASSWORD_ENCRYPTED)
                .remove(KEY_PASSWORD_LEGACY)
                .commit()
            check(committed) { "Android could not clear the Navidrome settings" }
        }

        fun save(context: Context, settings: ServerSettings) {
            settings.validationError()?.let { throw IllegalArgumentException(it) }
            val encryptedPassword = SecretStore.encrypt(settings.password)
            val committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, settings.normalizedBaseUrl())
                .putString(KEY_USER, settings.username.trim())
                .putString(KEY_PASSWORD_ENCRYPTED, encryptedPassword)
                .remove(KEY_PASSWORD_LEGACY)
                .commit()
            check(committed) { "Android could not save the Navidrome settings" }
        }
    }
}

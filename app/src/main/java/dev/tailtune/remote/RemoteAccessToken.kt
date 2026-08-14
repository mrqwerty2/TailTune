package dev.tailtune.remote

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Shared secret used by TailTune remote clients. */
object RemoteAccessToken {
    private const val PREFS = "tailtune_remote_access"
    private const val KEY_TOKEN = "token_v1"
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{32}")

    @Synchronized
    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)
            ?.takeIf { TOKEN_PATTERN.matches(it) }
            ?.let { return it }

        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(
            bytes,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
        )
        check(prefs.edit().putString(KEY_TOKEN, token).commit()) {
            "Android could not save the remote-access token"
        }
        return token
    }

    fun matches(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val expected = getOrCreate(context).toByteArray(Charsets.UTF_8)
        val supplied = candidate.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, supplied)
    }
}

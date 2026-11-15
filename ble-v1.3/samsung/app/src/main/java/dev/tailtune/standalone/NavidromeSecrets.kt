package dev.tailtune.standalone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class NavidromeSettings(val url: String, val username: String, val password: String)

/** Secrets encrypted with a non-exportable Android Keystore AES key; backups disabled in the manifest. */
class NavidromeSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("navidrome_settings", Context.MODE_PRIVATE)
    private val alias = "tailtune_nav_v1"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    fun save(config: NavidromeSettings) {
        val normalized = config.copy(url = NavidromeWire.normalizeBase(config.url))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val json = JSONObject().put("url", normalized.url)
            .put("user", normalized.username).put("pass", normalized.password).toString()
        val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("secret", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
    }

    fun load(): NavidromeSettings? {
        val iv = prefs.getString("iv", null) ?: return null
        val secret = prefs.getString("secret", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
            val obj = JSONObject(String(cipher.doFinal(Base64.decode(secret, Base64.DEFAULT)), Charsets.UTF_8))
            NavidromeSettings(obj.getString("url"), obj.getString("user"), obj.getString("pass"))
        } catch (_: Exception) { null } // E.g. Keystore key was invalidated after a system reset.
    }

    fun clear() { prefs.edit().clear().apply() }
}

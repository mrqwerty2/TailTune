package dev.tailtune.standalone

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Pure JVM request construction. No Android dependencies, so it can be regression tested on Linux. */
object NavidromeWire {
    private val rng = SecureRandom()

    fun normalizeBase(raw: String): String {
        val uri = URI(raw.trim())
        require(uri.scheme?.lowercase() in setOf("http", "https")) { "Use http:// or https://" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Enter a server address without credentials, query or fragment"
        }
        require(uri.port in -1..65535 && uri.port != 0) { "Invalid port" }
        if (uri.scheme.equals("http", true)) require(isPrivateHost(uri.host)) {
            "Unencrypted HTTP is allowed only for private LAN/Tailscale addresses. Use HTTPS for public servers."
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return URI(uri.scheme.lowercase(), null, uri.host, uri.port,
            (if (path.endsWith("/rest")) path.dropLast(5) else path).ifEmpty { null },
            null, null).toASCIIString().trimEnd('/')
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", true) || host.endsWith(".local", true) || host.endsWith(".home.arpa", true)) return true
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val a = octets[0]!!; val b = octets[1]!!
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
    }

    fun token(password: String, salt: String): String = MessageDigest.getInstance("MD5")
        .digest((password + salt).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun randomSalt(): String = ByteArray(12).also { rng.nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    fun url(base: String, operation: String, username: String, password: String,
            options: Map<String, String> = emptyMap(), salt: String = randomSalt()): String {
        require(operation.matches(Regex("[a-zA-Z0-9]+"))) { "Invalid API operation" }
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter Navidrome login credentials" }
        require(salt.matches(Regex("[a-f0-9]{24}"))) { "Invalid salt" }
        val params = linkedMapOf("u" to username, "t" to token(password, salt), "s" to salt,
            "v" to "1.16.1", "c" to "TailTuneSamsung", "f" to "json")
        require(options.keys.none { it in params }) { "Cannot override authentication fields" }
        params.putAll(options)
        return normalizeBase(base) + "/rest/$operation.view?" +
            params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }
}

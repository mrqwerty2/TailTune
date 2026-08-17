package dev.tailtune.remote

object NetworkAddressUtils {
    fun isPrivateLan(ip: String): Boolean {
        val octets = parseIpv4(ip) ?: return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun isTailscale(ip: String): Boolean {
        val octets = parseIpv4(ip) ?: return false
        // Tailscale IPv4 addresses are allocated from RFC 6598 100.64.0.0/10.
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val number = parts[index].toIntOrNull() ?: return null
            if (number !in 0..255) return null
            octets[index] = number
        }
        return octets
    }
}

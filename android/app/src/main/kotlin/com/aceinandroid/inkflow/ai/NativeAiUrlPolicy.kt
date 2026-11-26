package com.aceinandroid.inkflow.ai

import java.net.URI

/** Transport policy for explicitly configured endpoints; never resolves hostnames. */
internal object NativeAiUrlPolicy {
    private val decimalOctet = Regex("0|[1-9][0-9]{0,2}")
    private val ipv6Loopback = Regex("[0:]*:0{0,3}1")

    fun isAllowed(url: URI): Boolean {
        if (url.scheme.equals("https", ignoreCase = true)) return true
        if (!url.scheme.equals("http", ignoreCase = true)) return false
        val host = url.host ?: return false
        if (host.equals("localhost", ignoreCase = true)) return true
        // URI validates IPv6 syntax; match only compressed or expanded loopback.
        if (host.startsWith("[")) return ipv6Loopback.matches(host.removeSurrounding("[", "]"))
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { !decimalOctet.matches(it) }) return false
        val octets = parts.map { it.toInt() }
        if (octets.any { it > 255 }) return false
        return octets[0] == 127 || octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}

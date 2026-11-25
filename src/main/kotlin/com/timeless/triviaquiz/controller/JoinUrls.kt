package com.timeless.triviaquiz.controller

import jakarta.servlet.http.HttpServletRequest
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The addresses players should type in. The host loads this page from the same server, so the
 * port on the incoming request is the right one; only the host part needs replacing, because
 * "localhost" means the phone itself. A laptop on Wi-Fi usually has one site-local IPv4, but
 * VPNs and Docker bridges can add more, so all candidates are listed rather than guessed at.
 *
 * [publicUrl] (`trivia.public-url`, e.g. the Cloudflare tunnel's address) comes first when set,
 * for players who aren't on the same Wi-Fi.
 */
internal fun joinUrls(request: HttpServletRequest, code: String, publicUrl: String?): List<String> {
    val port = request.serverPort
    val online = publicUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { "$it/j/$code" }
    return listOfNotNull(online) + lanAddresses().map { "http://$it:$port/j/$code" }
}

private fun lanAddresses(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())

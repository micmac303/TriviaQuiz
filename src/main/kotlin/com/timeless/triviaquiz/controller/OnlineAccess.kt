package com.timeless.triviaquiz.controller

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Headers a proxy adds. Cloudflare Tunnel always sends the Cf- ones. Tomcat consumes
 * X-Forwarded-For (server.forward-headers-strategy=native), so the address check below is what
 * catches it then; the header is listed too in case that setting is ever removed.
 */
private val PROXY_HEADERS = listOf("Cf-Connecting-Ip", "Cf-Ray", "X-Forwarded-For", "X-Forwarded-Host", "Forwarded")

/**
 * True for a request that came from the internet rather than the LAN or this computer: either it
 * passed through a proxy such as the Cloudflare tunnel, or its address is a public one.
 *
 * The tunnel runs on this computer, so without this check every internet request would look like
 * it came from localhost.
 */
internal fun isFromInternet(request: HttpServletRequest): Boolean {
    if (PROXY_HEADERS.any { request.getHeader(it) != null }) return true
    val address = runCatching { InetAddress.getByName(request.remoteAddr) }.getOrNull() ?: return true
    return !isPrivate(address)
}

private fun isPrivate(address: InetAddress): Boolean =
    address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC) // fc00::/7

/**
 * Over the internet only the player side of the networked game is reachable: joining, playing,
 * the room API and static files. Hosting, theme management, single-player, hotseat, and anything
 * else (such as the H2 console devtools turns on) answer 404.
 */
@Component
class OnlineAccessFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!isFromInternet(request) || isPlayerPath(request.requestURI.removePrefix(request.contextPath))) {
            chain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_NOT_FOUND
        response.contentType = "text/html;charset=UTF-8"
        response.writer.write(
            "<!doctype html><meta name=viewport content=\"width=device-width\"><title>Trivia</title>" +
                "<p>This page isn't available online. To play, open the join link the host sent you.</p>"
        )
    }

    private fun isPlayerPath(path: String): Boolean =
        path.startsWith("/j/") ||
            path == "/play" || path == "/play/leave" ||
            path.startsWith("/api/room/") ||
            path.startsWith("/css/") || path.startsWith("/js/") ||
            path == "/favicon.ico"
}

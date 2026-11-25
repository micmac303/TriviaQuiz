package com.timeless.triviaquiz.controller

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Requests through the Cloudflare tunnel arrive from localhost with proxy headers. They must only
 * reach the player pages, never hosting or the theme files.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnlineAccessIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    private val http = RestTemplate(
        object : SimpleClientHttpRequestFactory() {
            override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                super.prepareConnection(connection, httpMethod)
                connection.instanceFollowRedirects = false
            }
        }
    ).apply {
        errorHandler = object : ResponseErrorHandler {
            override fun hasError(response: ClientHttpResponse) = false
            override fun handleError(response: ClientHttpResponse) = Unit
        }
    }

    private val viaTunnel = mapOf("Cf-Connecting-Ip" to "203.0.113.5", "X-Forwarded-For" to "203.0.113.5")

    private fun request(method: HttpMethod, path: String, headers: Map<String, String> = emptyMap()): ResponseEntity<String> {
        val httpHeaders = HttpHeaders().apply { headers.forEach { (k, v) -> add(k, v) } }
        return http.exchange("http://localhost:$port$path", method, HttpEntity<Any>(httpHeaders), String::class.java)
    }

    @Test
    fun `host pages are refused through the tunnel`() {
        for (path in listOf("/host", "/party/host", "/", "/game", "/multiplayer", "/questions", "/h2-console")) {
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, path, viaTunnel).statusCode, path)
        }
    }

    @Test
    fun `theme admin actions are refused through the tunnel`() {
        for (path in listOf("/party/host/reset-used", "/party/host/remove-theme", "/party/host/kick", "/host/start")) {
            assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST, path, viaTunnel).statusCode, path)
        }
    }

    @Test
    fun `a forwarded public address alone is enough to count as online`() {
        // Tomcat consumes X-Forwarded-For, so this relies on the rewritten client address.
        val response = request(HttpMethod.GET, "/host", mapOf("X-Forwarded-For" to "198.51.100.7"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `player pages work through the tunnel`() {
        val join = request(HttpMethod.GET, "/j/ZZZZ", viaTunnel)
        assertEquals(HttpStatus.OK, join.statusCode)
        assertTrue(join.body.orEmpty().contains("No room with that code"), join.body.orEmpty())

        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/css/styles.css", viaTunnel).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/room/ZZZZ/state", viaTunnel).statusCode)
    }

    @Test
    fun `the host page still works from this computer`() {
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/host").statusCode)
    }
}

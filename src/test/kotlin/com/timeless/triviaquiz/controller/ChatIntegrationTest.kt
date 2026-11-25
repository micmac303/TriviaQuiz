package com.timeless.triviaquiz.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.timeless.triviaquiz.service.TriviaCategory
import com.timeless.triviaquiz.service.TriviaQuestionService
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.springframework.web.reactive.function.client.WebClient
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The room chat over real HTTP, each cookie jar a separate device. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChatIntegrationTest.StubbedCategories::class)
class ChatIntegrationTest {

    @TestConfiguration
    class StubbedCategories {
        @Bean
        @Primary
        fun stubbedTriviaQuestionService(webClient: WebClient): TriviaQuestionService =
            object : TriviaQuestionService(webClient) {
                override suspend fun getCategories(): List<TriviaCategory> =
                    (1..10).map { TriviaCategory(it, "Category $it") }
            }
    }

    @LocalServerPort
    private var port: Int = 0

    private inner class Device {
        private var cookie: String? = null

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

        fun get(path: String): ResponseEntity<String> = exchange(path, HttpMethod.GET, HttpEntity<Any>(headers()))

        fun postForm(path: String, form: Map<String, List<String>>): ResponseEntity<String> {
            val headers = headers().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
            val body = LinkedMultiValueMap<String, String>()
            form.forEach { (key, values) -> values.forEach { body.add(key, it) } }
            return exchange(path, HttpMethod.POST, HttpEntity(body, headers))
        }

        fun postJson(path: String, json: String): ResponseEntity<String> {
            val headers = headers().apply { contentType = MediaType.APPLICATION_JSON }
            return exchange(path, HttpMethod.POST, HttpEntity(json, headers))
        }

        private fun headers() = HttpHeaders().apply { cookie?.let { add(HttpHeaders.COOKIE, it) } }

        private fun exchange(path: String, method: HttpMethod, entity: HttpEntity<*>): ResponseEntity<String> {
            val response = http.exchange("http://localhost:$port$path", method, entity, String::class.java)
            response.headers[HttpHeaders.SET_COOKIE]
                ?.firstOrNull { it.startsWith("JSESSIONID") }
                ?.let { cookie = it.substringBefore(';') }
            return response
        }
    }

    private fun hostNewRoom(host: Device): String {
        val body = host.get("/host").body.orEmpty()
        val code = Regex("Room code <strong>([A-Z0-9]{4})</strong>").find(body)?.groupValues?.get(1)
        assertNotNull(code, "host page should show a room code")
        return code
    }

    private fun join(device: Device, code: String, name: String) {
        val response = device.postForm(
            "/j/$code",
            mapOf("name" to listOf(name), "categories" to (1..6).map { it.toString() })
        )
        assertTrue(response.statusCode.is3xxRedirection, "join failed: ${response.statusCode}")
    }

    private fun say(device: Device, code: String, text: String) =
        device.postJson("/api/room/$code/chat", """{"text":"$text"}""")

    @Test
    fun `players and the host see each other's messages`() {
        val host = Device()
        val code = hostNewRoom(host)
        val ann = Device()
        val bob = Device()
        join(ann, code, "Ann")
        join(bob, code, "Bob")

        assertEquals(HttpStatus.OK, say(ann, code, "hi all").statusCode)
        assertEquals(HttpStatus.OK, say(host, code, "starting soon").statusCode)

        val bobView = bob.get("/api/room/$code/chat").body.orEmpty()
        assertTrue(bobView.contains(""""author":"Ann","text":"hi all""""), bobView)
        assertTrue(bobView.contains(""""author":"Host","text":"starting soon""""), bobView)
        assertTrue(bobView.contains(""""mine":false,"host":true"""), bobView)
        assertTrue(!bobView.contains(""""mine":true"""), "Bob sent nothing: $bobView")

        val annView = ann.get("/api/room/$code/chat").body.orEmpty()
        assertTrue(annView.contains(""""text":"hi all","sentAt":""") && annView.contains(""""mine":true"""), annView)
    }

    @Test
    fun `only newer messages come back after an id`() {
        val host = Device()
        val code = hostNewRoom(host)
        say(host, code, "first")
        val firstId = Regex(""""id":(\d+)""").find(host.get("/api/room/$code/chat").body.orEmpty())!!.groupValues[1]
        Thread.sleep(700) // The per-sender rate limit.
        say(host, code, "second")

        val view = host.get("/api/room/$code/chat?after=$firstId").body.orEmpty()

        assertTrue(view.contains("second") && !view.contains("first"), view)
    }

    @Test
    fun `someone not in the room can't read or post`() {
        val code = hostNewRoom(Device())
        val stranger = Device()

        assertEquals(HttpStatus.FORBIDDEN, stranger.get("/api/room/$code/chat").statusCode)
        assertEquals(HttpStatus.FORBIDDEN, say(stranger, code, "let me in").statusCode)
    }

    @Test
    fun `an overlong message is refused with a reason`() {
        val host = Device()
        val code = hostNewRoom(host)

        val response = say(host, code, "x".repeat(201))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body.orEmpty().contains("under 200 characters"), response.body.orEmpty())
    }

    @Test
    fun `a reaction shows up in everyone's chat`() {
        val host = Device()
        val code = hostNewRoom(host)
        val ann = Device()
        join(ann, code, "Ann")

        val sent = ann.postJson("/api/room/$code/chat/react", """{"emoji":"👏"}""")
        assertEquals(HttpStatus.OK, sent.statusCode, sent.body)

        // Parsed, as Jackson writes emoji as \u escapes.
        val view = ObjectMapper().readTree(host.get("/api/room/$code/chat").body.orEmpty())
        val reaction = view["messages"].single()
        assertEquals("Ann", reaction["author"].asText())
        assertEquals("👏", reaction["text"].asText())
        assertTrue(reaction["reaction"].asBoolean(), view.toString())
        assertTrue(view["reactions"].map { it.asText() }.contains("👏"), view.toString())
    }

    @Test
    fun `an unlisted reaction is refused`() {
        val host = Device()
        val code = hostNewRoom(host)

        val response = host.postJson("/api/room/$code/chat/react", """{"emoji":"spam spam spam"}""")

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `a closed room has no chat`() {
        assertEquals(HttpStatus.NOT_FOUND, Device().get("/api/room/ZZZZ/chat").statusCode)
    }
}

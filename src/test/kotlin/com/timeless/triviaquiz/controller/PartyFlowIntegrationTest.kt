package com.timeless.triviaquiz.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.timeless.triviaquiz.game.THEMES_OFFERED
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.ui.ExtendedModelMap
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Theme Party over real HTTP, one cookie jar per device. The themes folder is a temporary one, as
 * playing writes to it: these tests must never touch the real themes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PartyFlowIntegrationTest {

    companion object {
        private val mapper = ObjectMapper()

        private val themesDir: Path = Files.createTempDirectory("party-themes").also { dir ->
            val questions = listOf("easy", "medium", "hard").flatMap { difficulty ->
                (1..20).map {
                    mapOf(
                        "difficulty" to difficulty,
                        "question" to "$difficulty question $it",
                        "correct_answer" to "right",
                        "incorrect_answers" to listOf("wrong"),
                        "explanation" to "Because."
                    )
                }
            }
            dir.resolve("test.json")
                .writeText(mapper.writeValueAsString(mapOf("theme" to "Test", "questions" to questions)))
            dir.toFile().deleteOnExit()
        }

        @JvmStatic
        @DynamicPropertySource
        fun themes(registry: DynamicPropertyRegistry) {
            registry.add("trivia.themes-dir") { themesDir.toString() }
            // No theme flash, so answers can follow the pick straight away. PartyViewsTest covers it.
            registry.add("trivia.party.theme-flash-ms") { "0" }
            // Likewise Next can follow a reveal straight away. ThemePartyGameTest covers the hold.
            registry.add("trivia.party.reveal-hold-ms") { "0" }
        }
    }

    @Autowired
    private lateinit var hostController: PartyHostController

    @LocalServerPort
    private var port: Int = 0

    private inner class Device {
        private val http = HttpClient.newBuilder().cookieHandler(CookieManager()).build()

        fun get(path: String): HttpResponse<String> = send(request(path).GET())

        fun form(path: String, body: String = ""): HttpResponse<String> = send(
            request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        )

        fun act(code: String, action: String, body: Map<String, Any>): Int = send(
            request("/api/room/$code/party/$action")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        ).statusCode()

        fun state(code: String): JsonNode = mapper.readTree(get("/api/room/$code/state").body())

        private fun request(path: String) = HttpRequest.newBuilder(URI("http://localhost:$port$path"))

        private fun send(builder: HttpRequest.Builder): HttpResponse<String> =
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private inner class Party(playerCount: Int, start: Boolean = true) {
        val host = Device()
        val code: String =
            Regex("Room code <strong>(\\w{4})</strong>").find(host.get("/party/host").body())!!.groupValues[1]
        val players = List(playerCount) { index ->
            Device().also { it.form("/j/$code", "name=P$index") }
        }

        init {
            if (start) host.form("/party/host/start")
        }

        fun party(device: Device = host): JsonNode = device.state(code).path("party")

        fun version(): Long = host.state(code).path("version").asLong()

        fun idOf(index: Int): String = party().path("players")[index].path("id").asText()

        fun pick(picker: Device): Int =
            picker.act(code, "pick", mapOf("version" to version(), "theme" to "test.json"))

        fun answer(device: Device, answer: String = "right", version: Long = version()): Int =
            device.act(code, "answer", mapOf("version" to version, "answer" to answer))

        fun next(device: Device, version: Long = version()): Int =
            device.act(code, "next", mapOf("version" to version))

        fun kick(index: Int): Int = host.form("/party/host/kick", "playerId=${idOf(index)}").statusCode()
    }

    private fun isMarkedUsed(question: String): Boolean =
        mapper.readTree(themesDir.resolve("test.json").readText()).path("questions")
            .single { it.path("question").asText() == question }
            .path("used").asBoolean()

    @Test
    fun `one question is shown, without its answer, and marked used at the reveal`() {
        val room = Party(2)
        val (ann, bob) = room.players
        assertEquals(200, room.pick(ann))

        val answering = room.party(bob)
        val question = answering.path("question").path("question").asText()
        assertTrue(question.startsWith("medium"), "no easy by default")
        assertTrue(answering.path("reveal").isNull)
        assertFalse("correctAnswer" in answering.toString() || "Because." in answering.toString())

        room.answer(ann)
        assertFalse(isMarkedUsed(question), "not used until everyone has answered")
        room.answer(bob, "wrong")

        assertTrue(isMarkedUsed(question))
        assertEquals("right", room.party(bob).path("reveal").path("correctAnswer").asText())
        assertEquals(2 + 1, room.party().path("players")[0].path("score").asInt(), "Ann was first")
        val reveal = room.party(bob).path("reveal")
        assertEquals("P0", reveal.path("firstCorrect").asText())
        assertTrue(room.party(bob).path("players")[0].path("first").asBoolean())
    }

    @Test
    fun `simultaneous answers from the same version are both accepted`() {
        val room = Party(2)
        room.pick(room.players[0])
        val version = room.version()

        assertEquals(200, room.answer(room.players[0], version = version))
        assertEquals(200, room.answer(room.players[1], version = version))
    }

    @Test
    fun `any player's first tap on next wins and the rest are ignored`() {
        val room = Party(2)
        val (ann, bob) = room.players
        room.pick(ann)
        room.players.forEach { room.answer(it) }
        val version = room.version()

        assertEquals(409, room.next(room.host, version), "the host isn't a player")
        assertEquals(200, room.next(bob, version))
        assertEquals(409, room.next(ann, version))
        assertEquals("P1", room.party().path("picker").asText())
    }

    @Test
    fun `a tap left over from the previous question is rejected`() {
        val room = Party(2)
        val (ann, bob) = room.players
        room.pick(ann)
        val oldVersion = room.version()
        room.players.forEach { room.answer(it) }
        room.next(ann)
        room.pick(bob)

        assertEquals(409, room.answer(ann, version = oldVersion))
    }

    @Test
    fun `a game runs six turns each`() {
        val room = Party(2)

        repeat(12) { turn ->
            assertEquals(200, room.pick(room.players[turn % 2]), "turn $turn")
            room.players.forEach { assertEquals(200, room.answer(it)) }
            assertEquals(200, room.next(room.players[0]))
        }

        assertEquals("finished", room.party().path("stage").asText())
        // Both right every time: six picks of two less one, and six guesses of one. P0 always
        // answers first, so also gets the first-correct point all twelve turns. And twelve in a
        // row is a streak point each from the 3rd on: ten each.
        assertEquals(listOf(12 + 12 + 10, 12 + 10), room.party().path("players").map { it.path("score").asInt() })
    }

    @Test
    fun `the host chooses how many rounds`() {
        val room = Party(2, start = false)
        fun selected() = Regex("<option value=\"(\\d+)\" selected")
            .findAll(room.host.get("/party/host").body()).map { it.groupValues[1].toInt() }.toList()
        assertEquals(6, selected().sum())

        room.host.form("/party/host/refresh", "rounds=999&rounds=-4&rounds=5")
        assertEquals(listOf(20, 0, 5), selected(), "kept in range")

        room.host.form("/party/host/start", "rounds=0&rounds=0&rounds=0")
        assertEquals("lobby", room.party().path("stage").asText())
        assertTrue("at least one round" in room.host.state(room.code).path("notice").asText())

        room.host.form("/party/host/start", "rounds=0&rounds=0&rounds=1")
        room.host.form("/party/host/refresh", "rounds=9&rounds=9&rounds=9")
        assertEquals(listOf(0, 0, 1), selected(), "locked once started")
        assertEquals(1, room.party().path("rounds").asInt())

        repeat(2) { turn ->
            room.pick(room.players[turn])
            room.players.forEach { room.answer(it) }
            room.next(room.players[0])
        }

        assertEquals("finished", room.party().path("stage").asText())
        room.host.form("/party/host/reset")
        assertEquals(listOf(0, 0, 1), selected(), "remembered for the next game")
    }

    @Test
    fun `the picker can flag a theme once, and only the host page shows the count`() {
        val file = themesDir.resolve("test.json")
        val original = file.readText()
        try {
            val room = Party(2)
            val (ann, bob) = room.players

            assertEquals(409, bob.act(room.code, "flag", mapOf("theme" to "test.json")), "only the picker")
            assertEquals(200, ann.act(room.code, "flag", mapOf("theme" to "test.json")))
            assertEquals(409, ann.act(room.code, "flag", mapOf("theme" to "test.json")), "once each")
            assertEquals(409, ann.act(room.code, "flag", mapOf("theme" to "../test.json")))

            assertEquals(1, mapper.readTree(file.readText()).path("flags").asInt())
            assertTrue(room.party(ann).path("themes")[0].path("flagged").asBoolean())
            assertFalse("flags" in room.party(bob).toString(), "players never see the count")
            assertTrue("flagged 1 time" in room.host.get("/party/host").body())
            assertEquals(200, room.pick(ann), "a flagged theme can still be picked")
        } finally {
            file.writeText(original)
        }
    }

    @Test
    fun `a hidden theme isn't offered or accepted, and can be shown again`() {
        val other = themesDir.resolve("other.json")
        other.writeText(themesDir.resolve("test.json").readText())
        try {
            val room = Party(2, start = false)
            val ann = room.players[0]
            room.host.form("/party/host/hide-theme", "theme=test.json")
            assertTrue("<strong id=\"hidden-count\">1 hidden.</strong>" in room.host.get("/party/host").body())

            room.host.form("/party/host/start", "limitThemes=true")
            assertEquals(listOf("other.json"), room.party(ann).path("themes").map { it.path("id").asText() })
            assertEquals(409, room.pick(ann), "test.json is hidden")

            room.host.form("/party/host/hide-theme", "theme=test.json") // Mid-game, shown again.
            assertTrue("test.json" in room.party(ann).path("themes").map { it.path("id").asText() })
            assertEquals(200, room.pick(ann))
        } finally {
            Files.deleteIfExists(other)
        }
    }

    @Test
    fun `the host page's script can hide and show a theme without a reload`() {
        val room = Party(2, start = false)

        val hidden = mapper.readTree(room.host.form("/party/host/theme-visibility", "theme=test.json").body())
        assertEquals(true, hidden.path("hidden").asBoolean())
        assertEquals(1, hidden.path("hiddenCount").asInt())

        val shown = mapper.readTree(room.host.form("/party/host/theme-visibility", "theme=test.json").body())
        assertEquals(false, shown.path("hidden").asBoolean())
        assertEquals(0, shown.path("hiddenCount").asInt())

        assertEquals(404, room.host.form("/party/host/theme-visibility", "theme=../test.json").statusCode())
        assertEquals(404, room.players[0].form("/party/host/theme-visibility", "theme=test.json").statusCode(),
            "only the room's host")
    }

    @Test
    fun `a game can't start with every theme hidden`() {
        val room = Party(2, start = false)
        room.host.form("/party/host/hide-theme", "theme=test.json")

        room.host.form("/party/host/start")

        assertEquals("lobby", room.party().path("stage").asText())
        // The notice reaches the screen through the polled state, which room.js draws.
        assertTrue("isn't hidden" in room.host.state(room.code).path("notice").asText())
    }

    @Test
    fun `the theme limit is on by default and enforced by the server`() {
        val ids = (1..THEMES_OFFERED + 2).map { "limit$it.json" } // More than are offered.
        ids.forEach { themesDir.resolve(it).writeText(themesDir.resolve("test.json").readText()) }
        try {
            val room = Party(2, start = false)
            assertTrue("name=\"limitThemes\" value=\"true\" checked" in room.host.get("/party/host").body())
            room.host.form("/party/host/start", "limitThemes=true")
            val picker = room.players[0]

            val offered = room.party(picker).path("themes").map { it.path("id").asText() }
            assertEquals(THEMES_OFFERED, offered.size)
            assertEquals(offered, room.party(picker).path("themes").map { it.path("id").asText() })
            assertTrue(room.party(room.players[1]).path("themes").isNull, "only the picker sees them")

            val other = (ids + "test.json").first { it !in offered }
            assertEquals(409, picker.act(room.code, "pick", mapOf("version" to room.version(), "theme" to other)))
            assertEquals(200, picker.act(room.code, "pick", mapOf("version" to room.version(), "theme" to offered[0])))
        } finally {
            ids.forEach { Files.deleteIfExists(themesDir.resolve(it)) }
        }
    }

    @Test
    fun `the host can remove a theme`() {
        val extra = themesDir.resolve("extra.json")
        extra.writeText(themesDir.resolve("test.json").readText())
        val room = Party(2, start = false)
        assertTrue("value=\"extra.json\"" in room.host.get("/party/host").body())

        room.host.form("/party/host/remove-theme", "theme=extra.json")

        assertTrue(Files.notExists(extra))
        assertFalse("value=\"extra.json\"" in room.host.get("/party/host").body())
    }

    @Test
    fun `a phone hosting its own room can't reset or remove themes`() {
        val room = Party(2)
        room.pick(room.players[0])
        val question = room.party().path("question").path("question").asText()
        room.players.forEach { room.answer(it) }

        val phone = MockHttpServletRequest().apply {
            remoteAddr = "192.168.1.50"
            localAddr = "192.168.1.10"
        }
        val session = MockHttpSession()
        val model = ExtendedModelMap()
        hostController.host(model, session, phone)
        runBlocking {
            hostController.resetUsed(false, false, false, null, session, phone)
            hostController.removeTheme("test.json", false, false, false, null, session, phone)
        }

        assertEquals(false, model["onHostComputer"])
        assertTrue(isMarkedUsed(question))
    }

    @Test
    fun `the host's computer is offered reset`() {
        assertTrue("/party/host/reset-used" in Party(2).host.get("/party/host").body())
    }

    @Test
    fun `leaving as the last one to answer reveals and marks the question used`() {
        val room = Party(3)
        room.pick(room.players[0])
        val question = room.party().path("question").path("question").asText()
        room.answer(room.players[0])
        room.answer(room.players[1])

        room.players[2].form("/play/leave")

        assertEquals("reveal", room.party().path("stage").asText())
        assertEquals(2, room.party().path("players").size())
        assertTrue(isMarkedUsed(question))
    }

    @Test
    fun `leaving on your turn passes it on`() {
        val room = Party(3)

        room.players[0].form("/play/leave")

        assertEquals("P1", room.party().path("picker").asText())
    }

    @Test
    fun `someone who leaves a finished game isn't in the next one`() {
        val room = Party(3)
        room.players[0].form("/play/leave")
        room.players[1].form("/play/leave") // One left, so the game is over.
        room.players[2].form("/play/leave")
        assertEquals(1, room.party().path("players").size(), "final scores stay up")

        room.host.form("/party/host/reset")

        assertEquals(0, room.party().path("players").size())
    }

    @Test
    fun `who was right is only sent at the reveal`() {
        val room = Party(2)
        val (ann, bob) = room.players
        room.pick(ann)
        room.answer(ann)

        for (device in listOf(ann, bob, room.host)) {
            assertTrue(room.party(device).path("players").all { it.path("correct").isNull })
        }
        room.answer(bob, "wrong")

        assertEquals(listOf(true, false), room.party().path("players").map { it.path("correct").asBoolean() })
    }

    @Test
    fun `the time limit setting starts a clock that reveals when it runs out`() {
        val room = Party(2, start = false)
        room.host.form("/party/host/start", "timeLimit=true")
        room.pick(room.players[0])
        val answering = room.party()
        val question = answering.path("question").path("question").asText()
        assertEquals(8000, answering.path("millisAllowed").asInt())

        Thread.sleep(answering.path("millisLeft").asLong() + 200)

        assertEquals("reveal", room.party().path("stage").asText())
        assertTrue(isMarkedUsed(question))
    }

    @Test
    fun `only the room's host can kick`() {
        val room = Party(2)
        val otherHost = Device().also { it.get("/party/host") }
        val body = "playerId=${room.idOf(1)}"

        assertEquals(404, room.players[0].form("/party/host/kick", body).statusCode())
        assertEquals(404, otherHost.form("/party/host/kick", body).statusCode())
        assertEquals(2, room.party().path("players").size())
    }

    @Test
    fun `kicking whoever everyone is waiting for reveals and marks the question used`() {
        val room = Party(3)
        room.pick(room.players[0])
        val question = room.party().path("question").path("question").asText()
        room.answer(room.players[0])
        room.answer(room.players[1])

        assertEquals(204, room.kick(2))

        assertEquals("reveal", room.party().path("stage").asText())
        assertTrue(isMarkedUsed(question))
    }

    @Test
    fun `a player kicked mid-game is told and can't act or rejoin`() {
        val room = Party(3)
        val kicked = room.players[0]
        room.kick(0)

        assertTrue(room.party(kicked).path("removed").asBoolean())
        assertEquals(409, room.pick(kicked))
        val page = kicked.get("/j/${room.code}").body()
        assertTrue("removed by the host" in page, page)
        assertFalse("name=\"name\"" in page, page)
    }

    @Test
    fun `a player kicked from the lobby is told and can rejoin`() {
        val room = Party(2, start = false)
        val kicked = room.players[0]
        room.kick(0)

        val page = kicked.get("/j/${room.code}").body()
        assertTrue("removed by the host" in page, page)
        assertTrue("name=\"name\"" in page, page)

        kicked.form("/j/${room.code}", "name=Again")
        assertFalse(room.party(kicked).path("removed").asBoolean())
        assertEquals(2, room.party().path("players").size())
    }
}

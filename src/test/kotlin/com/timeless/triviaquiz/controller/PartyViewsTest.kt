package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.LobbyPlayer
import com.timeless.triviaquiz.game.PartyPlayer
import com.timeless.triviaquiz.game.RoomMode
import com.timeless.triviaquiz.game.THEME_QUESTION_SPLIT
import com.timeless.triviaquiz.game.Theme
import com.timeless.triviaquiz.game.ThemePartyGame
import com.timeless.triviaquiz.game.ThemeQuestion
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What devices are sent about a Theme Party question (no Spring, no network). */
class PartyViewsTest {

    private val start = Instant.parse("2026-01-01T12:00:00Z")

    private val theme = Theme(
        id = "t.json",
        name = "Space",
        questions = THEME_QUESTION_SPLIT.keys.flatMap { difficulty ->
            (1..3).map { ThemeQuestion(difficulty, "$difficulty $it", "right", listOf("wrong"), "Because.") }
        }
    )

    private fun roomWithPick(): GameRoom = GameRoom("ABCD", RoomMode.THEME_PARTY).apply {
        listOf("a", "b").forEach { lobby.add(LobbyPlayer(it, it.uppercase(), emptyList())) }
        party = ThemePartyGame(
            lobby.map { PartyPlayer(it.playerId, it.name) }, false, themeFlash = Duration.ofSeconds(1)
        ).also { it.pickTheme("a", theme, start) }
    }

    @Test
    fun `during the theme flash only the theme is sent, not the question`() {
        val view = buildPartyView(roomWithPick(), "b", emptyList(), start.plusMillis(400))

        assertEquals("answering", view.stage)
        assertEquals("Space", view.theme)
        assertNull(view.question)
        assertEquals(600, view.millisUntilQuestion)
    }

    @Test
    fun `the lobby sends the scoring guide with the host's current settings`() {
        val room = GameRoom("ABCD", RoomMode.THEME_PARTY).apply { bonusNeedsSecond = true }

        val rules = buildPartyView(room, null, emptyList(), start).rules

        assertNotNull(rules)
        assertEquals(true, rules.bonusNeedsSecond)
        assertNull(buildPartyView(roomWithPick(), "b", emptyList(), start).rules, "only in the lobby")
    }

    @Test
    fun `after the flash the question is sent`() {
        val view = buildPartyView(roomWithPick(), "b", emptyList(), start.plusSeconds(1))

        assertNotNull(view.question)
        assertNull(view.millisUntilQuestion)
    }
}

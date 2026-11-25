package com.timeless.triviaquiz.game

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Theme Party turn order, reveals, kicking and question choice (no Spring, no network). */
class ThemePartyGameTest {

    private fun theme(perDifficulty: Int = 20, used: (Int) -> Boolean = { false }) = Theme(
        id = "test.json",
        name = "Test",
        questions = THEME_QUESTION_SPLIT.keys.flatMap { difficulty ->
            (1..perDifficulty).map {
                ThemeQuestion(difficulty, "$difficulty $it", "right", listOf("wrong"), "Because.", used(it))
            }
        }
    )

    private fun game(vararg ids: String = arrayOf("a", "b", "c")) =
        // Two of each, so every difficulty is played.
        ThemePartyGame(ids.map { PartyPlayer(it, it.uppercase()) }, false, roundCounts = THEME_QUESTION_SPLIT)

    /** Picks, and has everyone answer, with only [correct] getting it right. */
    private fun ThemePartyGame.playToReveal(vararg correct: String, theme: Theme = theme()) {
        assertTrue(pickTheme(picker.playerId, theme))
        players.forEach { assertTrue(answer(it.playerId, if (it.playerId in correct) "right" else "wrong")) }
    }

    private fun ThemePartyGame.playTurn() {
        playToReveal()
        assertTrue(next(players.first().playerId))
    }

    @Test
    fun `turns rotate one question at a time for six rounds that get harder`() {
        val game = game()
        val turns = mutableListOf<Pair<String, String>>()

        while (!game.isOver) {
            turns += game.picker.playerId to game.difficulty
            game.playTurn()
        }

        val rounds = listOf("easy", "easy", "medium", "medium", "hard", "hard")
        assertEquals(rounds.flatMap { difficulty -> listOf("a", "b", "c").map { it to difficulty } }, turns)
    }

    @Test
    fun `by default there are six rounds and none are easy`() {
        val rounds = roundDifficulties(DEFAULT_ROUND_COUNTS)

        assertEquals(6, rounds.size)
        assertFalse("easy" in rounds)
    }

    @Test
    fun `a game has the chosen rounds of each difficulty, in order`() {
        val order = THEME_QUESTION_SPLIT.keys.toList()
        for (counts in listOf(listOf(0, 0, 1), listOf(0, 4, 1), listOf(5, 0, 2), listOf(20, 0, 0))) {
            val game = ThemePartyGame(
                listOf("a", "b").map { PartyPlayer(it, it) }, false, roundCounts = order.zip(counts).toMap()
            )
            val played = mutableListOf<String>()

            while (!game.isOver) {
                played += game.difficulty
                game.playTurn()
            }

            val expected = order.indices.flatMap { index -> List(counts[index] * 2) { order[index] } }
            assertEquals(expected, played)
        }
    }

    @Test
    fun `the question has the round's difficulty`() {
        val game = game("a", "b")
        repeat(4) { game.playTurn() } // Two rounds.

        game.pickTheme("a", theme())

        assertEquals("medium", game.turn?.question?.difficulty)
    }

    private fun manyThemes(count: Int = 10) = (1..count).map { theme().copy(id = "t$it.json") }

    private fun limitedGame() = ThemePartyGame(
        listOf("a", "b").map { PartyPlayer(it, it) }, false, roundCounts = THEME_QUESTION_SPLIT, limitThemes = true
    )

    @Test
    fun `the theme limit offers a fixed number that stay put, and only those can be picked`() {
        val game = limitedGame()
        val themes = manyThemes()
        val offered = game.themeChoices(themes)

        assertEquals(THEMES_OFFERED, offered.toSet().size)
        repeat(20) { assertEquals(offered, game.themeChoices(themes)) }
        assertFalse(game.pickTheme("a", themes.first { it !in offered }))
        assertTrue(game.pickTheme("a", offered.first()))
    }

    @Test
    fun `a new turn gets new choices, without the theme just played`() {
        val game = limitedGame()
        val themes = manyThemes(THEMES_OFFERED + 1)
        val first = game.themeChoices(themes).first()
        game.playToReveal(theme = first)
        game.next("a")

        assertFalse(game.pickTheme("b", first), "nothing is offered until the new deal")
        val second = game.themeChoices(themes)
        assertEquals(THEMES_OFFERED, second.size)
        assertFalse(first in second)
    }

    @Test
    fun `themes used up for this round come last`() {
        val fresh = manyThemes(THEMES_OFFERED)
        val usedUp = (1..10).map { theme { true }.copy(id = "used$it.json") }

        assertEquals(fresh.toSet(), limitedGame().themeChoices(usedUp + fresh).toSet())
        assertEquals(THEMES_OFFERED, limitedGame().themeChoices(usedUp).size, "still offered when that's all there is")
    }

    @Test
    fun `a removed theme is replaced without reshuffling the rest`() {
        val game = limitedGame()
        val themes = manyThemes()
        val before = game.themeChoices(themes)

        val after = game.themeChoices(themes - before.first())

        assertEquals(THEMES_OFFERED, after.size)
        assertEquals(before.drop(1), after.take(THEMES_OFFERED - 1))
    }

    /** Picks the first theme on offer, then plays the turn out with only [correct] right. */
    private fun ThemePartyGame.limitedTurn(themes: List<Theme>, vararg correct: String) {
        playToReveal(*correct, theme = themeChoices(themes).first())
        assertTrue(next(players.first().playerId))
    }

    @Test
    fun `the further ahead the picker, the fewer themes they get`() {
        val game = ThemePartyGame(
            listOf("a", "b", "c", "d").map { PartyPlayer(it, it) }, false,
            roundCounts = THEME_QUESTION_SPLIT, limitThemes = true
        )
        val themes = manyThemes()
        assertNull(game.pickerPlace())
        assertEquals(THEMES_OFFERED, game.themeChoices(themes).size, "everyone is level at the start")

        game.limitedTurn(themes, "b") // Now b 2, c 0, d 0, a -1.

        assertEquals("b", game.picker.playerId)
        assertEquals(1, game.pickerPlace())
        assertEquals(THEMES_FOR_LEADER, game.themeChoices(themes).size)

        game.limitedTurn(themes)
        assertEquals(2, game.pickerPlace(), "c shares second with d")
        assertEquals(THEMES_FOR_LEADER + THEMES_PER_PLACE, game.themeChoices(themes).size)

        game.limitedTurn(themes)
        assertEquals(2, game.pickerPlace(), "and so does d")

        game.limitedTurn(themes)
        assertEquals("a", game.picker.playerId)
        assertEquals(4, game.pickerPlace())
        assertEquals(THEMES_OFFERED, game.themeChoices(themes).size, "never more than the most")
    }

    @Test
    fun `next waits until the answer has been up for the hold`() {
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val game = ThemePartyGame(
            listOf("a", "b").map { PartyPlayer(it, it) }, false, revealHold = Duration.ofMillis(1500)
        )
        game.pickTheme("a", theme(), start)
        game.answer("a", "right", start)
        game.answer("b", "wrong", start.plusSeconds(1)) // Revealed here.

        assertFalse(game.next("b", start.plusMillis(2499)))
        assertTrue(game.next("b", start.plusMillis(2500)))
    }

    @Test
    fun `without the theme limit every theme is offered`() {
        assertEquals(10, game().themeChoices(manyThemes()).size)
    }

    @Test
    fun `only the picker may pick`() {
        val game = game()

        assertFalse(game.pickTheme("b", theme()))
        assertEquals(PartyStage.PICKING, game.stage)
    }

    @Test
    fun `nothing is revealed until everyone has answered`() {
        val game = game()
        game.pickTheme("a", theme())

        game.answer("a", "right")
        game.answer("b", "wrong")

        assertEquals(PartyStage.ANSWERING, game.stage)
        assertNull(game.turn?.score)
        game.answer("c", "wrong")
        assertEquals(PartyStage.REVEAL, game.stage)
    }

    @Test
    fun `a second answer, one not on offer or one from a non-player is refused`() {
        val game = game()
        game.pickTheme("a", theme())
        game.answer("a", "wrong")

        assertFalse(game.answer("a", "right"))
        assertFalse(game.answer("b", "not on offer"))
        assertFalse(game.answer("stranger", "right"))
    }

    @Test
    fun `the picker gets the player count less one per other, and a right guesser gets one`() {
        val game = game()

        game.playToReveal("a", "b")

        // a also answered first, so gets the first-correct point.
        assertEquals(listOf(2 + 1, 1, 0), game.players.map { game.scoreOf(it.playerId) })
    }

    @Test
    fun `a guesser still scores when the picker is wrong`() {
        val game = game()

        game.playToReveal("b", "c")

        assertEquals(listOf(-2, 1 + 1, 1), game.players.map { game.scoreOf(it.playerId) }, "b was first")
    }

    @Test
    fun `the first correct answer gets an extra point, whoever gave it`() {
        val game = game()
        game.pickTheme("a", theme())

        game.answer("c", "wrong")
        game.answer("b", "right")
        game.answer("a", "right")

        assertEquals("b", game.turn?.firstCorrectId)
        assertEquals(listOf(2, 1 + FIRST_CORRECT_POINTS, 0), game.players.map { game.scoreOf(it.playerId) })
    }

    @Test
    fun `from three in a row on, each correct answer earns a streak point`() {
        val game = game("a", "b")
        val streak = mutableListOf<Int>()
        val bonus = mutableListOf<Boolean>()

        repeat(8) { turn ->
            game.pickTheme(game.picker.playerId, theme())
            game.answer("b", "wrong")
            game.answer("a", if (turn == 4) "wrong" else "right")
            streak += game.streakOf("a")
            bonus += "a" in game.turn!!.streakBonusIds
            game.next("a")
        }

        assertEquals(listOf(1, 2, 3, 4, 0, 1, 2, 3), streak)
        assertEquals(listOf(false, false, true, true, false, false, false, true), bonus)
        assertEquals(0, game.streakOf("b"))
    }

    @Test
    fun `the streak point is added to the score at the reveal`() {
        val game = game("a", "b")
        repeat(2) {
            game.playToReveal("b")
            game.next("a")
        }
        val before = game.scoreOf("b")
        val pickerIsB = game.picker.playerId == "b"

        game.playToReveal("b")

        // b picks (2, less nothing) or guesses (1), is first (+1), and has three in a row (+1).
        val base = if (pickerIsB) 2 else GUESSER_POINTS
        assertEquals(before + base + FIRST_CORRECT_POINTS + STREAK_POINTS, game.scoreOf("b"))
        assertEquals(setOf("b"), game.turn?.streakBonusIds)
    }

    @Test
    fun `during the theme flash answers are refused, and the clock starts after it`() {
        val start = Instant.parse("2026-01-01T12:00:00Z")
        val game = ThemePartyGame(
            listOf("a", "b").map { PartyPlayer(it, it) }, false, timeLimit = true, themeFlash = Duration.ofSeconds(1)
        )
        game.pickTheme("a", theme(), start)

        assertFalse(game.answer("b", "right", start.plusMillis(999)))
        assertEquals(start.plusSeconds(1).plus(TIME_PER_PLAYER.multipliedBy(2)), game.turn?.deadline)
        assertTrue(game.answer("b", "right", start.plusSeconds(1)))
    }

    /** One round of hard questions, with tiebreaks on. */
    private fun tiebreakGame(vararg ids: String) = ThemePartyGame(
        ids.map { PartyPlayer(it, it) }, false,
        roundCounts = mapOf("easy" to 0, "medium" to 0, "hard" to 1), tiebreak = true
    )

    /** Picks, has everyone answer in [order] with only [correct] right, then moves on. */
    private fun ThemePartyGame.turnWith(order: List<String>, vararg correct: String) {
        assertTrue(pickTheme(picker.playerId, theme()))
        order.forEach { answer(it, if (it in correct) "right" else "wrong") }
        assertTrue(next(players.first().playerId))
    }

    /** a and b end level on 1 each, c behind on 0. */
    private fun tiedAtTheEnd(): ThemePartyGame = tiebreakGame("a", "b", "c").apply {
        turnWith(listOf("b", "a", "c"), "b") // a picks: a -1, b 1 + first 1.
        turnWith(listOf("a", "b", "c"), "a") // b picks: b -1, a 1 + first 1.
        turnWith(listOf("a", "b", "c")) // c picks, nobody right.
    }

    @Test
    fun `a tie for the lead at the end goes to a tiebreak between only those tied`() {
        val game = tiedAtTheEnd()
        assertEquals(listOf(1, 1, 0), game.players.map { game.scoreOf(it.playerId) })
        assertEquals(PartyStage.TIEBREAK, game.stage)
        assertFalse(game.isOver)
        assertEquals(listOf("a", "b"), game.tiebreakers)

        // Only themes played this game are used: never.json wasn't.
        assertTrue(game.dealTiebreak(listOf(theme().copy(id = "never.json"), theme())))
        assertEquals("test.json", game.turn?.themeId)

        assertFalse(game.answer("c", "right"), "c isn't tied, so can't answer")
        assertTrue(game.answer("b", "wrong"))
        assertEquals(PartyStage.ANSWERING, game.stage, "a wrong answer doesn't end it")
        assertTrue(game.answer("a", "right"))

        assertEquals(PartyStage.REVEAL, game.stage, "the first right answer wins at once")
        assertEquals("a", game.turn?.firstCorrectId)
        assertTrue(game.next("c"))
        assertTrue(game.isOver)
        assertEquals(listOf("a"), game.winners().map { it.playerId })
        assertEquals(1 + TIEBREAK_POINTS, game.scoreOf("a"))
    }

    @Test
    fun `if no tied player is right, another tiebreak question follows`() {
        val game = tiedAtTheEnd()
        game.dealTiebreak(listOf(theme()))

        game.answer("a", "wrong")
        game.answer("b", "wrong")

        assertEquals(PartyStage.REVEAL, game.stage)
        assertNull(game.turn?.firstCorrectId)
        assertTrue(game.next("a"))
        assertEquals(PartyStage.TIEBREAK, game.stage)
        assertTrue(game.dealTiebreak(listOf(theme())))
        assertEquals(2, game.tiebreakRound)
        assertEquals(PartyStage.ANSWERING, game.stage)
    }

    @Test
    fun `a tied player leaving hands the win to the other`() {
        val game = tiedAtTheEnd()

        game.remove("b")

        assertTrue(game.isOver)
        assertEquals(listOf("a"), game.winners().map { it.playerId })
    }

    @Test
    fun `with no theme left the tie can't be broken and is shared`() {
        val game = tiedAtTheEnd()

        assertTrue(game.dealTiebreak(emptyList()))

        assertTrue(game.isOver)
        assertEquals(listOf("a", "b"), game.winners().map { it.playerId })
    }

    @Test
    fun `a clear winner means no tiebreak`() {
        val game = tiebreakGame("a", "b")
        game.turnWith(listOf("a", "b"), "a")
        game.turnWith(listOf("a", "b"))

        assertTrue(game.isOver)
        assertEquals(emptyList(), game.tiebreakers)
    }

    @Test
    fun `nobody gets the first-correct point when nobody is right`() {
        val game = game()

        game.playToReveal()

        assertNull(game.turn?.firstCorrectId)
        assertEquals(listOf(0, 0, 0), game.players.map { game.scoreOf(it.playerId) })
    }

    @Test
    fun `a guesser still scores when the picker's bonus doesn't`() {
        val game = ThemePartyGame(listOf("a", "b").map { PartyPlayer(it, it) }, bonusNeedsSecond = true)

        game.playToReveal("a")
        game.next("a")
        game.playToReveal("a")

        // A guess of 1, plus the first-correct point both times.
        assertEquals(1 + 2 * FIRST_CORRECT_POINTS, game.scoreOf("a"))
    }

    @Test
    fun `any player can move on, but only once and only from a reveal`() {
        val game = game()
        assertFalse(game.next("a"))
        game.playToReveal()

        assertFalse(game.next("stranger"))
        assertTrue(game.next("c"))
        assertFalse(game.next("b"))
        assertEquals("b", game.picker.playerId)
    }

    @Test
    fun `the next picker is null only on the very last turn`() {
        val game = game("a", "b")
        repeat(11) {
            assertNotEquals(null, game.nextPicker)
            game.playTurn()
        }

        assertNull(game.nextPicker)
    }

    @Test
    fun `kicking the last one still to answer reveals without them`() {
        val game = game()
        game.pickTheme("a", theme())
        game.answer("a", "right")
        game.answer("b", "wrong")

        assertTrue(game.remove("c"))

        assertEquals(PartyStage.REVEAL, game.stage)
        assertEquals(2 + 1, game.scoreOf("a"), "two are left, and a was first")
    }

    @Test
    fun `a kicked player's answer doesn't count against the picker`() {
        val game = game()
        game.pickTheme("a", theme())
        game.answer("c", "right")
        game.remove("c")

        game.answer("a", "right")
        game.answer("b", "wrong")

        // c answered right first but has gone, so the first-correct point is a's.
        assertEquals(2 + 1, game.scoreOf("a"))
        assertEquals("a", game.turn?.firstCorrectId)
    }

    @Test
    fun `kicking the picker drops the question and passes the turn on`() {
        val game = game()
        game.pickTheme("a", theme())

        game.remove("a")

        assertEquals(PartyStage.PICKING, game.stage)
        assertEquals("b", game.picker.playerId)
        assertNull(game.turn)
        assertEquals(0, game.roundIndex)
    }

    @Test
    fun `kicking the picker during their reveal passes the turn on`() {
        val game = game()
        game.playToReveal()

        game.remove("a")

        assertEquals(PartyStage.PICKING, game.stage)
        assertEquals("b", game.picker.playerId)
    }

    @Test
    fun `kicking the round's last picker starts the next round`() {
        val game = game()
        repeat(2) { game.playTurn() }

        game.remove("c")

        assertEquals("a", game.picker.playerId)
        assertEquals(1, game.roundIndex)
    }

    @Test
    fun `kicking the very last picker ends the game`() {
        val game = game()
        repeat(17) { game.playTurn() }

        game.remove("c")

        assertTrue(game.isOver)
    }

    @Test
    fun `kicking someone who has had their turn doesn't skip anyone`() {
        val game = game()
        game.playTurn()

        game.remove("a")

        assertEquals("b", game.picker.playerId)
        game.playTurn()
        assertEquals("c", game.picker.playerId)
    }

    @Test
    fun `those left keep six turns each`() {
        val game = game()
        val turns = mutableListOf<String>()
        game.remove("b")

        while (!game.isOver) {
            turns += game.picker.playerId
            game.playTurn()
        }

        assertEquals(mapOf("a" to 6, "c" to 6), turns.groupingBy { it }.eachCount())
    }

    @Test
    fun `kicking down to one player ends the game`() {
        val game = game("a", "b")

        game.remove("a")

        assertTrue(game.isOver)
        assertEquals(listOf("b"), game.winners().map { it.playerId })
    }

    private val opened = Instant.parse("2026-01-01T12:00:00Z")

    private fun after(seconds: Long): Instant = opened.plusSeconds(seconds)

    private fun timedQuestion(vararg ids: String) =
        ThemePartyGame(ids.map { PartyPlayer(it, it) }, bonusNeedsSecond = false, timeLimit = true)
            .also { it.pickTheme(ids.first(), theme(), opened) }

    private fun ThemePartyGame.secondsLeft(at: Instant) = Duration.between(at, turn!!.deadline).seconds

    @Test
    fun `the clock starts at four seconds per player`() {
        assertEquals(8, timedQuestion("a", "b").secondsLeft(opened))
        assertEquals(20, timedQuestion("a", "b", "c", "d", "e").secondsLeft(opened))
    }

    @Test
    fun `each answer takes two seconds off`() {
        val game = timedQuestion("a", "b", "c", "d")

        game.answer("a", "right", after(1))
        assertEquals(13, game.secondsLeft(after(1)))
        game.answer("b", "right", after(2))
        assertEquals(10, game.secondsLeft(after(2)))
    }

    @Test
    fun `answers can't push the clock below three seconds`() {
        val game = timedQuestion("a", "b", "c")

        game.answer("a", "right", after(8)) // Four left.

        assertEquals(3, game.secondsLeft(after(8)))
    }

    @Test
    fun `at three or less an answer leaves the clock alone`() {
        val game = timedQuestion("a", "b", "c")

        game.answer("a", "right", after(10))

        assertEquals(2, game.secondsLeft(after(10)))
    }

    @Test
    fun `running out reveals, and no answer counts as wrong`() {
        val game = timedQuestion("a", "b")
        game.answer("a", "right", opened) // Six left.

        assertFalse(game.expire(after(5)))
        assertFalse(game.answer("b", "right", after(6)), "too late")
        assertTrue(game.expire(after(6)))

        assertEquals(PartyStage.REVEAL, game.stage)
        assertEquals(2 + 1, game.scoreOf("a"), "a was first")
        assertFalse(game.turn!!.isCorrect("b"))
        assertFalse(game.expire(after(999)), "a reveal has no clock")
    }

    @Test
    fun `there is no clock without the setting`() {
        val game = game()
        game.pickTheme("a", theme(), opened)

        assertFalse(game.expire(after(100000)))
        assertNull(game.turn?.deadline)
    }

    @Test
    fun `unused questions are preferred`() {
        val theme = theme(perDifficulty = 5, used = { it != 3 })

        repeat(50) { assertEquals("easy 3", theme.drawQuestion("easy", emptySet())?.question) }
    }

    @Test
    fun `used questions come back once a difficulty runs out, avoiding this game's`() {
        val theme = theme(perDifficulty = 2, used = { true })

        repeat(50) { assertEquals("easy 2", theme.drawQuestion("easy", setOf("easy 1"))?.question) }
        assertNotEquals(null, theme.drawQuestion("easy", setOf("easy 1", "easy 2")))
    }

    @Test
    fun `a question isn't repeated within a game even if its flag wasn't saved`() {
        val game = game("a", "b")
        val theme = theme(perDifficulty = 4) // Never marked used, as when the file can't be written.
        val asked = mutableListOf<String>()

        while (!game.isOver) {
            game.playToReveal(theme = theme)
            asked += game.turn!!.question.question
            game.next("a")
        }

        assertEquals(12, asked.toSet().size)
    }
}

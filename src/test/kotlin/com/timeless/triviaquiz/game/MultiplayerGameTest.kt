package com.timeless.triviaquiz.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiplayerGameTest {

    private fun player(name: String) = MpPlayer(
        name = name,
        categories = (1..CATEGORIES_PER_PLAYER).map { MpCategory(id = it, name = "Cat $it") }
    )

    private fun twoPlayerGame() = MultiplayerGame(listOf(player("Ann"), player("Ben")))

    /** Simulates the controller picking a remaining category and grading the answer. */
    private fun answer(game: MultiplayerGame, correct: Boolean): TurnOutcome {
        game.currentCategoryId = game.currentPlayer.remainingCategories().first().id
        return game.applyAnswer(correct)
    }

    @Test
    fun `three correct answers clear three categories then the turn ends`() {
        val game = twoPlayerGame()

        assertEquals(TurnOutcome.CONTINUE_TURN, answer(game, correct = true))  // easy
        assertEquals("medium", game.currentDifficulty)
        assertEquals(TurnOutcome.CONTINUE_TURN, answer(game, correct = true))  // medium
        assertEquals("hard", game.currentDifficulty)
        assertEquals(TurnOutcome.TURN_OVER, answer(game, correct = true))      // hard

        assertEquals(3, game.players[0].clearedCount)
        assertFalse(game.isOver)
    }

    @Test
    fun `a wrong answer ends the turn immediately`() {
        val game = twoPlayerGame()

        assertEquals(TurnOutcome.CONTINUE_TURN, answer(game, correct = true))  // easy cleared
        assertEquals(TurnOutcome.TURN_OVER, answer(game, correct = false))     // medium wrong

        assertEquals(1, game.players[0].clearedCount)
        assertNull(game.currentQuestion)

        game.endTurn()
        assertEquals(1, game.currentPlayerIndex)  // passed to Ben
        assertEquals(0, game.currentStep)         // fresh turn, back to easy
        assertEquals("easy", game.currentDifficulty)
    }

    @Test
    fun `clearing the final category wins the game`() {
        val game = twoPlayerGame()
        // Ann has already cleared five of her six categories.
        game.players[0].clearedCategoryIds.addAll(setOf(1, 2, 3, 4, 5))

        val outcome = answer(game, correct = true)  // clears the sixth

        assertEquals(TurnOutcome.GAME_WON, outcome)
        assertTrue(game.isOver)
        assertEquals("Ann", game.winnerName)
    }

    @Test
    fun `endTurn wraps back to the first player`() {
        val game = twoPlayerGame()

        game.endTurn()
        assertEquals(1, game.currentPlayerIndex)
        game.endTurn()
        assertEquals(0, game.currentPlayerIndex)
    }
}

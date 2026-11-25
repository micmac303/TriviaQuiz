package com.timeless.triviaquiz.game

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Theme Party scoring function (no Spring, no network). */
class ThemePartyScoringTest {

    @Test
    fun `a right picker gets the player count however many others were right`() {
        for (players in 2..8) {
            for (others in 0 until players) {
                val score = scoreQuestion(pickerCorrect = true, others, players)

                assertEquals(players, score.bonus, "$others of $players players")
            }
        }
    }

    @Test
    fun `a right picker with one other right gets the player count, less one`() {
        val score = scoreQuestion(pickerCorrect = true, othersCorrect = 1, playerCount = 4)

        assertEquals(4, score.bonus)
        assertEquals(1, score.penalty)
        assertEquals(3, score.total)
    }

    @Test
    fun `each other correct answer costs one, whether or not the picker was right`() {
        for (pickerCorrect in listOf(true, false)) {
            for (others in 0..7) {
                val score = scoreQuestion(pickerCorrect, others, playerCount = 8)

                assertEquals(others, score.penalty)
                assertEquals(score.bonus - others, score.total)
            }
        }
    }

    @Test
    fun `a wrong picker never gets the bonus`() {
        for (others in 0..7) {
            assertEquals(0, scoreQuestion(false, others, 8).bonus)
        }
    }
}

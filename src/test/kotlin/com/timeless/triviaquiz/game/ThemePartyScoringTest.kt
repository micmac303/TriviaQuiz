package com.timeless.triviaquiz.game

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Theme Party scoring function (no Spring, no network). */
class ThemePartyScoringTest {

    @Test
    fun `bonus on with no other correct answer gives the picker nothing`() {
        val score = scoreQuestion(pickerCorrect = true, othersCorrect = 0, playerCount = 4, bonusNeedsSecond = true)

        assertEquals(0, score.bonus)
        assertEquals(0, score.total)
    }

    @Test
    fun `bonus on with one other correct answer gives the picker the player count, less one`() {
        val score = scoreQuestion(pickerCorrect = true, othersCorrect = 1, playerCount = 4, bonusNeedsSecond = true)

        assertEquals(4, score.bonus)
        assertEquals(1, score.penalty)
        assertEquals(3, score.total)
    }

    @Test
    fun `bonus off gives the picker the player count however many others were right`() {
        for (players in 2..8) {
            for (others in 0 until players) {
                val score = scoreQuestion(pickerCorrect = true, others, players, bonusNeedsSecond = false)

                assertEquals(players, score.bonus, "$others of $players players")
            }
        }
    }

    @Test
    fun `each other correct answer costs one, whatever the rule and whether or not the picker was right`() {
        for (bonusNeedsSecond in listOf(true, false)) {
            for (pickerCorrect in listOf(true, false)) {
                for (others in 0..7) {
                    val score = scoreQuestion(pickerCorrect, others, playerCount = 8, bonusNeedsSecond)

                    assertEquals(others, score.penalty)
                    assertEquals(score.bonus - others, score.total)
                }
            }
        }
    }

    @Test
    fun `a wrong picker never gets the bonus`() {
        for (bonusNeedsSecond in listOf(true, false)) {
            for (others in 0..7) {
                assertEquals(0, scoreQuestion(false, others, 8, bonusNeedsSecond).bonus)
            }
        }
    }
}

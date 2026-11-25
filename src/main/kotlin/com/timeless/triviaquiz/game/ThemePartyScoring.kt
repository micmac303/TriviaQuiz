package com.timeless.triviaquiz.game

/** What anyone but the picker earns for being right. */
const val GUESSER_POINTS = 1

/** Extra for whoever answered correctly first, picker included, on top of their other points. */
const val FIRST_CORRECT_POINTS = 1

/**
 * Once a player has [STREAK_LENGTH] correct answers in a row, each correct answer (the 3rd, 4th,
 * 5th, ...) earns [STREAK_POINTS] extra until they get one wrong.
 */
const val STREAK_LENGTH = 3
const val STREAK_POINTS = 1

/** What winning the end-of-game tiebreaker is worth, which makes the winner the sole leader. */
const val TIEBREAK_POINTS = 1

/** What each other player's correct answer costs the picker. */
const val PENALTY_PER_OTHER_CORRECT = 1

/** The picker's result on one question. Everyone else who is right gets [GUESSER_POINTS]. */
data class QuestionScore(
    val pickerCorrect: Boolean,
    val othersCorrect: Int,
    val bonus: Int,
    val penalty: Int
) {
    val total: Int
        get() = bonus - penalty
}

/**
 * Scores one Theme Party question for the picker. The bonus and the penalty are independent: the
 * penalty applies whether or not the picker was right, and can take their score below zero.
 *
 * @param playerCount how many are playing at the reveal, picker included. It is the bonus.
 * @param othersCorrect how many players other than the picker answered correctly.
 * @param bonusNeedsSecond when set, the bonus only counts if at least one other player was right.
 */
fun scoreQuestion(pickerCorrect: Boolean, othersCorrect: Int, playerCount: Int, bonusNeedsSecond: Boolean): QuestionScore {
    val bonusAllowed = !bonusNeedsSecond || othersCorrect > 0
    return QuestionScore(
        pickerCorrect = pickerCorrect,
        othersCorrect = othersCorrect,
        bonus = if (pickerCorrect && bonusAllowed) playerCount else 0,
        penalty = othersCorrect * PENALTY_PER_OTHER_CORRECT
    )
}

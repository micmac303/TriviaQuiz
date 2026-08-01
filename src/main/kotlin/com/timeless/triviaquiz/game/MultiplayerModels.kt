package com.timeless.triviaquiz.game

import java.io.Serializable

/** How many categories each player picks, and must clear to win. */
const val CATEGORIES_PER_PLAYER = 6

/** Difficulty asked at each step of a turn: step 0 = easy, 1 = medium, 2 = hard. */
val DIFFICULTIES = listOf("easy", "medium", "hard")

/** A category a player chose during setup. */
data class MpCategory(val id: Int, val name: String) : Serializable

/**
 * A player in the multiplayer game: their [name], the [categories] they chose, and the
 * ids of the categories they have already cleared (one correct answer clears a category,
 * regardless of the difficulty it was asked at).
 */
data class MpPlayer(
    val name: String,
    val categories: List<MpCategory>,
    val clearedCategoryIds: MutableSet<Int> = mutableSetOf()
) : Serializable {

    /** Categories this player has not cleared yet — the pool the next question is drawn from. */
    fun remainingCategories(): List<MpCategory> =
        categories.filter { it.id !in clearedCategoryIds }

    /** True once every chosen category has been cleared. */
    val isFinished: Boolean
        get() = clearedCategoryIds.size >= categories.size

    /** How many categories this player has cleared (used for the winner-page standings). */
    val clearedCount: Int
        get() = clearedCategoryIds.size
}

/** What the controller should do after grading an answer. */
enum class TurnOutcome {
    /** Correct, and the same player continues the turn with a harder question. */
    CONTINUE_TURN,

    /** The turn is over (wrong answer, or three questions cleared); play passes to the next player. */
    TURN_OVER,

    /** Correct, and that answer cleared the player's final category — they win. */
    GAME_WON
}

/**
 * The multiplayer game state, held in the HttpSession. Contains the turn-based state machine:
 * each turn asks easy -> medium -> hard, each from a different not-yet-cleared category. A wrong
 * answer ends the turn immediately; three correct answers ends the turn having cleared three
 * categories. The first player to clear all their categories wins.
 *
 * Question fetching lives in the controller; this model only tracks state and applies transitions
 * so the rules can be unit-tested without the network.
 */
class MultiplayerGame(val players: List<MpPlayer>) : Serializable {

    var currentPlayerIndex: Int = 0
    /** Step within the current turn: 0 = easy, 1 = medium, 2 = hard. */
    var currentStep: Int = 0
    /** The question awaiting an answer; null means the controller should fetch the next one. */
    var currentQuestion: QuizQuestion? = null
    /** The category id the [currentQuestion] was drawn from — cleared for the player if answered right. */
    var currentCategoryId: Int? = null
    /** Set once someone has won; non-null means the game is over. */
    var winnerName: String? = null

    val currentPlayer: MpPlayer
        get() = players[currentPlayerIndex]

    val currentDifficulty: String
        get() = DIFFICULTIES[currentStep]

    val isOver: Boolean
        get() = winnerName != null

    /**
     * Applies the outcome of the current question. On a correct answer the current category is
     * cleared for the player; if that was their last category they win, otherwise the turn advances
     * to the next (harder) step, ending after three cleared. A wrong answer ends the turn. Either
     * way [currentQuestion]/[currentCategoryId] are cleared so the next question is fetched fresh.
     */
    fun applyAnswer(correct: Boolean): TurnOutcome {
        val categoryId = currentCategoryId
        currentQuestion = null
        currentCategoryId = null

        if (!correct) {
            return TurnOutcome.TURN_OVER
        }

        if (categoryId != null) {
            currentPlayer.clearedCategoryIds.add(categoryId)
        }

        if (currentPlayer.isFinished) {
            winnerName = currentPlayer.name
            return TurnOutcome.GAME_WON
        }

        currentStep++
        return if (currentStep > DIFFICULTIES.lastIndex) TurnOutcome.TURN_OVER else TurnOutcome.CONTINUE_TURN
    }

    /** Passes play to the next player (wrapping) and resets the turn to its first (easy) step. */
    fun endTurn() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size
        currentStep = 0
        currentQuestion = null
        currentCategoryId = null
    }
}

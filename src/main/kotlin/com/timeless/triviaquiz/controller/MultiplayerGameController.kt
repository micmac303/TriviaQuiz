package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.CATEGORIES_PER_PLAYER
import com.timeless.triviaquiz.game.MpCategory
import com.timeless.triviaquiz.game.MpPlayer
import com.timeless.triviaquiz.game.MultiplayerGame
import com.timeless.triviaquiz.game.TurnOutcome
import com.timeless.triviaquiz.service.TriviaQuestionService
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The turn-based, pass-the-device multiplayer game. Players are added during setup (name + six
 * categories each), then take turns answering easy -> medium -> hard questions drawn from their
 * remaining categories. A wrong answer ends the turn; the first player to clear all six categories
 * wins. All state lives in the HttpSession, matching the single-player [GameController].
 */
@Controller
class MultiplayerGameController(
    private val triviaQuestionService: TriviaQuestionService
) {
    companion object {
        const val ROSTER_KEY = "mpPlayers"
        const val GAME_KEY = "mpGame"
        const val MIN_PLAYERS = 2
    }

    /** Setup screen: shows the current roster and a form to add another player. */
    @GetMapping("/multiplayer")
    suspend fun setup(model: Model, session: HttpSession): String {
        renderSetup(model, session)
        return "mp-setup"
    }

    /** Adds a player after validating a non-blank name and exactly six distinct categories. */
    @PostMapping("/multiplayer/add")
    suspend fun addPlayer(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) categories: List<Int>?,
        model: Model,
        session: HttpSession
    ): String {
        val trimmedName = name?.trim().orEmpty()
        val chosen = categories?.distinct().orEmpty()

        val error = when {
            trimmedName.isBlank() -> "Please enter a name."
            chosen.size != CATEGORIES_PER_PLAYER -> "Please choose exactly $CATEGORIES_PER_PLAYER categories."
            else -> null
        }

        if (error != null) {
            renderSetup(model, session)
            model["error"] = error
            return "mp-setup"
        }

        val categoryNames = triviaQuestionService.getCategories().associate { it.id to it.name }
        val picked = chosen.map { MpCategory(id = it, name = categoryNames[it] ?: "Category $it") }
        roster(session).add(MpPlayer(name = trimmedName, categories = picked))
        return "redirect:/multiplayer"
    }

    /** Removes a player from the roster by position. */
    @PostMapping("/multiplayer/remove")
    fun removePlayer(@RequestParam index: Int, session: HttpSession): String {
        val players = roster(session)
        if (index in players.indices) {
            players.removeAt(index)
        }
        return "redirect:/multiplayer"
    }

    /** Starts the game once at least [MIN_PLAYERS] players have been added. */
    @PostMapping("/multiplayer/start")
    suspend fun start(model: Model, session: HttpSession): String {
        val players = roster(session)
        if (players.size < MIN_PLAYERS) {
            renderSetup(model, session)
            model["error"] = "Add at least $MIN_PLAYERS players to start."
            return "mp-setup"
        }
        session.setAttribute(GAME_KEY, MultiplayerGame(players.toList()))
        return "redirect:/multiplayer/play"
    }

    /** Shows the current player's question, fetching a fresh one if the turn just began. */
    @GetMapping("/multiplayer/play")
    suspend fun play(model: Model, session: HttpSession): String {
        val game = game(session) ?: return "redirect:/multiplayer"
        if (game.isOver) return "redirect:/multiplayer/winner"

        if (game.currentQuestion == null && !fetchNextQuestion(game)) {
            // No question could be fetched for anyone (e.g. API unavailable).
            model["title"] = "Multiplayer Trivia"
            model["message"] = "Couldn't fetch a question right now. Please try again."
            return "mp-message"
        }

        val question = game.currentQuestion!!
        model["title"] = "Multiplayer Trivia"
        model["player"] = game.currentPlayer.name
        model["cleared"] = game.currentPlayer.clearedCount
        model["total"] = CATEGORIES_PER_PLAYER
        model["step"] = game.currentStep + 1
        model["difficulty"] = game.currentDifficulty
        model["question"] = question
        return "mp-play"
    }

    /** Grades the submitted answer, advances the state machine, and shows feedback. */
    @PostMapping("/multiplayer/answer")
    fun answer(
        @RequestParam(required = false) answer: String?,
        model: Model,
        session: HttpSession
    ): String {
        val game = game(session) ?: return "redirect:/multiplayer"
        val question = game.currentQuestion ?: return "redirect:/multiplayer/play"

        val correct = answer != null && answer == question.correctAnswer
        val answeredBy = game.currentPlayer.name
        val outcome = game.applyAnswer(correct)
        if (outcome == TurnOutcome.TURN_OVER) {
            game.endTurn()
        }

        if (outcome == TurnOutcome.GAME_WON) {
            return "redirect:/multiplayer/winner"
        }

        model["title"] = "Multiplayer Trivia"
        model["correct"] = correct
        model["question"] = question.question
        model["yourAnswer"] = answer
        model["correctAnswer"] = question.correctAnswer
        model["answeredBy"] = answeredBy
        model["continueTurn"] = (outcome == TurnOutcome.CONTINUE_TURN)
        // After the transition, currentPlayer/currentDifficulty describe who is up next.
        model["nextPlayer"] = game.currentPlayer.name
        model["nextDifficulty"] = game.currentDifficulty
        return "mp-feedback"
    }

    /** Winner screen with final standings. */
    @GetMapping("/multiplayer/winner")
    fun winner(model: Model, session: HttpSession): String {
        val game = game(session) ?: return "redirect:/multiplayer"
        model["title"] = "Multiplayer Trivia"
        model["winner"] = game.winnerName
        model["standings"] = game.players
            .sortedByDescending { it.clearedCount }
            .map { mapOf("name" to it.name, "cleared" to it.clearedCount, "total" to CATEGORIES_PER_PLAYER) }
        return "mp-winner"
    }

    /**
     * "Play again": drops the finished game and rebuilds the roster so each player keeps their name
     * and category choices but starts with a clean, uncleared slate. Returns to setup where the same
     * players can start another round (or be edited first).
     */
    @PostMapping("/multiplayer/reset")
    fun reset(session: HttpSession): String {
        val game = game(session)
        if (game != null) {
            val freshRoster = game.players.map { player ->
                MpPlayer(name = player.name, categories = player.categories)
            }.toMutableList()
            session.setAttribute(ROSTER_KEY, freshRoster)
        }
        session.removeAttribute(GAME_KEY)
        return "redirect:/multiplayer"
    }

    /** Clears all multiplayer state — roster and game — and returns to an empty setup. */
    @PostMapping("/multiplayer/clear")
    fun clear(session: HttpSession): String {
        session.removeAttribute(ROSTER_KEY)
        session.removeAttribute(GAME_KEY)
        return "redirect:/multiplayer"
    }

    /**
     * Fetches a question for the current player at the current difficulty, trying each remaining
     * category until one yields a question. If none do, the turn ends and the next player is tried;
     * returns false only if no player could be given a question (bounded to avoid looping forever).
     */
    private suspend fun fetchNextQuestion(game: MultiplayerGame): Boolean {
        repeat(game.players.size) {
            for (category in game.currentPlayer.remainingCategories().shuffled()) {
                val question = triviaQuestionService
                    .getBufferedQuestion(category.id, game.currentDifficulty)
                if (question != null) {
                    game.currentQuestion = question
                    game.currentCategoryId = category.id
                    return true
                }
            }
            // Nothing available for this player at this difficulty — pass the turn on.
            game.endTurn()
        }
        return false
    }

    /** Populates the setup model with categories and the current roster. */
    private suspend fun renderSetup(model: Model, session: HttpSession) {
        model["title"] = "Multiplayer Trivia"
        model["categories"] = triviaQuestionService.getCategories()
        model["players"] = roster(session).mapIndexed { index, player ->
            mapOf(
                "index" to index,
                "name" to player.name,
                "categories" to player.categories.map { it.name }
            )
        }
        model["canStart"] = roster(session).size >= MIN_PLAYERS
    }

    @Suppress("UNCHECKED_CAST")
    private fun roster(session: HttpSession): MutableList<MpPlayer> {
        val existing = session.getAttribute(ROSTER_KEY) as? MutableList<MpPlayer>
        if (existing != null) return existing
        val created = mutableListOf<MpPlayer>()
        session.setAttribute(ROSTER_KEY, created)
        return created
    }

    private fun game(session: HttpSession): MultiplayerGame? =
        session.getAttribute(GAME_KEY) as? MultiplayerGame
}

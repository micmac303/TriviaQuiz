package com.timeless.triviaquiz.controller

import com.fasterxml.jackson.annotation.JsonInclude
import com.timeless.triviaquiz.game.DIFFICULTIES
import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.MultiplayerGame
import com.timeless.triviaquiz.game.PartyStage
import com.timeless.triviaquiz.game.PartyTurn
import com.timeless.triviaquiz.game.RoomFeedback
import com.timeless.triviaquiz.game.RoomMode
import com.timeless.triviaquiz.game.RoomPhase
import com.timeless.triviaquiz.game.Theme
import com.timeless.triviaquiz.game.TurnOutcome
import com.timeless.triviaquiz.service.GameRoomService
import com.timeless.triviaquiz.service.QuestionDealer
import com.timeless.triviaquiz.service.ThemeLoader
import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * A question as the browser is allowed to see it.
 *
 * Deliberately *not* [com.timeless.triviaquiz.game.QuizQuestion]: that carries `correctAnswer`,
 * which was safe while Mustache rendered server-side and never emitted it, but would ship the
 * answer to every phone the moment it were serialised to JSON.
 */
data class QuestionView(
    val category: String,
    val difficulty: String,
    val question: String,
    val answers: List<String>,
    val step: Int,
    val steps: Int
)

data class PlayerView(
    val name: String,
    val cleared: Int,
    val total: Int,
    val current: Boolean,
    val you: Boolean
)

data class FeedbackView(
    val answeredBy: String,
    val question: String,
    val givenAnswer: String?,
    val correctAnswer: String,
    val correct: Boolean
)

/** Everything one device needs to draw its screen, tailored to who is asking. */
data class RoomStateView(
    val version: Long,
    val phase: String,
    val you: String?,
    val yourTurn: Boolean,
    val canStart: Boolean,
    val players: List<PlayerView>,
    val question: QuestionView?,
    val feedback: FeedbackView?,
    val winner: String?,
    val notice: String?,
    /** Only present in Theme Party rooms; left out of the JSON entirely for classic ones. */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val party: PartyView? = null
)

data class AnswerRequest(val version: Long, val answer: String?)

data class PartyPickRequest(val version: Long, val theme: String)

data class PartyAnswerRequest(val version: Long, val answer: String)

data class PartyNextRequest(val version: Long)

data class PartyFlagRequest(val theme: String)

data class PartyHideRequest(val theme: String, val hidden: Boolean)

/**
 * The polling API every device in a room talks to. One endpoint to read the room and one to answer
 * (Theme Party rooms have their own pick, answer and next endpoints); all return the same tailored view so
 * a submitted answer updates the screen without waiting for the next poll.
 *
 * Reading a classic room is not purely a read: whichever device polls first once the previous answer's
 * feedback has expired is the one that goes and fetches the next question. That keeps the game
 * moving with no scheduler and no "next question" button for someone to forget to press.
 */
@RestController
class RoomApiController(
    private val gameRoomService: GameRoomService,
    private val questionDealer: QuestionDealer,
    private val themeLoader: ThemeLoader
) {

    @GetMapping("/api/room/{code}/state")
    suspend fun state(@PathVariable code: String, session: HttpSession): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(advanceAndBuild(room, viewerId(session, room)))
    }

    @PostMapping("/api/room/{code}/answer")
    suspend fun answer(
        @PathVariable code: String,
        @RequestBody body: AnswerRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)

        room.mutex.withLock {
            val game = room.game
            val question = game?.currentQuestion

            // Not your turn, nothing to answer, or a stale submission (double tap, or a screen the
            // player had open from before the turn moved on). Fall through to returning the current
            // state so the device re-syncs instead of showing an error.
            if (game == null || question == null ||
                viewerId == null || viewerId != room.currentPlayerId() ||
                body.version != room.version
            ) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(buildView(room, viewerId))
            }

            val correct = body.answer != null && body.answer == question.correctAnswer
            val answeredBy = game.currentPlayer.name
            val outcome = game.applyAnswer(correct)
            if (outcome == TurnOutcome.TURN_OVER) {
                game.endTurn()
            }

            room.feedback = RoomFeedback(
                answeredBy = answeredBy,
                question = question.question,
                givenAnswer = body.answer,
                correctAnswer = question.correctAnswer,
                correct = correct,
                until = Instant.now().plus(GameRoomService.FEEDBACK_DURATION)
            )
            room.touch()
        }

        return ResponseEntity.ok(advanceAndBuild(room, viewerId))
    }

    @PostMapping("/api/room/{code}/party/pick")
    suspend fun pickTheme(
        @PathVariable code: String,
        @RequestBody body: PartyPickRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)
        // Matched against the folder listing, so the client's value is never used as a path.
        val theme = loadThemes(room).firstOrNull { it.id == body.theme }

        val accepted = room.mutex.withLock {
            val game = room.party
            val picked = game != null && theme != null && viewerId != null && body.version == room.version &&
                game.pickTheme(viewerId, theme, Instant.now())
            if (picked) {
                room.touch()
                room.questionsOpenedVersion = room.version
            }
            picked
        }
        return partyResponse(room, viewerId, accepted)
    }

    @PostMapping("/api/room/{code}/party/answer")
    suspend fun answerPartyQuestion(
        @PathVariable code: String,
        @RequestBody body: PartyAnswerRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)

        var revealed: PartyTurn? = null
        val accepted = room.mutex.withLock {
            val game = room.party
            // A range, not equality: see GameRoom.questionsOpenedVersion. A double tap is still
            // rejected, as the game keeps only a player's first answer to each question, and a tap
            // left over from the previous question predates the range.
            val answered = game != null && viewerId != null &&
                body.version in room.questionsOpenedVersion..room.version &&
                game.answer(viewerId, body.answer, Instant.now())
            if (answered) {
                room.touch()
                // Answers are only accepted before the reveal, so this one caused it.
                if (game?.stage == PartyStage.REVEAL) revealed = game.turn
            }
            answered
        }
        revealed?.let { markQuestionUsed(room, it, themeLoader) }
        return partyResponse(room, viewerId, accepted)
    }

    /** Moves everyone on from a reveal. The exact version match makes the first tap the only one. */
    @PostMapping("/api/room/{code}/party/next")
    suspend fun nextPartyTurn(
        @PathVariable code: String,
        @RequestBody body: PartyNextRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)

        val accepted = room.mutex.withLock {
            val game = room.party
            val moved = game != null && viewerId != null && body.version == room.version && game.next(viewerId)
            if (moved) room.touch()
            moved
        }
        return partyResponse(room, viewerId, accepted)
    }

    /**
     * Marks a theme as bad, adding to the count the host sees. Only the picker, only for a theme on
     * offer to them, and once per player per theme. Doesn't bump the version: nothing anyone else
     * sees changes, and the picker's exact-match pick mustn't be made stale by it.
     */
    @PostMapping("/api/room/{code}/party/flag")
    suspend fun flagTheme(
        @PathVariable code: String,
        @RequestBody body: PartyFlagRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)
        // Matched against the folder listing, so the client's value is never used as a path.
        val themes = loadThemes(room)
        val theme = themes.firstOrNull { it.id == body.theme }

        val accepted = room.mutex.withLock {
            val game = room.party
            game != null && theme != null && viewerId != null && game.canPick(viewerId) &&
                game.themeChoices(themes).any { it.id == theme.id } &&
                room.flagTheme(viewerId, theme.id)
        }
        if (accepted) withContext(Dispatchers.IO) { themeLoader.flag(theme!!.id) }
        return partyResponse(room, viewerId, accepted)
    }

    /**
     * Lets a player waiting in the lobby hide a theme from this room's game, or show it again: the
     * same list the host's Hide and Show change. Only before the start. [PartyHideRequest.hidden]
     * is the state wanted rather than a toggle, so two players hiding the same theme agree. No
     * version bump, as nothing else in the lobby changes.
     */
    @PostMapping("/api/room/{code}/party/hide")
    suspend fun hideTheme(
        @PathVariable code: String,
        @RequestBody body: PartyHideRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)
        // Matched against the folder listing, hidden ones included, so they can be shown again.
        val known = withContext(Dispatchers.IO) { themeLoader.load().themes }.any { it.id == body.theme }

        val accepted = room.mutex.withLock {
            val allowed = known && room.party == null && room.phase == RoomPhase.LOBBY &&
                room.lobby.any { it.playerId == viewerId }
            if (allowed) {
                if (body.hidden) room.hiddenThemes.add(body.theme) else room.hiddenThemes.remove(body.theme)
                room.keepAlive()
            }
            allowed
        }
        return partyResponse(room, viewerId, accepted)
    }

    private suspend fun partyResponse(
        room: GameRoom,
        viewerId: String?,
        accepted: Boolean
    ): ResponseEntity<RoomStateView> {
        val status = if (accepted) HttpStatus.OK else HttpStatus.CONFLICT
        return ResponseEntity.status(status).body(advanceAndBuild(room, viewerId))
    }

    /**
     * Builds a party room's state. The time limit has no scheduler: the first poll after the clock
     * runs out causes the reveal. Polling never moves a game on from a reveal. The used flag and
     * the picker's theme list are both file work, so done outside the mutex.
     *
     * Likewise a tiebreak question has no scheduler: the first request that finds the game waiting
     * for one deals it. That is usually the response to the Next that caused the wait.
     */
    private suspend fun readPartyState(room: GameRoom, viewerId: String?): RoomStateView {
        // The lobby lists every theme, hidden ones too, so players can hide or show them.
        if (room.party == null) {
            val all = withContext(Dispatchers.IO) { themeLoader.load().themes }
            room.mutex.withLock {
                // Checked again: the host may have started while the themes loaded, and a started
                // game must only be given the themes that aren't hidden.
                if (room.party == null) {
                    room.keepAlive()
                    return buildPartyState(room, viewerId, all)
                }
            }
        }
        var timedOut: PartyTurn? = null
        room.mutex.withLock {
            room.keepAlive()
            val game = room.party
            if (game?.expire(Instant.now()) == true) {
                room.touch()
                timedOut = game.turn
            } else if (game?.canPick(viewerId) != true && game?.stage != PartyStage.TIEBREAK) {
                return buildPartyState(room, viewerId, emptyList())
            }
        }
        timedOut?.let { markQuestionUsed(room, it, themeLoader) }
        val themes = loadThemes(room)
        return room.mutex.withLock {
            // Checked again: another request may have dealt it while the themes loaded.
            if (room.party?.dealTiebreak(themes, Instant.now()) == true) {
                room.touch()
                room.questionsOpenedVersion = room.version
            }
            buildPartyState(room, viewerId, themes)
        }
    }

    private fun buildPartyState(room: GameRoom, viewerId: String?, themes: List<Theme>): RoomStateView =
        buildView(room, viewerId).copy(party = buildPartyView(room, viewerId, themes, Instant.now()))

    /**
     * The themes this room may use: all valid ones but those the host has hidden. Hiding one that's
     * on offer swaps it out of the picker's deal, like a removed theme.
     */
    private suspend fun loadThemes(room: GameRoom): List<Theme> =
        withContext(Dispatchers.IO) { themeLoader.load().themes }.filter { it.id !in room.hiddenThemes }

    /**
     * Expires stale feedback, deals the next question if one is needed, then builds the view.
     *
     * Exactly one caller claims the fetch ([GameRoom.dealing]) and does it *outside* the mutex,
     * because it is a network call: everyone else polls straight through and sees a question-less
     * state for a moment rather than piling up behind it. Failures back off via
     * [GameRoom.nextDealAttempt] so a struggling opentdb isn't retried once per second per device.
     */
    private suspend fun advanceAndBuild(room: GameRoom, viewerId: String?): RoomStateView {
        if (room.mode == RoomMode.THEME_PARTY) return readPartyState(room, viewerId)

        val game: MultiplayerGame

        room.mutex.withLock {
            room.keepAlive()

            if (room.feedback?.until?.isBefore(Instant.now()) == true) {
                room.feedback = null
                room.touch()
            }

            val current = room.game
            val needsQuestion = current != null && !current.isOver && room.feedback == null &&
                current.currentQuestion == null && !room.dealing &&
                Instant.now().isAfter(room.nextDealAttempt)

            if (!needsQuestion || current == null) return buildView(room, viewerId)

            room.dealing = true
            game = current
        }

        // runCatching so an unexpected failure can never leave `dealing` stuck true, which would
        // wedge the room: no other poll would ever claim the fetch again.
        val dealt = runCatching { questionDealer.dealNextQuestion(game) }.getOrDefault(false)

        return room.mutex.withLock {
            room.dealing = false
            if (dealt) {
                room.notice = null
                questionDealer.prefetchAhead(game)
            } else {
                room.notice = "Trouble reaching the question service — retrying."
                room.nextDealAttempt = Instant.now().plus(GameRoomService.DEAL_RETRY_DELAY)
            }
            room.touch()
            buildView(room, viewerId)
        }
    }

    private fun buildView(room: GameRoom, viewerId: String?): RoomStateView {
        val game = room.game
        val currentId = room.currentPlayerId()
        val question = game?.currentQuestion?.takeIf { room.feedback == null }

        val players = if (game == null) {
            room.lobby.map {
                PlayerView(
                    name = it.name,
                    cleared = 0,
                    total = it.categories.size,
                    current = false,
                    you = it.playerId == viewerId
                )
            }
        } else {
            // playerIds is built from the lobby at start, so positions line up with game.players.
            room.playerIds.zip(game.players) { id, player ->
                PlayerView(
                    name = player.name,
                    cleared = player.clearedCount,
                    total = player.categories.size,
                    current = id == currentId,
                    you = id == viewerId
                )
            }
        }

        return RoomStateView(
            version = room.version,
            phase = room.phase.name.lowercase(),
            you = room.lobby.firstOrNull { it.playerId == viewerId }?.name,
            yourTurn = viewerId != null && viewerId == currentId && question != null,
            canStart = room.phase == RoomPhase.LOBBY && room.lobby.size >= GameRoomService.MIN_PLAYERS,
            players = players,
            question = question?.let {
                QuestionView(
                    category = it.category,
                    difficulty = it.difficulty,
                    question = it.question,
                    answers = it.answers,
                    step = game.currentStep + 1,
                    steps = DIFFICULTIES.size
                )
            },
            feedback = room.feedback?.let {
                FeedbackView(
                    answeredBy = it.answeredBy,
                    question = it.question,
                    givenAnswer = it.givenAnswer,
                    correctAnswer = it.correctAnswer,
                    correct = it.correct
                )
            },
            winner = game?.winnerName,
            notice = room.notice
        )
    }

    /** The player this browser is, or null when the host (or a bystander) is watching. */
    private fun viewerId(session: HttpSession, room: GameRoom): String? {
        if (session.getAttribute(RoomSession.ROOM_CODE) != room.code) return null
        return session.getAttribute(RoomSession.PLAYER_ID) as? String
    }
}

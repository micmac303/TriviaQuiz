package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.CATEGORIES_PER_PLAYER
import com.timeless.triviaquiz.game.LobbyPlayer
import com.timeless.triviaquiz.game.MpCategory
import com.timeless.triviaquiz.game.RoomMode
import com.timeless.triviaquiz.game.RoomPhase
import com.timeless.triviaquiz.service.GameRoomService
import com.timeless.triviaquiz.service.ThemeLoader
import com.timeless.triviaquiz.service.TriviaQuestionService
import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/**
 * The phone-facing half of the networked game: joining a room and playing in it. Joining is a
 * single form (name plus six categories) because it is filled in on a phone — the hotseat setup
 * screen could afford more steps since one person drove the whole thing. A Theme Party room only
 * asks for the name, and so never needs opentdb's category list.
 */
@Controller
class JoinController(
    private val gameRoomService: GameRoomService,
    private val triviaQuestionService: TriviaQuestionService,
    private val themeLoader: ThemeLoader
) {

    /** The join form, reached by typing the host's URL or scanning it. */
    @GetMapping("/j/{code}")
    suspend fun joinForm(@PathVariable code: String, model: Model, session: HttpSession): String {
        val room = gameRoomService.find(code) ?: return message(model, "No room with that code. Check the code on the host's screen.")

        if (alreadyJoined(room.code, session)) return "redirect:/play"

        val inLobby = room.phase == RoomPhase.LOBBY
        val removed = session.getAttribute(RoomSession.ROOM_CODE) == room.code &&
            room.wasKicked(session.getAttribute(RoomSession.PLAYER_ID) as? String)
        if (removed) {
            model["error"] = "You were removed by the host."
            return renderPartyJoinForm(model, room.code, enteredName = "", canJoin = inLobby)
        }

        if (!inLobby) {
            return message(model, "That game has already started. Ask the host to start a new one.")
        }

        if (room.mode == RoomMode.THEME_PARTY) return renderPartyJoinForm(model, room.code, enteredName = "")

        renderJoinForm(model, room.code, enteredName = "")
        return "join"
    }

    /** Adds this device to the lobby, then sends it to the game screen to wait for the host. */
    @PostMapping("/j/{code}")
    suspend fun join(
        @PathVariable code: String,
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) categories: List<Int>?,
        model: Model,
        session: HttpSession
    ): String {
        val room = gameRoomService.find(code) ?: return message(model, "No room with that code.")

        val trimmedName = name?.trim().orEmpty()
        val chosen = categories?.distinct().orEmpty()
        val isParty = room.mode == RoomMode.THEME_PARTY

        val error = when {
            trimmedName.isBlank() -> "Please enter a name."
            trimmedName.length > MAX_NAME_LENGTH -> "Please use a name of $MAX_NAME_LENGTH characters or fewer."
            !isParty && chosen.size != CATEGORIES_PER_PLAYER -> "Please choose exactly $CATEGORIES_PER_PLAYER categories."
            else -> null
        }

        if (error != null) {
            val shownName = trimmedName.take(MAX_NAME_LENGTH)
            if (isParty) {
                model["error"] = error
                return renderPartyJoinForm(model, room.code, enteredName = shownName)
            }
            renderJoinForm(model, room.code, enteredName = shownName)
            model["error"] = error
            return "join"
        }

        val picked = if (isParty) emptyList() else {
            val categoryNames = triviaQuestionService.getCategories().associate { it.id to it.name }
            chosen.map { MpCategory(id = it, name = categoryNames[it] ?: "Category $it") }
        }
        val playerId = UUID.randomUUID().toString()

        room.mutex.withLock {
            if (room.phase != RoomPhase.LOBBY) {
                return message(model, "That game has already started. Ask the host to start a new one.")
            }
            if (isParty) room.notice = null // "Need more players" may no longer be true.
            room.lobby.add(LobbyPlayer(playerId = playerId, name = trimmedName, categories = picked))
            room.touch()
        }

        session.setAttribute(RoomSession.PLAYER_ID, playerId)
        session.setAttribute(RoomSession.ROOM_CODE, room.code)
        return "redirect:/play"
    }

    /**
     * The game screen. It is deliberately almost empty: everything on it is drawn by room.js from
     * the polled state, because what this device should see changes when *other* devices act.
     */
    @GetMapping("/play")
    fun play(model: Model, session: HttpSession): String {
        val code = session.getAttribute(RoomSession.ROOM_CODE) as? String
        val room = gameRoomService.find(code)
            ?: return message(model, "You're not in a game. Ask the host for the join address.")

        if (!alreadyJoined(room.code, session)) {
            return "redirect:/j/${room.code}"
        }

        model["title"] = "Trivia"
        model["code"] = room.code
        model["party"] = room.mode == RoomMode.THEME_PARTY
        return "play"
    }

    /**
     * Leaves the game. A classic game only lets go of a player in the lobby. A Theme Party does at
     * any time: it has no timers, so it would otherwise wait for them.
     */
    @PostMapping("/play/leave")
    suspend fun leave(session: HttpSession): String {
        val room = gameRoomService.find(session.getAttribute(RoomSession.ROOM_CODE) as? String)
        val playerId = session.getAttribute(RoomSession.PLAYER_ID) as? String

        if (room?.mode == RoomMode.THEME_PARTY && playerId != null) {
            removeFromParty(room, themeLoader) { room.removePlayer(playerId) }
        } else if (room != null && playerId != null) {
            room.mutex.withLock {
                if (room.phase == RoomPhase.LOBBY && room.lobby.removeIf { it.playerId == playerId }) {
                    room.touch()
                }
            }
        }

        session.removeAttribute(RoomSession.PLAYER_ID)
        session.removeAttribute(RoomSession.ROOM_CODE)
        return "redirect:/"
    }

    /**
     * Populates the join form. [enteredName] must always be set, even when empty: JMustache raises
     * rather than rendering blank for a name the model doesn't mention, so the form's `value`
     * attribute would 500 the page on a first visit.
     */
    private suspend fun renderJoinForm(model: Model, code: String, enteredName: String) {
        model["title"] = "Join the game"
        model["code"] = code
        model["categories"] = triviaQuestionService.getCategories()
        model["required"] = CATEGORIES_PER_PLAYER
        model["name"] = enteredName
        model["maxNameLength"] = MAX_NAME_LENGTH
    }

    private fun renderPartyJoinForm(model: Model, code: String, enteredName: String, canJoin: Boolean = true): String {
        model["title"] = "Join the Theme Party"
        model["canJoin"] = canJoin
        model["code"] = code
        model["name"] = enteredName
        model["maxNameLength"] = MAX_NAME_LENGTH
        return "party-join"
    }

    private fun alreadyJoined(roomCode: String, session: HttpSession): Boolean {
        val playerId = session.getAttribute(RoomSession.PLAYER_ID) as? String ?: return false
        return session.getAttribute(RoomSession.ROOM_CODE) == roomCode &&
            gameRoomService.find(roomCode)?.lobby?.any { it.playerId == playerId } == true
    }

    private fun message(model: Model, text: String): String {
        model["title"] = "Trivia"
        model["message"] = text
        return "mp-message"
    }

    companion object {
        /** Keeps names fitting the roster, and stops anyone online pasting in an essay. */
        const val MAX_NAME_LENGTH = 24
    }
}

package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.ChatPostResult
import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.RoomChat
import com.timeless.triviaquiz.service.GameRoomService
import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A chat line as a device sees it: whether it's "mine" instead of who sent it. */
data class ChatMessageView(
    val id: Long,
    val author: String,
    val text: String,
    val sentAt: Long,
    val mine: Boolean,
    val host: Boolean,
    /** True when [text] is an emoji reaction rather than a typed message. */
    val reaction: Boolean
)

data class ChatView(
    val messages: List<ChatMessageView>,
    val maxLength: Int,
    /** The emoji the reaction picker offers. */
    val reactions: List<String>,
    val error: String? = null
)

data class ChatRequest(val text: String?, val after: Long = 0)

data class ReactionRequest(val emoji: String?, val after: Long = 0)

/**
 * The room chat, polled by chat.js. Only the room's joined players and its host may read or post;
 * a player who leaves or is kicked loses access with their lobby place.
 */
@RestController
class ChatController(private val gameRoomService: GameRoomService) {

    private data class Sender(val key: String, val name: String, val host: Boolean)

    @GetMapping("/api/room/{code}/chat")
    fun read(
        @PathVariable code: String,
        @RequestParam(defaultValue = "0") after: Long,
        session: HttpSession
    ): ResponseEntity<ChatView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val sender = sender(room, session) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(view(room, sender, after))
    }

    @PostMapping("/api/room/{code}/chat")
    fun send(
        @PathVariable code: String,
        @RequestBody body: ChatRequest,
        session: HttpSession
    ): ResponseEntity<ChatView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val sender = sender(room, session) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val result = room.chat.post(sender.key, sender.name, sender.host, body.text)
        return respond(room, sender, body.after, result)
    }

    @PostMapping("/api/room/{code}/chat/react")
    fun react(
        @PathVariable code: String,
        @RequestBody body: ReactionRequest,
        session: HttpSession
    ): ResponseEntity<ChatView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val sender = sender(room, session) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val result = room.chat.react(sender.key, sender.name, sender.host, body.emoji)
        return respond(room, sender, body.after, result)
    }

    private fun respond(room: GameRoom, sender: Sender, after: Long, result: ChatPostResult): ResponseEntity<ChatView> {
        val (status, error) = when (result) {
            ChatPostResult.SENT -> HttpStatus.OK to null
            ChatPostResult.EMPTY -> HttpStatus.BAD_REQUEST to "Type a message first."
            ChatPostResult.TOO_LONG -> HttpStatus.BAD_REQUEST to "Keep it under ${RoomChat.MAX_LENGTH} characters."
            ChatPostResult.INVALID -> HttpStatus.BAD_REQUEST to "That reaction isn't available."
            ChatPostResult.TOO_FAST -> HttpStatus.TOO_MANY_REQUESTS to "Slow down a little."
        }
        return ResponseEntity.status(status).body(view(room, sender, after).copy(error = error))
    }

    private fun view(room: GameRoom, sender: Sender, after: Long) = ChatView(
        messages = room.chat.after(after).map {
            ChatMessageView(
                id = it.id,
                author = it.author,
                text = it.text,
                sentAt = it.sentAt.toEpochMilli(),
                mine = it.senderKey == sender.key,
                host = it.fromHost,
                reaction = it.reaction
            )
        },
        maxLength = RoomChat.MAX_LENGTH,
        reactions = RoomChat.REACTIONS
    )

    /** A joined player of this room, else this room's host, else nobody. */
    private fun sender(room: GameRoom, session: HttpSession): Sender? {
        val playerId = session.getAttribute(RoomSession.PLAYER_ID) as? String
        if (playerId != null && session.getAttribute(RoomSession.ROOM_CODE) == room.code) {
            room.lobby.firstOrNull { it.playerId == playerId }?.let { return Sender("player:$playerId", it.name, false) }
        }
        val hosting = session.getAttribute(RoomSession.HOSTED_ROOM_CODE) == room.code ||
            session.getAttribute(RoomSession.HOSTED_PARTY_CODE) == room.code
        return if (hosting) Sender("host", "Host", true) else null
    }
}

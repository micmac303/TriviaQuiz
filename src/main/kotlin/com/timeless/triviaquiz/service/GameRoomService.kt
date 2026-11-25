package com.timeless.triviaquiz.service

import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.RoomMode
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The registry of live game rooms. Rooms live in memory only — restarting the server ends every
 * game in progress, which is the right trade for a hobby app run from a laptop on the sofa.
 */
@Service
class GameRoomService {

    companion object {
        /** Minimum players before the host can start. Matches the hotseat game. */
        const val MIN_PLAYERS = 2

        /** How long the graded answer stays on screen before play moves on. */
        val FEEDBACK_DURATION: Duration = Duration.ofSeconds(4)

        /** Wait this long before retrying after opentdb fails to give us a question. */
        val DEAL_RETRY_DELAY: Duration = Duration.ofSeconds(5)

        private const val CODE_LENGTH = 4

        /** No I/O/0/1 — the code gets read off a laptop screen and typed into a phone. */
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private val IDLE_TIMEOUT: Duration = Duration.ofHours(2)
    }

    private val rooms = ConcurrentHashMap<String, GameRoom>()
    private val random = SecureRandom()

    /** Creates a room with an unused join code. */
    fun create(mode: RoomMode = RoomMode.CLASSIC): GameRoom {
        reapIdle()
        while (true) {
            val code = randomCode()
            val room = GameRoom(code, mode)
            if (rooms.putIfAbsent(code, room) == null) return room
        }
    }

    /** Looks a room up by join code, case-insensitively. */
    fun find(code: String?): GameRoom? {
        if (code.isNullOrBlank()) return null
        reapIdle()
        return rooms[code.trim().uppercase()]
    }

    fun close(code: String?) {
        if (code != null) rooms.remove(code.trim().uppercase())
    }

    private fun randomCode(): String =
        (1..CODE_LENGTH)
            .map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
            .joinToString("")

    /** Drops rooms nobody has touched for [IDLE_TIMEOUT] so abandoned games don't accumulate. */
    private fun reapIdle() {
        val cutoff = Instant.now().minus(IDLE_TIMEOUT)
        rooms.values.removeIf { it.lastActivity.isBefore(cutoff) }
    }
}

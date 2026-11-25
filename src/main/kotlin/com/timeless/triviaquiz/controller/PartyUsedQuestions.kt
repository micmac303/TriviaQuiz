package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.PartyStage
import com.timeless.triviaquiz.game.PartyTurn
import com.timeless.triviaquiz.service.ThemeLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SAVE_FAILED = "Couldn't mark that question as used in its theme file."

/**
 * Runs [remove], a kick or a leave, under the room's lock. Either can be what triggers the reveal,
 * as nobody waits for a player who has gone, and then the question has to be marked used.
 */
internal suspend fun removeFromParty(room: GameRoom, themeLoader: ThemeLoader, remove: () -> Boolean): Boolean {
    var revealed: PartyTurn? = null
    val removed = room.mutex.withLock {
        val stageBefore = room.party?.stage
        val done = remove()
        val game = room.party
        if (stageBefore == PartyStage.ANSWERING && game?.stage == PartyStage.REVEAL) revealed = game.turn
        done
    }
    revealed?.let { markQuestionUsed(room, it, themeLoader) }
    return removed
}

/**
 * Saves that [turn]'s question has been revealed. Call without [GameRoom.mutex] held, as it writes
 * a file. A failure is shown to the room but never holds up the game.
 */
internal suspend fun markQuestionUsed(room: GameRoom, turn: PartyTurn, themeLoader: ThemeLoader) {
    val saved = withContext(Dispatchers.IO) { themeLoader.markUsed(turn.themeId, turn.question.question) }
    val notice = if (saved) null else SAVE_FAILED
    room.mutex.withLock {
        if (room.notice != notice) {
            room.notice = notice
            room.touch()
        }
    }
}

package com.timeless.triviaquiz.controller

/**
 * HttpSession keys for the networked game. Unlike the hotseat game — where the session held the
 * whole [com.timeless.triviaquiz.game.MultiplayerGame] — a networked player's session holds only
 * who they are and which room they are in. The game itself lives in the room registry, shared by
 * every device. Identity is kept in the session rather than the URL so a player can't answer as
 * someone else by editing an address.
 */
object RoomSession {
    /** The player's id within the room they joined. */
    const val PLAYER_ID = "roomPlayerId"

    /** The join code of the room this device joined as a player. */
    const val ROOM_CODE = "roomCode"

    /** The join code of the room this browser is hosting (set on the host's own machine). */
    const val HOSTED_ROOM_CODE = "hostedRoomCode"

    /** The join code of the Theme Party room this browser is hosting. */
    const val HOSTED_PARTY_CODE = "hostedPartyCode"
}

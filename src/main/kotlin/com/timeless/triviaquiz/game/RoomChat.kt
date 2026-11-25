package com.timeless.triviaquiz.game

import java.time.Duration
import java.time.Instant

/**
 * One chat line, or a reaction (then [text] is one of [RoomChat.REACTIONS]). [senderKey]
 * identifies the device that sent it and never leaves the server.
 */
data class ChatMessage(
    val id: Long,
    val senderKey: String,
    val author: String,
    val fromHost: Boolean,
    val text: String,
    val sentAt: Instant,
    val reaction: Boolean = false
)

enum class ChatPostResult { SENT, EMPTY, TOO_LONG, TOO_FAST, INVALID }

/**
 * A room's chat. Kept apart from [GameRoom.mutex] and [GameRoom.version] on purpose: picking a
 * theme and pressing Next need an exact version match, so a chat message bumping the version would
 * make those taps fail for whoever was mid-tap.
 */
class RoomChat(private val clock: () -> Instant = Instant::now) {

    private val messages = ArrayDeque<ChatMessage>()
    private val lastSent = HashMap<String, Instant>()
    private var nextId = 1L

    @Synchronized
    fun post(senderKey: String, author: String, fromHost: Boolean, rawText: String?): ChatPostResult {
        val text = clean(rawText)
        if (text.isEmpty()) return ChatPostResult.EMPTY
        if (text.length > MAX_LENGTH) return ChatPostResult.TOO_LONG

        return add(senderKey, author, fromHost, text, reaction = false, limitKey = senderKey, interval = MIN_INTERVAL)
    }

    /**
     * Sends one of [REACTIONS]. Limited separately from messages, so tapping emoji can't stop
     * someone typing, and more loosely, since reactions come in bursts.
     */
    @Synchronized
    fun react(senderKey: String, author: String, fromHost: Boolean, emoji: String?): ChatPostResult {
        if (emoji !in REACTIONS) return ChatPostResult.INVALID
        return add(senderKey, author, fromHost, emoji!!, reaction = true,
            limitKey = "$senderKey#react", interval = MIN_REACTION_INTERVAL)
    }

    private fun add(
        senderKey: String,
        author: String,
        fromHost: Boolean,
        text: String,
        reaction: Boolean,
        limitKey: String,
        interval: Duration
    ): ChatPostResult {
        val now = clock()
        val previous = lastSent[limitKey]
        if (previous != null && Duration.between(previous, now) < interval) return ChatPostResult.TOO_FAST

        lastSent[limitKey] = now
        messages.addLast(ChatMessage(nextId++, senderKey, author, fromHost, text, now, reaction))
        while (messages.size > HISTORY) messages.removeFirst()
        return ChatPostResult.SENT
    }

    /** Messages newer than [id], oldest first. 0 gets the whole kept history. */
    @Synchronized
    fun after(id: Long): List<ChatMessage> = messages.filter { it.id > id }

    companion object {
        const val MAX_LENGTH = 200
        const val HISTORY = 100
        val MIN_INTERVAL: Duration = Duration.ofMillis(600)
        val MIN_REACTION_INTERVAL: Duration = Duration.ofMillis(300)

        /** The only reactions accepted, in the order the picker shows them. */
        val REACTIONS = listOf("👍", "😂", "😮", "🔥", "👏", "😢", "❤️", "🤯")

        // Control characters (newlines included) become spaces, as a message is one line. Bidi
        // overrides are dropped so nobody can make their text display backwards over others'.
        private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029]")
        private val BIDI = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
        private val SPACES = Regex("\\s+")

        fun clean(raw: String?): String =
            raw.orEmpty().replace(CONTROL, " ").replace(BIDI, "").replace(SPACES, " ").trim()
    }
}

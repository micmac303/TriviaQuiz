package com.timeless.triviaquiz.game

import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomChatTest {

    private lateinit var now: Instant
    private lateinit var chat: RoomChat

    // A fresh chat per test: JUnit runs these on one shared instance (junit-platform.properties).
    @BeforeTest
    fun setUp() {
        now = Instant.parse("2026-01-01T12:00:00Z")
        chat = RoomChat { now }
    }

    private fun later(millis: Long) {
        now = now.plusMillis(millis)
    }

    @Test
    fun `messages come back in order, after a given id`() {
        chat.post("a", "Ann", false, "hi")
        later(1000)
        chat.post("b", "Bob", false, "hello")

        assertEquals(listOf("hi", "hello"), chat.after(0).map { it.text })
        assertEquals(listOf("hello"), chat.after(chat.after(0).first().id).map { it.text })
    }

    @Test
    fun `text is cleaned to one line`() {
        chat.post("a", "Ann", false, "  two\nlines‮  and\ttabs ")

        assertEquals("two lines and tabs", chat.after(0).single().text)
    }

    @Test
    fun `empty and overlong messages are refused`() {
        assertEquals(ChatPostResult.EMPTY, chat.post("a", "Ann", false, "   \n "))
        assertEquals(ChatPostResult.EMPTY, chat.post("a", "Ann", false, null))
        assertEquals(ChatPostResult.TOO_LONG, chat.post("a", "Ann", false, "x".repeat(RoomChat.MAX_LENGTH + 1)))
        assertEquals(ChatPostResult.SENT, chat.post("a", "Ann", false, "x".repeat(RoomChat.MAX_LENGTH)))
    }

    @Test
    fun `one sender can't post faster than the limit, but others can`() {
        assertEquals(ChatPostResult.SENT, chat.post("a", "Ann", false, "one"))
        assertEquals(ChatPostResult.TOO_FAST, chat.post("a", "Ann", false, "two"))
        assertEquals(ChatPostResult.SENT, chat.post("b", "Bob", false, "mine"))

        later(RoomChat.MIN_INTERVAL.toMillis())
        assertEquals(ChatPostResult.SENT, chat.post("a", "Ann", false, "two"))
    }

    @Test
    fun `only listed emoji can be sent as reactions`() {
        assertEquals(ChatPostResult.INVALID, chat.react("a", "Ann", false, "<b>hi</b>"))
        assertEquals(ChatPostResult.INVALID, chat.react("a", "Ann", false, null))
        assertEquals(ChatPostResult.SENT, chat.react("a", "Ann", false, RoomChat.REACTIONS.first()))

        val sent = chat.after(0).single()
        assertEquals(true, sent.reaction)
        assertEquals(RoomChat.REACTIONS.first(), sent.text)
    }

    @Test
    fun `reactions have their own rate limit, so they don't block typing`() {
        assertEquals(ChatPostResult.SENT, chat.react("a", "Ann", false, "🔥"))
        assertEquals(ChatPostResult.TOO_FAST, chat.react("a", "Ann", false, "🔥"))
        assertEquals(ChatPostResult.SENT, chat.post("a", "Ann", false, "nice"))

        later(RoomChat.MIN_REACTION_INTERVAL.toMillis())
        assertEquals(ChatPostResult.SENT, chat.react("a", "Ann", false, "🔥"))
    }

    @Test
    fun `only the most recent messages are kept`() {
        repeat(RoomChat.HISTORY + 5) {
            chat.post("a", "Ann", false, "m$it")
            later(1000)
        }

        val kept = chat.after(0)
        assertEquals(RoomChat.HISTORY, kept.size)
        assertEquals("m5", kept.first().text)
    }
}

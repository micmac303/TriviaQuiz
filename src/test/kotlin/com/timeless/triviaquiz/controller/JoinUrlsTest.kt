package com.timeless.triviaquiz.controller

import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JoinUrlsTest {

    private val request = MockHttpServletRequest().apply { serverPort = 8080 }

    @Test
    fun `the public url comes first`() {
        val urls = joinUrls(request, "AB12", "https://quiz.example.com/")

        assertEquals("https://quiz.example.com/j/AB12", urls.first())
    }

    @Test
    fun `without a public url only LAN addresses are listed`() {
        val urls = joinUrls(request, "AB12", "  ")

        assertTrue(urls.all { it.startsWith("http://") && it.endsWith(":8080/j/AB12") }, urls.toString())
    }
}

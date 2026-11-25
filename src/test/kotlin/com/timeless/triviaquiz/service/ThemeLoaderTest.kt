package com.timeless.triviaquiz.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading theme files from a folder (no Spring, no network). */
class ThemeLoaderTest {

    private val enoughForAGame = listOf("easy", "easy", "medium", "medium", "hard", "hard")

    private fun themeJson(difficulties: List<String> = enoughForAGame): String =
        ObjectMapper().writeValueAsString(
            mapOf(
                "theme" to "Minecraft: The End",
                "questions" to difficulties.mapIndexed { index, difficulty ->
                    mapOf(
                        "difficulty" to difficulty,
                        "question" to "Question $index",
                        "correct_answer" to "Right $index",
                        "incorrect_answers" to listOf("Wrong A", "Wrong B", "Wrong C"),
                        "explanation" to "Because $index."
                    )
                }
            )
        )

    private fun load(dir: Path) = ThemeLoader(dir.toString()).load()

    @Test
    fun `a valid file loads`(@TempDir dir: Path) {
        dir.resolve("end.json").writeText(themeJson())

        val catalog = load(dir)

        assertEquals(emptyList(), catalog.errors)
        val theme = catalog.themes.single()
        assertEquals("end.json", theme.id)
        assertEquals("Minecraft: The End", theme.name)
        assertEquals("Right 0", theme.questions.first().correctAnswer)
        assertEquals("Because 0.", theme.questions.first().explanation)
    }

    @Test
    fun `flagging a theme counts up and is saved in its file`(@TempDir dir: Path) {
        dir.resolve("end.json").writeText(themeJson())
        val loader = ThemeLoader(dir.toString())
        assertEquals(0, loader.load().themes.single().flags)

        assertTrue(loader.flag("end.json"))
        assertTrue(loader.flag("end.json"))

        assertEquals(2, loader.load().themes.single().flags)
        assertEquals(2, ObjectMapper().readTree(dir.resolve("end.json").readText()).path("flags").asInt())
        assertFalse(loader.flag("missing.json"))
    }

    @Test
    fun `a flags value that isn't a whole number is reported`(@TempDir dir: Path) {
        dir.resolve("end.json").writeText(themeJson().replaceFirst("{", "{\"flags\": \"lots\","))

        val error = load(dir).errors.single()

        assertTrue("\"flags\"" in error.reason, error.reason)
    }

    @Test
    fun `a malformed file is skipped with a clear error and the others still load`(@TempDir dir: Path) {
        dir.resolve("broken.json").writeText("{ \"theme\": \"Broken\",\n  \"questions\": [ SecretAnswer ] }")
        dir.resolve("end.json").writeText(themeJson())

        val catalog = load(dir)

        assertEquals(listOf("end.json"), catalog.themes.map { it.id })
        val error = catalog.errors.single()
        assertEquals("broken.json", error.file)
        assertTrue(error.reason.startsWith("Not valid JSON at line 2"), error.reason)
        assertTrue("SecretAnswer" !in error.reason, "errors must not quote the file: ${error.reason}")
    }

    @Test
    fun `well-formed JSON with a missing field is skipped, naming the field`(@TempDir dir: Path) {
        dir.resolve("incomplete.json").writeText(themeJson().replace("explanation", "explanatoin"))

        val catalog = load(dir)

        assertEquals(emptyList(), catalog.themes)
        assertTrue("\"explanation\"" in catalog.errors.single().reason, catalog.errors.toString())
    }

    @Test
    fun `a theme too small for the split is skipped, saying what is short`(@TempDir dir: Path) {
        dir.resolve("short.json").writeText(themeJson(enoughForAGame.dropLast(1)))

        val catalog = load(dir)

        assertTrue("2 hard (found 1)" in catalog.errors.single().reason, catalog.errors.toString())
    }

    /** Laid out as theme files are written by hand. */
    private val handWritten = """
        {
          "theme": "Maths ²",
          "questions": [
        QUESTIONS
          ]
        }
    """.trimIndent().replace(
        "QUESTIONS",
        enoughForAGame.mapIndexed { index, difficulty ->
            """
            |    {
            |      "difficulty": "$difficulty",
            |      "question": "Question $index: x²?",
            |      "correct_answer": "2x",
            |      "incorrect_answers": ["x", "x²", "2"],
            |      "explanation": "Because \"so\"."
            |    }
            """.trimMargin()
        }.joinToString(",\n")
    ) + "\n"

    @Test
    fun `marking a question used changes only that question, and reset restores the file exactly`(@TempDir dir: Path) {
        for (original in listOf(handWritten, handWritten.replace("\n", "\r\n"))) {
            val file = dir.resolve("maths.json")
            file.writeText(original)
            val loader = ThemeLoader(dir.toString())

            assertTrue(loader.markUsed("maths.json", "Question 3: x²?"))

            val questions = loader.load().themes.single().questions
            assertEquals(listOf("Question 3: x²?"), questions.filter { it.used }.map { it.question })
            assertEquals(original.lines().size + 1, file.readText().lines().size, "only one line added")

            assertTrue(loader.resetUsed())
            assertEquals(original, file.readText())
            assertEquals(listOf(file), dir.listDirectoryEntries(), "no temporary files left")
        }
    }

    @Test
    fun `a failed save is reported rather than thrown`(@TempDir dir: Path) {
        dir.resolve("broken.json").writeText("{")

        assertFalse(ThemeLoader(dir.toString()).markUsed("broken.json", "Question 0"))
    }

    @Test
    fun `removing moves the file aside without overwriting an earlier one`(@TempDir dir: Path) {
        val loader = ThemeLoader(dir.toString())
        repeat(2) {
            dir.resolve("end.json").writeText(themeJson())
            assertTrue(loader.remove("end.json"))
        }

        assertEquals(emptyList(), loader.load().themes)
        assertEquals(2, dir.resolve("removed").listDirectoryEntries().size)
        assertTrue(loader.markUsed("end.json", "Question 0"), "a theme removed mid-question isn't an error")
    }

    @Test
    fun `only files in the themes folder can be removed`(@TempDir dir: Path) {
        val themes = dir.resolve("themes").createDirectory()
        dir.resolve("outside.json").writeText(themeJson())

        assertFalse(ThemeLoader(themes.toString()).remove("../outside.json"))
        assertEquals(themeJson(), dir.resolve("outside.json").readText())
    }

    @Test
    fun `used must be true or false`(@TempDir dir: Path) {
        dir.resolve("bad.json").writeText(themeJson().replaceFirst("{\"difficulty\"", "{\"used\":\"yes\",\"difficulty\""))

        assertTrue("\"used\"" in load(dir).errors.single().reason)
    }

    @Test
    fun `a file added later appears on the next load`(@TempDir dir: Path) {
        val loader = ThemeLoader(dir.toString())
        assertEquals(emptyList(), loader.load().themes)

        dir.resolve("end.json").writeText(themeJson())

        assertEquals(1, loader.load().themes.size)
    }
}

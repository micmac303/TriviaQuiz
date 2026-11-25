package com.timeless.triviaquiz.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.timeless.triviaquiz.game.THEME_QUESTION_SPLIT
import com.timeless.triviaquiz.game.Theme
import com.timeless.triviaquiz.game.ThemeQuestion
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.name

/** Why a theme file was skipped. */
data class ThemeError(val file: String, val reason: String)

data class ThemeCatalog(val themes: List<Theme>, val errors: List<ThemeError>)

/**
 * Reads the themes folder afresh on every [load], so added or edited files appear without a restart.
 * A bad file is reported and skipped; it never stops the others loading. This is also the only
 * writer: it sets and clears each question's "used" flag, counts a theme's "flags" (marked as a bad
 * theme by pickers), and moves removed themes aside.
 *
 * Error reasons give positions and field names but never values from the file: any device on the
 * LAN can open the host page that shows them, and the values include answers.
 */
@Service
class ThemeLoader(@Value("\${trivia.themes-dir:themes}") themesDir: String) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val folder: Path = Path.of(themesDir).toAbsolutePath()

    // Strict, as these files are edited by hand: a repeated key or stray text after the closing
    // brace would otherwise silently lose questions.
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    // The picker's phone causes a load every second, so only log problems when they change.
    @Volatile
    private var loggedErrors: List<ThemeError> = emptyList()

    // Windows won't replace a file that is open, so reads and writes must not overlap.
    private val fileLock = Any()

    fun load(): ThemeCatalog = synchronized(fileLock) { readCatalog() }

    /**
     * Sets "used" on one question. [themeId] must come from a loaded [Theme], never from a client.
     * A question since edited out, or whose theme has since been removed, is left alone. Returns
     * false if the save failed.
     */
    fun markUsed(themeId: String, question: String): Boolean = synchronized(fileLock) {
        if (Files.notExists(folder.resolve(themeId))) return true
        rewrite(themeId) { questions ->
            val match = questions.firstOrNull { it.path("question").asText().trim() == question }
            if (match is ObjectNode && !match.path("used").asBoolean()) {
                match.put("used", true)
                true
            } else {
                false
            }
        }
    }

    /**
     * Adds one to a theme's "flags", the count of pickers who marked it as a bad theme.
     * [themeId] must come from a loaded [Theme], never from a client. Returns false if the save failed.
     */
    fun flag(themeId: String): Boolean = synchronized(fileLock) {
        if (Files.notExists(folder.resolve(themeId))) return false
        rewriteRoot(themeId) { root ->
            root.put("flags", root.path("flags").asInt(0).coerceAtLeast(0) + 1)
            true
        }
    }

    /**
     * Moves a theme file, valid or not, into [REMOVED_FOLDER], which [load] doesn't read. Moved
     * rather than deleted, as themes may exist nowhere else. [fileName] may come from a client: it is
     * only ever matched against the folder listing. Returns false if there was no such file or it
     * couldn't be moved.
     */
    fun remove(fileName: String): Boolean = synchronized(fileLock) {
        try {
            val file = themeFiles().firstOrNull { it.name == fileName } ?: return false
            val removedFolder = Files.createDirectories(folder.resolve(REMOVED_FOLDER))
            var target = removedFolder.resolve(file.name)
            var copy = 1
            // Never overwrite a theme of the same name that was removed earlier.
            while (Files.exists(target)) target = removedFolder.resolve("${++copy}-${file.name}")
            Files.move(file, target)
            true
        } catch (e: IOException) {
            logger.warn("Couldn't remove theme {} ({})", fileName, e.javaClass.simpleName)
            false
        }
    }

    /** Clears "used" from every valid theme. Returns false if any of them couldn't be saved. */
    fun resetUsed(): Boolean = synchronized(fileLock) {
        readCatalog().themes
            .filter { it.usedCount > 0 }
            .map { theme ->
                rewrite(theme.id) { questions ->
                    questions.filterIsInstance<ObjectNode>().forEach { it.remove("used") }
                    true
                }
            }
            .all { it } // After the map, so one failure doesn't stop the rest being reset.
    }

    /** Applies [change] to a file's questions, saving only if it reports a change. */
    private fun rewrite(themeId: String, change: (JsonNode) -> Boolean): Boolean =
        rewriteRoot(themeId) { change(it.path("questions")) }

    /** Applies [change] to a file's top-level object, saving only if it reports a change. */
    private fun rewriteRoot(themeId: String, change: (ObjectNode) -> Boolean): Boolean {
        return try {
            val file = folder.resolve(themeId)
            val original = Files.readString(file)
            val root = mapper.readTree(original) as? ObjectNode ?: return false
            if (!change(root)) return true

            var json = format(root)
            if (original.endsWith("\n")) json += "\n"
            if ("\r\n" in original) json = json.replace("\n", "\r\n")
            replaceFile(file, json)
            true
        } catch (e: IOException) {
            // Not e.message: as in readCatalog, it may quote an answer.
            logger.warn("Couldn't update {} ({})", themeId, e.javaClass.simpleName)
            false
        }
    }

    /** Writes beside [file] and renames over it, so a crash can't leave half a theme. */
    private fun replaceFile(file: Path, content: String) {
        val temp = Files.createTempFile(folder, ".theme-", ".tmp")
        try {
            Files.writeString(temp, content)
            var attempt = 1
            while (true) {
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    return
                } catch (e: IOException) {
                    // On Windows an editor or virus scanner can hold the file open for a moment.
                    if (attempt++ == MOVE_ATTEMPTS) throw e
                    Thread.sleep(MOVE_RETRY_MILLIS)
                }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * The layout theme files are written in by hand: two-space indents, with lists of plain values
     * on one line. Jackson's own pretty printer would reformat every line.
     */
    private fun format(node: JsonNode, indent: String = ""): String {
        val inner = "$indent  "
        return when {
            node.isObject && !node.isEmpty -> node.properties().joinToString(",\n", "{\n", "\n$indent}") {
                inner + mapper.writeValueAsString(it.key) + ": " + format(it.value, inner)
            }
            node.isArray && node.all { it.isValueNode } ->
                node.joinToString(", ", "[", "]") { mapper.writeValueAsString(it) }
            node.isArray -> node.joinToString(",\n", "[\n", "\n$indent]") { inner + format(it, inner) }
            else -> mapper.writeValueAsString(node)
        }
    }

    private fun readCatalog(): ThemeCatalog {
        val themes = mutableListOf<Theme>()
        val errors = mutableListOf<ThemeError>()

        val files = try {
            themeFiles()
        } catch (e: IOException) {
            errors.add(folderError(e))
            emptyList()
        } catch (e: UncheckedIOException) { // Files.list reports failures mid-listing this way.
            errors.add(folderError(e))
            emptyList()
        }

        for (file in files) {
            try {
                themes.add(parse(file))
            } catch (e: InvalidThemeException) {
                errors.add(ThemeError(file.name, e.reason))
            } catch (e: JsonProcessingException) {
                // Not e.message: Jackson quotes the offending text, which may be an answer.
                val where = e.location?.let { " at line ${it.lineNr}, column ${it.columnNr}" }.orEmpty()
                errors.add(ThemeError(file.name, "Not valid JSON$where."))
            } catch (e: IOException) {
                errors.add(ThemeError(file.name, "Couldn't read the file (${e.javaClass.simpleName})."))
            }
        }

        if (errors != loggedErrors) {
            errors.forEach { logger.warn("Skipped theme {} in {}: {}", it.file, folder, it.reason) }
            loggedErrors = errors
        }
        return ThemeCatalog(themes.sortedBy { it.name.lowercase() }, errors)
    }

    private fun folderError(e: Exception) =
        ThemeError(folder.name, "Couldn't read the folder (${e.javaClass.simpleName}).")

    private fun themeFiles(): List<Path> {
        if (!Files.exists(folder)) return emptyList()
        return Files.list(folder).use { paths ->
            paths.filter { it.extension.equals("json", ignoreCase = true) && Files.isRegularFile(it) }
                .sorted()
                .toList()
        }
    }

    private fun parse(file: Path): Theme {
        val root = mapper.readTree(Files.readAllBytes(file))
        if (!root.isObject) invalid("The file must contain a JSON object.")

        val name = text(root.path("theme"), "\"theme\"")
        val questionsNode = root.path("questions")
        if (!questionsNode.isArray) invalid("\"questions\" must be a list.")
        val questions = questionsNode.mapIndexed { index, node -> parseQuestion(node, index + 1) }

        if (questions.distinctBy { it.question }.size != questions.size) {
            invalid("The same question appears more than once.")
        }
        val shortfalls = THEME_QUESTION_SPLIT.mapNotNull { (difficulty, needed) ->
            val found = questions.count { it.difficulty == difficulty }
            if (found < needed) "$needed $difficulty (found $found)" else null
        }
        if (shortfalls.isNotEmpty()) invalid("Not enough questions: needs ${shortfalls.joinToString()}.")

        val flags = root.path("flags")
        if (!flags.isMissingNode && !(flags.isIntegralNumber && flags.asInt() >= 0)) {
            invalid("\"flags\" must be a whole number, 0 or more.")
        }

        return Theme(id = file.name, name = name, questions = questions, flags = flags.asInt(0))
    }

    private fun parseQuestion(node: JsonNode, number: Int): ThemeQuestion {
        val at = "Question $number:"

        val difficulty = text(node.path("difficulty"), "$at \"difficulty\"")
        if (difficulty !in THEME_QUESTION_SPLIT) {
            invalid("$at \"difficulty\" must be one of ${THEME_QUESTION_SPLIT.keys.joinToString()}.")
        }

        val correct = text(node.path("correct_answer"), "$at \"correct_answer\"")
        val incorrectNode = node.path("incorrect_answers")
        if (!incorrectNode.isArray || incorrectNode.isEmpty) {
            invalid("$at \"incorrect_answers\" must be a non-empty list.")
        }
        val incorrect = incorrectNode.map { text(it, "$at each of \"incorrect_answers\"") }
        // Answers are graded by their text, so two identical ones would both count as right.
        if ((incorrect + correct).toSet().size != incorrect.size + 1) {
            invalid("$at the answers must all be different.")
        }

        val used = node.path("used")
        if (!used.isMissingNode && !used.isBoolean) {
            invalid("$at \"used\" must be true or false, without quotes.")
        }

        return ThemeQuestion(
            difficulty = difficulty,
            question = text(node.path("question"), "$at \"question\""),
            correctAnswer = correct,
            incorrectAnswers = incorrect,
            explanation = text(node.path("explanation"), "$at \"explanation\""),
            used = used.asBoolean()
        )
    }

    private fun text(node: JsonNode, label: String): String {
        if (!node.isTextual || node.textValue().isBlank()) invalid("$label must be non-empty text in quotes.")
        return node.textValue().trim()
    }

    private companion object {
        const val REMOVED_FOLDER = "removed"
        const val MOVE_ATTEMPTS = 5
        const val MOVE_RETRY_MILLIS = 100L
    }

    private class InvalidThemeException(val reason: String) : Exception(reason)

    private fun invalid(reason: String): Nothing = throw InvalidThemeException(reason)
}

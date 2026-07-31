package com.timeless.triviaquiz.service

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.util.HtmlUtils

// Represents the response from the opentdb category-list endpoint.
data class TriviaCategoryResponse(
    @JsonProperty("trivia_categories")
    val categories: List<TriviaCategory>
)

// Represents a single trivia category the user can pick from.
data class TriviaCategory(
    val id: Int,
    val name: String
)

// Represents the top-level JSON object from the API response.
data class TriviaApiResponse(
    @JsonProperty("response_code")
    val responseCode: Int,
    val results: List<TriviaQuestion>
)

// Represents a single trivia question object.
data class TriviaQuestion(
    val type: String,
    val difficulty: String,
    val category: String,
    val question: String,
    @JsonProperty("correct_answer")
    val correctAnswer: String,
    @JsonProperty("incorrect_answers")
    val incorrectAnswers: List<String>
) {
    fun decoded(): TriviaQuestion {
        return this.copy(
            category = HtmlUtils.htmlUnescape(category),
            question = HtmlUtils.htmlUnescape(question),
            correctAnswer = HtmlUtils.htmlUnescape(correctAnswer),
            incorrectAnswers = incorrectAnswers.map { HtmlUtils.htmlUnescape(it) }
        )
    }
}
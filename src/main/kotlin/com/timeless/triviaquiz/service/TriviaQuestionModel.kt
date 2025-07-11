package com.timeless.triviaquiz.service

import com.fasterxml.jackson.annotation.JsonProperty

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
)
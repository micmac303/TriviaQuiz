package com.timeless.triviaquiz.game

import java.io.Serializable

/**
 * A single quiz question prepared for play: the correct and incorrect answers
 * are combined into one shuffled [answers] list for display. Stored in the
 * HttpSession so the [correctAnswer] never reaches the browser and grading can
 * happen server-side.
 */
data class QuizQuestion(
    val index: Int,
    val number: Int,
    val category: String,
    val difficulty: String,
    val question: String,
    val answers: List<String>,
    val correctAnswer: String
) : Serializable

/** The outcome of a single question after the player submits their answers. */
data class AnsweredQuestion(
    val question: String,
    val yourAnswer: String?,
    val correctAnswer: String,
    val correct: Boolean
)

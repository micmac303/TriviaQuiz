package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.AnsweredQuestion
import com.timeless.triviaquiz.game.QuizQuestion
import com.timeless.triviaquiz.service.TriviaQuestionService
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class GameController(
    private val triviaQuestionService: TriviaQuestionService
) {
    companion object {
        const val QUESTION_COUNT = 5
        const val SESSION_KEY = "quizQuestions"
        val ALLOWED_DIFFICULTIES = setOf("easy", "medium", "hard")
    }

    /** Starts a new game: fetch questions, stash them in the session, render the form. */
    @GetMapping("/game")
    suspend fun newGame(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) difficulty: String?,
        model: Model,
        session: HttpSession
    ): String {
        // "all" (or anything non-numeric/absent) means no category filter.
        val categoryId = category?.toIntOrNull()
        // Only pass a recognised difficulty; "any"/absent means no filter.
        val difficultyFilter = difficulty?.takeIf { it in ALLOWED_DIFFICULTIES }
        val questions = triviaQuestionService.startGame(QUESTION_COUNT, categoryId, difficultyFilter)
        session.setAttribute(SESSION_KEY, questions)
        model["title"] = "Trivia Quiz"
        model["questions"] = questions
        return "game"
    }

    /** Grades the submitted answers against the questions held in the session. */
    @PostMapping("/game")
    fun submitGame(
        @RequestParam params: Map<String, String>,
        model: Model,
        session: HttpSession
    ): String {
        @Suppress("UNCHECKED_CAST")
        val questions = session.getAttribute(SESSION_KEY) as? List<QuizQuestion>
            ?: return "redirect:/game"

        val results = questions.map { question ->
            val given = params["answer-${question.index}"]
            AnsweredQuestion(
                question = question.question,
                yourAnswer = given,
                correctAnswer = question.correctAnswer,
                correct = given == question.correctAnswer
            )
        }

        session.removeAttribute(SESSION_KEY)

        model["title"] = "Results"
        model["results"] = results
        model["score"] = results.count { it.correct }
        model["total"] = questions.size
        return "result"
    }
}

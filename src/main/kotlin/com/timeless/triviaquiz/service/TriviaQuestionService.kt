package com.timeless.triviaquiz.service

import com.timeless.triviaquiz.game.QuizQuestion
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class TriviaQuestionService(private val webClient: WebClient) {

    /**
     * Fetches [amount] questions and prepares them for a game: each question's
     * correct and incorrect answers are merged into a single shuffled list.
     */
    suspend fun startGame(amount: Int): List<QuizQuestion> {
        return getNewQuestions(amount, difficulty = "easy").mapIndexed { index, question ->
            val answers = (question.incorrectAnswers + question.correctAnswer).shuffled()
            QuizQuestion(
                index = index,
                number = index + 1,
                category = question.category,
                difficulty = question.difficulty,
                question = question.question,
                answers = answers,
                correctAnswer = question.correctAnswer
            )
        }
    }

    /**
     * Fetches a specified number of trivia questions from the Open Trivia Database.
     * @param amount The number of questions to fetch.
     * @param difficulty Optional difficulty filter (e.g. "easy", "medium", "hard").
     * @return A list of TriviaQuestion objects.
     */
    suspend fun getNewQuestions(amount: Int, difficulty: String? = null): List<TriviaQuestion> {
        val response = webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/api.php")
                    .queryParam("amount", amount)
                if (difficulty != null) {
                    uriBuilder.queryParam("difficulty", difficulty)
                }
                uriBuilder.build()
            }
            .retrieve()
            .awaitBody<TriviaApiResponse>()

        return response.results.map { it.decoded() }
    }
}
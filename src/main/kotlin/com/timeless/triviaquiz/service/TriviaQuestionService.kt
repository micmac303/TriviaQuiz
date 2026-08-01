package com.timeless.triviaquiz.service

import com.timeless.triviaquiz.game.QuizQuestion
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class TriviaQuestionService(private val webClient: WebClient) {

    /** Fetches the list of categories the player can choose from. */
    suspend fun getCategories(): List<TriviaCategory> {
        return webClient.get()
            .uri("/api_category.php")
            .retrieve()
            .awaitBody<TriviaCategoryResponse>()
            .categories
    }

    /**
     * Fetches [amount] questions and prepares them for a game: each question's
     * correct and incorrect answers are merged into a single shuffled list.
     * @param categoryId Optional category to restrict questions to; null means any category.
     * @param difficulty Optional difficulty filter ("easy"/"medium"/"hard"); null means any difficulty.
     */
    suspend fun startGame(
        amount: Int,
        categoryId: Int? = null,
        difficulty: String? = null
    ): List<QuizQuestion> {
        return getNewQuestions(amount, difficulty = difficulty, categoryId = categoryId).mapIndexed { index, question ->
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
     * @param categoryId Optional category id to restrict questions to; null means any category.
     * @return A list of TriviaQuestion objects.
     */
    suspend fun getNewQuestions(
        amount: Int,
        difficulty: String? = null,
        categoryId: Int? = null
    ): List<TriviaQuestion> {
        val response = webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/api.php")
                    .queryParam("amount", amount)
                if (difficulty != null) {
                    uriBuilder.queryParam("difficulty", difficulty)
                }
                if (categoryId != null) {
                    uriBuilder.queryParam("category", categoryId)
                }
                uriBuilder.build()
            }
            .retrieve()
            .awaitBody<TriviaApiResponse>()

        return response.results.map { it.decoded() }
    }
}
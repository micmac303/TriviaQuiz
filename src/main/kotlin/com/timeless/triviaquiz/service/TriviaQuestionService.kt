package com.timeless.triviaquiz.service

import com.timeless.triviaquiz.game.QuizQuestion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class TriviaQuestionService(private val webClient: WebClient) {

    companion object {
        /** How many questions to pull per opentdb call when refilling a buffer. */
        private const val QUESTION_BATCH = 5
    }

    // opentdb's category list is effectively static, so fetch it once and reuse it.
    @Volatile
    private var cachedCategories: List<TriviaCategory>? = null
    private val categoriesMutex = Mutex()

    // Small per-(category, difficulty) buffers of prepared questions. opentdb rate-limits to
    // ~1 request / 5s per IP, so we pull questions in batches and hand them out one at a time.
    private data class QuestionKey(val categoryId: Int, val difficulty: String)
    private val questionBuffers = HashMap<QuestionKey, ArrayDeque<QuizQuestion>>()
    private val questionBuffersMutex = Mutex()

    /** Fetches (and then caches) the list of categories the player can choose from. */
    suspend fun getCategories(): List<TriviaCategory> {
        cachedCategories?.let { return it }
        return categoriesMutex.withLock {
            cachedCategories ?: webClient.get()
                .uri("/api_category.php")
                .retrieve()
                .awaitBody<TriviaCategoryResponse>()
                .categories
                .also { cachedCategories = it }
        }
    }

    /**
     * Returns one prepared question for the given [categoryId] and [difficulty], served from a
     * small buffer that is refilled [QUESTION_BATCH] at a time. This cuts calls to opentdb (which
     * rate-limits to ~1 request / 5s per IP): the first request for a combination fetches a batch,
     * the next few are served instantly from memory. Returns null if opentdb has no questions for
     * the combination.
     */
    suspend fun getBufferedQuestion(categoryId: Int, difficulty: String): QuizQuestion? {
        val key = QuestionKey(categoryId, difficulty)

        questionBuffersMutex.withLock {
            questionBuffers[key]?.takeIf { it.isNotEmpty() }?.let { return it.removeFirst() }
        }

        // Buffer empty — refill. Fetch outside the lock so a slow call doesn't block other
        // categories. opentdb returns nothing when it can't fill a batch (sparse combos), so
        // fall back to a single question in that case.
        var batch = startGame(QUESTION_BATCH, categoryId, difficulty)
        if (batch.isEmpty()) {
            batch = startGame(1, categoryId, difficulty)
        }
        if (batch.isEmpty()) return null

        return questionBuffersMutex.withLock {
            questionBuffers.getOrPut(key) { ArrayDeque() }.addAll(batch.drop(1))
            batch.first()
        }
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
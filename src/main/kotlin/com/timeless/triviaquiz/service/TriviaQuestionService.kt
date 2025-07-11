package com.timeless.triviaquiz.service

import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

@Service
class TriviaQuestionService(private val webClient: WebClient) {

    /**
     * Fetches a specified number of trivia questions from the Open Trivia Database.
     * @param amount The number of questions to fetch.
     * @return A list of TriviaQuestion objects.
     */
    suspend fun getNewQuestions(amount: Int): List<TriviaQuestion> {
        val response = webClient.get()
            .uri { uriBuilder ->
                uriBuilder
                    .path("/api.php")
                    .queryParam("amount", amount)
                    .build()
            }
            .retrieve()
            .awaitBody<TriviaApiResponse>()

        return response.results
    }
}
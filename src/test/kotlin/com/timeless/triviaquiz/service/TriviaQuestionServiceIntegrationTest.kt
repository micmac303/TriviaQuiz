package com.timeless.triviaquiz.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class TriviaQuestionServiceIntegrationTest {

    @Autowired
    // The type is updated to your newly named service.
    private lateinit var triviaQuestionService: TriviaQuestionService

    @Test
    fun `getNewQuestions should fetch questions from the live API`() = runBlocking {
        // Arrange
        val numberOfQuestions = 5

        // Act: Call the service method
        // The call now uses the autowired triviaQuestionService.
        val questions = triviaQuestionService.getNewQuestions(numberOfQuestions)

        // Assert: Check the results
        println("Fetched ${questions.size} questions. The first one is: ${questions.firstOrNull()}")

        assertEquals(numberOfQuestions, questions.size, "The number of fetched questions should match the requested amount.")
        assertTrue(questions.isNotEmpty(), "The questions list should not be empty.")

        val firstQuestion = questions.first()
        assertTrue(firstQuestion.question.isNotBlank(), "The question text should not be blank.")
        assertTrue(firstQuestion.correctAnswer.isNotBlank(), "The correct answer should not be blank.")
    }
}
package com.timeless.triviaquiz.service

class TriviaQuestionService {


    fun getTriviaQuestions(): List<String> {


        // This is a placeholder implementation.
        // In a real application, this method would fetch trivia questions from a database or an external API.
        return listOf(
            "What is the capital of France?",
            "Who wrote 'To Kill a Mockingbird'?",
            "What is the largest planet in our solar system?"
        )
    }
}
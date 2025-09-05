package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.service.TriviaQuestionService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping

@Controller
class QuestionListController(
    private val triviaQuestionService: TriviaQuestionService
) {

    @GetMapping("/questions")
    suspend fun home(model: Model): String {
        model["title"] = "Questions"
        model["questions"] = triviaQuestionService.getNewQuestions(10)
        return "questions"
    }
}
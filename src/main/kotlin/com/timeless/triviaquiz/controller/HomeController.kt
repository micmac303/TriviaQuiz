package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.service.TriviaQuestionService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(
    private val triviaQuestionService: TriviaQuestionService
) {

    @GetMapping("/")
    suspend fun home(model: Model): String {
        model["title"] = "Home"
        model["categories"] = triviaQuestionService.getCategories()
        return "home"
    }
}
package com.timeless.triviaquiz.webclient

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {

    @Bean
    fun webClient(builder: WebClient.Builder): WebClient {
        return builder
            .baseUrl("https://opentdb.com")
            .filter(logResponse())
            .build()
    }

    private fun logResponse(): ExchangeFilterFunction {
        return ExchangeFilterFunction.ofResponseProcessor { response ->
            response.bodyToMono(String::class.java)
                .doOnNext { body ->
                    println("Response JSON: $body")
                }
                .map { body ->
                    // Create a new response with the same body to avoid consuming it
                    ClientResponse.create(response.statusCode())
                        .headers { headers -> headers.addAll(response.headers().asHttpHeaders()) }
                        .body(body)
                        .build()
                }
        }
    }
}
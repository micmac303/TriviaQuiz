# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew build

# Run the app
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests TriviaQuizApplicationTests
./gradlew test --tests IntegratioinTests          # note: typo in filename is intentional
./gradlew test --tests TriviaQuestionServiceIntegrationTest
./gradlew test --tests RepositoriesTests

# Clean
./gradlew clean
```

> Java 21 is required (Java 24 is not yet supported by the Kotlin plugin).

## Architecture

Spring Boot 3.5 / Kotlin app that renders a trivia quiz using server-side Mustache templates. Data is fetched from the [Open Trivia Database](https://opentdb.com) via a reactive `WebClient`.

Both `spring-boot-starter-web` (MVC) and `spring-boot-starter-webflux` are on the classpath intentionally: MVC handles HTTP controllers, WebFlux provides the `WebClient` used to call the opentdb API.

**Package root:** `com.timeless.triviaquiz`

| Layer | Location | Notes |
|---|---|---|
| Controller | `controller/HomeController.kt` | Serves `/` with Mustache template — does not yet inject `TriviaQuestionService` |
| Service | `service/TriviaQuestionService.kt` | Calls opentdb API using a suspend function + `WebClient` |
| Data models | `service/TriviaQuestionModel.kt` | `TriviaApiResponse` / `TriviaQuestion` — opentdb returns HTML-entity-encoded strings |
| JPA entity | `entity/Entities.kt` | `User` entity (login, firstname, lastname, description) |
| Repository | `repository/Repositories.kt` | `UserRepository` with `findByLogin` |
| WebClient config | `webclient/WebClientConfig.kt` | Base URL `https://opentdb.com`; `logResponse()` filter is a dev debug aid that consumes and reconstructs the response body |
| Extensions | `extension/Extensions.kt` | `LocalDateTime.format()`, `String.toSlug()` |
| Templates | `resources/templates/` | `header.mustache`, `home.mustache`, `footer.mustache` |

**Database:** H2 in-memory. Hibernate `globally_quoted_identifiers` is enabled (configured in `application.properties`).

**Reactive/coroutine boundary:** The service layer uses Kotlin coroutines (`suspend` functions + `kotlinx-coroutines-reactor`). Tests for suspend functions use `runBlocking`.

## Tests

| File | Annotation | What it tests |
|---|---|---|
| `TriviaQuizApplicationTests.kt` | `@SpringBootTest` | Context loads |
| `IntegratioinTests.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | Home page HTTP response via `TestRestTemplate` |
| `service/TriviaQuestionServiceIntegrationTest.kt` | `@SpringBootTest` | Live call to opentdb API — will fail if network is unavailable |
| `repository/Reppositories.kt` | `@DataJpaTest` | `findByLogin` query |

JUnit 5 lifecycle is set to `per_class` in `src/test/resources/junit-platform.properties`.

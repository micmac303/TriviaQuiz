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
./gradlew test --tests IntegrationTests
./gradlew test --tests TriviaQuestionServiceIntegrationTest
./gradlew test --tests RepositoriesTests
./gradlew test --tests MultiplayerGameTest

# Clean
./gradlew clean
```

> Java 21 is required (Java 24 is not yet supported by the Kotlin plugin).

## Architecture

Spring Boot 3.5 / Kotlin app that renders a trivia quiz using server-side Mustache templates. Data is fetched from the [Open Trivia Database](https://opentdb.com) via a reactive `WebClient`.

Both `spring-boot-starter-web` (MVC) and `spring-boot-starter-webflux` are on the classpath intentionally: MVC handles HTTP controllers, WebFlux provides the `WebClient` used to call the opentdb API.

**Game modes** (both are hotseat: all state lives in the `HttpSession`, one browser):
- **Single-player** (`GameController`, `/game`): 5 questions with optional category/difficulty filters, graded together, 2-minute timer.
- **Multiplayer** (`MultiplayerGameController`, `/multiplayer`): players each pick 6 categories and take turns answering easy → medium → hard questions drawn from their remaining categories. A wrong answer ends the turn; the first player to clear all 6 categories wins. The turn rules live in a pure `MultiplayerGame` state machine (`game/MultiplayerModels.kt`) so they can be unit-tested without the network.

**Package root:** `com.timeless.triviaquiz`

| Layer | Location | Notes |
|---|---|---|
| Controllers | `controller/` | `HomeController` (`/`), `QuestionListController` (`/questions`), `GameController` (single-player `/game`), `MultiplayerGameController` (`/multiplayer`) |
| Service | `service/TriviaQuestionService.kt` | opentdb calls via suspend + `WebClient`. Caches the category list and buffers questions per (category, difficulty) — `getBufferedQuestion()` — to stay under opentdb's ~1 request / 5s rate limit |
| API models | `service/TriviaQuestionModel.kt` | `TriviaApiResponse` / `TriviaQuestion` / `TriviaCategory` — opentdb returns HTML-entity-encoded strings (`decoded()` unescapes them) |
| Game models | `game/` | `QuizQuestion` / `AnsweredQuestion` (`GameModels.kt`); `MultiplayerGame` state machine + `MpPlayer` / `MpCategory` / `TurnOutcome` (`MultiplayerModels.kt`). All `Serializable` for session storage |
| JPA entity | `entity/Entities.kt` | `User` entity (login, firstname, lastname, description) |
| Repository | `repository/Repositories.kt` | `UserRepository` with `findByLogin` |
| WebClient config | `webclient/WebClientConfig.kt` | Base URL `https://opentdb.com`; `logResponse()` filter is a dev debug aid that consumes and reconstructs the response body |
| Extensions | `extension/Extensions.kt` | `LocalDateTime.format()`, `String.toSlug()` |
| Templates | `resources/templates/` | `header`/`footer` partials wrap every page: `header` emits the full `<head>` (charset, viewport, stylesheet link), the branded header bar, and opens `<main class="container">`; `footer` closes it. Pages: `home`, `game`, `result`, `questions` (single-player); `mp-setup`, `mp-play`, `mp-feedback`, `mp-winner`, `mp-message` (multiplayer) |
| Styling | `resources/static/css/styles.css` | Single stylesheet (blue/black/white theme, CSS custom properties). Served at `/css/styles.css` from Spring Boot's static resources. Templates carry a few semantic hooks: `.player`, `.error`, `.banner.ok` / `.banner.bad` |

**Database:** H2 in-memory. Hibernate `globally_quoted_identifiers` is enabled (configured in `application.properties`).

**Client-side JS:** Kept inline in the templates, no build step or framework. Two spots: `game.mustache`'s 2-minute countdown that auto-submits the quiz, and `mp-setup.mustache`'s "Pick 6 random categories" button that checks six category boxes at random (server still re-validates the selection).

**Reactive/coroutine boundary:** The service layer uses Kotlin coroutines (`suspend` functions + `kotlinx-coroutines-reactor`). Tests for suspend functions use `runBlocking`.

## Tests

| File | Annotation | What it tests |
|---|---|---|
| `TriviaQuizApplicationTests.kt` | `@SpringBootTest` | Context loads |
| `IntegrationTests.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | Home page HTTP response via `TestRestTemplate` |
| `service/TriviaQuestionServiceIntegrationTest.kt` | `@SpringBootTest` | Live call to opentdb API — will fail if network is unavailable |
| `repository/Repositories.kt` | `@DataJpaTest` | `findByLogin` query |
| `game/MultiplayerGameTest.kt` | plain JUnit 5 | `MultiplayerGame` turn transitions (no Spring, no network) |

JUnit 5 lifecycle is set to `per_class` in `src/test/resources/junit-platform.properties`.

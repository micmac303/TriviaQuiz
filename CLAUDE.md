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

# Run a single test class (class names are listed under Tests below)
./gradlew test --tests MultiplayerGameTest

# Run a single test method (wildcards avoid quoting backtick-named Kotlin tests)
./gradlew test --tests 'MultiplayerGameTest.*wrong answer*'

# Run only the tests that don't need the network
./gradlew test --tests MultiplayerGameTest --tests GameRoomTest --tests RoomLobbyIntegrationTest --tests RepositoriesTests

# Clean
./gradlew clean
```

> Java 21 is required (Java 24 is not yet supported by the Kotlin plugin).

**Hosting a networked game** (`caffeinate` stops the laptop sleeping mid-game and killing the server):

```bash
caffeinate -i ./gradlew bootRun
```

Then open `/host` locally for the join address. Everyone must be on the same Wi-Fi — guest networks
with client isolation block phone → laptop entirely — and macOS will prompt to allow incoming
connections for the JVM the first time.

## Architecture

Spring Boot 3.5 / Kotlin app that renders a trivia quiz using server-side Mustache templates. Data is fetched from the [Open Trivia Database](https://opentdb.com) via a reactive `WebClient`.

Both `spring-boot-starter-web` (MVC) and `spring-boot-starter-webflux` are on the classpath intentionally: MVC handles HTTP controllers, WebFlux provides the `WebClient` used to call the opentdb API.

**Game modes:**
- **Single-player** (`GameController`, `/game`): 5 questions with optional category/difficulty filters, graded together, 2-minute timer. State lives in the `HttpSession`.
- **Hotseat multiplayer** (`MultiplayerGameController`, `/multiplayer`): players each pick 6 categories and take turns answering easy → medium → hard questions drawn from their remaining categories. A wrong answer ends the turn; the first player to clear all 6 categories wins. All state in one browser's `HttpSession`. The turn rules live in a pure `MultiplayerGame` state machine (`game/MultiplayerModels.kt`) so they can be unit-tested without the network.
- **Networked multiplayer** (`HostController` `/host`, `JoinController` `/j/{code}` + `/play`, `RoomApiController` `/api/room/...`): the same rules, but everyone plays on their own phone over the LAN. Run the app on a laptop, open `/host` there for the join address and room code, and players browse to it — no app to install.

**How networked mode differs from hotseat.** The game rules are shared and unchanged; what changes is where state lives and who is allowed to act:

| | Hotseat | Networked |
|---|---|---|
| Game state | the one browser's `HttpSession` | `GameRoom` in the server-side registry (`GameRoomService`) |
| A session holds | the whole game | only `playerId` + `roomCode` (`RoomSession`) |
| Concurrency | naturally serial | `GameRoom.mutex`; `version` counter guards double-submits |
| Screen updates | a page load per action | `room.js` polls `/api/room/{code}/state` once a second |
| Who may answer | whoever posts | only the session whose `playerId` matches `currentPlayerId()` |

Rooms are in memory only: restarting the server ends games in progress, and idle rooms are reaped after 2 hours.

**Two things to keep in mind when touching the networked path:**
- **Never serialise `QuizQuestion` to the client** — it carries `correctAnswer`. That was safe when Mustache rendered server-side; over JSON it would hand every phone the answer. `QuestionView` in `RoomApiController.kt` exists solely to omit it.
- **opentdb rate-limits to ~1 request / 5s per IP.** Several devices polling makes this easy to trip, and a 429 surfaces as a thrown `WebClientResponseException`. `TriviaQuestionService.rateLimited()` spaces outbound calls, `QuestionDealer` swallows fetch failures rather than 500ing a poll, and `GameRoom.dealing` ensures only one poller fetches at a time.

**Package root:** `com.timeless.triviaquiz`

| Layer | Location | Notes |
|---|---|---|
| Controllers | `controller/` | `HomeController` (`/`), `QuestionListController` (`/questions`), `GameController` (single-player `/game`), `MultiplayerGameController` (hotseat `/multiplayer`), `HostController` (`/host`), `JoinController` (`/j/{code}`, `/play`), `RoomApiController` (`/api/room/...` JSON) |
| Session keys | `controller/RoomSession.kt` | The three `HttpSession` attribute names used by the networked game |
| Service | `service/TriviaQuestionService.kt` | opentdb calls via suspend + `WebClient`. Caches the category list, buffers questions per (category, difficulty) — `getBufferedQuestion()` / `prefetch()` — and serialises outbound calls through `rateLimited()` to stay under opentdb's ~1 request / 5s limit |
| Question dealing | `service/QuestionDealer.kt` | Puts the next question in front of a `MultiplayerGame`'s current player; shared by both multiplayer modes. Returns false rather than throwing when opentdb refuses |
| Room registry | `service/GameRoomService.kt` | `ConcurrentHashMap` of live rooms, join-code generation, idle reaping. Also holds the room-wide tunables (`MIN_PLAYERS`, `FEEDBACK_DURATION`, `DEAL_RETRY_DELAY`) |
| API models | `service/TriviaQuestionModel.kt` | `TriviaApiResponse` / `TriviaQuestion` / `TriviaCategory` — opentdb returns HTML-entity-encoded strings (`decoded()` unescapes them) |
| Game models | `game/` | `QuizQuestion` / `AnsweredQuestion` (`GameModels.kt`); `MultiplayerGame` state machine + `MpPlayer` / `MpCategory` / `TurnOutcome` (`MultiplayerModels.kt`), `Serializable` for session storage; `GameRoom` / `LobbyPlayer` / `RoomFeedback` / `RoomPhase` (`GameRoom.kt`) for the networked game |
| JPA entity | `entity/Entities.kt` | `User` entity (login, firstname, lastname, description) |
| Repository | `repository/Repositories.kt` | `UserRepository` with `findByLogin` |
| WebClient config | `webclient/WebClientConfig.kt` | Base URL `https://opentdb.com`; `logResponse()` filter is a dev debug aid that consumes and reconstructs the response body |
| Extensions | `extension/Extensions.kt` | `LocalDateTime.format()`, `String.toSlug()` |
| Templates | `resources/templates/` | `header`/`footer` partials wrap every page: `header` emits the full `<head>` (charset, viewport, stylesheet link), the branded header bar, and opens `<main class="container">`; `footer` closes it. Pages: `home`, `game`, `result`, `questions` (single-player); `mp-setup`, `mp-play`, `mp-feedback`, `mp-winner`, `mp-message` (hotseat); `host`, `join`, `play` (networked) |
| Styling | `resources/static/css/styles.css` | Single stylesheet (blue/black/white theme, CSS custom properties). Served at `/css/styles.css` from Spring Boot's static resources. Templates carry a few semantic hooks: `.player`, `.error`, `.banner.ok` / `.banner.bad`, and for the networked game `.join-card`, `.roster`, `.answer`, `.room-status` |

**Database:** H2 in-memory. Hibernate `globally_quoted_identifiers` is enabled (configured in `application.properties`).

**Client-side JS:** No build step or framework. Small one-off scripts stay inline (`game.mustache`'s 2-minute countdown that auto-submits the quiz); anything shared by two templates lives in `resources/static/js/`:
- `pick-categories.js` — the "pick N random categories" button, used by `mp-setup` and `join` (the server re-validates the selection either way).
- `room.js` — the networked game's whole client: polls the room state once a second and redraws. Both `host.mustache` and `play.mustache` call `startRoom({code, mount})`; the server decides what each device may see, so the host simply never comes back as "you" and therefore never gets answer buttons. Builds DOM with `textContent`, never `innerHTML` — opentdb question text arrives with entities already decoded and can contain markup.

**Reactive/coroutine boundary:** The service layer uses Kotlin coroutines (`suspend` functions + `kotlinx-coroutines-reactor`). Tests for suspend functions use `runBlocking`.

## Tests

| File | Annotation | What it tests |
|---|---|---|
| `TriviaQuizApplicationTests.kt` | `@SpringBootTest` | Context loads |
| `IntegrationTests.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | Home page HTTP response via `TestRestTemplate` |
| `service/TriviaQuestionServiceIntegrationTest.kt` | `@SpringBootTest` | Live call to opentdb API — will fail if network is unavailable |
| `repository/Repositories.kt` | `@DataJpaTest` | `findByLogin` query |
| `game/MultiplayerGameTest.kt` | plain JUnit 5 | `MultiplayerGame` turn transitions (no Spring, no network) |
| `game/GameRoomTest.kt` | plain JUnit 5 | `GameRoom` phases, player-id ↔ seat mapping, reset (no Spring, no network) |
| `controller/RoomLobbyIntegrationTest.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | The networked join flow over real HTTP with several cookie jars standing in for devices: page rendering, per-device state, and that a device which isn't up can't answer. Stubs `TriviaQuestionService.getCategories()` and stops before starting a game, so it passes with the network unplugged |

> `RoomLobbyIntegrationTest` replaces the injected `TriviaQuestionService` with a subclass via `@TestConfiguration` + `@Primary`. This works because `kotlin("plugin.spring")` opens `@Service` classes and their members.

JUnit 5 lifecycle is set to `per_class` in `src/test/resources/junit-platform.properties`.

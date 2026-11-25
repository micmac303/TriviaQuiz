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
./gradlew test --tests ThemePartyScoringTest
./gradlew test --tests ThemePartyGameTest
./gradlew test --tests PartyFlowIntegrationTest
./gradlew test --tests ThemeLoaderTest

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

**Hosting online** (friends not on your Wi-Fi), through a Cloudflare quick tunnel:

```bash
cloudflared tunnel --url http://localhost:8080      # prints https://<random>.trycloudflare.com
./gradlew bootRun --args='--trivia.public-url=https://<random>.trycloudflare.com'
```

Open `/host` or `/party/host` on this computer; the public join link is listed first. Stop the
tunnel when the game ends.

**Online safety (`controller/OnlineAccess.kt`).** The tunnel runs on this computer, so internet
requests arrive from localhost. `isFromInternet()` spots them by Cloudflare/proxy headers or a public
client address (`server.forward-headers-strategy=native` makes Tomcat use the forwarded address).
`OnlineAccessFilter` lets those requests reach only `/j/**`, `/play`, `/play/leave`, `/api/room/**`,
`/css/**`, `/js/**`; everything else, including all host pages and theme admin, is 404.
`isHostComputer()` also rejects them. Any new player-facing route must be added to the filter's
allowlist; anything else is host-only by default. Player names are capped at
`JoinController.MAX_NAME_LENGTH` (24).

## Architecture

Spring Boot 3.5 / Kotlin app that renders a trivia quiz using server-side Mustache templates. Data is fetched from the [Open Trivia Database](https://opentdb.com) via a reactive `WebClient`.

Both `spring-boot-starter-web` (MVC) and `spring-boot-starter-webflux` are on the classpath intentionally: MVC handles HTTP controllers, WebFlux provides the `WebClient` used to call the opentdb API.

**Game modes:**
- **Single-player** (`GameController`, `/game`): 5 questions with optional category/difficulty filters, graded together, 2-minute timer. State lives in the `HttpSession`.
- **Hotseat multiplayer** (`MultiplayerGameController`, `/multiplayer`): players each pick 6 categories and take turns answering easy → medium → hard questions drawn from their remaining categories. A wrong answer ends the turn; the first player to clear all 6 categories wins. All state in one browser's `HttpSession`. The turn rules live in a pure `MultiplayerGame` state machine (`game/MultiplayerModels.kt`) so they can be unit-tested without the network.
- **Networked multiplayer** (`HostController` `/host`, `JoinController` `/j/{code}` + `/play`, `RoomApiController` `/api/room/...`): the same rules, but everyone plays on their own phone over the LAN. Run the app on a laptop, open `/host` there for the join address and room code, and players browse to it — no app to install.
- **Theme Party** (`PartyHostController` `/party/host`, then the same `/j/{code}`, `/play` and `/api/room/...` as networked multiplayer): the host picks how many rounds of each difficulty (0 to 20 each; by default 1 of each), each round one turn per player in join order. On a turn the picker chooses a theme, everyone answers one question from it, then the picker scores the number of players if right, less 1 per other right answer (`scoreQuestion` in `game/ThemePartyScoring.kt`), and each of those others gets `GUESSER_POINTS` (1). Whoever answered correctly first, picker included, also gets `FIRST_CORRECT_POINTS` (1); `PartyTurn`'s answers are kept in arrival order for this, and a player who left before the reveal doesn't count. Once a player has `STREAK_LENGTH` (3) correct answers in a row, each correct answer earns `STREAK_POINTS` (1) extra; a wrong or missing answer resets the streak. Highest score wins; ties are shared.
- **Theme flash:** after a pick, `PartyTurn.opensAt` is `trivia.party.theme-flash-ms` (1000) later. Until then `PartyView.question` is null (only `theme` and `millisUntilQuestion` are sent), answers are refused, and the clock hasn't started. `room.js` shows the theme big, and polls just as it ends. `ThemePartyGame.themeFlash` defaults to zero so unit tests can answer at once; `PartyFlowIntegrationTest` sets the property to 0.
- **Tiebreak:** with `ThemePartyGame.tiebreak` (`trivia.party.tiebreak`, on; off by default in unit tests so level games still finish), a tie for the lead after the last turn goes to `PartyStage.TIEBREAK`. The next request deals a question (`dealTiebreak`, from `readPartyState`) from a random theme played this game, else any available, else the win is shared. Only `tiebreakers` may answer; the first right answer reveals at once and wins `TIEBREAK_POINTS`; if none is right, Next waits for another. A tied player leaving gives the other the win. Normal scoring, streaks and the first-correct point don't apply.
- **Hidden themes:** the host's Hide/Show (`/party/host/hide-theme`) toggles a theme in `GameRoom.hiddenThemes`. `RoomApiController.loadThemes(room)` leaves hidden ones out, so they're never dealt, picked or flagged; one already on offer is swapped out like a removed theme. Allowed mid-game, kept through "Play again", files untouched. A game needs one theme that isn't hidden to start. Players waiting in the lobby can hide and show themes too (`/api/room/{code}/party/hide`, sends the wanted state, not a toggle; lobby only, joined players only, no version bump): the lobby's `PartyView.lobbyThemes` lists every theme with its hidden state, and `room.js` passes it to the host page in a `lobbythemes` event so the host's list stays in sync.
- **Bad-theme flags:** the picker can flag an offered theme (`/api/room/{code}/party/flag`), once per player per theme per room (`GameRoom.flagTheme`). The count is saved as top-level `"flags"` in the theme file and shown only on the host page, most flagged first. Players only see whether they flagged it. Flagging doesn't bump `version`.

**How Theme Party works.** A `GameRoom` has a `mode`; party rooms hold a `ThemePartyGame` in `party` and never set `game`.
- Themes are `themes/*.json` in the working directory (override with `trivia.themes-dir`), re-read on each request by `ThemeLoader`. Format: `{"theme", "questions": [{"difficulty", "question", "correct_answer", "incorrect_answers", "explanation", "used"?}]}`. Invalid files are skipped and listed on the host page.
- `THEME_QUESTION_SPLIT` (`game/ThemeModels.kt`) sets the difficulties, their order and the minimum of each a theme file needs, whatever rounds are chosen (2 easy, 2 medium, 2 hard). `DEFAULT_ROUND_COUNTS` is the host's starting choice, and `roundDifficulties` turns a choice into the list of rounds.
- The only timer is the optional time limit on answering (constants in `game/ThemePartyGame.kt`). It has no scheduler: the first poll after the deadline reveals. Polling never moves on from a reveal. The last answer also causes the reveal, any player's `/party/next` ends it (exact version match, so the first tap wins), and the host's `/party/host/kick` removes a player nobody should wait for, which can also cause the reveal. So can a player's `/play/leave`: both go through `removeFromParty`.
- **The app writes to the theme files:** `"used": true` is saved at each reveal, outside the room mutex. Draws prefer questions not asked this game, then unused ones. Any test or manual run that plays a game must point `trivia.themes-dir` at a copy.
- With the theme limit (the room's default), `themeChoices` deals the picker `themesOffered()` ids: `THEMES_OFFERED` (8) while everyone is level, otherwise `THEMES_FOR_LEADER` (4) for first place plus `THEMES_PER_PLACE` (2) per place below, capped at 8 (so 4, 6, then 8). Ties share a place (`pickerPlace()`), which the picker is told while picking.
- **Reveal hold:** Next is refused until the answer has been up `trivia.party.reveal-hold-ms` (1500) (`ThemePartyGame.revealHold`, zero in unit tests; `PartyFlowIntegrationTest` sets it to 0). The view sends `millisUntilNext` and `room.js` keeps the button disabled until then.
- The deal: `themeChoices` hands out the ids once per turn and `pickTheme` refuses any other. Only call it with the full theme list: an empty one clears the deal.
- Removing a theme moves it to `themes/removed/` (never deletes), and, like resetting used flags, is only allowed from the computer running the app, as any phone can open a host page.
- `PartyQuestionView` omits the answer and explanation; `PartyRevealView` is the only view with them and is built only during the reveal. Theme load errors must not quote file contents, for the same reason.
- `PartyPlayerView.correct` (who was right) is likewise null outside the reveal.
- Answers are checked against a version *range* (`questionsOpenedVersion..version`), because simultaneous answers each bump `version`. Picking a theme uses the exact-match check.
- The party client updates the DOM in place between screens. Don't make it redraw per version: it loses taps. The scoreboard is never rebuilt, or its bars wouldn't animate, and new screens ignore taps briefly so a double tap can't press Next for everyone.

**Room chat and reactions** (`ChatController` `/api/room/{code}/chat`, `.../chat/react`; `game/RoomChat.kt`; `static/js/chat.js`). Both networked modes, on `play`, `host` and `party-host`. Only the room's joined players and its host (author "Host") can read or post; leaving or being kicked ends access.
- `RoomChat` has its own lock and **never bumps `GameRoom.version`**: pick and Next need an exact version match, so a chat message would make those taps fail.
- Messages are cleaned to one line (control and bidi characters removed), max 200 chars, one per 600 ms per sender. The last 100 are kept, across "Play again".
- Reactions must be one of `RoomChat.REACTIONS` (the server sends the list to the client), with their own looser rate limit so they don't block typing. They're stored as chat lines with `reaction: true` so they reach every device, but `chat.js` doesn't show them in the chat log: it only floats them up everyone's screen (live ones only, not history; skipped with reduced motion).
- Views say `mine` rather than exposing who sent what. `chat.js` polls separately from `room.js` (1 s open, 2.5 s closed) and uses `textContent` only. Phones get a full-screen panel sized to `visualViewport` so the keyboard can't cover it; 700 px and wider get a floating window.

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
| Controllers | `controller/` | `HomeController` (`/`), `QuestionListController` (`/questions`), `GameController` (single-player `/game`), `MultiplayerGameController` (hotseat `/multiplayer`), `HostController` (`/host`), `JoinController` (`/j/{code}`, `/play`), `RoomApiController` (`/api/room/...` JSON), `PartyHostController` (`/party/host`); `PartyViews.kt` builds the party JSON, `PartyUsedQuestions.kt` saves used flags |
| Session keys | `controller/RoomSession.kt` | The `HttpSession` attribute names used by the networked game |
| Service | `service/TriviaQuestionService.kt` | opentdb calls via suspend + `WebClient`. Caches the category list, buffers questions per (category, difficulty) — `getBufferedQuestion()` / `prefetch()` — and serialises outbound calls through `rateLimited()` to stay under opentdb's ~1 request / 5s limit |
| Question dealing | `service/QuestionDealer.kt` | Puts the next question in front of a `MultiplayerGame`'s current player; shared by both multiplayer modes. Returns false rather than throwing when opentdb refuses |
| Room registry | `service/GameRoomService.kt` | `ConcurrentHashMap` of live rooms, join-code generation, idle reaping. Also holds the room-wide tunables (`MIN_PLAYERS`, `FEEDBACK_DURATION`, `DEAL_RETRY_DELAY`) |
| API models | `service/TriviaQuestionModel.kt` | `TriviaApiResponse` / `TriviaQuestion` / `TriviaCategory` — opentdb returns HTML-entity-encoded strings (`decoded()` unescapes them) |
| Game models | `game/` | `QuizQuestion` / `AnsweredQuestion` (`GameModels.kt`); `MultiplayerGame` state machine + `MpPlayer` / `MpCategory` / `TurnOutcome` (`MultiplayerModels.kt`), `Serializable` for session storage; `GameRoom` / `LobbyPlayer` / `RoomFeedback` / `RoomPhase` (`GameRoom.kt`) for the networked game |
| JPA entity | `entity/Entities.kt` | `User` entity (login, firstname, lastname, description) |
| Repository | `repository/Repositories.kt` | `UserRepository` with `findByLogin` |
| WebClient config | `webclient/WebClientConfig.kt` | Base URL `https://opentdb.com`; `logResponse()` filter is a dev debug aid that consumes and reconstructs the response body |
| Extensions | `extension/Extensions.kt` | `LocalDateTime.format()`, `String.toSlug()` |
| Templates | `resources/templates/` | `header`/`footer` partials wrap every page: `header` emits the full `<head>` (charset, viewport, stylesheet link), the branded header bar, and opens `<main class="container">`; `footer` closes it. Pages: `home`, `game`, `result`, `questions` (single-player); `mp-setup`, `mp-play`, `mp-feedback`, `mp-winner`, `mp-message` (hotseat); `host`, `join`, `play` (networked); `party-host`, `party-join` (Theme Party) |
| Styling | `resources/static/css/styles.css` | Single stylesheet (blue/black/white theme, CSS custom properties). Served at `/css/styles.css` from Spring Boot's static resources. Templates carry a few semantic hooks: `.player`, `.error`, `.banner.ok` / `.banner.bad`, and for the networked game `.join-card`, `.roster`, `.answer`, `.room-status` |

**Database:** H2 in-memory. Hibernate `globally_quoted_identifiers` is enabled (configured in `application.properties`).

**Client-side JS:** No build step or framework. Small one-off scripts stay inline (`game.mustache`'s 2-minute countdown that auto-submits the quiz); anything shared by two templates lives in `resources/static/js/`:
- `pick-categories.js` — the "pick N random categories" button, used by `mp-setup` and `join` (the server re-validates the selection either way).
- `chat.js` — the room chat and reactions; see "Room chat and reactions" above.
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
| `game/ThemePartyScoringTest.kt` | plain JUnit 5 | Theme Party scoring (no Spring, no network) |
| `game/ThemePartyGameTest.kt` | plain JUnit 5 | Theme Party turns, rounds, kicking, question choice (no Spring, no network) |
| `controller/PartyFlowIntegrationTest.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | Theme Party over HTTP: answer, next, kick, used flags. Uses a temporary themes folder |
| `service/ThemeLoaderTest.kt` | plain JUnit 5 | Theme files: valid, malformed, too small, added later, marking and resetting used |
| `game/GameRoomTest.kt` | plain JUnit 5 | `GameRoom` phases, player-id ↔ seat mapping, reset (no Spring, no network) |
| `game/RoomChatTest.kt` | plain JUnit 5 | Chat cleaning, length, rate limits, history cap, reactions (fake clock) |
| `controller/ChatIntegrationTest.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | Chat and reactions between devices; strangers refused |
| `controller/OnlineAccessIntegrationTest.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | Tunnel traffic (proxy headers) reaches only player pages; host pages and theme admin are 404 |
| `controller/JoinUrlsTest.kt` | plain JUnit 5 | The public join URL is listed first when `trivia.public-url` is set |
| `controller/RoomLobbyIntegrationTest.kt` | `@SpringBootTest(webEnvironment=RANDOM_PORT)` | The networked join flow over real HTTP with several cookie jars standing in for devices: page rendering, per-device state, and that a device which isn't up can't answer. Stubs `TriviaQuestionService.getCategories()` and stops before starting a game, so it passes with the network unplugged |

> `RoomLobbyIntegrationTest` replaces the injected `TriviaQuestionService` with a subclass via `@TestConfiguration` + `@Primary`. This works because `kotlin("plugin.spring")` opens `@Service` classes and their members.

JUnit 5 lifecycle is set to `per_class` in `src/test/resources/junit-platform.properties`.

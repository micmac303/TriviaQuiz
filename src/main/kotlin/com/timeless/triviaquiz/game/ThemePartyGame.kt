package com.timeless.triviaquiz.game

import java.time.Duration
import java.time.Instant

/** With the time limit on, a question starts with this long for each player. */
val TIME_PER_PLAYER: Duration = Duration.ofSeconds(4)

/** How much each answer takes off the clock for those still to answer. */
val TIME_CUT_PER_ANSWER: Duration = Duration.ofSeconds(2)

/** Answers can't push the clock below this. From here it only counts down. */
val TIME_CUT_FLOOR: Duration = Duration.ofSeconds(3)

/**
 * Where a Theme Party game is within the current turn. [TIEBREAK] is the moment between a tie at
 * the end (or an unanswered tiebreak question) and the next tiebreak question being dealt.
 */
enum class PartyStage { PICKING, ANSWERING, REVEAL, TIEBREAK, FINISHED }

data class PartyPlayer(val playerId: String, val name: String)

/** One picker's turn: the question everyone is answering and, from the reveal on, its score. */
class PartyTurn(
    val themeId: String,
    val themeName: String,
    val question: PartyQuestion,
    /** What the clock started at, or null without a time limit. */
    val timeAllowed: Duration?,
    /** When the question is shown and answers open: after the theme's flash. */
    val opensAt: Instant
) {

    /** When answering ends. Null without a time limit, and once revealed. */
    var deadline: Instant? = timeAllowed?.let { opensAt.plus(it) }
        internal set

    /** False during the theme flash, when only the theme name is shown. */
    fun isOpen(now: Instant): Boolean = !now.isBefore(opensAt)

    /** When the answer was revealed, or null before. */
    var revealedAt: Instant? = null
        internal set

    /** Players whose streak earned them [STREAK_POINTS] at this reveal. */
    var streakBonusIds: Set<String> = emptySet()
        internal set

    // Insertion-ordered (a LinkedHashMap), which is how the first correct answer is known.
    private val answers = mutableMapOf<String, String>()

    var score: QuestionScore? = null
        internal set

    /** Who answered correctly first, set at the reveal. Null if nobody was right. */
    var firstCorrectId: String? = null
        internal set

    fun answerBy(playerId: String?): String? = playerId?.let { answers[it] }

    fun isCorrect(playerId: String): Boolean = answerBy(playerId) == question.correctAnswer

    /** The earliest correct answer from [among], so a player who has since left doesn't count. */
    internal fun firstCorrect(among: Collection<String>): String? =
        answers.entries.firstOrNull { it.value == question.correctAnswer && it.key in among }?.key

    /** Records an answer. The first one stands; returns false for a repeat or one not on offer. */
    internal fun record(playerId: String, answer: String): Boolean {
        if (answer !in question.answers) return false
        return answers.putIfAbsent(playerId, answer) == null
    }
}

/**
 * The Theme Party rules: a round is one turn per player, in join order, and a turn is one question
 * that everyone answers. A turn is revealed when all current players have answered or, with
 * [timeLimit], when the clock runs out. Only a player's [next] moves it on from there. Like
 * [MultiplayerGame] it has no Spring or network dependencies: time is passed in, and the caller
 * provides the locking ([GameRoom.mutex]).
 *
 * [bonusNeedsSecond], [timeLimit], [limitThemes] and the round counts are fixed at construction,
 * which is what locks the host's settings for the game.
 *
 * [themeFlash] is how long only the theme name shows before the question does. Answers are refused
 * until then, so nobody gets a head start on the first-correct point. The app sets it from
 * `trivia.party.theme-flash-ms` (1 second); it defaults to none so tests can answer straight away.
 *
 * With [tiebreak], a tie for the lead after the last round goes to a tiebreak: only the tied
 * players answer, one question at a time from a random theme played this game ([dealTiebreak]),
 * and the first of them to answer correctly wins [TIEBREAK_POINTS]. A wrong answer is out for that
 * question; if nobody is right, another is dealt. Off by default so tests that play a game to the
 * end with level scores still finish; the app turns it on.
 */
class ThemePartyGame(
    players: List<PartyPlayer>,
    val bonusNeedsSecond: Boolean,
    val timeLimit: Boolean = false,
    roundCounts: Map<String, Int> = DEFAULT_ROUND_COUNTS,
    val limitThemes: Boolean = false,
    val themeFlash: Duration = Duration.ZERO,
    /** How long the answer stays up before [next] is allowed. Zero by default, for tests. */
    val revealHold: Duration = Duration.ZERO,
    val tiebreak: Boolean = false
) {

    private val difficulties = roundDifficulties(roundCounts)

    val rounds: Int = difficulties.size

    init {
        require(rounds > 0) { "A game needs at least one round." }
    }

    private val seats = players.toMutableList()

    /** Everyone still in the game, in turn order. */
    val players: List<PartyPlayer>
        get() = seats

    var stage: PartyStage = PartyStage.PICKING
        private set

    var pickerIndex: Int = 0
        private set

    /** Zero-based. */
    var roundIndex: Int = 0
        private set

    /** Null while the picker is choosing and once the game has finished. */
    var turn: PartyTurn? = null
        private set

    private val scores = players.associate { it.playerId to 0 }.toMutableMap()

    // Correct answers in a row, per player. Any wrong or missing answer resets it.
    private val streaks = players.associate { it.playerId to 0 }.toMutableMap()

    // Kept as well as the files' used flags: once a theme is all used, or if saving a flag failed,
    // this is what stops a question coming back minutes after its answer was shown.
    private val askedQuestions = mutableSetOf<String>()

    private val playedThemes = mutableSetOf<String>()

    // This turn's choices, by id. Kept on the server, so reloading the page can't reshuffle them.
    private val offeredIds = mutableListOf<String>()

    /** The players in the tiebreak, in seat order; empty outside one. */
    var tiebreakers: List<String> = emptyList()
        private set

    /** How many tiebreak questions have been dealt. */
    var tiebreakRound: Int = 0
        private set

    val inTiebreak: Boolean
        get() = tiebreakers.isNotEmpty()

    /** This turn's picker. Meaningless in a tiebreak, which has none. */
    val picker: PartyPlayer
        get() = seats[pickerIndex]

    val difficulty: String
        get() = difficulties[roundIndex]

    val isOver: Boolean
        get() = stage == PartyStage.FINISHED

    /** Who picks after this turn, or null if it is the last one. */
    val nextPicker: PartyPlayer?
        get() {
            val lastTurn = roundIndex == difficulties.lastIndex && pickerIndex == seats.lastIndex
            return if (isOver || lastTurn || inTiebreak) null else seats[(pickerIndex + 1) % seats.size]
        }

    fun scoreOf(playerId: String): Int = scores.getValue(playerId)

    fun streakOf(playerId: String): Int = streaks[playerId] ?: 0

    fun canPick(playerId: String?): Boolean = stage == PartyStage.PICKING && playerId == picker.playerId

    fun hasAnswered(playerId: String): Boolean = turn?.answerBy(playerId) != null

    /** Whether [playerId] answers the current question: everyone, or in a tiebreak only those tied. */
    fun canAnswer(playerId: String): Boolean =
        if (inTiebreak) playerId in tiebreakers else isPlaying(playerId)

    /** Everyone sharing the highest score, or empty until the game is over. */
    fun winners(): List<PartyPlayer> = if (isOver) leaders() else emptyList()

    private fun leaders(): List<PartyPlayer> {
        val best = seats.maxOfOrNull { scoreOf(it.playerId) } ?: return emptyList()
        return seats.filter { scoreOf(it.playerId) == best }
    }

    /**
     * Deals the next tiebreak question, from a random theme played this game (any of [themes] if
     * none of those is left). With no theme at all the tie can't be broken, so the game ends shared.
     * Returns whether anything changed. [themes] must be the room's full list.
     */
    fun dealTiebreak(themes: List<Theme>, now: Instant = Instant.now()): Boolean {
        if (stage != PartyStage.TIEBREAK) return false
        val pool = themes.filter { it.id in playedThemes }.ifEmpty { themes }
        val theme = pool.randomOrNull()
        val question = theme?.drawQuestion(difficulty, askedQuestions)
        if (theme == null || question == null) {
            finish()
            return true
        }
        val timeAllowed = if (timeLimit) TIME_PER_PLAYER.multipliedBy(tiebreakers.size.toLong()) else null
        turn = PartyTurn(theme.id, theme.name, question, timeAllowed, opensAt = now.plus(themeFlash))
        tiebreakRound++
        stage = PartyStage.ANSWERING
        return true
    }

    /**
     * The picker's place, 1 for the lead, sharing it on a tie. Null while everyone is level (as at
     * the start), when nobody is ahead.
     */
    fun pickerPlace(): Int? {
        if (isOver || seats.map { scoreOf(it.playerId) }.distinct().size <= 1) return null
        val score = scoreOf(picker.playerId)
        return seats.count { scoreOf(it.playerId) > score } + 1
    }

    /** With [limitThemes], how many themes this turn's picker gets: fewer the better they're doing. */
    fun themesOffered(): Int {
        val place = pickerPlace() ?: return THEMES_OFFERED
        return (THEMES_FOR_LEADER + (place - 1) * THEMES_PER_PLACE).coerceAtMost(THEMES_OFFERED)
    }

    /**
     * What the picker may choose from. With [limitThemes] it is dealt on the turn's first call and
     * then stays, except to replace a theme that is no longer in [themes]. Themes with a fresh
     * question this round come first, then ones not played this game, and otherwise it's random.
     * How many depends on the picker's place ([themesOffered]); scores can't change mid-pick.
     */
    fun themeChoices(themes: List<Theme>): List<Theme> {
        if (!limitThemes) return themes
        val wanted = themesOffered()
        val byId = themes.associateBy { it.id }
        offeredIds.retainAll(byId.keys)
        if (offeredIds.size < wanted) {
            offeredIds += themes
                .filter { it.id !in offeredIds }
                .shuffled()
                .sortedWith(compareBy({ !it.hasFreshQuestion(difficulty, askedQuestions) }, { it.id in playedThemes }))
                .take(wanted - offeredIds.size)
                .map { it.id }
        }
        return offeredIds.map { byId.getValue(it) }
    }

    /** Deals one question of this round's difficulty from the picker's theme. */
    fun pickTheme(playerId: String, theme: Theme, now: Instant = Instant.now()): Boolean {
        if (!canPick(playerId)) return false
        if (limitThemes && theme.id !in offeredIds) return false
        val question = theme.drawQuestion(difficulty, askedQuestions) ?: return false

        val timeAllowed = if (timeLimit) TIME_PER_PLAYER.multipliedBy(seats.size.toLong()) else null
        turn = PartyTurn(theme.id, theme.name, question, timeAllowed, opensAt = now.plus(themeFlash))
        stage = PartyStage.ANSWERING
        return true
    }

    /** Records one answer, then either reveals, if it was the last, or cuts the clock. */
    fun answer(playerId: String, answer: String, now: Instant = Instant.now()): Boolean {
        val current = turn ?: return false
        if (stage != PartyStage.ANSWERING || !canAnswer(playerId) || !current.isOpen(now)) return false
        if (timeIsUp(current, now) || !current.record(playerId, answer)) return false

        // In a tiebreak the first right answer wins at once, without waiting for the others.
        if (inTiebreak && current.isCorrect(playerId)) reveal(current, now) else revealIfAllAnswered(current, now)
        if (stage == PartyStage.ANSWERING) cutTime(current, now)
        return true
    }

    /** Reveals if the clock has run out. Returns whether it did. */
    fun expire(now: Instant): Boolean {
        val current = turn ?: return false
        if (stage != PartyStage.ANSWERING || !timeIsUp(current, now)) return false

        reveal(current, now)
        return true
    }

    /** When [next] becomes allowed, or null outside a reveal. */
    fun nextAllowedAt(): Instant? = turn?.takeIf { stage == PartyStage.REVEAL }?.revealedAt?.plus(revealHold)

    /** Ends a revealed turn. Any player may do it, once the answer has been up for [revealHold]. */
    fun next(playerId: String, now: Instant = Instant.now()): Boolean {
        if (stage != PartyStage.REVEAL || !isPlaying(playerId)) return false
        if (nextAllowedAt()?.let { now.isBefore(it) } == true) return false

        if (inTiebreak) {
            // A winner ends the game; otherwise wait for the next tiebreak question.
            val won = turn?.firstCorrectId != null
            turn = null
            if (won) finish() else stage = PartyStage.TIEBREAK
            return true
        }
        turn = null
        pickerIndex++
        startTurn()
        return true
    }

    /**
     * Takes a player out of the game. If it was their turn, the turn is dropped and passes on;
     * otherwise the turn carries on without waiting for them.
     */
    fun remove(playerId: String, now: Instant = Instant.now()): Boolean {
        val index = seats.indexOfFirst { it.playerId == playerId }
        if (isOver || index < 0) return false

        if (inTiebreak) {
            seats.removeAt(index)
            tiebreakers = tiebreakers - playerId
            when {
                // One tied player left leads alone, so has won.
                seats.size < 2 || tiebreakers.size < 2 -> finish()
                stage == PartyStage.ANSWERING -> turn?.let { revealIfAllAnswered(it, now) }
            }
            return true
        }

        val wasPicker = index == pickerIndex
        seats.removeAt(index)
        if (index < pickerIndex) pickerIndex--

        when {
            seats.size < 2 -> finish()
            wasPicker -> {
                turn = null
                startTurn() // pickerIndex already points at whoever was next.
            }
            stage == PartyStage.ANSWERING -> turn?.let { revealIfAllAnswered(it, now) }
        }
        return true
    }

    private fun isPlaying(playerId: String): Boolean = seats.any { it.playerId == playerId }

    private fun revealIfAllAnswered(current: PartyTurn, now: Instant) {
        val answering = if (inTiebreak) tiebreakers else seats.map { it.playerId }
        if (answering.all { current.answerBy(it) != null }) reveal(current, now)
    }

    private fun timeIsUp(current: PartyTurn, now: Instant): Boolean =
        current.deadline?.let { !now.isBefore(it) } == true

    private fun cutTime(current: PartyTurn, now: Instant) {
        val deadline = current.deadline ?: return
        val floor = now.plus(TIME_CUT_FLOOR)
        if (deadline > floor) current.deadline = maxOf(floor, deadline.minus(TIME_CUT_PER_ANSWER))
    }

    /** Anyone who hasn't answered by now counts as wrong. */
    private fun reveal(current: PartyTurn, now: Instant) {
        current.revealedAt = now
        if (inTiebreak) return revealTiebreak(current)
        val pickerId = picker.playerId
        val correctIds = seats
            .map { it.playerId }
            .filter { current.isCorrect(it) }
        val score = scoreQuestion(
            pickerCorrect = pickerId in correctIds,
            othersCorrect = correctIds.count { it != pickerId },
            playerCount = seats.size,
            bonusNeedsSecond = bonusNeedsSecond
        )
        current.score = score
        current.deadline = null
        scores[pickerId] = scoreOf(pickerId) + score.total
        correctIds.filter { it != pickerId }.forEach { scores[it] = scoreOf(it) + GUESSER_POINTS }
        current.firstCorrectId = current.firstCorrect(correctIds)
        current.firstCorrectId?.let { scores[it] = scoreOf(it) + FIRST_CORRECT_POINTS }
        updateStreaks(current, correctIds)
        askedQuestions.add(current.question.question)
        playedThemes.add(current.themeId)
        stage = PartyStage.REVEAL
    }

    /** The first tied player to answer correctly wins; none of the usual scoring applies. */
    private fun revealTiebreak(current: PartyTurn) {
        current.score = QuestionScore(pickerCorrect = false, othersCorrect = 0, bonus = 0, penalty = 0)
        current.deadline = null
        current.firstCorrectId = current.firstCorrect(tiebreakers)
        current.firstCorrectId?.let { scores[it] = scoreOf(it) + TIEBREAK_POINTS }
        askedQuestions.add(current.question.question)
        stage = PartyStage.REVEAL
    }

    /** Each correct answer that makes [STREAK_LENGTH] or more in a row earns [STREAK_POINTS]. */
    private fun updateStreaks(current: PartyTurn, correctIds: List<String>) {
        val earned = mutableSetOf<String>()
        seats.forEach { seat ->
            val id = seat.playerId
            val streak = if (id in correctIds) streakOf(id) + 1 else 0
            streaks[id] = streak
            if (streak >= STREAK_LENGTH) {
                scores[id] = scoreOf(id) + STREAK_POINTS
                earned += id
            }
        }
        current.streakBonusIds = earned
    }

    /** Starts the turn at [pickerIndex], which is one past the last seat when a round has ended. */
    private fun startTurn() {
        offeredIds.clear()
        if (pickerIndex >= seats.size) {
            if (roundIndex == difficulties.lastIndex) return endOrTiebreak()
            pickerIndex = 0
            roundIndex++
        }
        stage = PartyStage.PICKING
    }

    /** After the last turn: a tie for the lead goes to a tiebreak, if they're on. */
    private fun endOrTiebreak() {
        val tied = leaders()
        if (!tiebreak || tied.size < 2) return finish()
        turn = null
        pickerIndex = 0
        tiebreakers = tied.map { it.playerId }
        stage = PartyStage.TIEBREAK
    }

    private fun finish() {
        turn = null
        pickerIndex = 0
        tiebreakers = emptyList()
        stage = PartyStage.FINISHED
    }
}

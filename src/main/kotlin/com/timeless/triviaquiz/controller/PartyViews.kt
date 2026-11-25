package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.FIRST_CORRECT_POINTS
import com.timeless.triviaquiz.game.GUESSER_POINTS
import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.PartyStage
import com.timeless.triviaquiz.game.PartyTurn
import com.timeless.triviaquiz.game.STREAK_LENGTH
import com.timeless.triviaquiz.game.PENALTY_PER_OTHER_CORRECT
import com.timeless.triviaquiz.game.STREAK_POINTS
import com.timeless.triviaquiz.game.THEMES_FOR_LEADER
import com.timeless.triviaquiz.game.THEMES_OFFERED
import com.timeless.triviaquiz.game.THEMES_PER_PLACE
import com.timeless.triviaquiz.game.TIEBREAK_POINTS
import com.timeless.triviaquiz.game.Theme
import java.time.Duration
import java.time.Instant

/**
 * [id] lets the scoreboard follow a player as bars re-sort, and tells the host's Kick whom to
 * remove. It is no credential: who a device is comes only from its session.
 *
 * [correct] is null except during the reveal: any earlier and it would give the answer away to
 * whoever is still answering.
 */
data class PartyPlayerView(
    val id: String,
    val name: String,
    val score: Int,
    val picker: Boolean,
    val you: Boolean,
    val answered: Boolean,
    val correct: Boolean?,
    /** True only during the reveal, for whoever answered correctly first. */
    val first: Boolean = false,
    /** Correct answers in a row so far. */
    val streak: Int = 0,
    /** True only during the reveal, for those whose streak just earned its point. */
    val streakBonus: Boolean = false,
    /** In a tiebreak, whether this player is one of those tied, who alone may answer. */
    val tiebreaker: Boolean = false
)

/** [flagged] is whether this viewer has marked it as a bad theme. The count is only on the host page. */
data class ThemeChoiceView(val id: String, val name: String, val flagged: Boolean = false)

/** A theme in the lobby's list, where players waiting for the start can hide it from this room's game. */
data class LobbyThemeView(val id: String, val name: String, val hidden: Boolean)

/**
 * A Theme Party question while it is still being answered. As with [QuestionView], it exists to
 * omit `correctAnswer`, and here `explanation` too. [yourAnswer] is only ever the viewer's own.
 */
data class PartyQuestionView(
    val question: String,
    val answers: List<String>,
    val yourAnswer: String?
)

/**
 * A question at the reveal. This is the only view that carries the answer and explanation, and it
 * is only built in [PartyStage.REVEAL].
 */
data class PartyRevealView(
    val question: String,
    val correctAnswer: String,
    val explanation: String,
    val yourAnswer: String?,
    val pickerCorrect: Boolean,
    val othersCorrect: Int,
    val bonus: Int,
    val penalty: Int,
    val points: Int,
    val guesserPoints: Int,
    /** The name of whoever answered correctly first, or null if nobody was right. */
    val firstCorrect: String?,
    val firstCorrectPoints: Int,
    val streakPoints: Int,
    val streakLength: Int,
    /** What winning a tiebreak is worth. In a tiebreak, [firstCorrect] is the winner. */
    val tiebreakPoints: Int
)

/**
 * How scoring works, for the lobby screen. Sent rather than written into room.js so it can't drift
 * from the real numbers. [limitThemes] and [bonusNeedsSecond] are the host's current choices, which
 * can change until the game starts.
 */
data class PartyRulesView(
    val guesserPoints: Int = GUESSER_POINTS,
    val penaltyPerOtherCorrect: Int = PENALTY_PER_OTHER_CORRECT,
    val firstCorrectPoints: Int = FIRST_CORRECT_POINTS,
    val streakLength: Int = STREAK_LENGTH,
    val streakPoints: Int = STREAK_POINTS,
    val tiebreakPoints: Int = TIEBREAK_POINTS,
    val themesForLeader: Int = THEMES_FOR_LEADER,
    val themesPerPlace: Int = THEMES_PER_PLACE,
    val themesOffered: Int = THEMES_OFFERED,
    val limitThemes: Boolean,
    val bonusNeedsSecond: Boolean
)

/** The Theme Party part of [RoomStateView], tailored to who is asking. */
data class PartyView(
    val stage: String,
    val removed: Boolean,
    val players: List<PartyPlayerView>,
    val round: Int,
    val rounds: Int,
    val difficulty: String?,
    val picker: String?,
    val nextPicker: String?,
    val yourPick: Boolean,
    val bonusNeedsSecond: Boolean?,
    val timeLimit: Boolean?,
    /** Time left rather than a deadline, as phones' clocks can't be trusted to match the laptop's. */
    val millisLeft: Long?,
    val millisAllowed: Long?,
    /**
     * During the theme flash, how long until the question is shown; [question] is null until
     * then, so no device can see it early. Null otherwise.
     */
    val millisUntilQuestion: Long?,
    /** During the reveal, how long until Next can be pressed. Null once it can. */
    val millisUntilNext: Long?,
    /**
     * The picker's place while picking, 1 for the lead; null while everyone is level. A higher
     * place gets fewer themes ([themes] has as many as they get).
     */
    val pickerPlace: Int?,
    val theme: String?,
    val themes: List<ThemeChoiceView>?,
    val question: PartyQuestionView?,
    val reveal: PartyRevealView?,
    val winners: List<String>,
    /** True through a tiebreak: only those tied answer, and the first right wins. */
    val tiebreak: Boolean = false,
    /** Which tiebreak question this is, from 1. */
    val tiebreakRound: Int = 0,
    /** Only in the lobby. */
    val rules: PartyRulesView? = null,
    /** Only in the lobby: every valid theme, hidden ones included, by name. */
    val lobbyThemes: List<LobbyThemeView>? = null
)

/**
 * Call with [GameRoom.mutex] held. The result shares no mutable state with the room.
 *
 * In the lobby [themes] must be every valid theme, hidden ones included, for the lobby's list. Once
 * a game has started it must leave out the hidden ones, as it is what the picker is dealt from.
 */
internal fun buildPartyView(room: GameRoom, viewerId: String?, themes: List<Theme>, now: Instant): PartyView {
    val removed = room.wasKicked(viewerId)
    val game = room.party
        ?: return PartyView(
            stage = "lobby",
            removed = removed,
            players = room.lobby.map {
                PartyPlayerView(
                    id = it.playerId,
                    name = it.name,
                    score = 0,
                    picker = false,
                    you = it.playerId == viewerId,
                    answered = false,
                    correct = null
                )
            },
            round = 0,
            rounds = 0, // Not final until the host presses start.
            difficulty = null,
            picker = null,
            nextPicker = null,
            yourPick = false,
            bonusNeedsSecond = null, // Neither setting is final until the host presses start.
            timeLimit = null,
            millisLeft = null,
            millisAllowed = null,
            millisUntilQuestion = null,
            millisUntilNext = null,
            pickerPlace = null,
            theme = null,
            themes = null,
            question = null,
            reveal = null,
            winners = emptyList(),
            rules = PartyRulesView(limitThemes = room.limitThemes, bonusNeedsSecond = room.bonusNeedsSecond),
            lobbyThemes = themes
                .map { LobbyThemeView(it.id, it.name, it.id in room.hiddenThemes) }
                .sortedBy { it.name.lowercase() }
        )

    // A tiebreak has no picker.
    val picker = if (game.isOver || game.inTiebreak) null else game.picker
    val revealed = game.turn?.takeIf { game.stage == PartyStage.REVEAL }
    val firstId = revealed?.firstCorrectId
    val firstName = game.players.firstOrNull { it.playerId == firstId }?.name
    val asking = game.turn?.takeIf { game.stage == PartyStage.ANSWERING }
    val flashing = asking != null && !asking.isOpen(now)

    return PartyView(
        stage = game.stage.name.lowercase(),
        removed = removed,
        players = game.players.map {
            PartyPlayerView(
                id = it.playerId,
                name = it.name,
                score = game.scoreOf(it.playerId),
                picker = it.playerId == picker?.playerId,
                you = it.playerId == viewerId,
                answered = game.hasAnswered(it.playerId),
                // Those watching a tiebreak weren't asked, so are neither right nor wrong.
                correct = if (game.stage == PartyStage.REVEAL && game.canAnswer(it.playerId)) {
                    game.turn?.isCorrect(it.playerId)
                } else {
                    null
                },
                first = it.playerId == firstId,
                streak = game.streakOf(it.playerId),
                streakBonus = revealed?.streakBonusIds?.contains(it.playerId) == true,
                tiebreaker = it.playerId in game.tiebreakers
            )
        },
        round = game.roundIndex + 1,
        rounds = game.rounds,
        difficulty = game.difficulty,
        picker = picker?.name,
        nextPicker = game.nextPicker?.name,
        yourPick = viewerId != null && viewerId == picker?.playerId,
        bonusNeedsSecond = game.bonusNeedsSecond,
        timeLimit = game.timeLimit,
        millisLeft = game.turn?.deadline?.let { Duration.between(now, it).toMillis().coerceAtLeast(0) },
        millisAllowed = game.turn?.timeAllowed?.toMillis(),
        millisUntilQuestion = if (flashing) Duration.between(now, asking!!.opensAt).toMillis().coerceAtLeast(0) else null,
        millisUntilNext = game.nextAllowedAt()?.let { Duration.between(now, it).toMillis() }?.takeIf { it > 0 },
        pickerPlace = if (game.stage == PartyStage.PICKING && game.limitThemes) game.pickerPlace() else null,
        theme = game.turn?.themeName,
        themes = if (game.canPick(viewerId)) {
            game.themeChoices(themes).map { ThemeChoiceView(it.id, it.name, room.hasFlagged(viewerId, it.id)) }
        } else {
            null
        },
        question = asking?.takeIf { !flashing }?.questionView(viewerId),
        reveal = revealed?.revealView(viewerId, firstName),
        winners = game.winners().map { it.name },
        tiebreak = game.inTiebreak,
        tiebreakRound = game.tiebreakRound
    )
}

private fun PartyTurn.questionView(viewerId: String?) = PartyQuestionView(
    question = question.question,
    answers = question.answers,
    yourAnswer = answerBy(viewerId)
)

private fun PartyTurn.revealView(viewerId: String?, firstName: String?): PartyRevealView? = score?.let {
    PartyRevealView(
        question = question.question,
        correctAnswer = question.correctAnswer,
        explanation = question.explanation,
        yourAnswer = answerBy(viewerId),
        pickerCorrect = it.pickerCorrect,
        othersCorrect = it.othersCorrect,
        bonus = it.bonus,
        penalty = it.penalty,
        points = it.total,
        guesserPoints = GUESSER_POINTS,
        firstCorrect = firstName,
        firstCorrectPoints = FIRST_CORRECT_POINTS,
        streakPoints = STREAK_POINTS,
        streakLength = STREAK_LENGTH,
        tiebreakPoints = TIEBREAK_POINTS
    )
}

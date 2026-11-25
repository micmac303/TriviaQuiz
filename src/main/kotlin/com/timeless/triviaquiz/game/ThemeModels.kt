package com.timeless.triviaquiz.game

/**
 * The difficulties from first to last, with how many questions of each a theme needs to load. It
 * needs them however many rounds of each are played.
 */
val THEME_QUESTION_SPLIT: Map<String, Int> = linkedMapOf("easy" to 2, "medium" to 2, "hard" to 2)

const val DEFAULT_ROUNDS = 6

/** With the theme limit on, the most themes a picker is offered: what everyone gets when level. */
const val THEMES_OFFERED = 8

/** How many the leader gets. Each place further down gets [THEMES_PER_PLACE] more, up to [THEMES_OFFERED]. */
const val THEMES_FOR_LEADER = 4
const val THEMES_PER_PLACE = 2

/** How many rounds of each difficulty a game has unless the host changes it. None are easy. */
val DEFAULT_ROUND_COUNTS: Map<String, Int> = run {
    val played = THEME_QUESTION_SPLIT.keys - "easy"
    THEME_QUESTION_SPLIT.mapValues { (difficulty, _) -> if (difficulty in played) DEFAULT_ROUNDS / played.size else 0 }
}

/** What the host can choose from for each difficulty. */
val ROUND_OPTIONS: IntRange = 0..20

/** The difficulty of each round, always from the first difficulty to the last. */
fun roundDifficulties(counts: Map<String, Int>): List<String> =
    THEME_QUESTION_SPLIT.keys.flatMap { difficulty -> List(counts[difficulty] ?: 0) { difficulty } }

/** A question as written in a theme file. [used] is set once its answer has been revealed. */
data class ThemeQuestion(
    val difficulty: String,
    val question: String,
    val correctAnswer: String,
    val incorrectAnswers: List<String>,
    val explanation: String,
    val used: Boolean = false
)

/**
 * A question prepared for one turn, with its answers shuffled. Like [QuizQuestion] it must never be
 * serialised to a client: it carries both [correctAnswer] and [explanation].
 */
data class PartyQuestion(
    val difficulty: String,
    val question: String,
    val answers: List<String>,
    val correctAnswer: String,
    val explanation: String
)

/**
 * A valid theme file. [id] is its file name, as two files may share a [name]. [flags] is how many
 * times pickers have marked it as a bad theme, which only the host sees.
 */
data class Theme(val id: String, val name: String, val questions: List<ThemeQuestion>, val flags: Int = 0) {

    val usedCount: Int
        get() = questions.count { it.used }

    /** Whether [drawQuestion] can give a question that's neither used nor asked this game. */
    fun hasFreshQuestion(difficulty: String, askedThisGame: Set<String>): Boolean =
        questions.any { it.difficulty == difficulty && !it.used && it.question !in askedThisGame }

    /**
     * Picks one question of [difficulty], preferring unused ones, then ones used in earlier games.
     * Those in [askedThisGame] come last, as everyone has just seen their answers.
     */
    fun drawQuestion(difficulty: String, askedThisGame: Set<String>): PartyQuestion? =
        questions
            .filter { it.difficulty == difficulty }
            .shuffled()
            .minWithOrNull(compareBy({ it.question in askedThisGame }, { it.used })) // False sorts first.
            ?.let {
                PartyQuestion(
                    difficulty = it.difficulty,
                    question = it.question,
                    answers = (it.incorrectAnswers + it.correctAnswer).shuffled(),
                    correctAnswer = it.correctAnswer,
                    explanation = it.explanation
                )
            }
}

package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.PartyPlayer
import com.timeless.triviaquiz.game.ROUND_OPTIONS
import com.timeless.triviaquiz.game.RoomMode
import com.timeless.triviaquiz.game.RoomPhase
import com.timeless.triviaquiz.game.TIME_CUT_FLOOR
import com.timeless.triviaquiz.game.TIME_CUT_PER_ANSWER
import com.timeless.triviaquiz.game.TIME_PER_PLAYER
import com.timeless.triviaquiz.game.THEMES_FOR_LEADER
import com.timeless.triviaquiz.game.THEMES_OFFERED
import com.timeless.triviaquiz.game.THEMES_PER_PLACE
import com.timeless.triviaquiz.game.THEME_QUESTION_SPLIT
import com.timeless.triviaquiz.game.ThemePartyGame
import com.timeless.triviaquiz.service.GameRoomService
import com.timeless.triviaquiz.service.ThemeLoader
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import java.net.InetAddress
import java.time.Duration

/**
 * A theme as the host page lists it: no questions, as any device on the LAN can open that page.
 * [flags] is how many times pickers marked it as bad; [flagged] is for the template, as JMustache
 * treats 0 as true.
 */
data class ThemeSummary(
    val id: String,
    val name: String,
    val used: Int,
    val total: Int,
    val flags: Int = 0,
    /** Hidden by the host for this room: not offered to pickers. */
    val hidden: Boolean = false
) {
    val flagged: Boolean get() = flags > 0
    val flaggedOnce: Boolean get() = flags == 1
}

/** A theme's new state after Hide or Show, for the host page's script. */
data class ThemeVisibility(val theme: String, val hidden: Boolean, val hiddenCount: Int)

/** One option in a rounds dropdown. */
data class RoundChoice(val count: Int, val selected: Boolean)

/** The host's rounds dropdown for one difficulty. */
data class DifficultySetting(val difficulty: String, val choices: List<RoundChoice>)

/**
 * The Theme Party host screen. It has its own routes and session key, so one browser's classic room
 * and party room can't act on each other.
 *
 * The settings share a form with "Refresh themes", "Reset used questions", "Remove" and
 * "Start", and all save them: that way none loses the host's choices and start always uses what is on screen.
 */
@Controller
class PartyHostController(
    private val gameRoomService: GameRoomService,
    private val themeLoader: ThemeLoader,
    @Value("\${trivia.public-url:}") private val publicUrl: String,
    /** How long the theme name shows before each question. */
    @Value("\${trivia.party.theme-flash-ms:1000}") private val themeFlashMs: Long,
    /** How long the answer stays up before anyone can press Next. */
    @Value("\${trivia.party.reveal-hold-ms:1500}") private val revealHoldMs: Long,
    /** Whether a tie for the lead at the end goes to a tiebreak. */
    @Value("\${trivia.party.tiebreak:true}") private val tiebreak: Boolean
) {

    @GetMapping("/party/host")
    fun host(model: Model, session: HttpSession, request: HttpServletRequest): String {
        val room = hostedRoom(session) ?: gameRoomService.create(RoomMode.THEME_PARTY).also {
            session.setAttribute(RoomSession.HOSTED_PARTY_CODE, it.code)
        }
        val catalog = themeLoader.load()

        model["title"] = "Host a Theme Party"
        model["code"] = room.code
        model["joinUrls"] = joinUrls(request, room.code, publicUrl)
        model["inLobby"] = room.phase == RoomPhase.LOBBY
        model["bonusNeedsSecond"] = room.bonusNeedsSecond
        model["timeLimit"] = room.timeLimit
        model["limitThemes"] = room.limitThemes
        model["themesOffered"] = THEMES_OFFERED
        model["themesForLeader"] = THEMES_FOR_LEADER
        model["themesPerPlace"] = THEMES_PER_PLACE
        model["secondsPerPlayer"] = TIME_PER_PLAYER.seconds
        model["secondsPerAnswer"] = TIME_CUT_PER_ANSWER.seconds
        model["secondsFloor"] = TIME_CUT_FLOOR.seconds
        model["difficultySettings"] = difficultySettings(room.roundCounts)
        // Most flagged first, so the ones to consider removing are at the top.
        model["themes"] = catalog.themes
            .map { ThemeSummary(it.id, it.name, it.usedCount, it.questions.size, it.flags, it.id in room.hiddenThemes) }
            .sortedByDescending { it.flags }
        val hiddenCount = catalog.themes.count { it.id in room.hiddenThemes }
        model["hiddenCount"] = hiddenCount
        model["anyHidden"] = hiddenCount > 0
        model["anyFlagged"] = catalog.themes.any { it.flags > 0 }
        model["themeErrors"] = catalog.errors
        model["onHostComputer"] = isHostComputer(request)
        return "party-host"
    }

    /** Reloads the theme list. The folder is read when the page we redirect to renders. */
    @PostMapping("/party/host/refresh")
    suspend fun refresh(
        @RequestParam(defaultValue = "false") bonusNeedsSecond: Boolean,
        @RequestParam(defaultValue = "false") timeLimit: Boolean,
        @RequestParam(defaultValue = "false") limitThemes: Boolean,
        @RequestParam(required = false) rounds: List<Int>?,
        session: HttpSession
    ): String {
        val room = hostedRoom(session) ?: return "redirect:/party/host"
        room.mutex.withLock {
            saveSettings(room, bonusNeedsSecond, timeLimit, limitThemes, rounds)
        }
        return "redirect:/party/host"
    }

    /** Clears every theme's "used" flags. Only from the computer running the app. */
    @PostMapping("/party/host/reset-used")
    suspend fun resetUsed(
        @RequestParam(defaultValue = "false") bonusNeedsSecond: Boolean,
        @RequestParam(defaultValue = "false") timeLimit: Boolean,
        @RequestParam(defaultValue = "false") limitThemes: Boolean,
        @RequestParam(required = false) rounds: List<Int>?,
        session: HttpSession,
        request: HttpServletRequest
    ): String {
        val room = hostedRoom(session) ?: return "redirect:/party/host"
        if (!isHostComputer(request)) return "redirect:/party/host"
        val saved = withContext(Dispatchers.IO) { themeLoader.resetUsed() }
        room.mutex.withLock {
            saveSettings(room, bonusNeedsSecond, timeLimit, limitThemes, rounds)
            room.notice = if (saved) null else "Couldn't reset the used questions in every theme file."
            room.touch()
        }
        return "redirect:/party/host"
    }

    /** Takes a theme out of every game. Only from the computer running the app. */
    @PostMapping("/party/host/remove-theme")
    suspend fun removeTheme(
        @RequestParam theme: String,
        @RequestParam(defaultValue = "false") bonusNeedsSecond: Boolean,
        @RequestParam(defaultValue = "false") timeLimit: Boolean,
        @RequestParam(defaultValue = "false") limitThemes: Boolean,
        @RequestParam(required = false) rounds: List<Int>?,
        session: HttpSession,
        request: HttpServletRequest
    ): String {
        val room = hostedRoom(session) ?: return "redirect:/party/host"
        if (!isHostComputer(request)) return "redirect:/party/host"

        val removed = withContext(Dispatchers.IO) { themeLoader.remove(theme) }
        room.mutex.withLock {
            saveSettings(room, bonusNeedsSecond, timeLimit, limitThemes, rounds)
            room.notice = if (removed) null else "Couldn't remove that theme."
            room.touch()
        }
        return "redirect:/party/host"
    }

    /**
     * Hides a theme from this room's pickers, or shows it again. The file isn't touched, so unlike
     * Remove any host may do it, and mid-game too. [theme] may come from a client: it is only
     * matched against the loaded themes.
     */
    @PostMapping("/party/host/hide-theme")
    suspend fun toggleHidden(
        @RequestParam theme: String,
        @RequestParam(defaultValue = "false") bonusNeedsSecond: Boolean,
        @RequestParam(defaultValue = "false") timeLimit: Boolean,
        @RequestParam(defaultValue = "false") limitThemes: Boolean,
        @RequestParam(required = false) rounds: List<Int>?,
        session: HttpSession
    ): String {
        val room = hostedRoom(session) ?: return "redirect:/party/host"
        val known = withContext(Dispatchers.IO) { themeLoader.load().themes }.any { it.id == theme }

        room.mutex.withLock { saveSettings(room, bonusNeedsSecond, timeLimit, limitThemes, rounds) }
        if (known) toggle(room, theme)
        return "redirect:/party/host"
    }

    /**
     * The same as [toggleHidden], for the host page's script: answers with the new state instead of
     * reloading the page, so the host keeps their place in the theme list.
     */
    @PostMapping("/party/host/theme-visibility")
    @ResponseBody
    suspend fun toggleVisibility(@RequestParam theme: String, session: HttpSession): ResponseEntity<ThemeVisibility> {
        val room = hostedRoom(session) ?: return ResponseEntity.notFound().build()
        val themes = withContext(Dispatchers.IO) { themeLoader.load().themes }
        if (themes.none { it.id == theme }) return ResponseEntity.notFound().build()

        val hidden = toggle(room, theme)
        return ResponseEntity.ok(ThemeVisibility(theme, hidden, themes.count { it.id in room.hiddenThemes }))
    }

    /**
     * Hides or shows [theme] in [room], returning whether it's now hidden. No version bump: the
     * picker's next poll re-deals without it anyway, and a bump would make a pick they're sending
     * right now fail its exact version match.
     */
    private suspend fun toggle(room: GameRoom, theme: String): Boolean = room.mutex.withLock {
        if (room.hiddenThemes.remove(theme)) false else room.hiddenThemes.add(theme)
    }

    /**
     * Removes a player, in the lobby or mid-game. Called by room.js, so it doesn't redirect. Mid
     * question this can be what triggers the reveal, as nobody waits for a kicked player.
     */
    @PostMapping("/party/host/kick")
    suspend fun kick(@RequestParam playerId: String, session: HttpSession): ResponseEntity<Void> {
        val room = hostedRoom(session) ?: return ResponseEntity.notFound().build()

        val kicked = removeFromParty(room, themeLoader) { room.kick(playerId) }
        return if (kicked) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    /** Starts the game with everyone in the lobby, in join order, and locks the settings. */
    @PostMapping("/party/host/start")
    suspend fun start(
        @RequestParam(defaultValue = "false") bonusNeedsSecond: Boolean,
        @RequestParam(defaultValue = "false") timeLimit: Boolean,
        @RequestParam(defaultValue = "false") limitThemes: Boolean,
        @RequestParam(required = false) rounds: List<Int>?,
        session: HttpSession
    ): String {
        val room = hostedRoom(session) ?: return "redirect:/party/host"
        val hasThemes = themeLoader.load().themes.any { it.id !in room.hiddenThemes }

        room.mutex.withLock {
            if (room.phase != RoomPhase.LOBBY) return "redirect:/party/host"
            saveSettings(room, bonusNeedsSecond, timeLimit, limitThemes, rounds)

            room.notice = when {
                room.roundCounts.values.sum() == 0 -> "Choose at least one round to start."
                room.lobby.size < GameRoomService.MIN_PLAYERS ->
                    "Need at least ${GameRoomService.MIN_PLAYERS} players to start."
                !hasThemes -> "Need at least one valid theme that isn't hidden to start."
                else -> null
            }
            if (room.notice == null) {
                room.party = ThemePartyGame(
                    players = room.lobby.map { PartyPlayer(it.playerId, it.name) },
                    bonusNeedsSecond = bonusNeedsSecond,
                    timeLimit = timeLimit,
                    roundCounts = room.roundCounts,
                    limitThemes = room.limitThemes,
                    themeFlash = Duration.ofMillis(themeFlashMs.coerceAtLeast(0)),
                    revealHold = Duration.ofMillis(revealHoldMs.coerceAtLeast(0)),
                    tiebreak = tiebreak
                )
            }
            room.touch()
        }
        return "redirect:/party/host"
    }

    @PostMapping("/party/host/reset")
    suspend fun reset(session: HttpSession): String {
        val room = hostedRoom(session) ?: return "redirect:/party/host"
        room.mutex.withLock { room.resetToLobby() }
        return "redirect:/party/host"
    }

    @PostMapping("/party/host/close")
    fun close(session: HttpSession): String {
        hostedRoom(session)?.let { gameRoomService.close(it.code) }
        session.removeAttribute(RoomSession.HOSTED_PARTY_CODE)
        return "redirect:/"
    }

    /**
     * [rounds] has a count per difficulty, in [THEME_QUESTION_SPLIT]'s order. Without one for each
     * it keeps the room's choice: unlike an unticked box, a missing count doesn't mean none.
     */
    private fun saveSettings(
        room: GameRoom,
        bonusNeedsSecond: Boolean,
        timeLimit: Boolean,
        limitThemes: Boolean,
        rounds: List<Int>?
    ) {
        if (room.phase != RoomPhase.LOBBY) return
        val difficulties = THEME_QUESTION_SPLIT.keys
        if (rounds?.size == difficulties.size) {
            room.roundCounts = difficulties.zip(rounds.map { it.coerceIn(ROUND_OPTIONS) }).toMap()
        }
        room.bonusNeedsSecond = bonusNeedsSecond
        room.timeLimit = timeLimit
        room.limitThemes = limitThemes
    }

    private fun difficultySettings(counts: Map<String, Int>): List<DifficultySetting> =
        THEME_QUESTION_SPLIT.keys.map { difficulty ->
            DifficultySetting(difficulty, ROUND_OPTIONS.map { RoundChoice(it, it == counts[difficulty]) })
        }

    // Any phone on the LAN can open a host page of its own, so hosting a room isn't enough to be
    // allowed to change theme files. The second test covers the computer browsing to its own LAN address.
    // The tunnel runs on this computer too, so internet requests are ruled out first: they'd look local.
    private fun isHostComputer(request: HttpServletRequest): Boolean =
        !isFromInternet(request) &&
            (InetAddress.getByName(request.remoteAddr).isLoopbackAddress || request.remoteAddr == request.localAddr)

    // The mode check matters: after a close or a reap the code could be reissued to a classic room.
    private fun hostedRoom(session: HttpSession): GameRoom? =
        gameRoomService.find(session.getAttribute(RoomSession.HOSTED_PARTY_CODE) as? String)
            ?.takeIf { it.mode == RoomMode.THEME_PARTY }
}

package com.hingevala

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.random.Random

/** The background loop: read the screen, decide, act, wait. Runs until Stop. Port of bot.py. */
class BotEngine(private val svc: BotRunService) {
    @Volatile
    var state = "stopped"
        private set

    @Volatile
    var nextActionAt: Long? = null
        private set

    @Volatile
    var dryRuns = 0
        private set

    /** Likes sent and profiles skipped since the last Start. */
    @Volatile
    var sentThisRun = 0
        private set

    @Volatile
    var skippedThisRun = 0
        private set

    private var failures = 0
    private var stopSignal = CountDownLatch(0)
    private var thread: Thread? = null

    val running: Boolean get() = thread?.isAlive == true

    @Synchronized
    fun start(): Boolean {
        if (running) return false
        stopSignal = CountDownLatch(1)
        sentThisRun = 0
        skippedThisRun = 0
        dryRuns = 0
        thread = Thread(::run, "hingevala-bot").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        stopSignal.countDown()
        if (running) state = "stopping…"
    }

    private val stopped get() = stopSignal.count == 0L

    /** Wait up to `seconds`; true if Stop was pressed meanwhile. */
    private fun waitOrStop(seconds: Double) = stopSignal.await((seconds * 1000).toLong(), TimeUnit.MILLISECONDS)

    // ---- the loop -------------------------------------------------------------------

    private fun run() {
        var u2: U2Server? = null
        var device: Device? = null
        try {
            var cfg = Config.settings(svc)
            state = "connecting"
            val adb = Adb.get(svc)
            if (!adb.ensureConnected(svc)) {
                BotLog.log(
                    if (Pairing.isPaired(svc)) {
                        "Couldn't connect. After a phone restart this needs Wi-Fi once: connect to any " +
                            "Wi-Fi and press Start again (mobile data works after that)."
                    } else {
                        "Not set up yet: open the Setup tab and pair first (once)."
                    },
                )
                return
            }
            adb.grant(svc)
            state = "starting uiautomator"
            u2 = U2Server(svc, adb).also { it.ensureRunning() }
            val dev = Device(svc, adb, u2, svc::stopButtonBounds)
            device = dev
            svc.showStopButton()
            val ui = HingeScreen(dev, Selectors.load(svc, cfg.variant))
            val comments = CommentPicker(Config.commentsText(svc), cfg.commentNoRepeat)
            val version = if (cfg.variant == Selectors.PLUS) "Hinge+" else "HingeX"
            BotLog.log("Started ($version, dry_run=${cfg.dryRun}, daily_cap=${cfg.dailyCap})")

            state = "opening Hinge"
            ui.openApp()
            if (waitOrStop(3.0)) return

            var triedDiscoverTab = false
            var restarts = 0
            failures = 0
            var toldOutsideHours = false
            var toldScreenOff = false
            while (!stopped) {
                cfg = Config.settings(svc) // Dry run and the daily cap can change while running.

                // A failure here shows up again in detect() below, which handles it.
                if (runCatching { dev.wake() }.getOrDefault(false)) BotLog.log("The screen was off; turned it on.")
                if (svc.screenLocked()) {
                    // Taps can't get past a PIN or pattern lock.
                    if (!toldScreenOff) {
                        BotLog.log("The phone is locked; paused until you unlock it.")
                        toldScreenOff = true
                    }
                    state = "paused: phone locked"
                    waitOrStop(5.0)
                    continue
                }
                if (toldScreenOff) {
                    toldScreenOff = false
                    BotLog.log("Screen unlocked; opening Hinge again.")
                    ui.openApp()
                    waitOrStop(3.0)
                    continue
                }

                if (!inActiveHours(cfg)) {
                    if (!toldOutsideHours) {
                        val (start, end) = cfg.activeHours
                        BotLog.log("Outside active hours ($start:00–$end:00); waiting until $start:00.")
                        toldOutsideHours = true
                    }
                    state = "waiting for active hours"
                    waitOrStop(60.0)
                    continue
                }
                toldOutsideHours = false
                if (cfg.dailyCap > 0 && !cfg.dryRun && Config.sentToday(svc) >= cfg.dailyCap) {
                    BotLog.log("Daily cap reached.")
                    break
                }

                // Anything that sets `problem` is fixed by opening Hinge fresh. Things a
                // restart can't fix (cap, verification, paywall) break.
                var problem: String? = null
                try {
                    val (screen, detail) = ui.detect()
                    if ((screen == "stop" || screen == "unknown") && !triedDiscoverTab && ui.goToDiscover()) {
                        // Probably just on another tab (e.g. Matches shows an upgrade offer).
                        triedDiscoverTab = true
                        BotLog.log("Not on the Discover feed; tapped the Discover tab.")
                        continue
                    }
                    when (screen) {
                        "discover" -> {
                            triedDiscoverTab = false
                            handleProfile(ui, comments, cfg)
                            if (failures >= 3) {
                                failures = 0
                                problem = "3 failed attempts in a row."
                            } else {
                                restarts = 0
                                pause(cfg)
                            }
                        }
                        "popup" -> {
                            BotLog.log("Closing the '$detail' popup.")
                            ui.dismissPopup(detail)
                            waitOrStop(2.0)
                        }
                        // Closing it unsent needs Back, and an extra Back leaves Hinge.
                        "like_sheet" -> problem = "A comment box was left open."
                        "not_hinge" -> {
                            if (detail == "android" && ui.closeAnrDialog()) {
                                BotLog.log("Closed an \"isn't responding\" dialog.")
                            } else {
                                problem = "Hinge isn't on screen (showing ${detail.ifEmpty { "nothing" }})."
                            }
                        }
                        "stop" -> {
                            BotLog.log("Stopping: saw the '$detail' screen.")
                            break
                        }
                        else -> {
                            val capture = saveCapture(dev, "unknown")
                            problem = "Didn't recognise this screen (saved ${capture.name})."
                        }
                    }
                } catch (e: Exception) {
                    BotLog.trace(e)
                    problem = "Error: ${e.message}."
                }

                if (problem == null || stopped) continue
                if (!u2.alive()) {
                    // Reopening Hinge can't help when uiautomator itself has gone (usually
                    // adb dropped with a Wi-Fi change).
                    if (!reconnect(adb, u2, problem)) break
                    continue
                }
                val limit = cfg.maxRestartsInARow
                if (restarts >= limit) {
                    BotLog.log("$problem Already restarted Hinge $restarts times in a row; stopping.")
                    break
                }
                restarts++
                triedDiscoverTab = false
                state = "restarting Hinge"
                BotLog.log("$problem Opening Hinge fresh ($restarts/$limit).")
                try {
                    ui.restartApp()
                } catch (e: Exception) {
                    BotLog.log("Couldn't reopen Hinge: ${e.message}.")
                }
            }
        } catch (e: Exception) {
            BotLog.log("Error: ${e.message}")
            BotLog.trace(e)
        } finally {
            device?.let { runCatching { it.restoreKeyboard() } }
            u2?.stop()
            nextActionAt = null
            state = "stopped"
            BotLog.log("Stopped.")
            svc.onBotStopped()
        }
    }

    /** Get adb and uiautomator back after a drop, retrying for up to 5 minutes. */
    private fun reconnect(adb: Adb, u2: U2Server, problem: String): Boolean {
        BotLog.log("$problem Lost the connection to uiautomator; reconnecting…")
        state = "reconnecting"
        val deadline = System.currentTimeMillis() + 5 * 60_000
        var told = false
        while (!stopped && System.currentTimeMillis() < deadline) {
            try {
                if (!adb.alive()) runCatching { adb.disconnect() }
                if (adb.ensureConnected(svc)) {
                    u2.ensureRunning()
                    BotLog.log("Reconnected.")
                    return true
                }
            } catch (e: Exception) {
                BotLog.trace(e)
            }
            if (!told) {
                BotLog.log("Still trying. If the phone restarted, connect to Wi-Fi once.")
                told = true
            }
            if (waitOrStop(15.0)) return false
        }
        if (!stopped) BotLog.log("Couldn't reconnect within 5 minutes; stopping.")
        return false
    }

    private fun handleProfile(ui: HingeScreen, comments: CommentPicker, cfg: Settings) {
        val dry = cfg.dryRun

        if (Random.nextDouble() < cfg.skipProbability) {
            skip(ui, dry, "random skip")
            return
        }

        // Only the first screen (name, pronouns, first photo and prompt) is checked here,
        // and the screen with the heart below.
        val patterns = keywordPatterns(cfg)
        if (patterns.isNotEmpty()) {
            findKeyword(patterns, ui.readProfile(0))?.let {
                skip(ui, dry, "profile mentions '$it'")
                return
            }
        }

        state = "looking at a profile"
        val target = ui.pickLikeTarget(cfg.maxScrolls)
        if (target == null) {
            BotLog.log("No like button found on this profile.")
            skip(ui, dry, "no like button")
            return
        }
        val label = ui.describe(target)
        if (patterns.isNotEmpty()) {
            findKeyword(patterns, ui.readProfile(0))?.let {
                skip(ui, dry, "profile mentions '$it'")
                return
            }
        }

        val comment = comments.pick()
        if (dry) {
            // Stop before opening the comment box: closing it unsent needs Back presses,
            // which can leave Hinge.
            dryRuns++
            BotLog.log("[dry run] Would like '$label' with “$comment”")
            ui.backToTop()
            return
        }

        // STOP pressed while this profile was being looked at: don't start a like.
        if (stopped) return
        state = "opening the like sheet"
        if (!ui.openLike(target)) {
            failures++
            BotLog.log("The like sheet didn't open; trying again next round.")
            return
        }
        failures = 0
        state = "writing comment"
        ui.writeComment(comment)
        if (stopped) {
            // The comment box stays open; the next Start reopens Hinge to close it.
            BotLog.log("Stopped before sending (the comment box is left open, unsent).")
            return
        }
        if (ui.send()) {
            sentThisRun++
            ui.newProfile()
            BotLog.log("Sent like #${Config.bumpSent(svc)} on '$label': “$comment”")
        } else {
            throw RuntimeException("Tapped Send, but the comment box stayed open")
        }
    }

    private fun skip(ui: HingeScreen, dry: Boolean, reason: String) {
        if (dry) {
            BotLog.log("[dry run] Would skip ($reason).")
            ui.backToTop()
        } else {
            ui.skip()
            ui.newProfile()
            skippedThisRun++
            BotLog.log("Skipped ($reason).")
        }
    }

    /** skip_keywords as whole-word, case-insensitive patterns, so 'he/him' doesn't match
     * 'she/her' and 'gym' doesn't match 'gymnast'. */
    private fun keywordPatterns(cfg: Settings): List<Pair<String, Pattern>> =
        cfg.skipKeywords.filter { it.isNotBlank() }.map { word ->
            word to Pattern.compile(
                "(?<!\\w)${Pattern.quote(word.trim())}(?!\\w)",
                // Android's regex engine treats \\w as Unicode already (like Python's re).
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
        }

    private fun findKeyword(patterns: List<Pair<String, Pattern>>, texts: List<String>): String? =
        patterns.firstOrNull { (_, p) -> texts.any { p.matcher(it).find() } }?.first

    private fun pause(cfg: Settings) {
        val (low, high) = if (cfg.dryRun) cfg.dryRunDelay else cfg.delay
        val seconds = if (high > low) Random.nextDouble(low, high) else low
        nextActionAt = System.currentTimeMillis() + (seconds * 1000).toLong()
        state = "waiting"
        waitOrStop(seconds)
        nextActionAt = null
    }

    private fun inActiveHours(cfg: Settings): Boolean {
        val (start, end) = cfg.activeHours
        val hour = LocalDateTime.now().hour
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
    }

    /** Saves what's on screen to files/captures, for fixing selectors.json. */
    fun saveCapture(dev: Device, label: String): File {
        val dir = File(svc.getExternalFilesDir(null) ?: svc.filesDir, "captures").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(dir, "$label-$stamp.txt").apply { writeText(dev.describeScreen()) }
    }
}

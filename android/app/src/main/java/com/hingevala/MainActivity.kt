package com.hingevala

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** The control app: Home (Start/Stop, stats, live activity), Settings, Comments, Setup. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val engine get() = BotRunService.instance?.engine

    // Home
    private lateinit var core: CoreButton
    private lateinit var statusChip: TextView
    private lateinit var hingeX: TextView
    private lateinit var hingePlus: TextView
    private lateinit var dryRun: Switch
    private lateinit var sentValue: TextView
    private lateinit var sentBar: View
    private lateinit var sentBarTrack: FrameLayout
    private lateinit var runValue: TextView
    private lateinit var nextValue: TextView
    private lateinit var connectionLine: TextView
    private lateinit var feed: LinearLayout
    private var feedKey = ""

    // Settings
    private lateinit var dailyCap: EditText
    private lateinit var delayMin: EditText
    private lateinit var delayMax: EditText
    private lateinit var dryDelayMin: EditText
    private lateinit var dryDelayMax: EditText
    private lateinit var skipPercent: EditText
    private lateinit var maxScrolls: EditText
    private lateinit var hoursStart: EditText
    private lateinit var hoursEnd: EditText
    private lateinit var keywords: EditText
    private lateinit var maxRestarts: EditText
    private lateinit var noRepeat: EditText

    // Comments
    private lateinit var comments: EditText
    private lateinit var commentCount: TextView

    // Setup
    private val stepStatus = mutableMapOf<String, TextView>()
    private lateinit var setupConnection: TextView
    private lateinit var fullLog: TextView

    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    @Volatile
    private var connection = "checking…"

    @Volatile
    private var polling = false

    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BotLog.init(this)
        setContentView(buildUi())
        loadFields()
        showPage(if (Pairing.isPaired(this) || Adb.canChangeSettings(this)) 0 else 3)
        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        main.post(ticker)
        startConnectionPolling()
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        polling = false
        super.onPause()
    }

    /** Debug builds only, for testing on the emulator without tapping:
     * `adb shell am start -n com.hingevala/.MainActivity --es cmd start` (or stop, check,
     * dry_on, dry_off), and `--es cmd set --es key daily_cap --es value 10`. */
    private fun handleCommand(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        when (intent.getStringExtra("cmd")) {
            "start" -> start()
            "stop" -> BotRunService.send(this, BotRunService.ACTION_STOP)
            "check" -> BotRunService.send(this, BotRunService.ACTION_CHECK)
            "dry_on", "dry_off" -> Config.update(this, mapOf("dry_run" to (intent.getStringExtra("cmd") == "dry_on")))
            "set" -> {
                val key = intent.getStringExtra("key") ?: return
                val raw = intent.getStringExtra("value") ?: ""
                val value: Any = raw.toIntOrNull() ?: raw.toDoubleOrNull() ?: raw.toBooleanStrictOrNull() ?: raw
                Config.update(this, mapOf(key to value))
            }
            else -> return
        }
        loadFields()
        intent.removeExtra("cmd")
    }

    // ---- actions ------------------------------------------------------------------------

    private fun toggleRun() {
        if (engine?.running == true) BotRunService.send(this, BotRunService.ACTION_STOP) else start()
    }

    private fun start() {
        val reachable = connection.startsWith("ready") || connection.startsWith("connected")
        if (!reachable && !Pairing.isPaired(this) && !Adb.canChangeSettings(this)) {
            toast("Do the Setup tab first (once).")
            showPage(3)
            return
        }
        askForNotifications()
        saveSettings(quiet = true)
        BotRunService.send(this, BotRunService.ACTION_START)
    }

    private fun askForNotifications() {
        if (!notificationsAllowed()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pair() {
        if (!notificationsAllowed()) {
            toast("Allow notifications first: the pairing code is typed into one. Then tap Pair again.")
            askForNotifications()
            return
        }
        Pairing.prompt(this)
        toast("Tap \"Pair device with pairing code\", then type the code into the Hingevala notification.")
        openWirelessDebugging()
    }

    /** Developer options, scrolled to Wireless debugging where the phone supports it. */
    private fun openWirelessDebugging() = openDeveloperOptions("toggle_adb_wireless")

    private fun openDeveloperOptions(highlight: String) {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", highlight)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", highlight) })
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("Turn on Developer options first (step 1).")
        }
    }

    private fun testConnection() {
        toast("Connecting…")
        Thread {
            val message = try {
                val adb = Adb.get(this)
                if (adb.ensureConnected(this)) {
                    "Connected ✓ ${adb.shell("getprop ro.product.model")}, Android ${adb.shell("getprop ro.build.version.release")}"
                } else {
                    "Couldn't connect. Connect to Wi-Fi (needed once after a restart) and try again."
                }
            } catch (e: Exception) {
                BotLog.trace(e)
                "Couldn't connect: ${e.message}"
            }
            BotLog.log("Connection test: $message")
            runOnUiThread { toast(message) }
        }.start()
    }

    private fun shareLog() {
        val text = BotLog.fileTail(400).ifBlank { "No log yet." }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Share the Hingevala log",
            ),
        )
    }

    /** Checks the connection in the background (it can take a moment) while the app is open. */
    private fun startConnectionPolling() {
        if (polling) return
        polling = true
        Thread {
            while (polling) {
                connection = try {
                    if (engine?.running == true) "connected" else Adb.get(this).quickCheck(this)
                } catch (e: Exception) {
                    "unknown (${e.message})"
                }
                Thread.sleep(4000)
            }
        }.start()
    }

    private fun saveSettings(quiet: Boolean = false) {
        fun num(field: EditText, fallback: Double) = field.text.toString().trim().toDoubleOrNull() ?: fallback
        val cfg = Config.settings(this)
        val low = num(delayMin, cfg.delay.first).coerceAtLeast(0.0)
        val high = num(delayMax, cfg.delay.second).coerceAtLeast(low)
        val dryLow = num(dryDelayMin, cfg.dryRunDelay.first).coerceAtLeast(0.0)
        val dryHigh = num(dryDelayMax, cfg.dryRunDelay.second).coerceAtLeast(dryLow)
        Config.update(
            this,
            mapOf(
                "daily_cap" to num(dailyCap, cfg.dailyCap.toDouble()).toInt().coerceAtLeast(0),
                "delay_seconds" to listOf(low, high),
                "dry_run_delay_seconds" to listOf(dryLow, dryHigh),
                "skip_probability" to (num(skipPercent, cfg.skipProbability * 100) / 100).coerceIn(0.0, 1.0),
                "max_scrolls" to num(maxScrolls, cfg.maxScrolls.toDouble()).toInt().coerceIn(0, 20),
                "active_hours" to listOf(
                    num(hoursStart, cfg.activeHours.first.toDouble()).toInt().coerceIn(0, 24),
                    num(hoursEnd, cfg.activeHours.second.toDouble()).toInt().coerceIn(0, 24),
                ),
                "skip_keywords" to keywords.text.lines().map { it.trim() }.filter { it.isNotEmpty() },
                "max_restarts_in_a_row" to num(maxRestarts, cfg.maxRestartsInARow.toDouble()).toInt().coerceIn(0, 20),
                "comment_no_repeat" to num(noRepeat, cfg.commentNoRepeat.toDouble()).toInt().coerceIn(0, 500),
            ),
        )
        loadFields()
        if (!quiet) toast(if (engine?.running == true) "Saved. Used from the next profile." else "Saved.")
    }

    private fun saveComments() {
        if (usableComments(comments.text.toString()) == 0) {
            toast("Add at least one comment first.")
            return
        }
        Config.saveComments(this, comments.text.toString())
        toast(if (engine?.running == true) "Saved. Used from the next Start." else "Saved.")
    }

    private fun resetComments() {
        comments.setText(assets.open("comments.txt").bufferedReader().use { it.readText() })
        toast("Restored the default list. Tap Save to keep it.")
    }

    private fun usableComments(text: String) = text.lines().count { it.isNotBlank() && !it.trim().startsWith("#") }

    private fun loadFields() {
        val cfg = Config.settings(this)
        dailyCap.setText(cfg.dailyCap.toString())
        delayMin.setText(fmt(cfg.delay.first))
        delayMax.setText(fmt(cfg.delay.second))
        dryDelayMin.setText(fmt(cfg.dryRunDelay.first))
        dryDelayMax.setText(fmt(cfg.dryRunDelay.second))
        skipPercent.setText(fmt(cfg.skipProbability * 100))
        maxScrolls.setText(cfg.maxScrolls.toString())
        hoursStart.setText(cfg.activeHours.first.toString())
        hoursEnd.setText(cfg.activeHours.second.toString())
        keywords.setText(cfg.skipKeywords.joinToString("\n"))
        maxRestarts.setText(cfg.maxRestartsInARow.toString())
        noRepeat.setText(cfg.commentNoRepeat.toString())
        if (comments.text.isEmpty()) comments.setText(Config.commentsText(this))
        dryRun.isChecked = cfg.dryRun
        renderVersion(cfg.variant)
    }

    private fun setVariant(variant: String) {
        if (Config.settings(this).variant == variant) return
        Config.update(this, mapOf("variant" to variant))
        renderVersion(variant)
        if (engine?.running == true) toast("Used from the next Start.")
    }

    private fun fmt(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value).trimEnd('0')

    // ---- rendering ----------------------------------------------------------------------

    private fun render() {
        val e = engine
        val running = e?.running == true
        val cfg = Config.settings(this)

        core.running = running
        core.label = if (running) "STOP" else "START"
        core.caption = if (running) e!!.state else if (cfg.dryRun) "dry run · sends nothing" else "full auto"
        when {
            running && cfg.dryRun -> chip(statusChip, "● DRY RUN", Theme.amber)
            running -> chip(statusChip, "● LIVE", Theme.green)
            else -> chip(statusChip, "○ IDLE", Theme.muted)
        }
        if (dryRun.isChecked != cfg.dryRun) dryRun.isChecked = cfg.dryRun

        val sent = Config.sentToday(this)
        sentValue.text = if (cfg.dailyCap > 0) "$sent/${cfg.dailyCap}" else "$sent"
        val fraction = if (cfg.dailyCap > 0) (sent.toFloat() / cfg.dailyCap).coerceIn(0f, 1f) else 0f
        val barWidth = (sentBarTrack.width * fraction).toInt()
        if (sentBar.layoutParams.width != barWidth) sentBar.layoutParams = sentBar.layoutParams.apply { width = barWidth }
        runValue.text = when {
            e == null -> "–"
            cfg.dryRun -> "♡ ${e.dryRuns}"
            else -> "♥${e.sentThisRun} ✕${e.skippedThisRun}"
        }
        val next = e?.nextActionAt?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0) }
        nextValue.text = if (next == null) "–" else "${next}s"
        connectionLine.text = "⚡  adb: $connection"
        val good = connection.startsWith("connected") || connection.startsWith("ready")
        connectionLine.setTextColor(if (good) Theme.muted else Theme.amber)

        renderFeed()
        renderSetup()
    }

    private fun renderVersion(variant: String) {
        segment(hingeX, variant != Selectors.PLUS)
        segment(hingePlus, variant == Selectors.PLUS)
    }

    private fun renderFeed() {
        val lines = BotLog.recent(300)
        val key = "${lines.size}|${lines.lastOrNull()}"
        if (key == feedKey) return
        feedKey = key
        fullLog.text = if (lines.isEmpty()) "No activity yet." else lines.joinToString("\n")
        feed.removeAllViews()
        val events = lines.asReversed().take(40)
        if (events.isEmpty()) {
            feed.addView(label("Nothing yet. Press START and Hinge opens; every like and skip shows up here.", 13f, Theme.muted))
            return
        }
        for (line in events) feed.addView(feedRow(line))
    }

    private fun renderSetup() {
        fun mark(key: String, done: Boolean, doneText: String, todoText: String) {
            stepStatus[key]?.apply {
                text = if (done) "✓ $doneText" else "○ $todoText"
                setTextColor(if (done) Theme.green else Theme.amber)
            }
        }
        val cr = contentResolver
        mark("dev", AndroidSettings.Global.getInt(cr, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1, "on", "off")
        mark("usb", AndroidSettings.Global.getInt(cr, AndroidSettings.Global.ADB_ENABLED, 0) == 1, "on", "off: needed to run on mobile data")
        mark("pair", Pairing.isPaired(this) || Adb.canChangeSettings(this), "paired", "not paired yet")
        mark("notify", notificationsAllowed(), "allowed", "not allowed")
        setupConnection.text = "adb: $connection"
    }

    private fun feedRow(line: String): View {
        val time = line.take(5)
        val msg = line.substringAfter("  ", line)
        val (icon, color) = when {
            msg.startsWith("Sent like") -> "♥" to Theme.pink
            msg.startsWith("[dry run] Would like") -> "♡" to Theme.amber
            msg.startsWith("[dry run] Would skip") || msg.startsWith("Skipped") -> "✕" to Theme.muted
            msg.startsWith("Started") || msg.startsWith("Stopped") || msg.startsWith("STOP") -> "●" to Theme.cyan
            listOf("Error", "Couldn't", "failed", "Didn't", "Lost").any { it in msg } -> "!" to Theme.red
            else -> "•" to Theme.muted
        }
        val text = msg.replace(Regex("^Sent like #(\\d+) on "), "Liked #$1 · ").removePrefix("[dry run] ")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
            addView(TextView(this@MainActivity).apply {
                this.text = icon
                gravity = Gravity.CENTER
                setTextColor(color)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                background = round(Theme.alpha(color, 36), dp(14))
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(text, 13.5f, Theme.text).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(label(time, 11f, Theme.muted))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    // ---- layout -------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
            fitsSystemWindows = true
        }
        val content = FrameLayout(this)
        pages = listOf(homePage(), settingsPage(), commentsPage(), setupPage())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(bottomNav())
        return root
    }

    private fun page(title: String, build: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
            // Takes the initial focus, so no text box grabs it (and the keyboard) on launch.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            if (title.isNotEmpty()) addView(label(title, 28f, Theme.text, bold = true).apply { setPadding(0, dp(6), 0, dp(4)) })
            build()
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
    }

    private fun homePage() = page("") {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gradientLabel("HINGEVALA", 15f).apply { letterSpacing = 0.32f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            statusChip = label("", 12f, Theme.muted, bold = true)
            addView(statusChip)
        })
        addView(label("Hinge autopilot", 26f, Theme.text, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        core = CoreButton(this@MainActivity).apply { setOnClickListener { toggleRun() } }
        addView(core, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        // Hinge version: a two-way segmented control.
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(Theme.surface, dp(24), Theme.line)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            hingeX = segmentButton("HingeX") { setVariant(Selectors.HINGEX) }
            hingePlus = segmentButton("Hinge+") { setVariant(Selectors.PLUS) }
            addView(hingeX, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(hingePlus, LinearLayout.LayoutParams(0, dp(40), 1f))
        })

        addView(card {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Dry run", 16f, Theme.text, bold = true))
                addView(label("Only logs what it would like. Turn off to send for real.", 12.5f, Theme.muted))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            dryRun = Switch(this@MainActivity).apply {
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(Theme.amber, Theme.muted))
                trackTintList = ColorStateList(states, intArrayOf(Theme.alpha(Theme.amber, 110), Theme.line))
                setOnCheckedChangeListener { _, checked ->
                    if (Config.settings(this@MainActivity).dryRun != checked) {
                        Config.update(this@MainActivity, mapOf("dry_run" to checked))
                    }
                }
            }
            addView(dryRun)
        })

        // Stats.
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            val (sent, sentTile) = statTile("SENT TODAY")
            sentValue = sent
            sentBarTrack = FrameLayout(this@MainActivity).apply { background = round(Theme.line, dp(2)) }
            sentBar = View(this@MainActivity).apply { background = gradient(dp(2)) }
            sentBarTrack.addView(sentBar, FrameLayout.LayoutParams(0, MATCH_PARENT))
            sentTile.addView(sentBarTrack, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
            addView(sentTile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(8) })
            val (run, runTile) = statTile("THIS RUN")
            runValue = run
            addView(runTile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(8) })
            val (next, nextTile) = statTile("NEXT IN")
            nextValue = next
            addView(nextTile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        })

        connectionLine = label("", 12.5f, Theme.muted).apply { setPadding(dp(4), dp(12), 0, 0) }
        addView(connectionLine)

        addView(sectionLabel("LIVE ACTIVITY"))
        addView(card { feed = this })
        addView(
            label(
                "Keep the phone unlocked and charging while it runs. The red STOP button floats over every app " +
                    "(drag it anywhere); there's also STOP in the notification.",
                12f, Theme.muted,
            ).apply { setPadding(dp(4), dp(12), dp(4), 0) },
        )
    }

    private fun settingsPage() = page("Settings") {
        addView(sectionLabel("PACE"))
        addView(card {
            delayMin = field("Wait between profiles, min (s)")
            delayMax = field("Wait between profiles, max (s)")
            skipPercent = field("Skip at random (% of profiles)")
            dryDelayMin = field("Dry run wait, min (s)")
            dryDelayMax = field("Dry run wait, max (s)")
        })
        addView(sectionLabel("LIMITS"))
        addView(card {
            dailyCap = field("Daily cap (0 = no limit)", decimal = false)
            hoursStart = field("Active from hour (0–24)", decimal = false)
            hoursEnd = field("Active until hour (0–24)", decimal = false)
            maxScrolls = field("Max scrolls before picking a heart", decimal = false)
        })
        addView(sectionLabel("SKIP PROFILES MENTIONING"))
        addView(card {
            addView(label("One per line. Whole words, any case, e.g. he/him.", 12.5f, Theme.muted))
            keywords = multiline(5)
        })
        addView(sectionLabel("RECOVERY"))
        addView(card {
            maxRestarts = field("Reopen Hinge up to (times in a row)", decimal = false)
            noRepeat = field("Don't repeat the last (comments)", decimal = false)
        })
        addView(primaryButton("Save settings") { saveSettings() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
    }

    private fun commentsPage() = page("Comments") {
        commentCount = label("", 13f, Theme.muted)
        addView(commentCount)
        addView(card {
            comments = multiline(14).apply {
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        commentCount.text = "${usableComments(s.toString())} comments · one per line, picked at random · # lines ignored"
                    }

                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
        })
        addView(primaryButton("Save comments") { saveComments() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
        addView(outlineButton("Restore the default list") { resetComments() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
    }

    private fun setupPage() = page("Setup") {
        addView(
            label(
                "Once only, no computer needed. Hinge hides its screen from accessibility apps, so Hingevala " +
                    "uses the phone's own debugging tools instead.",
                13f, Theme.muted,
            ),
        )
        addView(step("dev", 1, "Developer options", "Settings → About phone → tap Build number 7 times.",
            outlineButton("Open About phone") { safeStart(Intent(AndroidSettings.ACTION_DEVICE_INFO_SETTINGS)) }))
        addView(step("usb", 2, "USB debugging", "In Developer options, turn on USB debugging. No cable needed: it keeps " +
            "the bot working on mobile data with Wi-Fi off.",
            outlineButton("Open Developer options") { openDeveloperOptions("enable_adb") }))
        addView(step("pair", 3, "Pair (on Wi-Fi)", "Connect to Wi-Fi and turn on Wireless debugging. Tap Pair, then " +
            "\"Pair device with pairing code\", and type the 6 digits into the Hingevala notification.",
            primaryButton("Pair") { pair() }, outlineButton("Wireless debugging") { openWirelessDebugging() }))
        addView(step("notify", 4, "Notifications", "For the pairing code and the STOP button in the notification.",
            outlineButton("Allow") { askForNotifications() }))

        addView(sectionLabel("CONNECTION"))
        addView(card {
            setupConnection = label("", 13.5f, Theme.text)
            addView(setupConnection)
            addView(
                label(
                    "After a phone restart: connect to any Wi-Fi once and press START (it turns Wireless debugging " +
                        "on by itself). After that, mobile data is enough until the next restart.",
                    12.5f, Theme.muted,
                ).apply { setPadding(0, dp(6), 0, dp(10)) },
            )
            addView(outlineButton("Test connection") { testConnection() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)))
        })

        addView(sectionLabel("WHEN HINGE UPDATES"))
        addView(card {
            addView(label("Opens Hinge and lists which buttons the bot can find there (in the log below).", 12.5f, Theme.muted).apply { setPadding(0, 0, 0, dp(10)) })
            addView(outlineButton("Check Hinge screen") { BotRunService.send(this@MainActivity, BotRunService.ACTION_CHECK) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)))
            addView(outlineButton("Share log") { shareLog() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        })

        addView(sectionLabel("FULL LOG"))
        addView(card {
            fullLog = label("No activity yet.", 11f, Theme.muted).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            addView(fullLog)
        })
    }

    private fun step(key: String, number: Int, title: String, body: String, vararg actions: View) = card {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = number.toString()
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = gradient(dp(14))
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(label(title, 16f, Theme.text, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        val status = label("", 12.5f, Theme.amber, bold = true).apply { setPadding(dp(40), dp(4), 0, 0) }
        stepStatus[key] = status
        addView(status)
        addView(label(body, 13f, Theme.muted).apply { setPadding(dp(40), dp(4), 0, dp(10)) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(40), 0, 0, 0)
            actions.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        })
    }

    private fun bottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.surface)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val items = listOf("◉\nHome", "⚙\nSettings", "✎\nComments", "⚡\nSetup")
        tabs = items.mapIndexed { i, t ->
            TextView(this).apply {
                text = t
                gravity = Gravity.CENTER
                textSize = 11.5f
                setOnClickListener { showPage(i) }
            }.also { bar.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        }
        return bar
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == index) Theme.pink else Theme.muted)
            t.typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        currentFocus?.clearFocus()
    }

    // ---- small building blocks ----------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun round(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun gradient(radius: Int) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, Theme.gradient).apply {
        cornerRadius = radius.toFloat()
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Text filled with the pink-to-orange gradient. */
    private fun gradientLabel(value: String, size: Float) = label(value, size, Theme.pink, bold = true).apply {
        post {
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, paint.measureText(value), 0f, Theme.pink, Theme.orange, android.graphics.Shader.TileMode.CLAMP,
            )
            invalidate()
        }
    }

    private fun sectionLabel(value: String) = label(value, 12f, Theme.muted, bold = true).apply {
        letterSpacing = 0.14f
        setPadding(dp(4), dp(22), 0, 0)
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = round(Theme.surface, dp(18), Theme.line)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        build()
    }

    private fun statTile(title: String): Pair<TextView, LinearLayout> {
        val value = label("–", 21f, Theme.text, bold = true).apply { maxLines = 1 }
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Theme.surface, dp(16), Theme.line)
            addView(label(title, 10.5f, Theme.muted, bold = true).apply { letterSpacing = 0.1f })
            addView(value)
        }
        return value to tile
    }

    private fun chip(view: TextView, text: String, color: Int) {
        if (view.text == text) return
        view.text = text
        view.setTextColor(color)
        view.setPadding(dp(10), dp(4), dp(10), dp(4))
        view.background = round(Theme.alpha(color, 30), dp(12), Theme.alpha(color, 90))
    }

    private fun segmentButton(text: String, onClick: () -> Unit) = label(text, 14f, Theme.muted, bold = true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun segment(view: TextView, selected: Boolean) {
        view.background = if (selected) gradient(dp(20)) else null
        view.setTextColor(if (selected) Color.WHITE else Theme.muted)
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = label(text, 15f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        background = gradient(dp(26))
        setOnClickListener { onClick() }
    }

    private fun outlineButton(text: String, onClick: () -> Unit) = label(text, 14f, Theme.text, bold = true).apply {
        gravity = Gravity.CENTER
        background = round(Theme.surface2, dp(24), Theme.line)
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.field(text: String, decimal: Boolean = true): EditText {
        val input = EditText(this@MainActivity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            gravity = Gravity.CENTER
            setTextColor(Theme.text)
            textSize = 15f
            background = round(Theme.surface2, dp(10), Theme.line)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(label(text, 14f, Theme.text), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(input, LinearLayout.LayoutParams(dp(84), dp(42)))
        })
        return input
    }

    private fun LinearLayout.multiline(lines: Int) = EditText(this@MainActivity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = lines
        gravity = Gravity.TOP or Gravity.START
        textSize = 14f
        setTextColor(Theme.text)
        background = round(Theme.surface2, dp(12), Theme.line)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }.also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }) }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("Couldn't open that screen on this phone.")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

package com.hingevala

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Runs the bot in the background while Hinge is in front, with a notification (it has a
 * Stop button) and a floating STOP button on top of every app; drag it anywhere.
 */
class BotRunService : Service() {
    companion object {
        const val ACTION_START = "com.hingevala.START"
        const val ACTION_STOP = "com.hingevala.STOP"
        const val ACTION_CHECK = "com.hingevala.CHECK"
        private const val CHANNEL = "bot"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: BotRunService? = null
            private set

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, BotRunService::class.java).setAction(action))
        }
    }

    val engine by lazy { BotEngine(this) }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var checking = false

    private var stopButton: View? = null
    private var stopStatus: TextView? = null

    @Volatile
    private var stopRect: Rect? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BotLog.init(this)
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Bot running", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start must go to the foreground straight away, even a Stop.
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        when (intent?.action) {
            ACTION_START -> {
                if (!engine.start()) BotLog.log("Already running.")
                main.removeCallbacks(notificationTicker)
                main.postDelayed(notificationTicker, 2000)
            }
            ACTION_STOP -> {
                engine.stop()
                finishIfIdle()
            }
            ACTION_CHECK -> checkScreen()
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        removeStopButton()
        instance = null
        super.onDestroy()
    }

    private val notificationTicker = object : Runnable {
        override fun run() {
            if (!engine.running) return
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            main.postDelayed(this, 5000)
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BotRunService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (engine.running) "Hingevala · ${Config.sentToday(this)} sent today" else "Hingevala")
            .setContentText(if (engine.running) engine.state else "Starting…")
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "STOP", stop).build())
            .build()
    }

    /** Called by the bot when its loop ends. */
    fun onBotStopped() = main.post {
        removeStopButton()
        finishIfIdle()
    }

    private fun finishIfIdle() {
        if (!engine.running && !checking) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun screenLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Where the floating STOP button is on screen, so the bot never taps it. */
    fun stopButtonBounds(): Rect? = stopRect

    /** The tools/doctor.py check: open Hinge, see which selectors match, save the screen. */
    private fun checkScreen() {
        if (engine.running || checking) {
            BotLog.log("Stop the bot before checking the screen.")
            return
        }
        checking = true
        Thread({
            var u2: U2Server? = null
            try {
                val adb = Adb.get(this)
                if (!adb.ensureConnected(this)) {
                    BotLog.log("Check: couldn't connect to adb. Connect to Wi-Fi and try again (see Setup).")
                    return@Thread
                }
                BotLog.log("Check: connected to adb; starting uiautomator…")
                u2 = U2Server(this, adb).also { it.ensureRunning() }
                val sel = Selectors.load(this, Config.settings(this).variant)
                val dev = Device(this, adb, u2)
                val ui = HingeScreen(dev, sel)
                BotLog.log("Check: opening Hinge and reading its screen in 4s…")
                ui.openApp()
                Thread.sleep(4000)
                val nodes = dev.snapshot()
                BotLog.log("Foreground app: ${dev.foregroundPackage()}")
                BotLog.log("Detected screen: ${ui.detect(wait = 0.0)}")
                BotLog.log("Like buttons visible: ${dev.match(sel.likeButtons, nodes).size}")
                BotLog.log("Skip button found: ${dev.exists(sel.skipButton, nodes)}")
                BotLog.log("Discover tab found: ${dev.exists(sel.discoverTab, nodes)}")
                BotLog.log("Comment box found: ${dev.exists(sel.commentField, nodes)}")
                BotLog.log("Send button found: ${dev.exists(sel.sendButton, nodes)}")
                val file = engine.saveCapture(dev, "check")
                BotLog.log("Saved the screen to ${file.absolutePath}")
                adb.shell("am start -n $packageName/.MainActivity")
            } catch (e: Exception) {
                BotLog.log("Check failed: ${e.message}")
                BotLog.trace(e)
            } finally {
                u2?.stop()
                checking = false
                main.post { finishIfIdle() }
            }
        }, "hingevala-check").start()
    }

    // ---- floating STOP button ---------------------------------------------------------

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Show the floating STOP button. The bot first allows "Display over other apps"
     * through adb, so there's nothing to set up by hand. */
    fun showStopButton() = main.post {
        if (stopButton != null || !engine.running) return@post
        if (!AndroidSettings.canDrawOverlays(this)) {
            BotLog.log("No floating STOP button: \"Display over other apps\" is off. Use the notification's STOP.")
            return@post
        }
        addStopButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addStopButton() {
        val wm = getSystemService(WindowManager::class.java)
        val label = TextView(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E0B3261E"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(status, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Left edge, a little below the middle: clear of the hearts (right side) and of the
        // bot's swipes (centre of the screen). Drag it anywhere.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, // the bot can't work on a dark screen
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (resources.displayMetrics.heightPixels * 0.55).toInt()
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        column.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        dragging = true
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        wm.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        view.post { updateStopRect(view) }
                    } else {
                        BotLog.log("STOP pressed.")
                        engine.stop()
                        status.text = "stopping…"
                    }
                }
            }
            true
        }

        wm.addView(column, params)
        stopButton = column
        stopStatus = status
        column.post { updateStopRect(column) }
        tick()
    }

    private fun updateStopRect(view: View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        // A margin around it, so a tap aimed just beside it can't catch its edge.
        stopRect = Rect(loc[0] - dp(8), loc[1] - dp(8), loc[0] + view.width + dp(8), loc[1] + view.height + dp(8))
    }

    private val ticker = Runnable { tick() }

    private fun tick() {
        val status = stopStatus ?: return
        val next = engine.nextActionAt?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(0) }
        val sent = Config.sentToday(this)
        status.text = if (next != null) "$sent sent · ${next}s" else "$sent sent"
        main.postDelayed(ticker, 1000)
    }

    private fun removeStopButton() {
        main.removeCallbacks(ticker)
        stopButton?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        stopButton = null
        stopStatus = null
        stopRect = null
    }
}

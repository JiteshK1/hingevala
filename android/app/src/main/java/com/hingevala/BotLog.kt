package com.hingevala

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The log shown on the home screen, also appended to files/bot.log. */
object BotLog {
    private val lines = ArrayDeque<String>()
    private var file: File? = null

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "bot.log")
    }

    @Synchronized
    fun log(message: String) {
        val now = Date()
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(now)}  $message"
        lines.addLast(line)
        while (lines.size > 300) lines.removeFirst()
        Log.i("Hingevala", message)
        append("${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)} $line\n")
    }

    /** A full stack trace, to the file only. */
    @Synchronized
    fun trace(error: Throwable) {
        Log.w("Hingevala", error)
        append(Log.getStackTraceString(error) + "\n")
    }

    /** The last `lines` lines of files/bot.log, for sharing. */
    @Synchronized
    fun fileTail(lines: Int): String = try {
        file?.takeIf { it.exists() }?.readLines()?.takeLast(lines)?.joinToString("\n") ?: ""
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun recent(count: Int = 150): List<String> = lines.toList().takeLast(count)

    private fun append(text: String) {
        try {
            val f = file ?: return
            // Keep the file small: past 2 MB, drop the older half.
            if (f.length() > 2_000_000) f.writeText(f.readText().takeLast(1_000_000))
            f.appendText(text)
        } catch (_: Exception) {
        }
    }
}

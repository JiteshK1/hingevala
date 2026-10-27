package com.hingevala

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** The bot's settings for one round of the loop. */
data class Settings(
    val variant: String, // "hingex" or "plus"
    val dryRun: Boolean,
    val dailyCap: Int, // 0 means no limit
    val delay: Pair<Double, Double>,
    val dryRunDelay: Pair<Double, Double>,
    val skipProbability: Double,
    val activeHours: Pair<Int, Int>,
    val maxScrolls: Int,
    val commentNoRepeat: Int,
    val maxRestartsInARow: Int,
    val skipKeywords: List<String>,
)

/**
 * Settings are config.json from the repo (bundled as an asset) with the app's own edits
 * on top, so a fresh install starts with the same limits as the Python bot.
 */
object Config {
    private const val PREFS = "hingevala"
    private const val EDITS = "config_edits"

    // Settings that only exist on the phone. Dry run starts off, as in config.json.
    private val PHONE_DEFAULTS = mapOf<String, Any>("variant" to "hingex", "dry_run" to false)

    fun json(ctx: Context): JSONObject {
        val config = JSONObject(ctx.assets.open("config.json").bufferedReader().use { it.readText() })
        PHONE_DEFAULTS.forEach { (key, value) -> config.put(key, value) }
        val edits = edits(ctx)
        for (key in edits.keys()) config.put(key, edits.get(key))
        return config
    }

    fun settings(ctx: Context): Settings {
        val j = json(ctx)
        return Settings(
            variant = j.optString("variant", "hingex"),
            dryRun = j.optBoolean("dry_run", true),
            dailyCap = j.optInt("daily_cap", 0),
            delay = doublePair(j, "delay_seconds", 30.0, 120.0),
            dryRunDelay = doublePair(j, "dry_run_delay_seconds", 5.0, 10.0),
            skipProbability = j.optDouble("skip_probability", 0.25),
            activeHours = j.optJSONArray("active_hours").let { (it?.optInt(0, 8) ?: 8) to (it?.optInt(1, 24) ?: 24) },
            maxScrolls = j.optInt("max_scrolls", 3),
            commentNoRepeat = j.optInt("comment_no_repeat", 10),
            maxRestartsInARow = j.optInt("max_restarts_in_a_row", 3),
            skipKeywords = j.optJSONArray("skip_keywords").strings(),
        )
    }

    /** Saves the given settings over the bundled config.json. */
    fun update(ctx: Context, changes: Map<String, Any>) {
        val edits = edits(ctx)
        for ((key, value) in changes) {
            edits.put(key, if (value is List<*>) JSONArray(value) else value)
        }
        prefs(ctx).edit().putString(EDITS, edits.toString()).apply()
    }

    // ---- comments.txt -------------------------------------------------------------------

    private fun commentsFile(ctx: Context) = File(ctx.filesDir, "comments.txt")

    /** The phone's own copy of comments.txt, first copied from the bundled one. */
    fun commentsText(ctx: Context): String {
        val file = commentsFile(ctx)
        if (!file.exists()) {
            file.writeText(ctx.assets.open("comments.txt").bufferedReader().use { it.readText() })
        }
        return file.readText()
    }

    fun saveComments(ctx: Context, text: String) = commentsFile(ctx).writeText(text)

    // ---- likes sent today ---------------------------------------------------------------

    fun sentToday(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString("sent_date", null) == LocalDate.now().toString()) p.getInt("sent", 0) else 0
    }

    fun bumpSent(ctx: Context): Int {
        val sent = sentToday(ctx) + 1
        prefs(ctx).edit().putString("sent_date", LocalDate.now().toString()).putInt("sent", sent).apply()
        return sent
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun edits(ctx: Context) = JSONObject(prefs(ctx).getString(EDITS, null) ?: "{}")

    private fun doublePair(j: JSONObject, key: String, low: Double, high: Double): Pair<Double, Double> {
        val a = j.optJSONArray(key) ?: return low to high
        return a.optDouble(0, low) to a.optDouble(1, high)
    }
}

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

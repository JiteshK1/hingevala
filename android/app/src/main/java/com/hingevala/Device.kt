package com.hingevala

import android.content.Context
import android.graphics.Rect
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** One view on screen, with the same attributes uiautomator2's layout dump has. */
data class Node(
    val text: String,
    val desc: String,
    val cls: String,
    val resId: String,
    val pkg: String,
    val clickable: Boolean,
    val bounds: Rect,
) {
    fun attr(name: String): String = when (name) {
        "content-desc" -> desc
        "text" -> text
        "class" -> cls
        "resource-id" -> resId
        "package" -> pkg
        else -> ""
    }
}

// uiautomator2 selector key -> (layout attribute, how to compare)
private val SELECTOR_KEYS = mapOf(
    "description" to ("content-desc" to "equals"),
    "descriptionContains" to ("content-desc" to "contains"),
    "descriptionMatches" to ("content-desc" to "matches"),
    "descriptionStartsWith" to ("content-desc" to "startswith"),
    "text" to ("text" to "equals"),
    "textContains" to ("text" to "contains"),
    "textMatches" to ("text" to "matches"),
    "textStartsWith" to ("text" to "startswith"),
    "className" to ("class" to "equals"),
    "classNameMatches" to ("class" to "matches"),
    "resourceId" to ("resource-id" to "equals"),
    "resourceIdMatches" to ("resource-id" to "matches"),
    "packageName" to ("package" to "equals"),
)
private val regexCache = ConcurrentHashMap<String, Regex>()

fun matches(node: Node, selector: Selector): Boolean {
    for ((key, wanted) in selector) {
        val (attr, how) = SELECTOR_KEYS[key]
            ?: throw IllegalArgumentException("Unsupported selector key in selectors.json: $key")
        val value = node.attr(attr)
        val ok = when (how) {
            "equals" -> value == wanted
            "contains" -> wanted in value
            "startswith" -> value.startsWith(wanted)
            // *Matches selectors are Java regexes that must match the whole string.
            else -> regexCache.getOrPut(wanted) { Regex(wanted) }.matches(value)
        }
        if (!ok) return false
    }
    return true
}

fun rand(low: Double, high: Double) = Random.nextDouble(low, high)

/**
 * The on-phone version of device.py: reads the screen through the uiautomator2 server (Hinge
 * blocks screenshots, so this is how we "see"), taps and swipes at slightly random points
 * like a person would, and runs adb shell commands for the rest.
 */
class Device(
    private val ctx: Context,
    private val adb: Adb,
    private val u2: U2Server,
    /** Where the floating STOP button is, so taps never land on it. */
    private val avoid: () -> Rect? = { null },
) {
    private var size: Pair<Int, Int>? = null

    // ---- reading the screen ------------------------------------------------------------

    /** Screen layout as XML, from every window on screen. */
    fun dump(): String = u2.rpc("dumpWindowHierarchy", false, 50) as? String ?: ""

    /** Every node on screen right now, leaving out this app's own floating STOP button. */
    fun snapshot(): List<Node> {
        val nodes = ArrayList<Node>()
        val parser = Xml.newPullParser().apply { setInput(StringReader(dump())) }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "node") continue
            fun attr(name: String) = parser.getAttributeValue(null, name) ?: ""
            val pkg = attr("package")
            if (pkg == ctx.packageName) continue
            val b = Regex("-?\\d+").findAll(attr("bounds")).map { it.value.toInt() }.toList()
            if (b.size != 4) continue
            nodes.add(
                Node(attr("text"), attr("content-desc"), attr("class"), attr("resource-id"), pkg,
                    attr("clickable") == "true", Rect(b[0], b[1], b[2], b[3])),
            )
        }
        return nodes
    }

    /** Nodes matching the first selector in the list that matches anything. */
    fun match(selectors: List<Selector>, nodes: List<Node>): List<Node> {
        for (sel in selectors) {
            val found = nodes.filter { matches(it, sel) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** The first node matching any selector, or null. With `nodes`, looks only in that
     * snapshot; otherwise keeps re-reading the screen for up to `timeout` seconds. */
    fun find(selectors: List<Selector>, timeout: Double = 0.0, nodes: List<Node>? = null): Node? {
        if (nodes != null) return match(selectors, nodes).firstOrNull()
        val deadline = System.currentTimeMillis() + (timeout * 1000).toLong()
        while (true) {
            match(selectors, snapshot()).firstOrNull()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(300)
        }
    }

    fun findAll(selectors: List<Selector>): List<Node> = match(selectors, snapshot())

    fun exists(selectors: List<Selector>, nodes: List<Node>? = null) = find(selectors, nodes = nodes) != null

    /** True if the floating STOP button covers part of the node. */
    fun covered(node: Node): Boolean = avoid()?.let { Rect.intersects(it, node.bounds) } ?: false

    /** Package of the app in front ("" if none). */
    fun foregroundPackage(): String {
        val out = adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        return Regex("""u0 ([\w.]+)/""").find(out)?.groupValues?.get(1) ?: ""
    }

    /** (description, bounds) for every node whose description matches a pattern. */
    fun describedNodes(patterns: List<String>, nodes: List<Node> = snapshot()): List<Pair<String, Rect>> {
        val compiled = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        return nodes.filter { n -> n.desc.isNotEmpty() && compiled.any { it.containsMatchIn(n.desc) } }
            .map { it.desc to it.bounds }
    }

    /** A readable list of what's on screen, like tools/map_screen.py writes. */
    fun describeScreen(nodes: List<Node> = snapshot()): String {
        val (w, h) = screenSize()
        val lines = mutableListOf("foreground: ${foregroundPackage()}", "window: ($w, $h)", "")
        for (n in nodes) {
            val rid = n.resId.substringAfterLast('/')
            if (n.text.isEmpty() && n.desc.isEmpty() && rid.isEmpty() && !n.clickable) continue
            val b = n.bounds
            lines.add(
                String.format(
                    "%-16s %s id=%-30s text=%-54s desc=%-54s [%d,%d][%d,%d]",
                    n.cls.substringAfterLast('.'), if (n.clickable) "CLICK" else "     ", rid,
                    "'" + n.text.take(50) + "'", "'" + n.desc.take(50) + "'", b.left, b.top, b.right, b.bottom,
                ),
            )
        }
        return lines.joinToString("\n") + "\n"
    }

    // ---- acting ------------------------------------------------------------------------

    /** Tap a random point near the middle of the node, not the exact centre, and never on
     * the floating STOP button (if that covers the whole middle, the tap goes ahead). */
    fun tap(node: Node) {
        val b = node.bounds
        val blocked = avoid()
        var x = 0.0
        var y = 0.0
        for (attempt in 0 until 20) {
            x = b.left + b.width() * rand(0.3, 0.7)
            y = b.top + b.height() * rand(0.3, 0.7)
            if (blocked == null || !blocked.contains(x.toInt(), y.toInt())) break
        }
        u2.rpc("click", x.toInt(), y.toInt())
    }

    fun swipeUp() = swipe(up = true)

    /** Scroll back up the page (the finger moves down). */
    fun swipeDown() = swipe(up = false)

    private fun swipe(up: Boolean) {
        val (w, h) = screenSize()
        val x = w * rand(0.4, 0.6)
        val low = (h * rand(0.70, 0.78)).toInt()
        val high = (h * rand(0.30, 0.38)).toInt()
        val x2 = (x + rand(-20.0, 20.0)).toInt()
        // uiautomator moves the finger in steps of about 5ms: 0.2-0.4s, as device.py does.
        val steps = (rand(0.2, 0.4) * 200).toInt()
        if (up) u2.rpc("swipe", x.toInt(), low, x2, high, steps) else u2.rpc("swipe", x.toInt(), high, x2, low, steps)
    }

    fun back() = u2.rpc("pressKey", "back")

    fun home() = u2.rpc("pressKey", "home")

    fun screenSize(): Pair<Int, Int> = size ?: run {
        val info = u2.rpc("deviceInfo") as JSONObject
        (info.getInt("displayWidth") to info.getInt("displayHeight")).also { size = it }
    }

    /** Turn the screen on if it's off. True if it was off. */
    fun wake(): Boolean {
        val info = u2.rpc("deviceInfo") as JSONObject
        if (info.optBoolean("screenOn", true)) return false
        u2.rpc("wakeUp")
        Thread.sleep(1500)
        return true
    }

    /** Open an app from its launcher icon; with `fresh`, force-stop it first (it keeps your
     * login, it only closes the app, like swiping it away in Recents). */
    fun launch(pkg: String, fresh: Boolean = false) {
        if (fresh) {
            adb.shell("am force-stop $pkg")
            Thread.sleep(2000)
        }
        val out = adb.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1")
        if ("No activities found" in out) throw RuntimeException("Hinge ($pkg) isn't installed")
    }

    // ---- typing ------------------------------------------------------------------------

    private var workingMethod: String? = null

    /**
     * Tap the box and type into whatever the keyboard is attached to. Hinge's comment box
     * isn't a real text field, so two ways are tried, each checked with `typed` (Hinge
     * renames the Send button once the box has text): pasting, as uiautomator2's
     * send_keys does, then typing with the app's own keyboard. The way that worked is tried
     * first next time. Returns its name, or null if neither got the text in.
     */
    fun typeInto(node: Node, text: String, typed: () -> Boolean): String? {
        tap(node)
        Thread.sleep(Random.nextLong(600, 1000))
        val methods = listOf("paste", "keyboard").sortedBy { if (it == workingMethod) 0 else 1 }
        for (method in methods) {
            val tried = try {
                if (method == "paste") paste(text) else typeWithKeyboard(text)
            } catch (e: Exception) {
                BotLog.log("Typing by $method failed: ${e.message}")
                false
            }
            Thread.sleep(600)
            if (tried && typed()) {
                if (workingMethod != method) BotLog.log("Typing comments by: $method.")
                workingMethod = method
                return method
            }
        }
        return null
    }

    private fun paste(text: String): Boolean {
        u2.rpc("setClipboard", null, text)
        u2.rpc("pasteClipboard")
        return true
    }

    private var previousKeyboard: String? = null

    /** Put your own keyboard back if a stop or crash left the app's one selected. */
    fun restoreKeyboard() {
        val previous = previousKeyboard ?: return
        if (adb.shell("settings get secure default_input_method") == TypingKeyboard.id(ctx)) adb.shell("ime set $previous")
    }

    /** Switch to the app's own keyboard, type with it, and switch back to yours. */
    private fun typeWithKeyboard(text: String): Boolean {
        val id = TypingKeyboard.id(ctx)
        val previous = adb.shell("settings get secure default_input_method")
        if (previous.isNotBlank() && previous != id && previous != "null") previousKeyboard = previous
        adb.shell("ime enable $id && ime set $id")
        var ok = false
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline && !ok) {
            Thread.sleep(300)
            ok = TypingKeyboard.instance?.commit(text) == true
        }
        Thread.sleep(400)
        if (previous.isNotBlank() && previous != id && previous != "null") adb.shell("ime set $previous")
        return ok
    }
}

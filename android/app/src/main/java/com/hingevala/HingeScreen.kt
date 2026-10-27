package com.hingevala

import kotlin.random.Random

/** Knows what Hinge's screens look like (from selectors.json) and how to act on them. Port of ui.py. */
class HingeScreen(private val dev: Device, private val sel: Selectors) {

    /** Returns (screen, detail). screen is one of:
     * discover, like_sheet, popup, stop, not_hinge, unknown.
     * Right after a like or skip the next profile can take several seconds to load, so
     * this keeps re-reading the screen for up to `wait` seconds before giving up. */
    fun detect(wait: Double = 8.0): Pair<String, String> {
        val current = dev.foregroundPackage()
        if (current != sel.pkg) return "not_hinge" to current
        val deadline = System.currentTimeMillis() + (wait * 1000).toLong()
        while (true) {
            val nodes = dev.snapshot()
            if (dev.exists(sel.commentField, nodes) && dev.exists(sel.sendButton, nodes)) return "like_sheet" to ""
            sel.popups.firstOrNull { dev.exists(it.marker, nodes) }?.let { return "popup" to it.name }
            sel.stopScreens.firstOrNull { dev.exists(it.marker, nodes) }?.let { return "stop" to it.name }
            if (dev.exists(sel.skipButton, nodes)) return "discover" to ""
            if (System.currentTimeMillis() >= deadline) return "unknown" to ""
            Thread.sleep(700)
        }
    }

    /** How far down the current profile the bot has scrolled. */
    private var scrolled = 0

    private fun scrollDown() {
        dev.swipeUp()
        scrolled++
    }

    /** Back to the top of the profile. A dry run never likes or skips, so Hinge keeps
     * showing the same profile; without this each round would start further down it
     * until it got stuck at the end. */
    fun backToTop() {
        repeat(scrolled + 1) {
            dev.swipeDown()
            Thread.sleep(Random.nextLong(250, 450))
        }
        scrolled = 0
        Thread.sleep(500)
    }

    /** A new profile is on screen (after a like or skip), starting at its top. */
    fun newProfile() {
        scrolled = 0
    }

    fun goToDiscover(): Boolean {
        val tab = dev.find(sel.discoverTab) ?: return false
        dev.tap(tab)
        Thread.sleep(2000)
        return true
    }

    fun dismissPopup(name: String) {
        val popup = sel.popups.first { it.name == name }
        val button = dev.find(popup.dismiss)
        if (button != null) dev.tap(button) else dev.back()
    }

    /** Tap "Close app" on an "<app> isn't responding" dialog. False if there isn't one. */
    fun closeAnrDialog(): Boolean {
        val button = dev.snapshot().firstOrNull {
            it.pkg == "android" && it.text.matches(Regex("(?i)^(close app|ok)$"))
        } ?: return false
        dev.tap(button)
        Thread.sleep(2000)
        return true
    }

    /** Scroll a random amount down the profile and pick a random visible like button. */
    fun pickLikeTarget(maxScrolls: Int): Node? {
        repeat(Random.nextInt(0, maxScrolls + 1)) {
            scrollDown()
            Thread.sleep(Random.nextLong(800, 1800))
        }
        var buttons = usableLikeButtons()
        if (buttons.isEmpty()) {
            scrollDown()
            Thread.sleep(1200)
            buttons = usableLikeButtons()
        }
        return buttons.randomOrNull()
    }

    /** Like buttons fully on screen and above the bottom tab bar, read once the scroll has
     * settled. A heart half hidden behind the tab bar would make the tap land on a tab
     * instead, and one still sliding would make it land beside the heart. Hearts under the
     * floating Stop button are left out too. */
    private fun usableLikeButtons(): List<Node> {
        var previous: List<android.graphics.Rect>? = null
        var nodes = dev.snapshot()
        var buttons = emptyList<Node>()
        for (i in 0 until 4) {
            if (i > 0) nodes = dev.snapshot()
            buttons = dev.match(sel.likeButtons, nodes)
            val positions = buttons.map { it.bounds }
            if (positions == previous) break
            previous = positions
            Thread.sleep(400)
        }
        val tab = dev.find(sel.discoverTab, nodes = nodes)
        val contentBottom = tab?.bounds?.top ?: (dev.screenSize().second * 0.9).toInt()
        return buttons.filter { b ->
            val r = b.bounds
            val fullyVisible = r.height() >= 0.8 * r.width()
            fullyVisible && r.bottom <= contentBottom && !dev.covered(b)
        }
    }

    /** Every piece of profile text (name, pronouns, prompts, details) on screen, scrolling
     * up to `maxScrolls` times. Hinge's own buttons, filter chips and tab bar are left out,
     * as is anything outside Hinge. */
    fun readProfile(maxScrolls: Int): List<String> {
        val chrome = sel.chromeDescriptions.map { Regex(it) }
        val seen = mutableListOf<String>()
        var scrolls = 0
        while (true) {
            var new = 0
            for (node in dev.snapshot()) {
                if (node.pkg != sel.pkg || node.clickable) continue
                for (raw in listOf(node.text, node.desc)) {
                    val value = raw.trim()
                    if (value.isNotEmpty() && value !in seen && chrome.none { it.matches(value) }) {
                        seen.add(value)
                        new++
                    }
                }
            }
            // Nothing new after a scroll means the end of the profile.
            if (scrolls >= maxScrolls || (scrolls > 0 && new == 0)) break
            scrollDown()
            scrolls++
            Thread.sleep(Random.nextLong(500, 900))
        }
        return seen
    }

    /** What a like button is attached to: the smallest photo/prompt area containing it,
     * preferring the kind the button names ('Like photo' -> a photo). */
    fun describe(button: Node): String {
        val b = button.bounds
        val x = b.centerX()
        val y = b.centerY()
        val buttonDesc = button.desc.ifEmpty { "like button" }
        val kind = buttonDesc.trim().split(Regex("\\s+")).last().lowercase() // 'photo', 'prompt', 'video', ...
        val items = dev.describedNodes(sel.profileItemPatterns)
        val containing = items.filter { (_, r) -> r.left <= x && x <= r.right && r.top <= y && y <= r.bottom }
            .map { (desc, r) -> r.width().toLong() * r.height() to desc }
        val sameKind = containing.filter { kind in it.second.lowercase() }
        val best = sameKind.ifEmpty { containing }
        if (best.isNotEmpty()) return best.minBy { it.first }.second
        // Some hearts sit just outside their prompt's area: take the nearest of the same kind.
        val nearby = items.filter { (desc, _) -> kind in desc.lowercase() }
            .map { (desc, r) -> minOf(Math.abs(y - r.top), Math.abs(y - r.bottom)) to desc }
        return nearby.minByOrNull { it.first }?.second ?: buttonDesc
    }

    fun openLike(button: Node): Boolean {
        dev.tap(button)
        return dev.find(sel.commentField, timeout = 5.0) != null
    }

    fun writeComment(text: String) {
        val field = dev.find(sel.commentField, timeout = 3.0)
            ?: throw RuntimeException("Comment box not found on the like sheet")
        // Never send a like whose comment didn't make it into the box. The box's text isn't
        // exposed, but Hinge renames the button to "...with message" once it has text.
        dev.typeInto(field, text) { dev.find(sel.commentTypedMarker, timeout = 3.0) != null }
            ?: throw RuntimeException("Typed the comment but the box still looks empty; not sending")
    }

    /** Tap Send like; true once the sheet has closed. If the first tap doesn't take (the
     * sheet is often still sliding as the keyboard settles), re-read where the button is
     * and tap again. Only re-taps while the sheet is still open, so a like can't be sent twice. */
    fun send(): Boolean {
        repeat(3) {
            val button = dev.find(sel.sendButton, timeout = 3.0) ?: return true // sheet already closed
            dev.tap(button)
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                if (!dev.exists(sel.sendButton)) return true
            }
        }
        return false
    }

    fun skip() {
        val button = dev.find(sel.skipButton, timeout = 5.0) ?: throw RuntimeException("Skip button not found")
        dev.tap(button)
    }

    /** Bring Hinge to the front where it was left. */
    fun openApp() = dev.launch(sel.pkg)

    /** Remove Hinge from Recents (force-stop) and open it fresh.
     * Only closes the app; your login and data are untouched. */
    fun restartApp() {
        dev.launch(sel.pkg, fresh = true)
        scrolled = 0
        dev.find(sel.skipButton + sel.discoverTab, timeout = 25.0) // wait for the feed or the tab bar
    }
}

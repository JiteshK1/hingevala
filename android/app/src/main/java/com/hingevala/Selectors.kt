package com.hingevala

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One uiautomator2-style selector from selectors.json, e.g. {"description": "Discover"}. */
typealias Selector = Map<String, String>

class NamedScreen(val name: String, val marker: List<Selector>, val dismiss: List<Selector>)

/**
 * selectors.json, the same file the Python bot uses. The Hinge version only changes the
 * order the Send button labels are tried in: HingeX says "Send priority like", Hinge+
 * says "Send like". Both stay in the list, so picking the wrong version still works.
 */
class Selectors(json: JSONObject, variant: String) {
    val pkg: String = json.getString("package")

    private val discover = json.getJSONObject("discover")
    val likeButtons = discover.getJSONArray("like_buttons").selectors()
    val skipButton = discover.getJSONArray("skip_button").selectors()
    val profileItemPatterns = discover.getJSONArray("profile_item_patterns").strings()
    val chromeDescriptions = discover.getJSONArray("chrome_descriptions").strings()

    val discoverTab = json.getJSONObject("nav").getJSONArray("discover_tab").selectors()

    private val sheet = json.getJSONObject("like_sheet")
    val commentField = sheet.getJSONArray("comment_field").selectors()
    val sendButton = preferVariant(sheet.getJSONArray("send_button").selectors(), variant)
    val commentTypedMarker = preferVariant(sheet.getJSONArray("comment_typed_marker").selectors(), variant)

    val popups = json.getJSONArray("popups_to_dismiss").screens()
    val stopScreens = json.getJSONArray("stop_screens").screens()

    companion object {
        const val HINGEX = "hingex"
        const val PLUS = "plus"

        fun load(ctx: Context, variant: String) = Selectors(
            JSONObject(ctx.assets.open("selectors.json").bufferedReader().use { it.readText() }), variant,
        )

        /** The labels of the chosen version first, keeping the file's order otherwise. */
        private fun preferVariant(list: List<Selector>, variant: String): List<Selector> =
            list.sortedBy { sel ->
                val priority = sel.values.any { it.contains("priority", ignoreCase = true) }
                if (variant == PLUS) priority else !priority
            }
    }
}

private fun JSONArray.selectors(): List<Selector> = (0 until length()).map { i ->
    val obj = getJSONObject(i)
    obj.keys().asSequence().associateWith { obj.getString(it) }
}

private fun JSONArray.screens(): List<NamedScreen> = (0 until length()).map { i ->
    val obj = getJSONObject(i)
    NamedScreen(
        obj.getString("name"),
        obj.getJSONArray("marker").selectors(),
        obj.optJSONArray("dismiss")?.selectors() ?: emptyList(),
    )
}

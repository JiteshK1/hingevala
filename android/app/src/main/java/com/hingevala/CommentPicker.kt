package com.hingevala

/** Picks a random comment from comments.txt without repeating recent ones. */
class CommentPicker(text: String, noRepeat: Int) {
    private val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    private val recent = ArrayDeque<String>()
    private val keep: Int

    init {
        require(lines.isNotEmpty()) { "comments has no comments in it" }
        keep = noRepeat.coerceAtMost(lines.size - 1).coerceAtLeast(0)
    }

    fun pick(): String {
        val choices = lines.filter { it !in recent }.ifEmpty { lines }
        val comment = choices.random()
        if (keep > 0) {
            recent.addLast(comment)
            while (recent.size > keep) recent.removeFirst()
        }
        return comment
    }
}

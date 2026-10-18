package com.pocketlm.app.data

/** One element of what is on screen, as the assistant API describes it: its text, its description and what is inside it. */
data class ScreenNode(
    val text: String? = null,
    val description: String? = null,
    val visible: Boolean = true,
    /** True for something that must never be read out, such as a password field. */
    val sensitive: Boolean = false,
    val children: List<ScreenNode> = emptyList(),
)

/** Turns the structure of another app's screen into plain text for a chat. */
object ScreenText {
    const val MAX_CHARS = 60_000
    private const val MIN_DESCRIPTION = 3

    fun extract(roots: List<ScreenNode>, maxChars: Int = MAX_CHARS): String {
        val lines = ArrayList<String>()
        fun add(raw: String?) {
            val line = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (line.isNotEmpty() && lines.lastOrNull() != line) lines += line
        }
        fun walk(node: ScreenNode) {
            if (!node.visible || node.sensitive) return
            if (!node.text.isNullOrBlank()) {
                add(node.text)
            } else if (node.children.isEmpty() && (node.description?.trim()?.length ?: 0) >= MIN_DESCRIPTION) {
                // A picture or button with no words of its own: its label can still say what it is.
                add(node.description)
            }
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)

        val out = StringBuilder()
        for (line in lines) {
            if (out.length + line.length + 1 > maxChars) {
                out.append("…")
                break
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }
}

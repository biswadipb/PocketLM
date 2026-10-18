package com.pocketlm.app.data

/**
 * What to ask about text selected in another app. Each action is its own entry in the text selection menu (Android
 * shows one per component), and which one was chosen is told by the component the selection arrived through.
 */
enum class TextAction(val aliasName: String, val instruction: String) {
    Explain("ExplainAlias", "Explain this in simple terms:"),
    Translate("TranslateAlias", "Translate this into English, and say which language it was in:"),
    Summarise("SummariseAlias", "Summarise this in a few sentences:"),
    Improve("ImproveAlias", "Rewrite this to be clearer and fix any mistakes, keeping the meaning:");

    companion object {
        /** The action for the component a selection arrived through (a class name ending in the alias name), or null. */
        fun fromComponent(className: String?): TextAction? =
            className?.substringAfterLast('.')?.let { name -> entries.firstOrNull { it.aliasName == name } }
    }

    /** The message to put in the box: the instruction, then the selected text. */
    fun promptFor(selected: String): String = "$instruction\n\n${selected.trim()}"
}

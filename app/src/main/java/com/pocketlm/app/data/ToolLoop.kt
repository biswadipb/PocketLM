package com.pocketlm.app.data

/** What a model wrote after the tools it asked for had been run. */
data class ToolLoopResult(
    /** The model's last words, which are its answer. */
    val text: String,
    /** The tools it used, in order. */
    val used: List<Pair<ToolCall, ToolOutcome>>,
)

/**
 * The back-and-forth between a model and its tools when nobody is watching a chat: the model writes, asks for a tool,
 * the tool is run, the result goes back, and this repeats until the model answers. The chat screen does the same
 * on screen; this version is for prompts that run by themselves. Pure apart from the two functions it is given.
 */
object ToolLoop {

    /** The tools a prompt that runs by itself may use: the ones switched on, minus those that need the person to answer. */
    fun allowed(enabled: Set<ToolId>): Set<ToolId> = enabled - setOf(ToolId.SaveNote, ToolId.Reminder)

    /**
     * [ask] sends one message to the model and returns what it wrote (up to a closing tool tag, if it wrote one).
     * [runTool] runs a tool request. With no [enabled] tools the model is asked once.
     */
    suspend fun run(
        prompt: String,
        enabled: Set<ToolId>,
        ask: suspend (String) -> String,
        runTool: suspend (ToolCall) -> ToolOutcome,
    ): ToolLoopResult {
        val used = ArrayList<Pair<ToolCall, ToolOutcome>>()
        var message = prompt
        var rounds = 0
        while (true) {
            val written = ask(message)
            val call = if (enabled.isEmpty()) null else Tools.parseCall(written)
            if (call == null) return ToolLoopResult(written.trim(), used)
            // Out of rounds: whatever it said before asking again is all there will be.
            if (rounds >= Tools.MAX_ROUNDS) return ToolLoopResult(written.substringBefore(Tools.OPEN_TAG).trim(), used)
            val outcome = runTool(call)
            used += call to outcome
            message = Tools.followUp(call, outcome)
            rounds++
        }
    }
}

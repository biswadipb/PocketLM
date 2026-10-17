package com.pocketlm.app.data

/** A ready-made scheduled task to start from. The person can change anything before it is saved. */
data class TaskTemplate(
    val id: String,
    val title: String,
    /** One line shown under the title. */
    val blurb: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    val days: Int,
    /** The tools it cannot do without. They must be switched on in Settings for the task to work as meant. */
    val needs: Set<ToolId> = emptySet(),
    val notes: NotesUse = NotesUse.None,
)

object TaskTemplates {
    private val monday = Schedules.dayBit(java.time.DayOfWeek.MONDAY)
    private val sunday = Schedules.dayBit(java.time.DayOfWeek.SUNDAY)

    val all: List<TaskTemplate> = listOf(
        TaskTemplate(
            "news-digest", "Create a news digest", "Get a daily deep dive on a topic you are curious about.",
            "Search the web for today's most important news about technology. Then write a digest of the five biggest stories: " +
                "a headline, then one sentence on what happened and one on why it matters. Change the topic to whatever you follow.",
            8, 0, Schedules.ALL_DAYS, needs = setOf(ToolId.WebSearch),
        ),
        TaskTemplate(
            "morning-briefing", "Morning briefing", "Today's date and the weather for your city, with what to wear.",
            "Use the date tool to tell me today's date. Then use the weather tool for my city (the one in the notes about me; " +
                "if you do not know it, say so instead of guessing) and tell me what the day will be like and what to wear.",
            7, 30, Schedules.ALL_DAYS, needs = setOf(ToolId.Weather, ToolId.DateTime),
        ),
        TaskTemplate(
            "study-quiz", "Build a study quiz", "Test your knowledge daily with questions on what you are learning.",
            "Write a short quiz of five questions, easy first and hard last, on the subjects I am learning " +
                "(see the notes about me; if there are none, pick a general topic). Put the answers after a line of dashes.",
            18, 0, Schedules.ALL_DAYS,
        ),
        TaskTemplate(
            "market-trends", "Track financial trends", "Summarise the week's market news in the sectors you follow.",
            "Search the web for this week's market news in the technology and energy sectors. Summarise the main trends in " +
                "five short bullet points. Do not give investment advice. Change the sectors to the ones you follow.",
            9, 0, monday, needs = setOf(ToolId.WebSearch),
        ),
        TaskTemplate(
            "meal-plan", "Create a meal plan", "Set up a weekly meal plan and grocery list for your household.",
            "Make a meal plan for the coming week: one dinner a day with a one-line description, then a grocery list grouped " +
                "by shop section. Keep it simple and cheap, and follow what the notes about me say about food and household.",
            10, 0, sunday,
        ),
        TaskTemplate(
            "industry-digest", "Build an industry digest", "A weekly roundup of news and shifts in your field.",
            "Search the web for this week's news about open source language models and on-device AI. Write a digest of the " +
                "most important developments, with one line each on why it matters. Change the field to yours.",
            8, 30, monday, needs = setOf(ToolId.WebSearch),
        ),
        TaskTemplate(
            "learn-something", "Learn something new", "One interesting fact a day, from Wikipedia.",
            "Pick one interesting topic from science, history or nature that you have not told me about recently. Use the " +
                "Wikipedia tool to read about it, then teach it to me in a short paragraph.",
            12, 0, Schedules.ALL_DAYS, needs = setOf(ToolId.Wikipedia),
        ),
        TaskTemplate(
            "notes-recap", "Recap my notes", "What changed in your notes folder today, and what is still open.",
            "Summarise the notes that changed and list any to-dos or open questions in them.",
            21, 0, Schedules.ALL_DAYS, notes = NotesUse.Recent,
        ),
        TaskTemplate(
            "journal-prompt", "Journal prompt", "A question to think about before bed.",
            "Give me one thoughtful journaling question for tonight, and a one-line suggestion for how to start answering it.",
            21, 30, Schedules.ALL_DAYS,
        ),
    )

    /** The tools of [template] that are not switched on in [enabled]. */
    fun missing(template: TaskTemplate, enabled: Set<ToolId>): Set<ToolId> = template.needs - enabled
}

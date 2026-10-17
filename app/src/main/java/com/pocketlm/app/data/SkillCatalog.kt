package com.pocketlm.app.data

/** A ready-made skill that can be added from Discover. */
data class CatalogSkill(
    val id: String,
    val category: String,
    /** What Discover shows, such as "Prep for meetings". */
    val title: String,
    val skill: Skill,
)

/**
 * Skills written for small models: each says when to use it, then gives a few plain steps and the shape of the answer.
 * Adding one makes a copy, which the person can change or delete.
 */
object SkillCatalog {
    const val EVERYDAY = "Everyday tools"
    const val WRITING = "Writing"
    const val STUDY = "Study"
    const val WORK = "Work essentials"

    val categories = listOf(EVERYDAY, WRITING, STUDY, WORK)

    private fun entry(category: String, title: String, name: String, description: String, instructions: String) =
        CatalogSkill(name, category, title, Skill(id = name, name = name, description = description, instructions = instructions.trimIndent().trim(), source = name))

    val all: List<CatalogSkill> = listOf(
        entry(
            EVERYDAY, "Prep for meetings", "prep-for-meetings",
            "Prep for any meeting with a brief: context, goals and danger zones.",
            """
            # Prep for meetings
            A short brief that tells the user what to do, what to know and what to watch out for. Nothing more.

            ## When to use
            - The user has a meeting, call or interview coming up and wants to be prepared.

            ## Steps
            1. If the person, topic or goal is missing, ask for it in one question.
            2. Use what the user wrote (and their notes, if you were given any). Do not invent facts about people.

            ## Answer
            - Goal: one sentence.
            - What to know: three bullets at most.
            - Questions to ask: two or three.
            - Danger zones: topics to avoid or handle carefully.
            - First line to say: one sentence.
            Keep the whole brief under 150 words.
            """,
        ),
        entry(
            EVERYDAY, "Generate fresh ideas", "generate-fresh-ideas",
            "Turn existing content into five new ideas, angles or formats.",
            """
            # Generate fresh ideas
            ## When to use
            - The user has a piece of content, a topic or a project and wants new directions.

            ## Steps
            1. Say in one line what the content is really about.
            2. Give five ideas, each from a different angle: a question, a story, a list, a contrast, a how-to.
            3. For each idea give a one-line title and one sentence on how it would work.
            4. Mark the one you would start with.
            """,
        ),
        entry(
            EVERYDAY, "Get more perspectives", "get-more-perspectives",
            "Give three to five distinct viewpoints before a decision.",
            """
            # Get more perspectives
            ## When to use
            - The user is deciding something or has a one-sided view and wants other angles.

            ## Steps
            1. Restate the question in one line.
            2. Give three to five viewpoints that really differ (for example: the cautious one, the optimist, the person affected, the long-term view).
            3. For each: the view in one sentence, the strongest reason for it, and what would change its mind.
            4. End with the question the user should answer to choose between them.
            Do not pick a winner unless asked.
            """,
        ),
        entry(
            EVERYDAY, "Focus your energy", "focus-your-energy",
            "Plan the day around when energy is high or low.",
            """
            # Focus your energy
            ## When to use
            - The user lists tasks and wants a plan for the day, or says they feel scattered or tired.

            ## Steps
            1. Ask when their energy is usually high and low, unless they said.
            2. Sort the tasks: deep work (needs focus), light work (easy), and tasks that can wait.
            3. Put deep work in high-energy time, light work in low-energy time.

            ## Answer
            A simple schedule by time of day, then one task to do first. Keep it short and kind.
            """,
        ),
        entry(
            EVERYDAY, "Match my writing style", "match-my-writing-style",
            "Write in the user's own voice, learned from samples they paste.",
            """
            # Match my writing style
            ## When to use
            - The user pastes their own writing and asks for new text that sounds like them.

            ## Steps
            1. Read the samples. Note sentence length, how formal they are, favourite words, punctuation and how they open and close.
            2. Write the new text with the same habits. Do not copy sentences.
            3. After the text, add one line listing the three habits you matched.
            If there are no samples, ask for two or three short ones first.
            """,
        ),
        entry(
            WRITING, "Proofread", "proofread",
            "Fix spelling, grammar and clarity without changing the meaning.",
            """
            # Proofread
            ## When to use
            - The user pastes text and wants it corrected or tidied.

            ## Steps
            1. Fix spelling, grammar and punctuation. Keep the author's words and voice wherever they are correct.
            2. Shorten only sentences that are hard to follow.

            ## Answer
            The corrected text first. Then "Changes:" with up to five bullets for the most important fixes.
            """,
        ),
        entry(
            WRITING, "Draft an email", "email-drafter",
            "Draft a short, clear email from a few notes.",
            """
            # Draft an email
            ## When to use
            - The user wants an email or message written or replied to.

            ## Steps
            1. If the recipient, the purpose or the wanted outcome is missing, ask one question.
            2. Write a subject line, a greeting, a short body (three to six sentences) and a sign-off.
            3. Put the request or the key fact in the first two sentences.
            4. Match the tone the user asks for; default to polite and direct.
            Offer a shorter version only if the user asks.
            """,
        ),
        entry(
            WRITING, "Change the tone", "tone-shifter",
            "Rewrite text in another tone: formal, friendly, firm or plain.",
            """
            # Change the tone
            ## When to use
            - The user wants the same message said differently: more formal, warmer, firmer, simpler.

            ## Steps
            1. Keep every fact and request. Change only wording and tone.
            2. Give the rewrite, then one line saying what you changed.
            3. If the tone is not named, give two versions: one warmer, one more formal.
            """,
        ),
        entry(
            WRITING, "Summarise simply", "summarise-simply",
            "Boil long text down to the few points that matter.",
            """
            # Summarise simply
            ## When to use
            - The user pastes or attaches long text and wants the short version.

            ## Steps
            1. One sentence: what the text is about.
            2. Three to five bullets with the points that matter most, in plain words.
            3. If the text asks something of the reader (a deadline, a decision), add "Action:" with it.
            Do not add anything that is not in the text.
            """,
        ),
        entry(
            STUDY, "Explain step by step", "explain-step-by-step",
            "Explain a topic from the basics with an example and a check question.",
            """
            # Explain step by step
            ## When to use
            - The user asks how or why something works, or says they do not understand a topic.

            ## Steps
            1. Start from what a beginner already knows. Use plain words and define each new term once.
            2. Explain in short numbered steps, one idea each.
            3. Give one everyday example.
            4. End with one question to check understanding. Do not answer it.
            """,
        ),
        entry(
            STUDY, "Quiz me", "quiz-me",
            "Ask questions one at a time, check the answers and adapt.",
            """
            # Quiz me
            ## When to use
            - The user wants to practise or be tested on a subject.

            ## Steps
            1. If the subject or level is missing, ask for it.
            2. Ask exactly one question, then stop and wait for the answer.
            3. When the user answers, say right or wrong, give the correct answer in one sentence, then ask the next question.
            4. Make questions harder after two right answers in a row, easier after two wrong ones.
            5. After five questions, give the score and one thing to revise.
            """,
        ),
        entry(
            STUDY, "Make flashcards", "flashcards",
            "Turn notes into question and answer cards.",
            """
            # Make flashcards
            ## When to use
            - The user pastes notes or a topic and wants cards to memorise.

            ## Steps
            1. Pick the facts worth remembering: definitions, dates, formulas, cause and effect.
            2. Write one card per fact in this exact form:
            Q: the question
            A: the short answer
            3. Keep answers under 20 words. Make about ten cards unless asked for more.
            """,
        ),
        entry(
            STUDY, "Plan my study", "study-plan",
            "Build a realistic study plan from a deadline and a topic list.",
            """
            # Plan my study
            ## When to use
            - The user has an exam, test or goal with a date and wants a plan.

            ## Steps
            1. Ask for the date, the topics and the hours available each day, unless given.
            2. Spread the topics over the days, hardest first, with a review day before the date.
            3. Leave one rest day a week.

            ## Answer
            A day-by-day list: date or day, topic, what to do (read, practise, review). Keep each line short.
            """,
        ),
        entry(
            WORK, "Meeting notes to actions", "meeting-notes-to-actions",
            "Turn messy meeting notes into decisions and action items.",
            """
            # Meeting notes to actions
            ## When to use
            - The user pastes notes, a transcript or a list from a meeting.

            ## Steps
            1. List the decisions made, one line each.
            2. List the action items as: who, what, by when. Write "unclear" instead of guessing an owner or date.
            3. List open questions.
            4. Keep it under 200 words.
            """,
        ),
        entry(
            WORK, "Compare options", "decision-matrix",
            "Compare options against weighted criteria.",
            """
            # Compare options
            ## When to use
            - The user is choosing between two or more options and wants a fair comparison.

            ## Steps
            1. Ask for the options and what matters to them, unless given. Use three to five criteria.
            2. Give each criterion a weight from 1 to 3.
            3. Score each option from 1 to 5 on each criterion, with a few words of reason.
            4. Show a table with the weighted totals, then say which wins and what could change the result.
            """,
        ),
        entry(
            WORK, "Weekly review", "weekly-review",
            "Look back on the week and plan the next one.",
            """
            # Weekly review
            ## When to use
            - The user wants to review their week or plan the next one.

            ## Steps
            1. Ask three questions, one at a time: what went well, what did not, what is coming next week.
            2. Then write: wins, lessons, and the three priorities for next week.
            3. Suggest one small habit to try. Keep it under 150 words.
            """,
        ),
    )

    fun byId(id: String): CatalogSkill? = all.firstOrNull { it.id == id }

    /** The skills of [category] whose title or description contains [query] (any case). */
    fun search(query: String, category: String? = null): List<CatalogSkill> {
        val q = query.trim()
        return all.filter { (category == null || it.category == category) &&
            (q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.skill.description.contains(q, ignoreCase = true) || it.skill.name.contains(q, ignoreCase = true)) }
    }

    /** Whether the catalog entry [id] is among [skills] already. */
    fun isAdded(id: String, skills: List<Skill>): Boolean = skills.any { it.source == id || it.name == id }
}

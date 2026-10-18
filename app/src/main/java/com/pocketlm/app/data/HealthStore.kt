package com.pocketlm.app.data

import android.content.Context
import java.io.File

/**
 * Where the health section keeps what the person wrote: in the app's private folder, in small files. It is left out of
 * backups and sync on purpose; "Delete everything" removes it.
 */
class HealthStore(context: Context) {
    private val dir = File(context.filesDir, "health")

    private fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    private fun write(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) {
            File(dir, name).writeText(text)
            tmp.delete()
        }
    }

    @Synchronized fun profile(): HealthProfile? = HealthProfiles.fromJson(read("profile.json"))

    @Synchronized fun saveProfile(profile: HealthProfile) = write("profile.json", HealthProfiles.toJson(profile))

    @Synchronized fun log(): HealthLog = HealthLogs.fromJson(read("log.json"))

    @Synchronized fun saveLog(log: HealthLog) = write("log.json", HealthLogs.toJson(log))

    @Synchronized fun progress(): Map<String, Leitner.Progress> = Leitner.fromJson(read("cards-progress.json"))

    @Synchronized fun saveProgress(progress: Map<String, Leitner.Progress>) = write("cards-progress.json", Leitner.toJson(progress))

    @Synchronized fun madeCards(): List<Flashcard> = FlashcardDecks.madeFromJson(read("cards-made.json"))

    @Synchronized fun saveMadeCards(cards: List<Flashcard>) = write("cards-made.json", FlashcardDecks.madeToJson(cards.takeLast(MAX_MADE_CARDS)))

    /** Deletes the profile, the log and the card progress. */
    @Synchronized fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        const val MAX_MADE_CARDS = 300
    }
}

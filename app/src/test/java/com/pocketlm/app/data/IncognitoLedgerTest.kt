package com.pocketlm.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IncognitoLedgerTest {

    private lateinit var root: File
    private lateinit var docs: File
    private lateinit var images: File
    private lateinit var ledger: IncognitoLedger

    @Before
    fun setUp() {
        root = Files.createTempDirectory("incognito").toFile()
        docs = File(root, "docs").also { it.mkdirs() }
        images = File(root, "images").also { it.mkdirs() }
        ledger = IncognitoLedger(File(root, "incognito.txt"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun everythingAnIncognitoChatMadeIsDeletedAndForgotten() {
        File(docs, "d-1.txt").writeText("private text")
        File(docs, "d-1.emb").writeBytes(byteArrayOf(1))
        File(images, "p.jpg").writeBytes(byteArrayOf(2))
        ledger.add(IncognitoLedger.doc("d-1"))
        ledger.add(IncognitoLedger.image("/data/user/0/app/files/images/p.jpg"))

        assertEquals(3, ledger.purge(docs, images))
        assertFalse(File(docs, "d-1.txt").exists())
        assertFalse(File(docs, "d-1.emb").exists())
        assertFalse(File(images, "p.jpg").exists())
        assertTrue(ledger.entries().isEmpty())
    }

    @Test
    fun filesOfOrdinaryChatsAreNeverTouched() {
        File(docs, "keep-me.txt").writeText("saved chat's document")
        File(images, "keep.jpg").writeBytes(byteArrayOf(1))
        File(docs, "gone.txt").writeText("x")
        ledger.add(IncognitoLedger.doc("gone"))
        ledger.purge(docs, images)
        assertTrue(File(docs, "keep-me.txt").exists())
        assertTrue(File(images, "keep.jpg").exists())
        assertFalse(File(docs, "gone.txt").exists())
    }

    @Test
    fun theListSurvivesTheAppBeingKilled() {
        File(docs, "d-2.txt").writeText("x")
        ledger.add(IncognitoLedger.doc("d-2"))
        // A new object over the same file stands for the next start of the app.
        val next = IncognitoLedger(File(root, "incognito.txt"))
        assertEquals(listOf("doc:d-2"), next.entries())
        assertEquals(1, next.purge(docs, images))
        assertFalse(File(docs, "d-2.txt").exists())
    }

    @Test
    fun entriesWithUnsafeNamesAreIgnoredSoNothingOutsideTheFoldersCanGo() {
        val outside = File(root, "secret.txt").also { it.writeText("do not delete") }
        ledger.add("doc:../secret")
        ledger.add("img:../secret.txt")
        ledger.add("img:evil.sh")
        ledger.add("other:thing")
        ledger.add("doc:")
        assertTrue(ledger.entries().isEmpty())
        // Even a hand-edited file cannot point outside.
        File(root, "incognito.txt").writeText("doc:../secret\nimg:../secret.txt\n")
        assertEquals(0, ledger.purge(docs, images))
        assertTrue(outside.exists())
    }

    @Test
    fun purgingAnEmptyOrMissingListIsHarmless() {
        assertEquals(0, ledger.purge(docs, images))
        ledger.clear()
        assertEquals(0, ledger.purge(docs, images))
    }
}

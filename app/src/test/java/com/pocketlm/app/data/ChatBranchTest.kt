package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBranchTest {

    private fun user(text: String, vararg photos: String) =
        StoredMessage(text, true, imagePath = photos.firstOrNull(), moreImages = photos.drop(1))

    private fun bot(text: String, starred: Boolean = false) = StoredMessage(text, false, starred = starred)

    private val chat = listOf(user("q1"), bot("a1", starred = true), user("q2"), bot("a2"), user("q3"), bot("a3"))

    @Test
    fun branchingFromAReplyKeepsEverythingUpToAndIncludingIt() {
        val plan = ChatBranch.plan(chat, 3)!!
        assertEquals(listOf("q1", "a1", "q2", "a2"), plan.messages.map { it.text })
        assertNull(plan.draft)
    }

    @Test
    fun branchingFromYourOwnMessageKeepsWhatCameBeforeAndOffersTheMessageAgain() {
        val plan = ChatBranch.plan(chat, 2)!!
        assertEquals(listOf("q1", "a1"), plan.messages.map { it.text })
        assertEquals("q2", plan.draft)
    }

    @Test
    fun thereIsNothingToBranchFromAtTheVeryFirstMessageOrOutsideTheChat() {
        assertNull(ChatBranch.plan(chat, 0))
        assertNull(ChatBranch.plan(chat, -1))
        assertNull(ChatBranch.plan(chat, 6))
        assertFalse(ChatBranch.canBranch(chat, 0))
        assertTrue(ChatBranch.canBranch(chat, 1))
    }

    @Test
    fun starsStayWithTheOriginal() {
        assertFalse(ChatBranch.plan(chat, 3)!!.messages[1].starred)
        assertTrue(chat[1].starred)
    }

    @Test
    fun theEarlierVersionsOfTheLastReplyComeAlong() {
        val withVersions = chat.dropLast(1) + bot("a3").copy(others = listOf(Reply("a3 old")), position = 1)
        val plan = ChatBranch.plan(withVersions, 5)!!
        assertEquals(1, plan.messages.last().others.size)
        assertEquals(1, plan.messages.last().position)
    }

    @Test
    fun photosAreCopiedAndOnesThatCannotBeAreLeftOut() {
        val messages = listOf(user("look", "/a.jpg", "/b.jpg", "/c.jpg"), bot("nice"))
        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), ChatBranch.photoPaths(messages))
        val copied = ChatBranch.withPhotos(messages) { if (it == "/b.jpg") null else "/new$it" }
        assertEquals(listOf("/new/a.jpg", "/new/c.jpg"), copied[0].images)
        assertEquals("/new/a.jpg", copied[0].imagePath)
        assertEquals(copied[1], messages[1])
    }

    @Test
    fun theNewChatIsTitledAfterTheOriginalOnce() {
        assertEquals("Branch: Trip planning", ChatBranch.title("Trip planning"))
        assertEquals("Branch: Trip planning", ChatBranch.title("Branch: Trip planning"))
        assertEquals("Branch: Chat", ChatBranch.title("  "))
        assertEquals(80, ChatBranch.title("x".repeat(200)).length)
    }
}

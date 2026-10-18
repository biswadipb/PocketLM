package com.pocketlm.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageImagesTest {

    private fun message(vararg images: String): JSONObject = JSONObject().put("text", "hi").apply {
        if (images.isNotEmpty()) put("image", images.first())
        if (images.size > 1) put("moreImages", JSONArray(images.drop(1)))
    }

    @Test
    fun aChatStoredBeforeSeveralPhotosStillReadsAsOnePhoto() {
        assertEquals(listOf("/p/a.jpg"), MessageImages.paths(JSONObject().put("image", "/p/a.jpg")))
        assertTrue(MessageImages.paths(JSONObject().put("text", "no photo")).isEmpty())
    }

    @Test
    fun allPhotosAreReadInOrder() {
        assertEquals(listOf("/p/a.jpg", "/p/b.jpg", "/p/c.png"), MessageImages.paths(message("/p/a.jpg", "/p/b.jpg", "/p/c.png")))
    }

    @Test
    fun rewritingChangesEveryPath() {
        val m = message("/old/a.jpg", "/old/b.jpg")
        MessageImages.rewrite(m) { "/new/" + File(it).name }
        assertEquals(listOf("/new/a.jpg", "/new/b.jpg"), MessageImages.paths(m))
    }

    @Test
    fun aPhotoTheRewriteRefusesIsDroppedAndTheRestKeepTheirOrder() {
        val m = message("/p/a.jpg", "/p/bad.txt", "/p/c.jpg")
        MessageImages.rewrite(m) { if (it.endsWith(".txt")) null else it }
        assertEquals(listOf("/p/a.jpg", "/p/c.jpg"), MessageImages.paths(m))
    }

    @Test
    fun aSinglePhotoIsStoredTheOldWay() {
        val m = message("/p/a.jpg", "/p/b.jpg")
        MessageImages.rewrite(m) { if (it.endsWith("b.jpg")) null else it }
        assertEquals("/p/a.jpg", m.getString("image"))
        assertFalse(m.has("moreImages"))
    }

    @Test
    fun droppingEveryPhotoLeavesNoneBehind() {
        val m = message("/p/a.jpg", "/p/b.jpg")
        MessageImages.rewrite(m) { null }
        assertFalse(m.has("image"))
        assertFalse(m.has("moreImages"))
    }

    @Test
    fun syncSendsAndReceivesEveryPhotoOfAMessage() {
        val chat = JSONObject().put("id", "x").put("title", "t").put("updatedAt", 1)
            .put("messages", JSONArray().put(message("/data/files/images/a.jpg", "/data/files/images/b.png")))
        assertEquals(listOf("a.jpg", "b.png"), SyncChats.media(chat).images)

        val portable = SyncChats.portable(chat)
        assertEquals(listOf("a.jpg", "b.png"), MessageImages.paths(portable.getJSONArray("messages").getJSONObject(0)))

        val local = SyncChats.localize(portable, File("/phone/images"))
        assertEquals(
            listOf(File("/phone/images", "a.jpg").absolutePath, File("/phone/images", "b.png").absolutePath),
            MessageImages.paths(local.getJSONArray("messages").getJSONObject(0)),
        )
    }

    @Test
    fun anUnsafeNameAmongSeveralIsDroppedOnlyForThatPhoto() {
        val chat = JSONObject().put("id", "x").put("title", "t").put("updatedAt", 1)
            .put("messages", JSONArray().put(message("a.jpg", "../../shared_prefs/settings.xml")))
        val local = SyncChats.localize(chat, File("/phone/images"))
        assertEquals(listOf(File("/phone/images", "a.jpg").absolutePath), MessageImages.paths(local.getJSONArray("messages").getJSONObject(0)))
    }

    @Test
    fun theExportCountsSeveralPhotos() {
        val md = ChatExport.toMarkdown("T", listOf(StoredMessage("Compare", isUser = true, imagePath = "/a.jpg", moreImages = listOf("/b.jpg", "/c.jpg"))), null, null)
        assertTrue("_[3 photos attached]_" in md)
        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), StoredMessage("x", true, imagePath = "/a.jpg", moreImages = listOf("/b.jpg", "/c.jpg")).images)
    }
}

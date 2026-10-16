package com.pocketlm.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncCryptoTest {

    private val fast = 1_000
    private val key = ByteArray(32) { it.toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun keyDerivationMatchesPublishedPbkdf2Sha256Vectors() {
        // Reference values from an independent PBKDF2-HMAC-SHA256 implementation.
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(SyncCrypto.deriveKey("password".toCharArray(), "salt".toByteArray(), 1)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(SyncCrypto.deriveKey("password".toCharArray(), "salt".toByteArray(), 4096)),
        )
    }

    @Test
    fun sealedDataComesBackUnchanged() {
        val plain = "Hello — ünïcode ✓ and a long tail ".repeat(50).toByteArray()
        val sealed = SyncCrypto.seal(key, "c-1.bin", plain)
        assertArrayEquals(plain, SyncCrypto.open(key, "c-1.bin", sealed))
    }

    @Test
    fun compressedDataComesBackUnchangedAndIsSmaller() {
        val plain = "the same words again and again ".repeat(500).toByteArray()
        val plainSealed = SyncCrypto.seal(key, "c-1.bin", plain)
        val packed = SyncCrypto.seal(key, "c-1.bin", plain, compress = true)
        assertTrue(packed.size < plainSealed.size / 4)
        assertArrayEquals(plain, SyncCrypto.open(key, "c-1.bin", packed))
    }

    @Test
    fun emptyDataRoundTrips() {
        assertArrayEquals(ByteArray(0), SyncCrypto.open(key, "x.bin", SyncCrypto.seal(key, "x.bin", ByteArray(0))))
        assertArrayEquals(ByteArray(0), SyncCrypto.open(key, "x.bin", SyncCrypto.seal(key, "x.bin", ByteArray(0), compress = true)))
    }

    @Test
    fun theFileCarriesAVersionAndNothingReadable() {
        val sealed = SyncCrypto.seal(key, "c-1.bin", "secret chat text".toByteArray())
        assertEquals(SyncCrypto.VERSION, sealed[0].toInt())
        assertEquals(0, sealed[1].toInt())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
        assertEquals(2 + 12 + "secret chat text".length + 16, sealed.size)
    }

    @Test
    fun everySealingUsesAFreshNonce() {
        val a = SyncCrypto.seal(key, "x.bin", "same".toByteArray())
        val b = SyncCrypto.seal(key, "x.bin", "same".toByteArray())
        assertFalse(a.contentEquals(b))
        assertFalse(a.copyOfRange(2, 14).contentEquals(b.copyOfRange(2, 14)))
    }

    @Test
    fun aWrongKeyIsRejected() {
        val sealed = SyncCrypto.seal(key, "x.bin", "data".toByteArray())
        val other = ByteArray(32) { (it + 1).toByte() }
        try {
            SyncCrypto.open(other, "x.bin", sealed)
            fail("expected a decrypt failure")
        } catch (e: SyncDecryptException) {
            assertTrue(e.message!!.contains("passphrase"))
        }
    }

    @Test
    fun changedBytesAreRejected() {
        val sealed = SyncCrypto.seal(key, "x.bin", "data to protect".toByteArray())
        for (index in listOf(0, 1, 5, 14, sealed.size - 1)) {
            val damaged = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            try {
                SyncCrypto.open(key, "x.bin", damaged)
                fail("byte $index was changed but the file was accepted")
            } catch (e: SyncException) {
                // expected (a changed version byte reads as a newer version, the rest as damage)
            }
        }
    }

    @Test
    fun aFileCannotBeSwappedForAnotherFile() {
        val sealed = SyncCrypto.seal(key, "c-aaa.bin", "chat A".toByteArray())
        try {
            SyncCrypto.open(key, "c-bbb.bin", sealed)
            fail("a file read under another name should not open")
        } catch (e: SyncDecryptException) {
            // expected
        }
    }

    @Test
    fun aNewerFormatVersionIsReportedClearly() {
        val sealed = SyncCrypto.seal(key, "x.bin", "data".toByteArray()).also { it[0] = 2 }
        try {
            SyncCrypto.open(key, "x.bin", sealed)
            fail()
        } catch (e: SyncVersionException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun truncatedFilesAreRejected() {
        val sealed = SyncCrypto.seal(key, "x.bin", "data".toByteArray())
        for (size in listOf(0, 1, 13, 20)) {
            try {
                SyncCrypto.open(key, "x.bin", sealed.copyOf(size))
                fail("size $size accepted")
            } catch (e: SyncDecryptException) {
                // expected
            }
        }
    }

    @Test
    fun theCorrectPassphraseGivesTheSameKeyOnAnotherDevice() {
        val (header, firstKey) = SyncCrypto.newHeader("correct horse battery".toCharArray(), fast)
        val parsed = SyncHeader.fromJson(header.toJson(), minIterations = 1)
        val secondKey = SyncCrypto.keyFor(parsed, "correct horse battery".toCharArray())
        assertNotNull(secondKey)
        assertArrayEquals(firstKey, secondKey)

        val sealed = SyncCrypto.seal(firstKey, "x.bin", "from device one".toByteArray())
        assertEquals("from device one", String(SyncCrypto.open(secondKey!!, "x.bin", sealed)))
    }

    @Test
    fun aWrongPassphraseIsRecognised() {
        val (header, _) = SyncCrypto.newHeader("correct horse battery".toCharArray(), fast)
        assertNull(SyncCrypto.keyFor(header, "wrong passphrase".toCharArray()))
        assertNull(SyncCrypto.keyFor(header, "".toCharArray()))
    }

    @Test
    fun headersGetFreshSaltsAndAreSafeToStoreOpenly() {
        val (a, _) = SyncCrypto.newHeader("same passphrase".toCharArray(), fast)
        val (b, _) = SyncCrypto.newHeader("same passphrase".toCharArray(), fast)
        assertFalse(a.salt.contentEquals(b.salt))
        assertEquals(SyncCrypto.SALT_BYTES, a.salt.size)
        assertFalse(a.toJson().contains("same passphrase"))
        assertTrue(a.sameKeyAs(SyncHeader.fromJson(a.toJson(), minIterations = 1)))
        assertFalse(a.sameKeyAs(b))
    }

    @Test
    fun keyMatchesTellsWhichKeyAHeaderWasMadeWith() {
        val (header, k) = SyncCrypto.newHeader("passphrase one".toCharArray(), fast)
        assertTrue(SyncCrypto.keyMatches(header, k))
        assertFalse(SyncCrypto.keyMatches(header, key))
    }

    @Test
    fun aHeaderWithTooFewIterationsIsRefusedWhenJoining() {
        val (header, _) = SyncCrypto.newHeader("passphrase one".toCharArray(), fast)
        try {
            SyncHeader.fromJson(header.toJson())
            fail("a weak header should be refused by default")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("key setting"))
        }
    }

    @Test
    fun theDefaultSettingsProduceAHeaderThatIsAcceptedWhenJoining() {
        val (header, key) = SyncCrypto.newHeader("a proper passphrase".toCharArray())
        assertEquals(600_000, header.iterations)
        val joined = SyncHeader.fromJson(header.toJson())
        assertArrayEquals(key, SyncCrypto.keyFor(joined, "a proper passphrase".toCharArray()))
    }

    @Test
    fun brokenHeadersAreReportedNotCrashed() {
        for (text in listOf("", "not json", "{}", """{"format":2}""", """{"format":1,"kdf":"x"}""",
            """{"format":1,"kdf":"PBKDF2WithHmacSHA256","iterations":600000,"salt":"AAAA","check":"AAAA"}""")) {
            try {
                SyncHeader.fromJson(text)
                fail("accepted: $text")
            } catch (e: SyncException) {
                // expected
            }
        }
    }
}

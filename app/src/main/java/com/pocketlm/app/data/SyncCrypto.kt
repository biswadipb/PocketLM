package com.pocketlm.app.data

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Something went wrong reading or writing synced data. The message is meant to be shown to the user. */
open class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A file could not be decrypted: wrong passphrase, or the file was changed or damaged. */
class SyncDecryptException(message: String, cause: Throwable? = null) : SyncException(message, cause)

/** Data written by a newer version of the app. It is left alone rather than treated as damaged. */
class SyncVersionException(message: String) : SyncException(message)

/**
 * The one unencrypted file kept beside the synced data. It says how to turn the passphrase into the key
 * (a random [salt] and the number of [iterations]) and holds [check], a small encrypted note that tells a
 * wrong passphrase apart from damaged data. None of it helps anyone without the passphrase.
 */
class SyncHeader(val salt: ByteArray, val iterations: Int, val check: ByteArray) {

    fun toJson(): String = JSONObject()
        .put("format", SyncCrypto.VERSION)
        .put("kdf", SyncCrypto.KDF)
        .put("iterations", iterations)
        .put("salt", b64(salt))
        .put("check", b64(check))
        .toString()

    fun sameKeyAs(other: SyncHeader): Boolean =
        iterations == other.iterations && MessageDigest.isEqual(salt, other.salt) && MessageDigest.isEqual(check, other.check)

    companion object {
        /** Reads a header from the sync location. [minIterations] stops a tampered header from asking for a weak key. */
        fun fromJson(text: String, minIterations: Int = SyncCrypto.MIN_ACCEPTED_ITERATIONS): SyncHeader {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: throw SyncException("The sync location does not hold PocketLM data.")
            if (o.optInt("format") != SyncCrypto.VERSION) {
                throw SyncException("This sync location was made by a different version of PocketLM. Update the app and try again.")
            }
            if (o.optString("kdf") != SyncCrypto.KDF) throw SyncException("This sync location uses a key method this version does not know.")
            val iterations = o.optInt("iterations")
            if (iterations < minIterations || iterations > SyncCrypto.MAX_ACCEPTED_ITERATIONS) {
                throw SyncException("This sync location has an unusable key setting.")
            }
            val salt = runCatching { Base64.getDecoder().decode(o.optString("salt")) }.getOrNull()
            val check = runCatching { Base64.getDecoder().decode(o.optString("check")) }.getOrNull()
            if (salt == null || salt.size != SyncCrypto.SALT_BYTES || check == null || check.isEmpty()) {
                throw SyncException("The sync location's header is damaged.")
            }
            return SyncHeader(salt, iterations, check)
        }

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    }
}

/**
 * Encrypts everything before it leaves the phone. A key is derived from the user's passphrase with PBKDF2
 * and every file is sealed with AES-256-GCM under a fresh random nonce.
 *
 * A sealed file is: version byte, flags byte (bit 0: compressed before sealing), 12-byte nonce, then the
 * ciphertext with its 16-byte tag. The version, flags and the file's own name are authenticated too, so a file
 * cannot be swapped for another one or have its flags changed without being noticed.
 */
object SyncCrypto {
    const val VERSION = 1
    const val KDF = "PBKDF2WithHmacSHA256"
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ACCEPTED_ITERATIONS = 100_000
    const val MAX_ACCEPTED_ITERATIONS = 10_000_000

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val FLAG_GZIP = 1
    private const val MAX_PLAIN_BYTES = 96 * 1024 * 1024
    private const val CHECK_NAME = "check"
    private val CHECK_TEXT = "PocketLM sync".toByteArray()
    private val random = SecureRandom()

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } catch (e: GeneralSecurityException) {
            throw SyncException("This phone cannot derive the sync key.", e)
        } finally {
            spec.clearPassword()
        }
    }

    /** Starts a new sync location: a fresh salt, and the key it gives for [passphrase]. */
    fun newHeader(passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): Pair<SyncHeader, ByteArray> {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)
        return SyncHeader(salt, iterations, seal(key, CHECK_NAME, CHECK_TEXT)) to key
    }

    /** The key for [passphrase] at an existing sync location, or null if the passphrase is wrong. */
    fun keyFor(header: SyncHeader, passphrase: CharArray): ByteArray? {
        val key = deriveKey(passphrase, header.salt, header.iterations)
        return try {
            if (MessageDigest.isEqual(open(key, CHECK_NAME, header.check), CHECK_TEXT)) key else null
        } catch (e: SyncDecryptException) {
            null
        }
    }

    /** True if [key] is the key that [header] was made with. */
    fun keyMatches(header: SyncHeader, key: ByteArray): Boolean = try {
        MessageDigest.isEqual(open(key, CHECK_NAME, header.check), CHECK_TEXT)
    } catch (e: SyncDecryptException) {
        false
    }

    fun seal(key: ByteArray, name: String, plain: ByteArray, compress: Boolean = false): ByteArray {
        val flags = if (compress) FLAG_GZIP else 0
        val body = if (compress) gzip(plain) else plain
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(VERSION, flags, name))
        val sealed = cipher.doFinal(body)
        return ByteArray(2 + NONCE_BYTES + sealed.size).also {
            it[0] = VERSION.toByte()
            it[1] = flags.toByte()
            nonce.copyInto(it, 2)
            sealed.copyInto(it, 2 + NONCE_BYTES)
        }
    }

    fun open(key: ByteArray, name: String, blob: ByteArray): ByteArray {
        if (blob.size < 2 + NONCE_BYTES + TAG_BITS / 8) throw SyncDecryptException("$name is too short to be a PocketLM file.")
        val version = blob[0].toInt() and 0xFF
        if (version != VERSION) throw SyncVersionException("$name was written by a newer version of PocketLM. Update the app to keep syncing.")
        val flags = blob[1].toInt() and 0xFF
        if (flags and FLAG_GZIP.inv() != 0) throw SyncVersionException("$name uses a feature this version of PocketLM does not know. Update the app to keep syncing.")
        val body = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob.copyOfRange(2, 2 + NONCE_BYTES)))
            cipher.updateAAD(aad(version, flags, name))
            cipher.doFinal(blob, 2 + NONCE_BYTES, blob.size - 2 - NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            throw SyncDecryptException("Could not decrypt $name. The passphrase is wrong, or the file was changed.", e)
        }
        return if (flags and FLAG_GZIP != 0) gunzip(body, name) else body
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun aad(version: Int, flags: Int, name: String): ByteArray =
        byteArrayOf(version.toByte(), flags.toByte()) + name.toByteArray(Charsets.UTF_8)

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** Refuses to inflate past a limit, so a crafted file cannot use up the phone's memory. */
    private fun gunzip(data: ByteArray, name: String): ByteArray {
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_PLAIN_BYTES) throw SyncDecryptException("$name is larger than expected.")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncDecryptException("$name is damaged.", e)
        }
    }
}

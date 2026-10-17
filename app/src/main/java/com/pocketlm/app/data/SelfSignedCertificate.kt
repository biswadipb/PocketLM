package com.pocketlm.app.data

import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Builds a self-signed X.509 certificate by hand (Android has no certificate builder), so the local API server
 * can offer HTTPS. The certificate lists the names and addresses the phone can be reached at, so a client that is
 * given this certificate to trust can check them.
 */
object SelfSignedCertificate {

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** [addresses] are IP literals such as "192.168.1.20"; anything that is not one is left out. */
    fun create(
        commonName: String,
        dnsNames: List<String>,
        addresses: List<String>,
        nowMs: Long,
        validDays: Int,
        keyPair: KeyPair,
        random: SecureRandom = SecureRandom(),
    ): X509Certificate {
        val name = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8(commonName))))
        val signatureAlgorithm = Der.sequence(Der.oid("1.2.840.10045.4.3.2")) // ecdsa-with-SHA256

        val alternativeNames = dnsNames.map { Der.context(2, it.toByteArray(Charsets.US_ASCII)) } +
            addresses.mapNotNull(::ipBytes).map { Der.context(7, it) }
        val extensions = Der.sequence(
            extension("2.5.29.19", critical = true, value = Der.sequence()), // basic constraints: not a CA
            extension("2.5.29.15", critical = true, value = byteArrayOf(0x03, 0x02, 0x07, 0x80.toByte())), // digital signature
            extension("2.5.29.37", critical = false, value = Der.sequence(Der.oid("1.3.6.1.5.5.7.3.1"))), // server authentication
            extension("2.5.29.17", critical = false, value = Der.sequence(*alternativeNames.toTypedArray())),
        )

        val serial = BigInteger(127, random).add(BigInteger.ONE)
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // version 3
            Der.integer(serial),
            signatureAlgorithm,
            name,
            Der.sequence(Der.utcTime(nowMs - 60_000), Der.utcTime(nowMs + validDays * 86_400_000L)),
            name,
            keyPair.public.encoded, // already a SubjectPublicKeyInfo
            Der.explicit(3, extensions),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = Der.sequence(tbs, signatureAlgorithm, Der.bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        else Der.sequence(Der.oid(oid), Der.octetString(value))

    /** The 4 or 16 bytes of an IP address literal, or null if [text] is not one (so a host name is never looked up). */
    fun ipBytes(text: String): ByteArray? {
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(text)
        val v6 = text.contains(':') && text.all { it.isLetterOrDigit() || it == ':' || it == '.' }
        if (!v4 && !v6) return null
        return runCatching { InetAddress.getByName(text).address }.getOrNull()
    }

    /** SHA-256 of the certificate as "AB:CD:…", the form clients show for pinning. */
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    fun pem(certificate: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
            "\n-----END CERTIFICATE-----\n"

    /** The IP addresses the certificate names. */
    fun addressesIn(certificate: X509Certificate): Set<InetAddress> =
        runCatching {
            certificate.subjectAlternativeNames.orEmpty()
                .filter { it[0] == 7 }
                .mapNotNull { (it[1] as? String)?.let(::ipBytes) }
                .map { InetAddress.getByAddress(it) }
                .toSet()
        }.getOrDefault(emptySet())

    private object Der {
        fun length(n: Int): ByteArray {
            if (n < 128) return byteArrayOf(n.toByte())
            val bytes = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }

        fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

        fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

        fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

        fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

        fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))

        fun octetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

        fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

        fun utf8(text: String): ByteArray = tlv(0x0C, text.toByteArray(Charsets.UTF_8))

        fun utcTime(ms: Long): ByteArray {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x17, format.format(Date(ms)).toByteArray(Charsets.US_ASCII))
        }

        fun explicit(number: Int, content: ByteArray): ByteArray = tlv(0xA0 or number, content)

        /** A context-specific primitive value, as used for names in subjectAltName. */
        fun context(number: Int, content: ByteArray): ByteArray = tlv(0x80 or number, content)

        fun oid(dotted: String): ByteArray {
            val parts = dotted.split('.').map { it.toLong() }
            val out = java.io.ByteArrayOutputStream()
            out.write((parts[0] * 40 + parts[1]).toInt())
            for (part in parts.drop(2)) {
                val chunks = ArrayList<Int>()
                var rest = part
                do {
                    chunks.add(0, (rest and 0x7F).toInt())
                    rest = rest shr 7
                } while (rest > 0)
                chunks.forEachIndexed { i, c -> out.write(if (i < chunks.lastIndex) c or 0x80 else c) }
            }
            return tlv(0x06, out.toByteArray())
        }
    }
}

/** The key and certificate the API server uses for HTTPS, kept in a private folder and renewed when needed. */
class TlsStore(private val dir: File) {

    class Identity(val certificate: X509Certificate, val privateKey: PrivateKey, val addresses: List<String>)

    private val keyFile get() = File(dir, "server-key.pk8")
    private val certFile get() = File(dir, "server-cert.der")
    private val addressFile get() = File(dir, "addresses.txt")

    /**
     * The identity to serve with. The certificate is reused as long as it is still valid for a month and names
     * every address in [current]; otherwise a new one is made that names those and the addresses seen before,
     * so coming back to a known network does not change what clients have to trust.
     */
    @Synchronized
    fun identity(current: List<String>, nowMs: Long = System.currentTimeMillis()): Identity {
        val known = readAddresses()
        val stored = load()
        if (stored != null && covers(stored.certificate, current, nowMs)) return Identity(stored.certificate, stored.privateKey, known)

        val addresses = (current + known).distinct().take(MAX_ADDRESSES)
        val keys = SelfSignedCertificate.newKeyPair()
        val certificate = SelfSignedCertificate.create(
            commonName = "PocketLM API",
            dnsNames = listOf("localhost"),
            addresses = listOf("127.0.0.1") + addresses,
            nowMs = nowMs,
            validDays = VALID_DAYS,
            keyPair = keys,
        )
        dir.mkdirs()
        write(keyFile, keys.private.encoded)
        write(certFile, certificate.encoded)
        write(addressFile, addresses.joinToString("\n").toByteArray())
        return Identity(certificate, keys.private, addresses)
    }

    /** The current certificate without making a new one, for showing its fingerprint. */
    @Synchronized
    fun existing(): X509Certificate? = load()?.certificate

    @Synchronized
    fun delete() {
        keyFile.delete(); certFile.delete(); addressFile.delete()
    }

    private fun covers(certificate: X509Certificate, current: List<String>, nowMs: Long): Boolean {
        if (certificate.notAfter.time - nowMs < RENEW_BEFORE_MS || certificate.notBefore.time > nowMs) return false
        val named = SelfSignedCertificate.addressesIn(certificate)
        return current.all { address ->
            val bytes = SelfSignedCertificate.ipBytes(address) ?: return@all true
            InetAddress.getByAddress(bytes) in named
        }
    }

    private class Loaded(val certificate: X509Certificate, val privateKey: PrivateKey)

    private fun load(): Loaded? = runCatching {
        if (!keyFile.isFile || !certFile.isFile) return null
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        Loaded(cert, key)
    }.getOrNull()

    private fun readAddresses(): List<String> =
        runCatching { addressFile.readText().lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun write(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    companion object {
        const val VALID_DAYS = 730
        private const val RENEW_BEFORE_MS = 30L * 86_400_000
        private const val MAX_ADDRESSES = 12

        /** A TLS context that serves with [identity]. */
        fun serverContext(identity: Identity): SSLContext {
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("pocketlm", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, CharArray(0)) }.keyManagers
            return SSLContext.getInstance("TLS").apply { init(managers, null, null) }
        }
    }
}

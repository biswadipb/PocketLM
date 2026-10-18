package com.pocketlm.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

class SelfSignedCertificateTest {

    private val now = 1_800_000_000_000L // a fixed moment in 2027
    private lateinit var dir: File

    @Before
    fun makeDir() {
        dir = Files.createTempDirectory("tls-test").toFile()
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun make(addresses: List<String> = listOf("192.168.1.20")): X509Certificate {
        val keys = SelfSignedCertificate.newKeyPair()
        return SelfSignedCertificate.create("PocketLM API", listOf("localhost"), addresses, now, 730, keys)
    }

    @Test
    fun theCertificateIsSignedByItsOwnKey() {
        val cert = make()
        cert.verify(cert.publicKey)
        assertEquals("CN=PocketLM API", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.checkValidity(java.util.Date(now))
    }

    @Test
    fun itNamesTheHostAndTheAddresses() {
        val names = make(listOf("192.168.1.20", "10.0.0.7", "not an address")).subjectAlternativeNames.map { it[0] to it[1] }
        assertTrue(2 to "localhost" in names)
        assertTrue(7 to "192.168.1.20" in names)
        assertTrue(7 to "10.0.0.7" in names)
        assertEquals(3, names.size)
    }

    @Test
    fun itIsOnlyGoodForServingAndLastsTwoYears() {
        val cert = make()
        assertEquals(-1, cert.basicConstraints)
        assertTrue(cert.keyUsage[0])
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), cert.extendedKeyUsage)
        val days = (cert.notAfter.time - cert.notBefore.time) / 86_400_000
        assertTrue(days in 729..731)
    }

    @Test
    fun hostNamesAreNeverLookedUpAsAddresses() {
        assertNull(SelfSignedCertificate.ipBytes("example.com"))
        assertNull(SelfSignedCertificate.ipBytes("1.2.3"))
        assertEquals(4, SelfSignedCertificate.ipBytes("127.0.0.1")!!.size)
        assertEquals(16, SelfSignedCertificate.ipBytes("::1")!!.size)
    }

    @Test
    fun thePinnedFingerprintAndTextFormAreWellFormed() {
        val cert = make()
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(SelfSignedCertificate.fingerprint(cert)))
        val pem = SelfSignedCertificate.pem(cert)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
    }

    @Test
    fun theSameCertificateIsKeptWhileItStillCoversTheAddresses() {
        val store = TlsStore(dir)
        val first = store.identity(listOf("192.168.1.20"), now)
        val again = store.identity(listOf("192.168.1.20"), now + 86_400_000L)
        assertEquals(SelfSignedCertificate.fingerprint(first.certificate), SelfSignedCertificate.fingerprint(again.certificate))
    }

    @Test
    fun aNewNetworkMakesANewCertificateThatKeepsTheOldAddresses() {
        val store = TlsStore(dir)
        val first = store.identity(listOf("192.168.1.20"), now)
        val second = store.identity(listOf("10.0.0.7"), now)
        assertNotEquals(SelfSignedCertificate.fingerprint(first.certificate), SelfSignedCertificate.fingerprint(second.certificate))
        val named = SelfSignedCertificate.addressesIn(second.certificate)
        assertTrue(InetAddress.getByName("10.0.0.7") in named)
        assertTrue(InetAddress.getByName("192.168.1.20") in named)

        // Back on the first network, the second certificate still applies.
        val third = store.identity(listOf("192.168.1.20"), now)
        assertEquals(SelfSignedCertificate.fingerprint(second.certificate), SelfSignedCertificate.fingerprint(third.certificate))
    }

    @Test
    fun anExpiringCertificateIsRenewed() {
        val store = TlsStore(dir)
        val first = store.identity(listOf("192.168.1.20"), now)
        val later = store.identity(listOf("192.168.1.20"), now + 720L * 86_400_000)
        assertNotEquals(SelfSignedCertificate.fingerprint(first.certificate), SelfSignedCertificate.fingerprint(later.certificate))
    }

    @Test
    fun deletingForgetsTheCertificate() {
        val store = TlsStore(dir)
        store.identity(emptyList(), now)
        assertNotNull(store.existing())
        store.delete()
        assertNull(store.existing())
    }

    @Test
    fun aClientThatTrustsTheCertificateCanTalkToTheServerOverHttps() {
        val identity = TlsStore(dir).identity(emptyList())
        val backend = object : ApiBackend {
            override fun modelNames() = listOf("tls-model.gguf")
            override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int = 0
        }
        val server = ApiServer(0, { null }, { backend }, tls = TlsStore.serverContext(identity))
        val port = server.start()
        try {
            val trust = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("pocketlm", identity.certificate)
            }
            val client = SSLContext.getInstance("TLS").apply {
                init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
            }
            val connection = java.net.URL("https://127.0.0.1:$port/v1/models").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = client.socketFactory
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            assertEquals(200, connection.responseCode)
            assertTrue("tls-model" in connection.inputStream.bufferedReader().readText())
        } finally {
            server.stop()
        }
    }

    @Test
    fun aClientThatDoesNotTrustTheCertificateIsRefused() {
        val identity = TlsStore(dir).identity(emptyList())
        val backend = object : ApiBackend {
            override fun modelNames() = emptyList<String>()
            override suspend fun complete(request: ChatRequest, onToken: (String) -> Unit): Int = 0
        }
        val server = ApiServer(0, { null }, { backend }, tls = TlsStore.serverContext(identity))
        val port = server.start()
        try {
            val connection = java.net.URL("https://127.0.0.1:$port/v1/models").openConnection() as HttpsURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            var refused = false
            try {
                connection.responseCode
            } catch (e: javax.net.ssl.SSLException) {
                refused = true
            }
            assertTrue(refused)
            assertFalse(identity.certificate.subjectX500Principal.name.isEmpty())
        } finally {
            server.stop()
        }
    }
}

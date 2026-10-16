package com.pocketlm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The examples are the ones Amazon publishes for Signature Version 4 on S3 ("Signature Calculations for the
 * Authorization Header"), with its documentation credentials. They were also reproduced with an independent
 * implementation when this test was written.
 */
class SigV4Test {

    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val host = "examplebucket.s3.amazonaws.com"

    /** 2013-05-24T00:00:00Z */
    private val time = 1369353600000L

    private fun signature(r: SignedRequest) = r.authorization.substringAfter("Signature=")

    @Test
    fun getObjectWithARangeHeader() {
        val r = SigV4.sign(
            accessKey, secretKey, "us-east-1", "GET", host, "/test.txt", emptyList(),
            mapOf("Range" to "bytes=0-9"), SigV4.EMPTY_HASH, time,
        )
        assertEquals("20130524T000000Z", r.amzDate)
        assertEquals(
            "GET\n/test.txt\n\nhost:examplebucket.s3.amazonaws.com\nrange:bytes=0-9\n" +
                "x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
                "x-amz-date:20130524T000000Z\n\nhost;range;x-amz-content-sha256;x-amz-date\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            r.canonicalRequest,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n20130524T000000Z\n20130524/us-east-1/s3/aws4_request\n" +
                "7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972",
            r.stringToSign,
        )
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signature(r))
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            r.authorization,
        )
    }

    @Test
    fun putObjectWithASpecialCharacterInTheNameAndExtraHeaders() {
        val body = "Welcome to Amazon S3.".toByteArray()
        val hash = SigV4.sha256Hex(body)
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val r = SigV4.sign(
            accessKey, secretKey, "us-east-1", "PUT", host, "/test\$file.text", emptyList(),
            mapOf("Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"), hash, time,
        )
        assertTrue(r.canonicalRequest.startsWith("PUT\n/test%24file.text\n\ndate:Fri, 24 May 2013 00:00:00 GMT\nhost:"))
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signature(r))
    }

    @Test
    fun aSubResourceWithNoValueKeepsItsEqualsSign() {
        val r = SigV4.sign(accessKey, secretKey, "us-east-1", "GET", host, "/", listOf("lifecycle" to ""), emptyMap(), SigV4.EMPTY_HASH, time)
        assertTrue(r.canonicalRequest.startsWith("GET\n/\nlifecycle=\n"))
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", signature(r))
    }

    @Test
    fun queryParametersAreSortedAndEncoded() {
        val r = SigV4.sign(
            accessKey, secretKey, "us-east-1", "GET", host, "/", listOf("prefix" to "J", "max-keys" to "2"),
            emptyMap(), SigV4.EMPTY_HASH, time,
        )
        assertTrue(r.canonicalRequest.startsWith("GET\n/\nmax-keys=2&prefix=J\n"))
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", signature(r))
    }

    @Test
    fun theHeadersToSendCarryTheSignedValues() {
        val r = SigV4.sign(accessKey, secretKey, "us-east-1", "GET", host, "/a", emptyList(), emptyMap(), SigV4.EMPTY_HASH, time)
        val headers = r.headers()
        assertEquals(r.authorization, headers["Authorization"])
        assertEquals("20130524T000000Z", headers["x-amz-date"])
        assertEquals(SigV4.EMPTY_HASH, headers["x-amz-content-sha256"])
    }

    @Test
    fun theRegionAndSecretChangeTheSignature() {
        fun sig(region: String, secret: String) = signature(
            SigV4.sign(accessKey, secret, region, "GET", host, "/a", emptyList(), emptyMap(), SigV4.EMPTY_HASH, time),
        )
        val base = sig("us-east-1", secretKey)
        assertTrue(base != sig("eu-west-1", secretKey))
        assertTrue(base != sig("us-east-1", "other"))
    }

    @Test
    fun encodingFollowsRfc3986() {
        assertEquals("AZaz09-_.~", SigV4.encode("AZaz09-_.~", true))
        assertEquals("a%20b%2Bc%3D%26%2F", SigV4.encode("a b+c=&/", true))
        assertEquals("a/b%20c", SigV4.encode("a/b c", false))
        assertEquals("%C3%A9%E2%9C%93", SigV4.encode("é✓", true))
        assertEquals("/bucket/some%20folder/file.bin", SigV4.encodePath("/bucket/some folder/file.bin"))
    }
}

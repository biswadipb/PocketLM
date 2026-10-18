package com.pocketlm.app.data

import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The result of signing one request: the headers to send with it, plus the intermediate text for checking. */
class SignedRequest(
    val authorization: String,
    val amzDate: String,
    val contentHash: String,
    val canonicalRequest: String,
    val stringToSign: String,
) {
    /** The headers a request must carry for the signature to hold. */
    fun headers(): Map<String, String> = mapOf(
        "Authorization" to authorization,
        "x-amz-date" to amzDate,
        "x-amz-content-sha256" to contentHash,
    )
}

/**
 * AWS Signature Version 4, as S3 and S3-compatible stores (MinIO, Cloudflare R2, Backblaze B2, Wasabi) expect it.
 * Written out in plain Kotlin so it can be tested against Amazon's published examples.
 */
object SigV4 {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    const val EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * Signs a request.
     * [host] is the exact Host header (with the port when it is not the default), [path] is the plain,
     * unencoded path, [headers] are any extra headers that should be covered by the signature, and
     * [payloadHash] is the lower-case hex SHA-256 of the body.
     */
    fun sign(
        accessKey: String,
        secretKey: String,
        region: String,
        method: String,
        host: String,
        path: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        payloadHash: String,
        timeMs: Long,
        service: String = "s3",
    ): SignedRequest {
        val amzDate = stamp.format(java.time.Instant.ofEpochMilli(timeMs))
        val day = amzDate.substring(0, 8)

        val signed = TreeMap<String, String>()
        for ((name, value) in headers) signed[name.lowercase()] = value.trim().replace(Regex("\\s+"), " ")
        signed["host"] = host
        signed["x-amz-date"] = amzDate
        signed["x-amz-content-sha256"] = payloadHash

        val canonicalQuery = query.map { encode(it.first, true) to encode(it.second, true) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val signedNames = signed.keys.joinToString(";")
        val canonicalRequest = buildString {
            append(method).append('\n')
            append(encodePath(path)).append('\n')
            append(canonicalQuery).append('\n')
            for ((name, value) in signed) append(name).append(':').append(value).append('\n')
            append('\n')
            append(signedNames).append('\n')
            append(payloadHash)
        }

        val scope = "$day/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"

        var key = hmac("AWS4$secretKey".toByteArray(), day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = SyncCrypto.hex(hmac(key, stringToSign))

        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedNames, Signature=$signature"
        return SignedRequest(authorization, amzDate, payloadHash, canonicalRequest, stringToSign)
    }

    fun sha256Hex(bytes: ByteArray): String = SyncCrypto.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** A path with each part percent-encoded and the slashes kept. S3 encodes a path once, not twice. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it, true) }

    /** Percent-encodes everything except letters, digits and - _ . ~ (RFC 3986), upper-case hex, UTF-8. */
    fun encode(text: String, encodeSlash: Boolean): String {
        val out = StringBuilder()
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val plain = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code || (c == '/'.code && !encodeSlash)
            if (plain) out.append(c.toChar()) else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
}

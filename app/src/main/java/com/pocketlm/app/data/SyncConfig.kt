package com.pocketlm.app.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SyncKind(val label: String) {
    Folder("A folder"),
    WebDav("WebDAV"),
    S3("S3-compatible"),
}

/** Where to sync to, as the user entered it. [password] and [secretKey] are secrets and are stored apart from the rest. */
data class SyncTarget(
    val kind: SyncKind,
    val folderUri: String = "",
    /** The folder's name, remembered so Settings does not have to ask the storage app each time. */
    val folderName: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val prefix: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
) {
    /** A short message about what is wrong with the entries, or null if they look usable. */
    fun problem(): String? = when (kind) {
        SyncKind.Folder -> if (folderUri.isBlank()) "Choose a folder first." else null
        SyncKind.WebDav -> when {
            hostOf(url) == null -> "Enter the folder's address, starting with https://."
            !url.trim().startsWith("https://", ignoreCase = true) -> "Only https:// addresses work. Android does not allow plain http."
            username.isBlank() -> "Enter your user name."
            password.isEmpty() -> "Enter your password. Use an app password if your server offers them."
            else -> null
        }
        SyncKind.S3 -> when {
            hostOf(endpoint) == null -> "Enter the storage's address, starting with https://."
            !endpoint.trim().startsWith("https://", ignoreCase = true) -> "Only https:// addresses work. Android does not allow plain http."
            !BUCKET.matches(bucket.trim()) -> "Enter the bucket's name."
            region.isBlank() -> "Enter the region, such as us-east-1 (use auto for Cloudflare R2)."
            accessKey.isBlank() || secretKey.isBlank() -> "Enter the access key and secret key."
            else -> null
        }
    }

    /** What to show in Settings for where this syncs. */
    fun describe(): String = when (kind) {
        SyncKind.Folder -> "Folder: " + folderName.ifEmpty { "chosen folder" }
        SyncKind.WebDav -> "WebDAV: " + (hostOf(url) ?: url)
        SyncKind.S3 -> "S3: $bucket at " + (hostOf(endpoint) ?: endpoint)
    }

    fun publicJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("folderUri", folderUri).put("folderName", folderName).put("url", url.trim()).put("username", username.trim())
        .put("endpoint", endpoint.trim()).put("region", region.trim()).put("bucket", bucket.trim()).put("prefix", prefix.trim())
        .put("accessKey", accessKey.trim())

    fun secretJson(): JSONObject = JSONObject().put("password", password).put("secretKey", secretKey.trim())

    /** Opens the place this points to. Call off the main thread when using it. */
    fun open(context: Context): SyncBackend = when (kind) {
        SyncKind.Folder -> FolderBackend(context, Uri.parse(folderUri))
        SyncKind.WebDav -> WebDavBackend(url.trim(), username.trim(), password)
        SyncKind.S3 -> S3Backend(endpoint.trim(), region.trim(), bucket.trim(), prefix, accessKey.trim(), secretKey.trim())
    }

    companion object {
        private val BUCKET = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{1,62}""")

        private fun hostOf(address: String): String? = runCatching { URI(address.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() }

        fun fromJson(public: JSONObject, secret: JSONObject): SyncTarget? {
            val kind = SyncKind.entries.firstOrNull { it.name == public.optString("kind") } ?: return null
            return SyncTarget(
                kind = kind,
                folderUri = public.optString("folderUri"),
                folderName = public.optString("folderName"),
                url = public.optString("url"),
                username = public.optString("username"),
                password = secret.optString("password"),
                endpoint = public.optString("endpoint"),
                region = public.optString("region").ifEmpty { "us-east-1" },
                bucket = public.optString("bucket"),
                prefix = public.optString("prefix"),
                accessKey = public.optString("accessKey"),
                secretKey = secret.optString("secretKey"),
            )
        }
    }
}

/** What Settings shows about sync. */
data class SyncStatus(
    val configured: Boolean = false,
    val description: String = "",
    val running: Boolean = false,
    val progress: String = "",
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    val lastSummary: String? = null,
    val syncSettings: Boolean = false,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Why the last attempt to connect failed; shown in the setup dialog. */
    val connectError: String? = null,
)

/** Wraps small secrets with a key that never leaves the phone's secure hardware or key store. */
interface SecretBox {
    fun seal(plain: ByteArray): String
    fun open(sealed: String): ByteArray?
}

/** [SecretBox] backed by the Android Keystore (AES-256-GCM). The key cannot be read out, only used. */
class KeystoreSecretBox(private val alias: String = "pocketlm_sync_wrap") : SecretBox {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }

    override fun open(sealed: String): ByteArray? = try {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes, 12, bytes.size - 12)
    } catch (e: Exception) {
        null
    }
}

/** Everything needed to sync, read back from storage. */
class SyncConnection(val target: SyncTarget, val header: SyncHeader, val key: ByteArray)

/**
 * Remembers where this phone syncs to. It lives in its own preferences file, apart from the app's settings, so it
 * can never travel in a backup or in synced settings. Passwords, access keys and the encryption key are only kept
 * wrapped by the Android Keystore; the passphrase itself is never stored.
 */
class SyncConfigStore(private val context: Context, private val box: SecretBox = KeystoreSecretBox()) {

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    val configured: Boolean get() = prefs.contains(KEY_TARGET)
    val syncSettings: Boolean get() = prefs.getBoolean(KEY_SETTINGS, false)
    val autoSync: Boolean get() = prefs.getBoolean(KEY_AUTO, true)
    val wifiOnly: Boolean get() = prefs.getBoolean(KEY_WIFI, true)

    fun setSyncSettings(on: Boolean) = prefs.edit().putBoolean(KEY_SETTINGS, on).apply()
    fun setAutoSync(on: Boolean) = prefs.edit().putBoolean(KEY_AUTO, on).apply()
    fun setWifiOnly(on: Boolean) = prefs.edit().putBoolean(KEY_WIFI, on).apply()

    fun save(target: SyncTarget, header: SyncHeader, key: ByteArray) {
        prefs.edit()
            .putString(KEY_TARGET, target.publicJson().toString())
            .putString(KEY_SECRET, box.seal(target.secretJson().toString().toByteArray()))
            .putString(KEY_HEADER, header.toJson())
            .putString(KEY_KEY, box.seal(key))
            .remove(KEY_ERROR).remove(KEY_SUMMARY)
            .apply()
    }

    /** The saved connection, or null if there is none or the Keystore can no longer unlock it. */
    fun load(): SyncConnection? {
        val publicJson = prefs.getString(KEY_TARGET, null) ?: return null
        return try {
            val secret = JSONObject(String(box.open(prefs.getString(KEY_SECRET, null) ?: return null) ?: return null))
            val target = SyncTarget.fromJson(JSONObject(publicJson), secret) ?: return null
            val header = SyncHeader.fromJson(prefs.getString(KEY_HEADER, null) ?: return null, minIterations = 1)
            val key = box.open(prefs.getString(KEY_KEY, null) ?: return null) ?: return null
            SyncConnection(target, header, key)
        } catch (e: Exception) {
            null
        }
    }

    fun recordResult(summary: String?, error: String?) {
        prefs.edit().apply {
            if (error == null) remove(KEY_ERROR) else putString(KEY_ERROR, error)
            if (summary != null) putString(KEY_SUMMARY, summary)
        }.apply()
    }

    fun clear() {
        val folder = runCatching { JSONObject(prefs.getString(KEY_TARGET, null).orEmpty()).optString("folderUri") }.getOrDefault("")
        if (folder.isNotEmpty()) FolderBackend.releasePermission(context, Uri.parse(folder))
        prefs.edit().clear().apply()
    }

    fun status(lastSyncAt: Long): SyncStatus {
        if (!configured) return SyncStatus()
        // Only the unsecret half is read, so showing the status never has to unlock the Keystore.
        return SyncStatus(
            configured = true,
            description = runCatching { SyncTarget.fromJson(JSONObject(prefs.getString(KEY_TARGET, null).orEmpty()), JSONObject())?.describe() }.getOrNull()
                ?: "Sync",
            lastSyncAt = lastSyncAt,
            lastError = prefs.getString(KEY_ERROR, null),
            lastSummary = prefs.getString(KEY_SUMMARY, null),
            syncSettings = syncSettings,
            autoSync = autoSync,
            wifiOnly = wifiOnly,
        )
    }

    private companion object {
        const val KEY_TARGET = "target"
        const val KEY_SECRET = "secret"
        const val KEY_HEADER = "header"
        const val KEY_KEY = "key"
        const val KEY_SETTINGS = "sync_settings"
        const val KEY_AUTO = "auto"
        const val KEY_WIFI = "wifi_only"
        const val KEY_ERROR = "last_error"
        const val KEY_SUMMARY = "last_summary"
    }
}

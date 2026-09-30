package so.drafft.core.data.media

import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.DrafftJson
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.platform.KeyValueStore

// Ports `MediaURL` from Drafft/Services/Backend/ProfileSync.swift.

/**
 * Media links. The bucket is private: the backend signs a link for each object the person may see
 * (cards, `media_urls`), valid for about an hour. Caches are keyed by the object ([canonical]), never by
 * the signature. [base] is where media is served from (`app-config`), for builds and backends from
 * before signed links.
 *
 * Called from anywhere, like the iPhone's static enum: [install] gives it the backend and the phone's
 * storage at launch (the Koin module does it).
 */
object MediaURL {
    private const val KEY = "mediaBaseURL"

    @Volatile
    private var backend: Backend? = null

    @Volatile
    private var defaults: KeyValueStore? = null

    fun install(backend: Backend, defaults: KeyValueStore) {
        this.backend = backend
        this.defaults = defaults
    }

    /** The base already known on this phone, without asking (null before the first [base]). */
    val saved: String? get() = defaults?.getString(KEY)

    @Serializable
    private data class Config(val mediaUrl: String)

    suspend fun base(): String? {
        saved?.let { return it }
        val backend = backend ?: return null
        val config = attempt {
            val response = MediaUploader.shared.client.get(backend.config.functionsURL + "/app-config")
            DrafftJson.decodeFromString(Config.serializer(), response.readRawBytes().decodeToString())
        } ?: return null
        if (runCatching { URI(config.mediaUrl) }.isFailure) return null
        defaults?.putString(KEY, config.mediaUrl)
        return config.mediaUrl
    }

    /**
     * Signed links for keys the app holds: its own media, and chat media of a current match. Keys the
     * person may not open are left out; empty when offline.
     */
    suspend fun signed(keys: List<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val backend = backend ?: return emptyMap()
        return attempt {
            val data = backend.rpc("media_urls", JsonObject(mapOf("p_keys" to JsonArray(keys.map(::JsonPrimitive)))))
            withContext(Dispatchers.Default) {
                DrafftJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), data.decodeToString())
            }
        } ?: emptyMap()
    }

    /** The object key of a media link (`u/<user>/...`, its path after the base), or null. */
    fun key(of: String): String? {
        val path = runCatching { URI(of).path }.getOrNull() ?: return null
        val start = path.indexOf("/u/")
        if (start < 0) return null
        return path.substring(start + 1)
    }

    /** The same object whatever its signature: what caches are keyed by. */
    fun canonical(url: String): String {
        val query = url.indexOf('?')
        if (query < 0) return url
        val fragment = url.indexOf('#', query)
        return url.substring(0, query) + (if (fragment >= 0) url.substring(fragment) else "")
    }

    /** When a signed link stops working; null for an unsigned one. */
    fun expiry(of: String): Instant? {
        val query = runCatching { URI(of).rawQuery }.getOrNull() ?: return null
        val value = query.split('&').firstOrNull { it.substringBefore('=') == "exp" }?.substringAfter('=', "") ?: return null
        return value.toDoubleOrNull()?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
    }

    /**
     * The link while it has more than a minute left; otherwise a new one from the backend (own and chat
     * media: cards come with fresh links each time they're read again), or the same link.
     */
    suspend fun fresh(url: String): String {
        val expiry = expiry(url) ?: return url
        if (expiry.epochSecond - Instant.now().epochSecond >= 60) return url
        val key = key(url) ?: return url
        return signed(listOf(key))[key] ?: url
    }
}

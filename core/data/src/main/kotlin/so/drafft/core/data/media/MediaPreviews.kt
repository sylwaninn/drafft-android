package so.drafft.core.data.media

import so.drafft.core.model.ThumbHash

// Ports Drafft/Services/Media/MediaPreviews.swift.

/**
 * The ThumbHash of each server photo the app knows (sent with its media row), turned into a blurred
 * preview the photo view draws while the real image loads. Keyed by media key: a photo's link changes
 * with its signature, its key never does. Previews are decoded on first use (about 32 × 32 px) and
 * cached with a bound; hashes are a few dozen bytes each, capped too. The UI turns the RGBA pixels into
 * a bitmap (core:ui).
 */
object MediaPreviews {
    private const val HASH_LIMIT = 5_000
    private const val IMAGE_LIMIT = 300
    private val lock = Any()
    private val hashes = HashMap<String, String>()
    private val images = object : LinkedHashMap<String, ThumbHash.Image>(IMAGE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThumbHash.Image>?) = size > IMAGE_LIMIT
    }

    /** Records a media row's hash (null or empty: nothing to show). */
    fun register(hash: String?, key: String) {
        if (hash.isNullOrEmpty()) return
        synchronized(lock) {
            if (hashes[key] == null && hashes.size >= HASH_LIMIT) hashes.clear()
            hashes[key] = hash
        }
    }

    /** The preview of a server photo (`http…`), if its hash is known. */
    fun image(name: String): ThumbHash.Image? {
        if (!name.startsWith("http")) return null
        val key = MediaURL.key(name) ?: return null
        val hash = synchronized(lock) {
            images[key]?.let { return it }
            hashes[key]
        } ?: return null
        val image = ThumbHash.image(fromBase64 = hash) ?: return null
        synchronized(lock) { images[key] = image }
        return image
    }
}

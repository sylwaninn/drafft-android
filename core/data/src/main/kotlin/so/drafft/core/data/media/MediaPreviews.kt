package so.drafft.core.data.media

import so.drafft.core.model.ThumbHash

// Ports Drafft/Services/Media/MediaPreviews.swift.

/**
 * The ThumbHash of each server photo the app knows (sent with its media row), turned into a blurred
 * preview the photo view draws while the real image loads, and its proportions, which decide the copy
 * to download (`Renditions`). Keyed by media key: a photo's link changes with its signature, its key
 * never does. Previews are decoded on first use (about 32 × 32 px) and
 * cached with a bound; hashes are a few dozen bytes each, capped too. The UI turns the RGBA pixels into
 * a bitmap (core:ui).
 */
object MediaPreviews {
    private const val HASH_LIMIT = 5_000
    private const val IMAGE_LIMIT = 300
    private val lock = Any()
    private val hashes = HashMap<String, String>()

    /** Width over height, from the media row's size (or, without one, its ThumbHash). */
    private val aspects = HashMap<String, Double>()
    private val images = object : LinkedHashMap<String, ThumbHash.Image>(IMAGE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThumbHash.Image>?) = size > IMAGE_LIMIT
    }

    /** Records a media row's hash (null or empty: nothing to show) and size in pixels (null: unknown). */
    fun register(hash: String?, key: String, width: Int? = null, height: Int? = null) {
        synchronized(lock) {
            if (width != null && height != null && width > 0 && height > 0) {
                if (aspects[key] == null && aspects.size >= HASH_LIMIT) aspects.clear()
                aspects[key] = width.toDouble() / height
            }
            if (hash.isNullOrEmpty()) return
            if (hashes[key] == null && hashes.size >= HASH_LIMIT) hashes.clear()
            hashes[key] = hash
        }
    }

    /**
     * A server photo's width over its height: its row's size, or its preview's (a ThumbHash keeps the
     * proportions); null when neither is known.
     */
    fun aspect(name: String): Double? {
        if (!name.startsWith("http")) return null
        val key = MediaURL.key(name) ?: return null
        synchronized(lock) { aspects[key] }?.let { return it }
        val preview = image(name) ?: return null
        if (preview.height <= 0) return null
        return preview.width.toDouble() / preview.height
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

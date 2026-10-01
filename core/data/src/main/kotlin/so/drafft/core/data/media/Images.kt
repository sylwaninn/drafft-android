package so.drafft.core.data.media

import java.net.URI

// Ports the platform-neutral part of Drafft/Services/Media/Images.swift: the sizes and the CDN's sized
// links. Loading, decoding and caching the photos is Coil's, configured in core:ui with these numbers.

/**
 * Every photo that isn't bundled with the app (on the server, or picked on this phone) goes through one
 * image pipeline (Coil, in core:ui):
 *
 * - decoded in the background, straight at the size it's drawn, never the full bitmap on the main thread;
 * - one download per photo, however many views ask for it at once;
 * - a capped memory cache and a capped disk cache of the downloaded bytes;
 * - both caches keyed by the object (`MediaURL.canonical`), never by its signed link: a photo stays
 *   cached when its link is renewed, and a link about to expire is renewed before it's downloaded;
 * - the media Worker sends a copy at the display width (`&w=`) instead of the 2048 px original whenever a
 *   smaller one is enough: thumbnails, grids, avatars ([sized]).
 */
object Images {
    /** Decoded photos kept in memory (bytes). */
    const val memoryLimit: Long = 120L shl 20

    /** Downloaded bytes kept on disk. */
    const val diskLimit: Long = 300L shl 20

    /**
     * Pixel sizes shared by nearby displays: a size change (rotation, animation) reuses a copy instead of
     * decoding again, and two avatars a few points apart share one.
     */
    val buckets = listOf(128, 256, 512, 768, 1_080, 1_440)
    const val largest = 2_048

    /**
     * The widths the media Worker resizes to (cloudflare/media-worker in drafft-backend); any other value
     * gets the original.
     */
    val serverWidths = listOf(160, 320, 640, 1_080)

    /** The pixel size a photo drawn [pixels] wide is fetched and decoded at. */
    fun bucket(pixels: Double): Int = buckets.firstOrNull { it.toDouble() >= pixels } ?: largest

    /**
     * A signed photo link on the media domain with `&w=` set to the smallest width the Worker serves that
     * covers [width] pixels; above the largest, the original (2048 px at most). `w` isn't part of the
     * signature, and the caches keep one copy per width (`MediaURL.canonical`). Other links are left as
     * they are. Null for a link that isn't one.
     */
    fun sized(name: String, width: Int): String? = sized(name, width, MediaURL.saved)

    /** [sized] against a given media base (null: unknown yet, left as it is). */
    fun sized(name: String, width: Int, base: String?): String? {
        val url = runCatching { URI(name) }.getOrNull() ?: return null
        if (base == null) return name
        val baseURL = runCatching { URI(base) }.getOrNull() ?: return name
        if (url.host != baseURL.host || url.scheme != baseURL.scheme || MediaURL.key(name) == null) return name
        return MediaURL.withWidth(name, serverWidths.firstOrNull { it >= width })
    }
}

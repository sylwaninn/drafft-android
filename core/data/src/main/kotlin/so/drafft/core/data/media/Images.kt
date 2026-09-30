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
 * - with [resizesOnServer] on (the media domain runs Cloudflare Image Resizing), the server sends a copy
 *   at the display width instead of the original ([sized]).
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
     * Whether the media domain resizes on the fly (`/cdn-cgi/image/…`, Cloudflare Image Resizing):
     * `MEDIA_IMAGE_RESIZING` in the app's build config, set at launch. Off until the media domain has it
     * turned on; the original is downloaded then, and still decoded at the display size.
     */
    @Volatile
    var resizesOnServer: Boolean = false

    /** The pixel size a photo drawn [pixels] wide is fetched and decoded at. */
    fun bucket(pixels: Double): Int = buckets.firstOrNull { it.toDouble() >= pixels } ?: largest

    /**
     * `https://media…/key` to `https://media…/cdn-cgi/image/width=W,quality=80,fit=scale-down/key`, only
     * for photos on the media domain (other links are left as they are). Null for a link that isn't one.
     */
    fun sized(name: String, width: Int): String? = sized(name, width, if (resizesOnServer) MediaURL.saved else null)

    /** [sized] against a given media base (null: resizing off). */
    fun sized(name: String, width: Int, base: String?): String? {
        val url = runCatching { URI(name) }.getOrNull() ?: return null
        if (!resizesOnServer || base == null) return name
        val baseURL = runCatching { URI(base) }.getOrNull() ?: return name
        if (url.host != baseURL.host || url.scheme != baseURL.scheme) return name
        val basePath = baseURL.rawPath.orEmpty().removeSuffix("/")
        val path = url.rawPath.orEmpty()
        if (!path.startsWith("$basePath/") || path.contains("/cdn-cgi/")) return name
        val key = path.substring(basePath.length + 1)
        val port = if (url.port != -1) ":${url.port}" else ""
        val query = url.rawQuery?.let { "?$it" }.orEmpty()
        return "${url.scheme}://${url.host}$port/cdn-cgi/image/width=$width,quality=80,fit=scale-down$basePath/$key$query"
    }
}

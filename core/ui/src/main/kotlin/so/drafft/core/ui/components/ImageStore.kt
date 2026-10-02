package so.drafft.core.ui.components

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.MediaPreviews
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.media.NetworkQuality
import so.drafft.core.data.media.PixelSize
import so.drafft.core.data.media.Renditions
import so.drafft.core.model.ThumbHash
import so.drafft.core.ui.image.BundledImages
import java.io.File
import kotlin.math.roundToInt

// ImageStore also builds the requests that pick, size and decode remote photos (the pipeline is set up by
// `installImages`).

/**
 * Display-ready copies of the bundled photos, through Coil's memory cache.
 *
 * A bundled photo (1000 to 1400 px) decoded on the main thread the first time it's drawn can freeze the
 * tab bar when a tab full of avatars does it dozens of times. Here small displays get a
 * downsampled copy, blurred displays get the blur baked in once (no live blur), and the photos the
 * first screens need are prepared in the background at launch ([prewarm]).
 */
object ImageStore {
    /**
     * Crops a request's decoded copy to `crop` pixels (centred) and bakes a blur of `radius` pixels into
     * it. Coil's transformations are Android-only, so the Android side installs it at launch
     * (`installDrafftUi()`); until then photos are drawn uncropped and sharp.
     */
    @Volatile
    var transformEngine: (builder: ImageRequest.Builder, crop: IntSize?, radius: Int) -> Unit = { _, _, _ -> }

    /** Pixel sizes shared by nearby displays (a 56 dp and a 64 dp avatar use one copy). 0 means the full photo. */
    fun bucket(pixels: Int?): Int {
        if (pixels == null) return 0
        return intArrayOf(96, 192, 384, 768).firstOrNull { it >= pixels } ?: 0
    }

    private fun key(name: String, bucket: Int, blur: Int) = "$name@${bucket}b$blur"

    /**
     * The request for a bundled photo drawn with a shorter side of [sidePx] pixels (null: full
     * size), blurred by [blurFraction] of its shorter side. A blurred copy is always 192 px:
     * `fraction` keeps it looking like a live blur of `fraction × side` on screen.
     */
    fun request(context: PlatformContext, name: String, sidePx: Int?, blurFraction: Float = 0f): ImageRequest {
        val blurred = blurFraction > 0f
        val bucket = if (blurred) 192 else bucket(sidePx)
        val blur = if (blurred) (192 * blurFraction).roundToInt() else 0
        val builder = ImageRequest.Builder(context)
            .data(BundledImages.resource(name) ?: name)
            .memoryCacheKey(key(name, bucket, blur))
            .size(if (bucket > 0) Size(bucket, bucket) else Size.ORIGINAL)
            .scale(Scale.FILL)
        if (blur > 0) transformEngine(builder, null, blur)
        return builder.build()
    }

    /**
     * Whether the full-size photo is already prepared (prewarmed). Otherwise the view falls back to
     * the platform's own decode rather than decoding on the spot (a stack of hidden photos, like the
     * welcome carousel, would all decode at once).
     */
    fun isPrepared(context: PlatformContext, name: String): Boolean =
        SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(key(name, 0, 0))) != null

    /** Prepares photos off the main thread so the first screens draw them without decoding. */
    fun prewarm(
        context: PlatformContext,
        density: Density,
        full: List<String>,
        small: List<Pair<String, Dp>>,
        blurred: List<Pair<String, Float>>,
    ) {
        val loader = SingletonImageLoader.get(context)
        full.forEach { loader.enqueue(request(context, it, null)) }
        small.forEach { (name, side) -> loader.enqueue(request(context, name, with(density) { side.roundToPx() })) }
        blurred.forEach { (name, fraction) -> loader.enqueue(request(context, name, null, fraction)) }
    }

    // MARK: Photos on the server or on this phone

    /**
     * The request for a photo drawn in a frame of [width] × [height] pixels: `/…` is a file on this
     * phone, `http…` a photo on the server, downloaded at the copy the frame needs ([closest]). Decoded
     * at the frame's size ([Renditions.decodeSize]), aspect fill, then cropped to it ([fill]; otherwise
     * fitted whole, the media viewer). [blur]: a radius as a share of the photo's longer side, applied
     * once when it's decoded. Cached by the object and width ([PhotoUrls.canonical]), never by its
     * signed link. [variant]: a rendition kept apart in the caches (the server's blurred copy of a
     * like), so it can never be served for another rendition of the same object, or the other way
     * round. Looks on disk, off the main thread; [cachedRequest] answers at once when it's in memory.
     */
    suspend fun remoteRequest(
        context: PlatformContext,
        name: String,
        width: Int,
        height: Int,
        priority: Images.Priority = Images.Priority.NORMAL,
        blur: Float = 0f,
        variant: String? = null,
        fill: Boolean = true,
        detail: Boolean = false,
    ): ImageRequest = cachedRequest(context, name, width, height, priority, blur, variant, fill, detail) ?: withContext(Dispatchers.IO) {
        val pixels = PixelSize(width, height)
        make(context, closest(context, name, fullWidth(name, pixels, detail), variant), Renditions.decodeSize(pixels), priority, blur, variant, fill)
    }

    /**
     * [remoteRequest] without looking on disk: a copy already decoded in memory for this frame (the
     * first that is among [Renditions.candidates]), or a file on this phone; null otherwise. Cheap enough
     * for composition, so a photo in memory shows on the first frame.
     */
    fun cachedRequest(
        context: PlatformContext,
        name: String,
        width: Int,
        height: Int,
        priority: Images.Priority = Images.Priority.NORMAL,
        blur: Float = 0f,
        variant: String? = null,
        fill: Boolean = true,
        detail: Boolean = false,
    ): ImageRequest? {
        val pixels = PixelSize(width, height)
        val decode = Renditions.decodeSize(pixels)
        if (name.startsWith("/")) return make(context, name, decode, priority, blur, variant, fill)
        val memory = SingletonImageLoader.get(context).memoryCache ?: return null
        for (candidate in Renditions.candidates(fullWidth(name, pixels, detail))) {
            val url = Images.sized(name, candidate) ?: continue
            val request = make(context, url, decode, priority, blur, variant, fill)
            if (isInMemory(memory, request)) return request
        }
        return null
    }

    /** Whether [request]'s decoded copy is in memory. */
    fun isInMemory(context: PlatformContext, request: ImageRequest): Boolean =
        SingletonImageLoader.get(context).memoryCache?.let { isInMemory(it, request) } == true

    private fun isInMemory(memory: MemoryCache, request: ImageRequest): Boolean =
        request.memoryCacheKey?.let { memory[MemoryCache.Key(it)] } != null

    /**
     * A small copy of a server photo for the same frame ([Renditions.previewWidth]), decoded at a third
     * of its pixels: shown first on a slow connection, under the right copy while it arrives.
     */
    fun preview(context: PlatformContext, name: String, width: Int, height: Int): ImageRequest? {
        val url = previewURL(name, width, height) ?: return null
        val decode = Renditions.decodeSize(PixelSize(width / 3.0, height / 3.0))
        // Ahead of every full copy but the card in play's: on a slow line, it's what keeps up with the swipes.
        return make(context, url, decode, Images.Priority.VERY_HIGH, blur = 0f, variant = null, fill = true)
    }

    /**
     * The width a full copy is chosen for: what the frame needs, at most the everyday width unless it's an
     * open profile's photo ([detail], [Renditions.asked]), then a step lighter on a slow line (a copy
     * already here still wins, [closest]): sooner beats sharper there. The decode size stays the frame's:
     * a smaller copy simply isn't scaled up.
     */
    private fun fullWidth(name: String, pixels: PixelSize, detail: Boolean): Double {
        val needed = Renditions.asked(Renditions.neededWidth(pixels, MediaPreviews.aspect(name)), detail)
        return if (NetworkQuality.shared.isSlow) needed * Renditions.limitedShare else needed
    }

    private fun priorityHeaders(priority: Images.Priority, photo: String) =
        NetworkHeaders.Builder()
            .set(Images.PRIORITY_HEADER, priority.ordinal.toString())
            .set(Images.PHOTO_HEADER, photo)
            .build()

    private fun previewURL(name: String, width: Int, height: Int): String? {
        if (!name.startsWith("http")) return null
        val needed = Renditions.neededWidth(PixelSize(width, height), MediaPreviews.aspect(name))
        return Images.sized(name, Renditions.previewWidth(needed))
    }

    /**
     * A server photo about to show, fetched to disk (`PhotoWindow`): the copy [remoteRequest] picks for
     * the same frame, under the same disk key (only the decode differs, which no disk key depends on), so
     * the card finds it there. Decoded tiny and never kept in memory: it's decoded at its display size once
     * it's drawn. Null when that copy is already on this phone. Looks on disk: off the main thread.
     */
    fun prefetchRequest(
        context: PlatformContext,
        name: String,
        width: Int,
        height: Int,
        priority: Images.Priority,
        detail: Boolean = false,
    ): ImageRequest? {
        if (!name.startsWith("http")) return null
        val url = closest(context, name, fullWidth(name, PixelSize(width, height), detail), null)
        if (isOnDisk(context, cacheID(url, null))) return null
        return ImageRequest.Builder(context)
            .data(url)
            .diskCacheKey(cacheID(url, null))
            .httpHeaders(priorityHeaders(priority, cacheID(url, null)))
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(Size(64, 64))
            .precision(Precision.INEXACT)
            .build()
    }

    private fun make(
        context: PlatformContext,
        url: String,
        decode: PixelSize,
        priority: Images.Priority,
        blur: Float,
        variant: String?,
        fill: Boolean,
    ): ImageRequest {
        val local = url.startsWith("/")
        val w = decode.width.toInt()
        val h = decode.height.toInt()
        val key = if (local) url else cacheID(url, variant)
        val radius = if (blur > 0f) maxOf(1, (blur * maxOf(w, h)).toInt()) else 0
        val builder = ImageRequest.Builder(context)
            .data(if (local) File(url) else url)
            .memoryCacheKey("$key@${w}x$h${if (fill) "" else "fit"}b$radius")
            .size(Size(w, h))
            .scale(if (fill) Scale.FILL else Scale.FIT)
            // Never larger than the photo: a small copy stays small (its frame scales it up when drawn).
            .precision(Precision.INEXACT)
        if (!local) {
            builder.diskCacheKey(key)
            builder.httpHeaders(priorityHeaders(priority, key))
        }
        // Aspect fill: the copy covers the frame whatever its proportions; then cropped to it, so memory
        // never keeps the edges the frame hides.
        transformEngine(builder, if (fill) IntSize(w, h) else null, radius)
        return builder.build()
    }

    /** The caches' key: the object and width, whatever the signature. */
    private fun cacheID(url: String, variant: String?): String {
        val key = PhotoUrls.canonical(url)
        return variant?.let { "$key#$it" } ?: key
    }

    /**
     * The copy to show for [needed] pixels of width: the first of [Renditions.candidates] already on
     * this phone (a larger copy beats a download), otherwise the one covering it. Looks on disk (under the
     * disk cache's lock): never on the main thread.
     */
    private fun closest(context: PlatformContext, name: String, needed: Double, variant: String?): String {
        val candidates = Renditions.candidates(needed)
        for (width in candidates) {
            val url = Images.sized(name, width) ?: continue
            if (isOnDisk(context, cacheID(url, variant))) return url
        }
        return Images.sized(name, candidates.first()) ?: name
    }

    private fun isOnDisk(context: PlatformContext, key: String): Boolean =
        SingletonImageLoader.get(context).diskCache?.openSnapshot(key)?.use { true } == true
}

/** Hooks the data layer sets for photos on the server. */
object PhotoUrls {
    /**
     * The object a link points to (and the width asked of the media Worker), without its signature: the
     * cache key, stable when the link is renewed.
     */
    @Volatile
    var canonical: (url: String) -> String = MediaURL::canonical

    /** The ThumbHash preview of a server photo, drawn while it loads (`MediaPreviews`); null when unknown. */
    @Volatile
    var preview: (url: String) -> ImageBitmap? = { url -> MediaPreviews.image(url)?.let(::previewBitmap) }

    private val previews = object : LinkedHashMap<ThumbHash.Image, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbHash.Image, ImageBitmap>?) = size > 300
    }

    /** A ThumbHash's RGBA pixels (about 32 × 32) as a bitmap, made once per preview. */
    private fun previewBitmap(image: ThumbHash.Image): ImageBitmap = synchronized(previews) {
        previews.getOrPut(image) {
            val bitmap = ImageBitmap(image.width, image.height)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val rgba = image.rgba
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val i = (y * image.width + x) * 4
                    paint.color = Color(
                        red = rgba[i].toInt() and 0xFF,
                        green = rgba[i + 1].toInt() and 0xFF,
                        blue = rgba[i + 2].toInt() and 0xFF,
                        alpha = rgba[i + 3].toInt() and 0xFF,
                    )
                    canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
                }
            }
            bitmap
        }
    }
}

/**
 * Photos and video posters sent in chat (kept as bytes on the message): their size is read from the
 * header, without decoding, and the pixels are decoded in the background at the bubble's size.
 */
object MessageImage {
    /** Reads the upright pixel size from the header (installed on Android at launch). */
    @Volatile
    var sizeReader: (ByteArray) -> IntSize? = { null }

    /** Pixel size, upright (EXIF orientation applied), from the image header only. */
    fun size(data: ByteArray): IntSize? = sizeReader(data)

    fun request(context: PlatformContext, id: String, data: ByteArray, sidePx: Int): ImageRequest =
        ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey("message-$id-$sidePx")
            .size(Size(sidePx, sidePx))
            .scale(Scale.FILL)
            .build()
}

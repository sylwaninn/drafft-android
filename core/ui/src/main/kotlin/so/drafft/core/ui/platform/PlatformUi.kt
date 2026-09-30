package so.drafft.core.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import so.drafft.core.ui.theme.DS

/**
 * The composable pieces that need the Android SDK or an Android-only library (Media3, windows,
 * RenderEffect), behind one interface so shared code stays platform-neutral. The Android
 * implementation (`AndroidPlatformUi`, src/android) is provided at the root through [LocalPlatformUi].
 * Screens call the top-level wrappers ([VideoPlayer]...), not the members.
 *
 * Later additions (camera preview, pickers, permission prompts, native share) join here the same way.
 */
@Stable
interface PlatformUi {
    /**
     * Whether pinned edges blur the content scrolling under them (Android 12+, RenderEffect), or
     * fall back to a soft scrim of the page colour. Read by `Modifier.progressiveBlur`.
     */
    val blursEdges: Boolean

    /** A video filling [modifier]'s frame (cropped like `scaledToFill`), no controls. */
    @Composable
    fun VideoPlayer(url: String, modifier: Modifier, autoplay: Boolean, muted: Boolean, loop: Boolean, contentScale: ContentScale)

    /**
     * A window over everything (sheets included), full screen and edge to edge, without a dim or a
     * window animation: `FullScreenCover` animates its content itself. System back calls
     * [onDismissRequest].
     */
    @Composable
    fun FullScreenWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

    /**
     * The ARGB pixels of a small rendered image (the edge-tone probe's 32 × 8 snapshot), or null.
     * On Android a layer snapshot is a hardware bitmap, which has to be copied before it's read.
     */
    fun pixels(image: ImageBitmap): IntArray?

    /**
     * The action that opens this app's page in the system Settings (the iPhone's
     * `UIApplication.openSettingsURLString`): location turned off, a permission refused for good.
     */
    @Composable
    fun rememberOpenAppSettings(): () -> Unit

    /**
     * The action that opens the phone's location settings, where its location switch is (Android only:
     * the iPhone can't open Location Services directly).
     */
    @Composable
    fun rememberOpenLocationSettings(): () -> Unit

    /**
     * The system photo picker, images only (the iPhone's `PhotosPicker(matching: .images)`): returns
     * the action that opens it. [onPicked] gets the chosen photo's bytes, read off the main thread;
     * nothing is called when the picker is cancelled or the photo can't be read.
     */
    @Composable
    fun rememberPhotoPicker(onPicked: (ByteArray) -> Unit): () -> Unit

    /**
     * The front camera for the selfie check (CameraX on Android): live face boxes and one photo when
     * asked. Made once per screen; [CameraPreview] shows its live picture.
     */
    @Composable
    fun rememberFrontCamera(): FrontCamera

    /** [camera]'s live picture, filling [modifier]'s frame (cropped like `resizeAspectFill`). */
    @Composable
    fun CameraPreview(camera: FrontCamera, modifier: Modifier)

    /**
     * Marks a code field as the one-time code from an SMS (Android's autofill offers the code that just
     * came in, like `textContentType(.oneTimeCode)`). Leaves [modifier] as is where there's no such hint.
     */
    fun smsCodeAutofill(modifier: Modifier): Modifier

    /**
     * A page kept alive in a web view (Cloudflare Turnstile): loaded and scripted by the caller, its
     * messages coming back through [WebPage.onMessage]. Made once per screen.
     */
    @Composable
    fun rememberWebPage(): WebPage

    /** [page]'s web view, placed in [modifier]'s frame (it can move from one place to another). */
    @Composable
    fun WebPageView(page: WebPage, modifier: Modifier)
    /**
     * The system photo picker for a chat: photos and videos, up to [maxSelection] (the iPhone's
     * `photosPicker(maxSelectionCount: 5, matching: .any(of: [.images, .videos]))`). Returns the action
     * that opens it. [onPicked] gets the items in the order picked, read off the main thread: a photo's
     * bytes, a video copied to a file of the app's; nothing when cancelled.
     */
    @Composable
    fun rememberMediaPicker(maxSelection: Int, onPicked: (List<PickedMedia>) -> Unit): () -> Unit

    /**
     * The system camera, photo or video (the person picks in the camera itself), full screen, with its
     * own shutter and review (the iPhone's `UIImagePickerController`). Returns the action that opens it,
     * or null when the phone has no camera (`CameraPicker.isAvailable`).
     */
    @Composable
    fun rememberCameraCapture(onCapture: (CameraCapture) -> Unit): (() -> Unit)?

    /** A video file's length and its first frame as a JPEG (at most 600 px), read off the main thread. */
    suspend fun videoInfo(url: String): VideoInfo?

    /** The system share sheet (the iPhone's `ShareLink`): returns the action that opens it for an item. */
    @Composable
    fun rememberShare(): (ShareItem) -> Unit
}

/** Harmless stand-in (previews, the JVM check): no blur, a night frame for video, covers drawn in place. */
object DefaultPlatformUi : PlatformUi {
    override val blursEdges: Boolean = false

    @Composable
    override fun VideoPlayer(url: String, modifier: Modifier, autoplay: Boolean, muted: Boolean, loop: Boolean, contentScale: ContentScale) {
        Box(modifier.background(DS.palette.night))
    }

    @Composable
    override fun FullScreenWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) { content() }
    }

    override fun pixels(image: ImageBitmap): IntArray? =
        IntArray(image.width * image.height).also { image.readPixels(it) }

    @Composable
    override fun rememberOpenAppSettings(): () -> Unit = {}

    @Composable
    override fun rememberOpenLocationSettings(): () -> Unit = {}

    @Composable
    override fun rememberPhotoPicker(onPicked: (ByteArray) -> Unit): () -> Unit = {}

    @Composable
    override fun rememberFrontCamera(): FrontCamera = NoFrontCamera

    @Composable
    override fun CameraPreview(camera: FrontCamera, modifier: Modifier) {
        Box(modifier.background(DS.palette.nightRaised))
    }

    override fun smsCodeAutofill(modifier: Modifier): Modifier = modifier

    @Composable
    override fun rememberWebPage(): WebPage = NoWebPage

    @Composable
    override fun WebPageView(page: WebPage, modifier: Modifier) {
        Box(modifier)
    }
    @Composable
    override fun rememberMediaPicker(maxSelection: Int, onPicked: (List<PickedMedia>) -> Unit): () -> Unit = {}

    @Composable
    override fun rememberCameraCapture(onCapture: (CameraCapture) -> Unit): (() -> Unit)? = null

    override suspend fun videoInfo(url: String): VideoInfo? = null

    @Composable
    override fun rememberShare(): (ShareItem) -> Unit = {}
}

/** The platform's pieces, provided at the root (`AndroidPlatformUi` on Android). */
val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { DefaultPlatformUi }

/** A video that fills its frame, no controls: muted and looping by default, like the profile videos. */
@Composable
fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoplay: Boolean = true,
    muted: Boolean = true,
    loop: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
) {
    LocalPlatformUi.current.VideoPlayer(url, modifier, autoplay, muted, loop, contentScale)
}

/** A face found by the camera, in 0...1 of the upright frame (x from the left, y from the top). */
data class FaceBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    val midX: Float get() = x + width / 2
    val midY: Float get() = y + height / 2
}

/** A photo from [FrontCamera.capture]: upright and mirrored, as the person saw themselves. */
class CapturedPhoto(
    val image: ImageBitmap,
    /** Full-size JPEG of [image]. */
    val jpeg: ByteArray,
    /** The faces found in the photo itself. */
    val faces: List<FaceBox>,
)

/** The front camera (see [PlatformUi.rememberFrontCamera]). Runs its work off the main thread. */
@Stable
interface FrontCamera {
    /** Camera access: asks the first time. False once refused. */
    suspend fun requestAccess(): Boolean

    /** Starts the live picture and the face check. False when there's no front camera. */
    suspend fun start(): Boolean

    fun stop()

    /** Takes a photo, or null when the camera isn't running or the shot failed. */
    suspend fun capture(): CapturedPhoto?

    /** The faces seen on the live picture, a few times a second, on the main thread. */
    var onFaces: ((List<FaceBox>) -> Unit)?
}

private object NoFrontCamera : FrontCamera {
    override suspend fun requestAccess(): Boolean = true
    override suspend fun start(): Boolean = false
    override fun stop() = Unit
    override suspend fun capture(): CapturedPhoto? = null
    override var onFaces: ((List<FaceBox>) -> Unit)? = null
}

/** A page run in a web view (see [PlatformUi.rememberWebPage]). */
@Stable
interface WebPage {
    /** Loads [html] as if it came from [baseUrl]. Its script posts messages with `window.[bridge].postMessage(string)`. */
    fun load(html: String, baseUrl: String, bridge: String)

    /** Runs [script] in the page. */
    fun evaluate(script: String)

    /** Stops the page and empties it. */
    fun clear()

    /** Each message the page posts, on the main thread. */
    var onMessage: ((String) -> Unit)?
}

private object NoWebPage : WebPage {
    override fun load(html: String, baseUrl: String, bridge: String) = Unit
    override fun evaluate(script: String) = Unit
    override fun clear() = Unit
    override var onMessage: ((String) -> Unit)? = null
}

/** A photo or video picked for a chat (see [PlatformUi.rememberMediaPicker]). */
sealed interface PickedMedia {
    /** The photo's bytes as picked (compressed before sending). */
    class Photo(val data: ByteArray) : PickedMedia

    /** A copy of the video in the app's cache: a local path. */
    class Video(val path: String) : PickedMedia
}

/** What the camera took (see [PlatformUi.rememberCameraCapture]): a photo, or a video file in the app's cache. */
sealed interface CameraCapture {
    /** JPEG bytes. */
    class Photo(val jpeg: ByteArray) : CameraCapture

    /** A local path. */
    class Video(val path: String) : CameraCapture
}

/** A video's length in seconds, and its first frame (JPEG) for the bubble's poster. */
class VideoInfo(val duration: Double, val thumbnail: ByteArray?)

/** Something to share (see [PlatformUi.rememberShare]). */
sealed interface ShareItem {
    /** A photo, as JPEG bytes. */
    class Image(val data: ByteArray) : ShareItem

    /** A video: a local path (shared as the file) or a link. */
    class Video(val url: String) : ShareItem
}

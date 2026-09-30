package so.drafft.core.ui.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import coil3.request.transformations
import so.drafft.core.ui.components.ImageStore
import so.drafft.core.ui.components.MessageImage
import so.drafft.core.ui.components.StackBlurTransformation
import so.drafft.core.ui.theme.DS
import java.io.ByteArrayInputStream
import kotlinx.coroutines.launch

/**
 * The Android side of [PlatformUi], provided at the root: `LocalPlatformUi provides AndroidPlatformUi`.
 * Creating it installs the design system's Android engines ([installDrafftUi]); calling that at
 * launch too (Application.onCreate) lets the image prewarm use them before the first screen.
 */
object AndroidPlatformUi : PlatformUi {
    init {
        installDrafftUi()
    }

    /** RenderEffect needs Android 12; older versions get the page-colour scrim. */
    override val blursEdges: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @OptIn(UnstableApi::class)
    @Composable
    override fun VideoPlayer(url: String, modifier: Modifier, autoplay: Boolean, muted: Boolean, loop: Boolean, contentScale: ContentScale) {
        val context = LocalContext.current
        val player = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        LaunchedEffect(player, autoplay, muted, loop) {
            player.volume = if (muted) 0f else 1f
            player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.playWhenReady = autoplay
        }
        val presentation = rememberPresentationState(player)
        Box(modifier.clipToBounds().background(DS.palette.night), contentAlignment = Alignment.Center) {
            PlayerSurface(
                player = player,
                modifier = Modifier.resizeWithContentScale(contentScale, presentation.videoSizeDp),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
        }
    }

    @Composable
    override fun FullScreenWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                // The cover animates itself: no dim behind it, no window animation.
                window?.setDimAmount(0f)
                window?.setWindowAnimations(0)
            }
            content()
        }
    }

    override fun pixels(image: ImageBitmap): IntArray? {
        val bitmap = image.asAndroidBitmap()
        // A layer snapshot is a hardware bitmap: its pixels can't be read before a copy.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bitmap
        return IntArray(readable.width * readable.height).also {
            readable.getPixels(it, 0, readable.width, 0, 0, readable.width, readable.height)
        }
    }

    @Composable
    override fun rememberOpenAppSettings(): () -> Unit {
        val context = LocalContext.current
        return remember(context) {
            {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
    }

    @Composable
    override fun rememberPhotoPicker(onPicked: (ByteArray) -> Unit): () -> Unit {
        val context = LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val deliver = androidx.compose.runtime.rememberUpdatedState(onPicked)
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                } ?: return@launch
                deliver.value(bytes)
            }
        }
        return remember(launcher) {
            {
                launcher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            }
        }
    }

    @Composable
    override fun rememberFrontCamera(): FrontCamera {
        val context = LocalContext.current
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val camera = remember(context, owner) { AndroidFrontCamera(context, owner) }
        DisposableEffect(camera) { onDispose { camera.release() } }
        return camera
    }

    @Composable
    override fun CameraPreview(camera: FrontCamera, modifier: Modifier) {
        val view = (camera as? AndroidFrontCamera)?.previewView
        if (view == null) {
            Box(modifier.background(DS.palette.nightRaised))
            return
        }
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                // One preview view per camera: taken from wherever it was shown last.
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view
            },
            modifier = modifier.clipToBounds(),
        )
    }

    override fun smsCodeAutofill(modifier: Modifier): Modifier =
        modifier.semantics { contentType = ContentType.SmsOtpCode }

    @Composable
    override fun rememberWebPage(): WebPage {
        val context = LocalContext.current
        val page = remember(context) { AndroidWebPage(context) }
        DisposableEffect(page) { onDispose { page.destroy() } }
        return page
    }

    @Composable
    override fun WebPageView(page: WebPage, modifier: Modifier) {
        val web = page as? AndroidWebPage ?: return
        // One web view, placed in one frame at a time (hidden in the form, visible in the check sheet).
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                web.detach()
                web.webView
            },
            modifier = modifier,
        )
    }
    @Composable
    override fun rememberMediaPicker(maxSelection: Int, onPicked: (List<PickedMedia>) -> Unit): () -> Unit =
        rememberChatMediaPicker(maxSelection, onPicked)

    @Composable
    override fun rememberCameraCapture(onCapture: (CameraCapture) -> Unit): (() -> Unit)? = rememberSystemCamera(onCapture)

    override suspend fun videoInfo(url: String): VideoInfo? = readVideoInfo(url)

    @Composable
    override fun rememberShare(): (ShareItem) -> Unit = rememberShareSheet()
}

@Volatile
private var installed = false

/** Installs the Android engines the shared design system code calls: baked blurs, image header sizes. */
fun installDrafftUi() {
    if (installed) return
    installed = true
    ImageStore.blurEngine = { builder, radius -> builder.transformations(StackBlurTransformation(radius)) }
    MessageImage.sizeReader = ::imageSize
}

/** Pixel size, upright (EXIF orientation applied), from the image header only. */
private fun imageSize(data: ByteArray): IntSize? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return if (orientation >= 5) IntSize(options.outHeight, options.outWidth) else IntSize(options.outWidth, options.outHeight)
}

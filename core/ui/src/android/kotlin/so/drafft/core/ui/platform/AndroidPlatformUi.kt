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

package so.drafft.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.NetworkQuality
import so.drafft.core.data.media.PhotoDownloads
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion

/**
 * A photo on the server (`http…`) or picked on this phone (`/…`), through `ImageStore`: the copy the
 * frame needs, decoded in the background at the frame's size, capped caches. A copy already in memory
 * shows on the first frame; otherwise its ThumbHash preview ([PhotoUrls.preview]), or a sage tile,
 * stands in until it's there. On a slow connection a large frame first shows a small copy
 * ([ImageStore.preview]), sharp enough to read the photo, while the right one arrives.
 */
@Composable
internal fun LoadedPhoto(name: String, blur: Float, priority: Images.Priority, detail: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val boundedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val fallback = maxOf(boundedWidth, boundedHeight).takeIf { it > 0 } ?: 1440
        val width = boundedWidth.takeIf { it > 0 } ?: fallback
        val height = boundedHeight.takeIf { it > 0 } ?: fallback
        val large = blur == 0f && min(maxWidth, maxHeight) >= 200.dp
        val context = LocalPlatformContext.current
        val request = rememberPhotoRequest(name, width, height, priority, blur, detail = detail)
        val photo = request?.diskCacheKey
        LaunchedEffect(photo, priority) { if (photo != null) PhotoDownloads.shared.prioritize(photo, priority.ordinal) }
        val painter = if (request != null) rememberAsyncImagePainter(request, contentScale = ContentScale.Crop) else null
        val sharp = painter != null && painter.state.collectAsState().value is AsyncImagePainter.State.Success
        val preview = remember(name) { PhotoUrls.preview(name) }
        val small = if (large) remember(name, width, height) { ImageStore.preview(context, name, width, height) } else null

        // Layers stack, never swap: each sharper one fades in over the last, which stays underneath, so a
        // change of quality is a fade and never a flash of the empty tile. Each one fills exactly the frame
        // (cropped, centred), so the photo never shifts as a sharper one replaces it.
        Box(Modifier.fillMaxSize().background(DS.palette.canvasSoft))
        if (preview != null) {
            Image(preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (small != null && smallCopyApplies(context, small, sharp)) {
            SmallCopy(small, sharpMissing = !sharp)
        } else if (large && !sharp) {
            PhotoLoader(Modifier.align(Alignment.Center))
        }
        if (painter != null && request != null) FadingImage(painter, request, sharp)
    }
}

/**
 * The request for a photo drawn in a frame of [width] × [height] pixels ([ImageStore.remoteRequest]): at
 * once when its copy is in memory (or it's a file on this phone), otherwise once the disk was asked, off
 * the main thread (null until then). Not keyed by [priority]: a card moving up the deck keeps its download
 * running, raised. [detail]: an open profile's photo, which may take a copy wider than the everyday one.
 */
@Composable
fun rememberPhotoRequest(
    name: String,
    width: Int,
    height: Int,
    priority: Images.Priority = Images.Priority.NORMAL,
    blur: Float = 0f,
    variant: String? = null,
    fill: Boolean = true,
    detail: Boolean = false,
): ImageRequest? {
    val context = LocalPlatformContext.current
    val request = remember(name, width, height, blur, variant, fill, detail) {
        mutableStateOf(ImageStore.cachedRequest(context, name, width, height, priority, blur, variant, fill, detail))
    }
    LaunchedEffect(request) {
        if (request.value == null) request.value = ImageStore.remoteRequest(context, name, width, height, priority, blur, variant, fill, detail)
    }
    return request.value
}

/**
 * The small copy: on a limited connection while the sharp one is missing, or whenever it's already in
 * memory (fetched ahead by the deck's window, or shown before the sharp one arrived, which then fades in
 * over it).
 */
private fun smallCopyApplies(context: PlatformContext, small: ImageRequest, sharp: Boolean): Boolean =
    ImageStore.isInMemory(context, small) || (!sharp && NetworkQuality.shared.isLimited)

/** The small copy's layer, with the loader while it's on its way (and the sharp one too). */
@Composable
private fun SmallCopy(request: ImageRequest, sharpMissing: Boolean) {
    Box(Modifier.fillMaxSize()) {
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
        val state by painter.state.collectAsState()
        val ready = state is AsyncImagePainter.State.Success
        FadingImage(painter, request, ready)
        if (!ready && sharpMissing) PhotoLoader(Modifier.align(Alignment.Center))
    }
}

/**
 * A layer that fills the frame and fades in (0.2 s ease-out) once [ready]; ready when [request] is first
 * drawn (in memory): no fade.
 */
@Composable
private fun FadingImage(painter: AsyncImagePainter, request: ImageRequest, ready: Boolean) {
    val readyAtFirstFrame = remember(request) { ready }
    val alpha by animateFloatAsState(
        if (ready) 1f else 0f,
        if (readyAtFirstFrame) tween(0) else tween(200, easing = Motion.EaseOut),
        label = "photoFade",
    )
    Image(
        painter,
        contentDescription = null,
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = if (readyAtFirstFrame) 1f else alpha },
        contentScale = ContentScale.Crop,
    )
}

/**
 * A spinner over a large photo still showing only its blurred preview (never over the small copy: that
 * one is readable). It appears after a moment, so a photo that arrives quickly never flashes it.
 */
@Composable
private fun PhotoLoader(modifier: Modifier = Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        shown.animateTo(1f, tween(200, easing = Motion.EaseOut))
    }
    Box(modifier.graphicsLayer { alpha = shown.value }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        // Its soft shadow, so it reads on a light photo too (a plain faint ring where blur isn't available).
        CircularProgressIndicator(
            Modifier.size(PhotoLoaderSize).blur(6.dp, BlurredEdgeTreatment.Unbounded),
            color = Color.Black.copy(alpha = 0.35f),
            strokeWidth = 3.5.dp,
        )
        CircularProgressIndicator(Modifier.size(PhotoLoaderSize), color = Color.White, strokeWidth = 2.5.dp)
    }
}

private val PhotoLoaderSize = 24.dp

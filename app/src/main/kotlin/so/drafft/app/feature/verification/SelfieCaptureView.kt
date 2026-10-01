package so.drafft.app.feature.verification

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlin.math.abs
import kotlin.coroutines.cancellation.CancellationException
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.core.data.AccountModeration
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.media.PhotoCompressor
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.platform.CapturedPhoto
import so.drafft.core.ui.platform.FaceBox
import so.drafft.core.ui.platform.FrontCamera
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.semibold

// Ports Drafft/Features/Verification/SelfieCaptureView.swift. The camera (AVFoundation, Vision) is the
// design system's `FrontCamera` (CameraX and the platform's on-device face detector on Android).

/**
 * The selfie the drafft team asked for (hold `selfie`): the front camera, live, with an on-device face
 * check every few frames that one face fills the oval. The shutter only works once it does, and the
 * photo taken is checked again before it can be sent. Sending moves the account to review.
 */
@Composable
fun SelfieCaptureView(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    TrackScreen(Screen.SELFIE_VERIFICATION)
    LaunchedEffect(Unit) { Telemetry.track(AnalyticsEvent.SelfieVerificationStarted()) }
    val platform = LocalPlatformUi.current
    val camera = platform.rememberFrontCamera()
    val openSettings = platform.rememberOpenAppSettings()
    val backend = koinInject<Backend>()
    val app = LocalAppModel.current
    val model = remember(camera) { SelfieCaptureModel(camera, backend, app.moderation) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(model) { model.start() }
    DisposableEffect(model) { onDispose { model.stop() } }
    LaunchedEffect(model.sent) { if (model.sent) onDismiss() }

    NightSurface {
        Column(modifier.fillMaxSize().background(DS.palette.night)) {
            Column(
                Modifier
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = DS.Space.xl)
                    .padding(top = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
            ) {
                Row {
                    PressScaleButton(onClick = onDismiss, modifier = Modifier.size(44.dp), contentDescription = L("Close")) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(Color.White.copy(alpha = 0.14f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            DrafftIcon("close", size = 20.dp, tint = Color.White)
                        }
                    }
                }
                Viewfinder(model, camera)
                Hint(model)
                Spacer(Modifier.weight(1f))
            }
            Actions(model, onSend = { scope.launch { model.send() } }, onRetake = { scope.launch { model.retake() } },
                onShoot = { scope.launch { model.shoot() } }, onOpenSettings = openSettings)
        }
    }
}

/**
 * The camera, or the photo taken, in a 3:4 frame with the oval to put the face in. The frame's size
 * comes from the space, never from the photo (a filled photo would widen the whole screen).
 */
@Composable
private fun Viewfinder(model: SelfieCaptureModel, camera: FrontCamera) {
    val p = DS.palette
    val ready = model.framing == SelfieFraming.READY
    val ovalColor by animateColorAsState(if (ready) p.positive else Color.White.copy(alpha = 0.55f), Motion.select(), label = "oval")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(DS.Radius.xl)),
    ) {
        val shown: Any = when (val s = model.stage) {
            is SelfieCaptureModel.Stage.Captured -> s.photo
            is SelfieCaptureModel.Stage.Sending -> s.photo
            else -> s
        }
        Crossfade(shown, Modifier.fillMaxSize(), animationSpec = Motion.snappy(), label = "viewfinder") { what ->
            when (what) {
                is CapturedPhoto -> Image(what.image, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                SelfieCaptureModel.Stage.Live -> LocalPlatformUi.current.CameraPreview(camera, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize().background(p.nightRaised))
            }
        }
        if (model.stage == SelfieCaptureModel.Stage.Live) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp, vertical = 56.dp)
                    .clearAndSetSemantics { },
            ) {
                val width = 3.dp.toPx()
                drawOval(
                    color = ovalColor,
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                    style = Stroke(
                        width = width,
                        pathEffect = if (ready) null else PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx())),
                    ),
                )
            }
        }
    }
}

/** What to do now, in one raised block under the frame. */
@Composable
private fun Hint(model: SelfieCaptureModel) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.nightRaised, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.md)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(if (model.isCaptured) "sun" else "face-scan-square", animationSpec = Motion.snappy(), label = "hintIcon") { symbol ->
                DrafftIcon(symbol, size = 18.dp, tint = Color.White)
            }
        }
        val color by animateColorAsState(if (model.error == null) Color.White else p.negative, Motion.select(), label = "hintInk")
        AnimatedContent(
            targetState = model.hint,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(Motion.select()).togetherWith(fadeOut(Motion.select())) },
            label = "hint",
        ) { hint ->
            Text(branded(hint, brandWeight = FontWeight.ExtraBold), style = TextStyles.subheadline.semibold, color = color)
        }
    }
}

/** Pinned: the shutter (live), send or retake (taken), or the way to Settings (no camera access). */
@Composable
private fun Actions(
    model: SelfieCaptureModel,
    onSend: () -> Unit,
    onRetake: () -> Unit,
    onShoot: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DS.palette.night)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = DS.Space.xl)
            .padding(top = DS.Space.sm, bottom = DS.Space.xs),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
    ) {
        when (model.stage) {
            is SelfieCaptureModel.Stage.Captured, is SelfieCaptureModel.Stage.Sending -> {
                DrafftButton(onClick = onSend, enabled = !model.isSending) {
                    if (model.isSending) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = DS.palette.onAccentOnNight, strokeWidth = 2.5.dp)
                    } else {
                        Label("plain", L("Send my selfie"))
                    }
                }
                TextLinkButton(
                    text = L("Retake"),
                    onClick = onRetake,
                    color = Color.White,
                    style = TextStyles.body.semibold,
                    enabled = !model.isSending,
                    fullWidth = true,
                )
            }
            SelfieCaptureModel.Stage.Denied -> DrafftButton(onClick = onOpenSettings) {
                Label("settings", L("Open Settings"))
            }
            else -> DrafftButton(
                onClick = onShoot,
                enabled = model.stage == SelfieCaptureModel.Stage.Live && model.framing == SelfieFraming.READY && !model.shooting,
            ) {
                Label("camera", L("Take the selfie"))
            }
        }
    }
}

/** A button label: the symbol, then the words (SwiftUI's `Label` in a button). */
@Composable
private fun Label(symbol: String, text: String) {
    DrafftIcon(symbol, size = 20.dp, tint = LocalContentColor.current)
    Text(text, maxLines = 2)
}

// Framing

/** What the live check sees. Face boxes are in 0...1 of the upright frame. */
enum class SelfieFraming {
    NO_FACE, SEVERAL_FACES, TOO_FAR, OFF_CENTER, READY;

    val hint: String
        get() = when (this) {
            NO_FACE -> L("Place your face in the oval.")
            SEVERAL_FACES -> L("Just you in the frame.")
            TOO_FAR -> L("Come a little closer.")
            OFF_CENTER -> L("Center your face in the oval.")
            READY -> L("Perfect. Hold still and take it.")
        }

    companion object {
        fun evaluate(faces: List<FaceBox>): SelfieFraming {
            // Faces far in the background don't count as someone else in the picture.
            val near = faces.filter { it.width >= 0.12f }
            if (near.size > 1) return SEVERAL_FACES
            val face = near.firstOrNull() ?: return if (faces.isEmpty()) NO_FACE else TOO_FAR
            if (face.width < 0.3f) return TOO_FAR
            if (abs(face.midX - 0.5f) > 0.18f || abs(face.midY - 0.5f) > 0.22f) return OFF_CENTER
            return READY
        }
    }
}

// Model

@Stable
class SelfieCaptureModel(
    private val camera: FrontCamera,
    private val backend: Backend,
    private val moderation: AccountModeration,
) {
    sealed interface Stage {
        data object Starting : Stage
        data object Live : Stage
        data object Unavailable : Stage
        data object Denied : Stage
        class Captured(val photo: CapturedPhoto) : Stage
        class Sending(val photo: CapturedPhoto) : Stage
    }

    var stage: Stage by mutableStateOf(Stage.Starting)
        private set
    var framing by mutableStateOf(SelfieFraming.NO_FACE)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var shooting by mutableStateOf(false)
        private set

    /** Set once the selfie is sent: the view closes, the hold screen turns to review. */
    var sent by mutableStateOf(false)
        private set

    val isCaptured: Boolean get() = stage is Stage.Captured
    val isSending: Boolean get() = stage is Stage.Sending

    val hint: String
        get() {
            error?.let { return it }
            return when (stage) {
                Stage.Starting -> L("Opening the camera.")
                Stage.Unavailable -> L("The camera isn't available on this device.")
                Stage.Denied -> L("drafft needs the camera for your selfie. Allow it in Settings.")
                is Stage.Captured, is Stage.Sending -> L("Is your face clear and well lit?")
                Stage.Live -> framing.hint
            }
        }

    suspend fun start() {
        error = null
        if (!camera.requestAccess()) {
            stage = Stage.Denied
            return
        }
        camera.onFaces = { faces ->
            val framing = SelfieFraming.evaluate(faces)
            // A failed shot's message stays until the person moves.
            if (framing != this.framing) error = null
            this.framing = framing
        }
        stage = if (camera.start()) Stage.Live else Stage.Unavailable
    }

    fun stop() = camera.stop()

    /** Takes the photo, then checks it again: what's sent must show the face as the live check saw it. */
    suspend fun shoot() {
        if (stage != Stage.Live || framing != SelfieFraming.READY || shooting) return
        shooting = true
        try {
            Haptics.tap()
            val photo = camera.capture()
            // One face, big enough: the still may sit a little off the centre the live check wanted.
            if (photo == null || SelfieFraming.evaluate(photo.faces) !in setOf(SelfieFraming.READY, SelfieFraming.OFF_CENTER)) {
                error = L("We couldn't see your face clearly. Try again.")
                Haptics.warning()
                return
            }
            error = null
            camera.stop()
            stage = Stage.Captured(photo)
        } finally {
            shooting = false
        }
    }

    suspend fun retake() {
        stage = Stage.Starting
        start()
    }

    suspend fun send() {
        val photo = (stage as? Stage.Captured)?.photo ?: return
        stage = Stage.Sending(photo)
        error = null
        try {
            Telemetry.trace("media.upload", "selfie") { SelfieUpload.send(backend, photo) }
            Telemetry.track(AnalyticsEvent.SelfieVerificationSubmitted())
            Haptics.success()
            // The hold turns to review first: the camera closes onto the review screen, never back onto
            // the selfie request.
            moderation.load()
            sent = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.track(AnalyticsEvent.SelfieVerificationFailed(Telemetry.reason(e)))
            Telemetry.unexpected(e, "verification", "selfie")
            if (ServerMessage.code(e) == "not_requested") {
                // The team decided meanwhile (the hold was lifted or changed): nothing left to send here.
                moderation.load()
                sent = true
                return
            }
            error = if (generateSequence<Throwable>(e) { it.cause }.any { it is java.io.IOException }) {
                L("Couldn't connect. Check your connection and try again.")
            } else {
                L("Your selfie couldn't be sent. Try again.")
            }
            Haptics.warning()
            stage = Stage.Captured(photo)
        }
    }
}

// Upload

/**
 * Into the private bucket (only the drafft team reads it), then `submit_selfie`: the account goes to
 * review. Resized to 1,600 px: enough to compare with the photos, not a full-size portrait.
 */
object SelfieUpload {
    class Failed : Exception()

    suspend fun send(backend: Backend, photo: CapturedPhoto) {
        val id = backend.userID ?: throw Failed()
        val data = PhotoCompressor.prepare(photo.jpeg, maxPixelSize = 1600, quality = 0.82).data
        val path = "${id.toString().lowercase()}/${UUID.randomUUID().toString().lowercase()}.jpg"
        backend.client.storage.from("verification-selfies").upload(path, data) {
            contentType = ContentType.Image.JPEG
        }
        backend.rpc("submit_selfie", mapOf("p_path" to path))
    }
}

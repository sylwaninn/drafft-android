package so.drafft.app.feature.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.model.L
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.ImageStore
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.VerticalEdge
import so.drafft.core.ui.components.Wordmark
import so.drafft.core.ui.components.progressiveBlur
import so.drafft.core.ui.image.BundledImages
import so.drafft.core.ui.navigation.NavStackHost
import so.drafft.core.ui.navigation.rememberNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Auth/WelcomeView.swift (Wordmark lives in core:ui).

/** The welcome screen: the session photos full bleed, the wordmark, and the way in (sign up or log in). */
@Composable
fun WelcomeView(modifier: Modifier = Modifier) {
    val route = rememberNavStack(WelcomeRoot)
    // The stack's own background shows during pushes: make it ours.
    Box(modifier.fillMaxSize().background(DS.palette.night)) {
        NavStackHost(route) { r ->
            when (r) {
                AuthRoute.SIGN_UP_EMAIL -> SignUpView()
                AuthRoute.LOG_IN -> LogInView()
                is ConfirmEmailRoute -> ConfirmEmailView(r.email)
                is ResetPasswordRoute -> ResetPasswordView(r.email)
                else -> WelcomeRootView(onSignUp = { route.push(AuthRoute.SIGN_UP_EMAIL) }, onLogIn = { route.push(AuthRoute.LOG_IN) })
            }
        }
    }
}

/** The stack's root: the welcome screen itself. */
private object WelcomeRoot

/** Framed portrait crops, women and men alternating. The splash shows the same ones. */
val WelcomePhotos: List<String> = listOf("hero_1", "hero_2", "hero_3", "hero_4", "hero_5", "hero_6")

/**
 * Per photo, its own framing: a point of the photo (the athlete) pinned to a point of the
 * screen, zoomed in so it can move. The band between the wordmark and the panel (12 to 64 %
 * of the height) is where the athlete reads; each photo is framed its own way, and some let a
 * hand, a hold or a face run past the edge on purpose.
 */
val WelcomeFraming: Map<String, PhotoFraming> = mapOf(
    // Tennis: the player whole, face and swing in the band.
    "hero_1" to PhotoFraming(zoom = 1.12f, focus = Offset(0.5f, 0.48f), at = Offset(0.5f, 0.42f)),
    "hero_2" to PhotoFraming(zoom = 1.05f, focus = Offset(0.5f, 0.45f), at = Offset(0.5f, 0.42f)),
    // Marathon: a close portrait, off-centre, her waving hand cut by the left edge.
    "hero_3" to PhotoFraming(zoom = 1.45f, focus = Offset(0.62f, 0.46f), at = Offset(0.62f, 0.36f)),
    // Trail: wide action, the runner in the left third, the stride running into the panel.
    "hero_4" to PhotoFraming(zoom = 1.15f, focus = Offset(0.5f, 0.3f), at = Offset(0.4f, 0.3f)),
    // Bouldering: the reach, hands and holds high, her profile half past the left edge.
    "hero_5" to PhotoFraming(zoom = 1.25f, focus = Offset(0.42f, 0.52f), at = Offset(0.36f, 0.44f)),
    // Gravel: the rider small and off-centre in the forest, the road leading down into the panel.
    "hero_6" to PhotoFraming(zoom = 1.3f, focus = Offset(0.53f, 0.68f), at = Offset(0.62f, 0.56f)),
)

@Composable
private fun WelcomeRootView(onSignUp: () -> Unit, onLogIn: () -> Unit) {
    // The panel's height: the progressive blur under it starts just above its text.
    var panelHeight by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(DS.palette.night)) {
        // The photo runs edge to edge, as on the splash: the panel floats on it.
        HeroSlideshow(
            photos = WelcomePhotos,
            modifier = Modifier
                .fillMaxSize()
                .progressiveBlur(VerticalEdge.BOTTOM, band = { panelHeight }, maxRadius = 18.dp)
                .clearAndSetSemantics { },
        )
        WelcomeWordmark(Modifier.align(Alignment.TopStart))
        WelcomePanel(
            onSignUp = onSignUp,
            onLogIn = onLogIn,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { panelHeight = it.height.toFloat() },
        )
    }
}

@Composable
private fun WelcomeWordmark(modifier: Modifier) {
    val night = DS.palette.night
    val scrim = remember(night) { easedScrim(night, peak = 0.75f).reversedStops() }
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // design-lint: allow gradient - photo scrim under the wordmark (72 pt past it).
                val h = size.height + 72.dp.toPx()
                drawRect(Brush.verticalGradient(*scrim, startY = 0f, endY = h), size = Size(size.width, h))
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.lg)
            .clearAndSetSemantics { },
    ) {
        // The plain word: no trail on a photo.
        Wordmark(color = Color.White, trailStrength = 0f)
    }
}

@Composable
private fun WelcomePanel(onSignUp: () -> Unit, onLogIn: () -> Unit, modifier: Modifier) {
    val night = DS.palette.night
    val scrim = remember(night) { easedScrim(night, peak = 0.82f) }
    NightSurface {
        Column(
            modifier
                .fillMaxWidth()
                .drawBehind {
                    // Sized by the panel, so it follows the font size and the language. An eased night
                    // scrim (smoothstep, no visible edge) rises well above the words and carries their
                    // contrast; the progressive blur starts just above the text, so the photo stays
                    // sharp down to it.
                    // design-lint: allow gradient - photo scrim under the panel
                    val rise = 120.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(*scrim, startY = -rise, endY = size.height),
                        topLeft = Offset(0f, -rise),
                        size = Size(size.width, size.height + rise),
                    )
                }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.xl, bottom = DS.Space.sm),
            verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
        ) {
            Text(
                L("Turn your matches into sessions, and meet at the start line."),
                style = TextStyles.title3.semibold,
                color = Color.White,
            )
            DrafftButton(L("Sign up with email"), onClick = onSignUp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.xs, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Already training with us?"), style = TextStyles.subheadline, color = Color.White.copy(alpha = 0.72f))
                TextLinkButton(
                    L("Log in"),
                    onClick = onLogIn,
                    color = DS.palette.accentOnNight,
                    style = TextStyles.subheadline.semibold,
                )
            }
        }
    }
}

/** Night from clear to [peak], eased as a smoothstep so the scrim never shows where it starts. */
private fun easedScrim(night: Color, peak: Float): Array<Pair<Float, Color>> =
    Array(11) { i ->
        val t = i / 10f
        t to night.copy(alpha = peak * t * t * (3 - 2 * t))
    }

/** The same stops, running the other way (a scrim darkest at the top). */
private fun Array<Pair<Float, Color>>.reversedStops(): Array<Pair<Float, Color>> =
    Array(size) { i -> (1f - this[size - 1 - i].first) to this[size - 1 - i].second }

// MARK: - Slideshow

/**
 * Which hero photo is showing, shared by every slideshow: the splash hands over to the welcome
 * screen on the photo it was showing, and the welcome screen goes on from there.
 */
@Stable
class HeroRotation private constructor(count: Int, defaults: KeyValueStore) {
    var index by mutableIntStateOf(0)

    init {
        // A random first photo, never the one the previous launch opened on.
        val last = defaults.getString(LAST_START_KEY)?.toIntOrNull()
        index = (0 until count).filter { it != last }.randomOrNull() ?: 0
        defaults.putString(LAST_START_KEY, index.toString())
    }

    companion object {
        private const val LAST_START_KEY = "heroSlideshow.lastStart"
        private var instance: HeroRotation? = null

        fun shared(defaults: KeyValueStore): HeroRotation =
            instance ?: HeroRotation(WelcomePhotos.size, defaults).also { instance = it }
    }
}

/**
 * False while another slideshow covers this one (the welcome screen under the splash): it shows the
 * shared photo but leaves the turning to the one on top. The root provides it.
 */
val LocalHeroSlideshowLeads = compositionLocalOf { true }

/** Photos rotating on their own, one quick crossfade every few seconds; nothing to swipe or tap. */
@Composable
fun HeroSlideshow(
    photos: List<String>,
    modifier: Modifier = Modifier,
    interval: Duration = 4.seconds,
) {
    val reduceMotion = LocalReduceMotion.current
    val leads = LocalHeroSlideshowLeads.current
    val defaults = koinInject<KeyValueStore>()
    val rotation = remember { HeroRotation.shared(defaults) }
    val current = rotation.index

    Box(modifier.clipToBounds()) {
        photos.forEachIndexed { i, name ->
            val alpha by animateFloatAsState(
                if (i == current) 1f else 0f,
                if (reduceMotion) snap() else tween(450, easing = Motion.EaseInOut),
                label = "hero$i",
            )
            // The shown photo, the one fading out and the next one (ready before its turn); the
            // others wait undrawn.
            if (i == current || i == (current + 1) % photos.size || alpha > 0f) {
                FocusedPhoto(
                    name,
                    WelcomeFraming[name] ?: PhotoFraming(),
                    Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
                )
            }
        }
    }
    // Restarts on every turn and when this slideshow takes the lead: a full interval each time.
    LaunchedEffect(current, leads) {
        if (!leads) return@LaunchedEffect
        delay(interval)
        rotation.index = (rotation.index + 1) % photos.size
    }
}

/**
 * How a photo sits in its frame: filled, zoomed by [zoom], with its [focus] point moved to the
 * frame's [at] point as far as the photo's edges allow (it never shows past them). Points are unit
 * points (0...1 of the width and height).
 */
data class PhotoFraming(
    val zoom: Float = 1f,
    val focus: Offset = Offset(0.5f, 0.5f),
    val at: Offset = Offset(0.5f, 0.5f),
)

@Composable
private fun FocusedPhoto(name: String, framing: PhotoFraming, modifier: Modifier) {
    val res = BundledImages.resource(name) ?: return
    val context = LocalPlatformContext.current
    // Prepared at launch (ImageStore.prewarm): drawn from the decoded copy, never decoded here.
    val prepared = remember(name) { ImageStore.isPrepared(context, name) }
    val painter: Painter = if (prepared) {
        rememberAsyncImagePainter(remember(name) { ImageStore.request(context, name, null) })
    } else {
        painterResource(res)
    }
    Canvas(modifier.clipToBounds()) {
        val img = painter.intrinsicSize
        if (img.isUnspecified || img.width <= 0f || img.height <= 0f) return@Canvas
        val fill = maxOf(size.width / img.width, size.height / img.height)
        val w = img.width * fill * framing.zoom
        val h = img.height * fill * framing.zoom
        val x = minOf(0f, maxOf(size.width - w, size.width * framing.at.x - framing.focus.x * w))
        val y = minOf(0f, maxOf(size.height - h, size.height * framing.at.y - framing.focus.y * h))
        translate(x, y) { with(painter) { draw(Size(w, h)) } }
    }
}

/** The screens pushed from the welcome screen. */
enum class AuthRoute { SIGN_UP_EMAIL, LOG_IN }

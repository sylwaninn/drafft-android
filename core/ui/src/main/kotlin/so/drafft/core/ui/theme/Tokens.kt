package so.drafft.core.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.pow

/**
 * DESIGN.md palette, with the app's accent: graphite (near-black `#111214` in light mode, off-white
 * `#F2F3F5` in dark mode). Token names keep "lime" for the role (the one accent), as on iOS.
 * Every token resolves for the current appearance ([LocalDarkTheme]) and, for the page and block
 * tones, for where it sits ([LocalIsSheetSurface]).
 */
object DS {
    val palette: Palette
        @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Palette.Dark else Palette.Light

    /** Spacing (4 pt base). */
    object Space {
        val xxs = 2.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
        val xxxl = 48.dp
    }

    object Radius {
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
    }
}

private fun hex(v: Long): Color = Color(0xFF000000 or v)

/** One appearance's colours. Read through `DS.palette` in composables. */
class Palette private constructor(val isDark: Boolean) {
    private fun c(light: Long, dark: Long) = if (isDark) hex(dark) else hex(light)

    val lime = c(0x111214, 0xF2F3F5)
    val limeActive = c(0x3A3E42, 0xD9DBDE)
    val limeNeutral = c(0x55595E, 0xC4C7CB)
    val limePale = c(0xE4E6E9, 0x26292C)

    /** Text and glyphs on the accent. */
    val onLime = c(0xFFFFFF, 0x111214)

    /** The accent as text or a glyph on neutral surfaces: links, the selected tab, "Typing", the app tint. */
    val accentInk = c(0x111214, 0xF2F3F5)

    /** Selected fill on a night surface (plan rows, time options, icebreaker picks): a wash, never a frame. */
    val selectedOnNight = Color.White.copy(alpha = 0.16f)

    /** The accent on a night surface. Graphite is near-black, so there it turns white and what sits on it turns ink. */
    val accentOnNight = c(0xFFFFFF, 0xF2F3F5)
    val accentOnNightActive = hex(0xD9DBDE)
    val onAccentOnNight = hex(0x111214)

    /** "tempo" set on an accent fill: a light grey with graphite. */
    val tierOnAccent = c(0xB8BCC2, 0x55595E)

    /** "tempo" set on a night block: a light grey next to the white "drafft" (7.3:1 on night, 1.9:1 from the word). */
    val tierOnNight = hex(0xB8BCC2)

    /** Glyph discs sitting on an accent fill (the tier card, a picked sport tile). */
    val onLimeWash = onLime.copy(alpha = 0.22f)

    /** Liking stays green whatever the brand accent: like buttons, the heart pop, "Send like", like messages in chat. */
    val like = hex(0x9FE870)
    val likeActive = hex(0xCDFFAD)
    val onLike = hex(0x0E0F0C)

    val ink = c(0x0E0F0C, 0xF1F3EF)

    /** Text on an ink fill (tab badges). */
    val onInk = c(0xFFFFFF, 0x0E0F0C)
    val body = c(0x454745, 0xB9BCB7)

    /** Secondary text: at least 4.5:1 on every surface, sage wells and raised sheets included. */
    val mute = c(0x626461, 0x969994)

    /** The fixed white (lifted grey in dark mode), for the rare place that needs it whatever the surface. */
    val white = c(0xFFFFFF, 0x1A1C18)

    /** Every input: the lifted white on a page, in a sheet's sage well, anywhere. */
    val field = white

    /** The page tone (a cool light grey with graphite). */
    val sage = c(0xEEEFF1, 0x0E0F10)
    val hairline = c(0xDADCE0, 0x2A2C2F)

    /**
     * The empty-state stickers are paper: the same in light and dark mode. A white die-cut edge, the
     * sign in graphite's near-black whatever the mode, a grey back.
     */
    val stickerPaper = hex(0xFFFFFF)
    val stickerBack = hex(0xE2E4E7)
    val stickerInk = hex(0x111214)

    /** Always-dark surface for the polarity-flipped moments, lifted a step above the page in dark mode. */
    val night = c(0x2A2D31, 0x232528)
    val nightRaised = c(0x383B40, 0x2E3033)

    /** Hairline around coloured blocks: invisible in light mode, a faint edge in dark mode. */
    val blockEdge = if (isDark) Color.White.copy(alpha = 0.09f) else Color.Transparent

    /** Veil over the cards waiting behind the top one on Discover. */
    val deckVeil = c(0xEEEFF1, 0x1B1C1E)

    /** Short modal sheets (confirmations, purchase confirmation, photo refused) sit a step above anything under them. */
    val sheetRaised = c(0xFFFFFF, 0x2A2D26)

    val positive = hex(0x2EAD4B)
    val positiveDeep = c(0x054D28, 0x7FD493)
    val warning = hex(0xFFD11A)

    /** A paused profile: the strip under the You card and the switch that pauses it. */
    val paused = warning
    val onPaused = hex(0x0E0F0C)
    val negative = hex(0xD03238)
    val accentOrange = hex(0xFFC091)
    val accentCyan = hex(0x38C8FF)

    /** One tone per sport in a person's week (the split bar and each row's disc), in list order. */
    val sportTones = listOf(accentCyan, hex(0x9FE870), hex(0xB5ADFE), hex(0xFFD84D), hex(0xFF9EC4), Color.White)

    /** Blocks (white cards on the sage page). In a sheet they become sage wells. */
    val canvas: Color
        @Composable @ReadOnlyComposable get() = if (LocalIsSheetSurface.current) sage else white

    /** The page (sage). In a sheet it's the lifted white. */
    val canvasSoft: Color
        @Composable @ReadOnlyComposable get() = if (LocalIsSheetSurface.current) white else sage

    /** The accent where content sits: `accentOnNight` on a night surface, `lime` elsewhere. */
    val accentHere: Color
        @Composable @ReadOnlyComposable get() = if (LocalIsNightSurface.current) accentOnNight else lime

    /** What sits on [accentHere]. */
    val onAccentHere: Color
        @Composable @ReadOnlyComposable get() = if (LocalIsNightSurface.current) onAccentOnNight else onLime

    companion object {
        val Light = Palette(isDark = false)
        val Dark = Palette(isDark = true)
    }
}

/** The appearance in use (the system's, set once at the root). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Content sits in a sheet: page and block tones flip (see `Palette.canvas`). */
val LocalIsSheetSurface = compositionLocalOf { false }

/** Content sits on a night surface: components swap the accent for `accentOnNight`. */
val LocalIsNightSurface = compositionLocalOf { false }

/**
 * Motion: everything answers fast. iOS springs are given as response and damping fraction; Compose
 * springs take a stiffness, derived here so each curve lands at the same time.
 */
object Motion {
    /** stiffness for an iOS spring response (seconds): k = (2π / response)². */
    private fun stiffness(response: Double): Float = (2 * PI / response).pow(2).toFloat()

    fun <T> springOf(response: Double, damping: Float, visibilityThreshold: T? = null) =
        spring(dampingRatio = damping, stiffness = stiffness(response), visibilityThreshold = visibilityThreshold)

    /** Selection feedback (chips, tiles, discs): 0.1 s ease-out on the colour, never a spring. */
    fun <T> select(): FiniteAnimationSpec<T> = tween(100, easing = EaseOut)
    fun <T> snappy(): FiniteAnimationSpec<T> = springOf(0.22, 0.86f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = springOf(0.3, 0.72f)
    fun <T> gentle(): FiniteAnimationSpec<T> = tween(180, easing = EaseOut)

    /** Progress that should be seen moving (sign-up stepper bars): longer than snappy, no overshoot. */
    fun <T> progress(): FiniteAnimationSpec<T> = springOf(0.5, 0.9f)

    /** Swipe fly-out. */
    const val FLY_OUT_MILLIS = 260

    /** SwiftUI's ease-out curve. */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val EaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
}

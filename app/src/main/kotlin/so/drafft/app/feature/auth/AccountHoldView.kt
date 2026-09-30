package so.drafft.app.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import so.drafft.app.feature.verification.SelfieCaptureView
import so.drafft.app.feature.verification.SupportSheet
import so.drafft.core.data.AccountHold
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateIllustration
import so.drafft.core.ui.components.FullScreenCover
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.Wordmark
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Auth/AccountHoldView.swift.

/**
 * The whole app while the account is on hold: why, what it means, and the only ways out (a selfie
 * when one is asked, support, log out). No close button. A check that clears lets the person straight
 * back in, where they were.
 */
@Composable
fun AccountHoldView(hold: AccountHold, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val reduceMotion = LocalReduceMotion.current
    var appeared by remember { mutableStateOf(false) }
    var askingHelp by remember { mutableStateOf(false) }
    var confirmingLogOut by remember { mutableStateOf(false) }
    var takingSelfie by remember { mutableStateOf(false) }
    val p = DS.palette

    val title = when (hold) {
        AccountHold.REVIEW -> L("We're checking your account.")
        AccountHold.SELFIE -> L("Show us it's you.")
        AccountHold.BANNED -> L("Your account has been closed.")
    }
    val message = when (hold) {
        AccountHold.REVIEW ->
            L("Our team needs a little time to make sure your account follows the drafft rules. It usually takes less than 48 hours.")
        AccountHold.SELFIE -> L("Our team needs a quick selfie to check that your photos are really you.")
        AccountHold.BANNED -> L("It broke the drafft community rules. This decision is final.")
    }
    val points: List<Pair<String, String>> = when (hold) {
        AccountHold.REVIEW -> listOf(
            "eye.slash.fill" to L("Your profile is hidden while we check."),
            "bubble.left.and.bubble.right.fill" to L("Your matches and chats are kept."),
            "lock.open.fill" to L("The app opens again by itself once it's done."),
        )
        AccountHold.SELFIE -> listOf(
            "person.crop.square.fill" to L("Just your face, well lit, nothing covering it."),
            "lock.fill" to L("Only the drafft team sees it, never other members."),
            "eye.slash.fill" to L("Your profile is hidden until then."),
        )
        AccountHold.BANNED -> listOf(
            "eye.slash.fill" to L("Your profile and chats are no longer visible."),
            "person.crop.circle.badge.xmark" to L("You can't create a new drafft account."),
        )
    }
    val art = when (hold) {
        AccountHold.REVIEW -> HoldArt.review
        AccountHold.SELFIE -> HoldArt.selfie
        AccountHold.BANNED -> HoldArt.closed
    }
    val tint = if (hold == AccountHold.BANNED) p.negative else p.accentOnNight

    Box(modifier.fillMaxSize().background(p.night)) {
        NightSurface {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                ) {
                    val viewport = maxHeight
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            // At least the screen's height: the wordmark at the top, the rest down by the buttons.
                            .heightIn(min = viewport)
                            .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.xl),
                        verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
                    ) {
                        Wordmark(
                            modifier = Modifier.clearAndSetSemantics { }.rise(appeared, 0, reduceMotion),
                            size = 26f,
                            color = Color.White,
                            trail = p.accentOnNight,
                        )
                        Spacer(Modifier.weight(1f).heightIn(min = DS.Space.lg))
                        // The empty screens' sign, its outline lined up with the text.
                        Box(Modifier.fillMaxWidth().rise(appeared, 1, reduceMotion)) {
                            // (200 - 54) / 2: the sign sits in the middle of its 200 pt map.
                            EmptyStateIllustration(art, Modifier.offset(x = (-73).dp), tint = tint)
                        }
                        Column(
                            Modifier.rise(appeared, 2, reduceMotion),
                            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                        ) {
                            Text(
                                title,
                                Modifier.semantics { heading() },
                                style = display(40f),
                                color = if (hold == AccountHold.BANNED) Color.White else p.accentOnNight,
                            )
                            Text(branded(message), style = TextStyles.body, color = Color.White.copy(alpha = 0.75f))
                        }
                        PointsBlock(points, Modifier.rise(appeared, 3, reduceMotion))
                    }
                }
                // Pinned at the bottom: the selfie when one is asked, help while in review, logging out once closed.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.night)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.sm, bottom = DS.Space.xs)
                        .rise(appeared, 4, reduceMotion),
                    verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
                ) {
                    when (hold) {
                        AccountHold.SELFIE -> {
                            DrafftButton(onClick = { takingSelfie = true }) {
                                ButtonIcon("camera.fill")
                                Text(L("Take my selfie"), maxLines = 2)
                            }
                            LogOutLink { confirmingLogOut = true }
                        }
                        AccountHold.REVIEW -> {
                            DrafftButton(onClick = { askingHelp = true }, kind = DrafftButtonKind.SECONDARY) {
                                ButtonIcon("questionmark.bubble.fill")
                                Text(L("Get help"), maxLines = 2)
                            }
                            LogOutLink { confirmingLogOut = true }
                        }
                        AccountHold.BANNED -> {
                            DrafftButton(onClick = { app.signOut() }, kind = DrafftButtonKind.SECONDARY) {
                                ButtonIcon("rectangle.portrait.and.arrow.right")
                                Text(L("Log out"), maxLines = 2)
                            }
                            TextLinkButton(
                                L("A mistake? Contact us"),
                                onClick = { askingHelp = true },
                                color = Color.White,
                                style = TextStyles.body.semibold,
                                fullWidth = true,
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { appeared = true }

    DrafftSheet(visible = askingHelp, onDismissRequest = { askingHelp = false }) {
        SupportSheet(topic = if (hold == AccountHold.BANNED) L("Closed account") else L("Account check"))
    }
    // Only while a selfie is asked: once it's sent, the hold turns to review and the camera goes with it.
    FullScreenCover(visible = takingSelfie && hold == AccountHold.SELFIE, onDismissRequest = { takingSelfie = false }) {
        SelfieCaptureView(onDismiss = { takingSelfie = false })
    }
    DrafftConfirm(
        visible = confirmingLogOut,
        onDismissRequest = { confirmingLogOut = false },
        icon = "rectangle.portrait.and.arrow.right",
        title = L("Log out?"),
        message = if (hold == AccountHold.SELFIE) {
            L("Log back in any time to send your selfie.")
        } else {
            L("Log back in any time to see where the check is.")
        },
        actions = listOf(ConfirmAction(L("Log out"), ConfirmAction.Kind.DESTRUCTIVE) { app.signOut() }),
    )
}

/** What the hold means, in one raised night block. */
@Composable
private fun PointsBlock(points: List<Pair<String, String>>, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(DS.palette.nightRaised, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        points.forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                    // `.system(size: 15, weight: .semibold)`.
                    DrafftIcon(icon, size = (15f * 1.2f).dp, tint = Color.White)
                }
                Text(branded(text), Modifier.weight(1f), style = TextStyles.subheadline.medium, color = Color.White)
            }
        }
    }
}

@Composable
private fun LogOutLink(onClick: () -> Unit) {
    TextLinkButton(L("Log out"), onClick = onClick, color = Color.White, style = TextStyles.body.semibold, fullWidth = true)
}

@Composable
private fun ButtonIcon(symbol: String) {
    DrafftIcon(symbol, size = (17f * 1.2f).dp, tint = LocalContentColor.current)
}

private object HoldArt {
    val review = EmptyStateArt(symbol = "hourglass")
    val closed = EmptyStateArt(symbol = "nosign")
    val selfie = EmptyStateArt(symbol = "faceid")
}

/** Entrance: each part rises into place a beat after the one above it. */
@Composable
private fun Modifier.rise(appeared: Boolean, step: Int, reduceMotion: Boolean): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(appeared) {
        if (!appeared) return@LaunchedEffect
        delay(60L * step)
        progress.animateTo(1f, Motion.bouncy())
    }
    return graphicsLayer {
        val v = if (reduceMotion) 1f else progress.value
        alpha = v.coerceIn(0f, 1f)
        translationY = (1f - v) * 14.dp.toPx()
    }
}

// MARK: - Window

/**
 * The hold screen over everything the app shows (the iPhone gives it its own window, above sheets,
 * covers, the keyboard and banners): the root places it on top. It covers the app the moment the
 * hold arrives and lifts the same way; whatever was being typed gives way.
 */
@Composable
fun HoldLayer(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val hold = app.moderation.hold
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(hold != null) {
        if (hold != null) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    AnimatedContent(
        targetState = hold,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(Motion.gentle()) + scaleIn(Motion.gentle(), initialScale = 1.04f))
                .togetherWith(fadeOut(Motion.gentle()) + scaleOut(Motion.gentle(), targetScale = 1.04f))
        },
        contentKey = { it },
        label = "hold",
    ) { h ->
        if (h != null) AccountHoldView(hold = h)
    }
}

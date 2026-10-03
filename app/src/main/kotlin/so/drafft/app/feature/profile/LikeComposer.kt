package so.drafft.app.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.semibold

/** What a like points at. */
sealed interface LikeTarget {
    val id: String

    data class Photo(val name: String) : LikeTarget {
        override val id: String get() = "photo-$name"
    }

    data class Prompt(val prompt: ProfilePrompt) : LikeTarget {
        override val id: String get() = "prompt-${prompt.question}"
    }
}

/**
 * Like composer shown in place, over the profile: the liked item lifts forward on a dimmed,
 * blurred background, with a comment field and the send button right under it. Tap outside to cancel.
 * (The presenter blurs what's under it: Compose has no backdrop material.)
 */
@Composable
fun LikeComposer(
    target: LikeTarget,
    name: String,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val reduceMotion = LocalReduceMotion.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    val appeared = remember { Animatable(if (reduceMotion) 1f else 0f) }
    // Set on the way out: the veil stops taking touches the moment it starts fading, so a tap
    // right after closing reaches the profile underneath.
    var closing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val hasMessage = message.isNotBlank()

    LaunchedEffect(Unit) { appeared.animateTo(1f, Motion.bouncy()) }

    /** Fades out, then hands back (the presenter removes it without its own animation). */
    fun close(done: () -> Unit) {
        if (closing) return
        closing = true
        focusManager.clearFocus()
        scope.launch {
            launch { appeared.animateTo(0f, tween(160, easing = Motion.EaseOut)) }
            delay(if (reduceMotion) 0 else 160)
            done()
        }
    }

    fun cancel() {
        Haptics.tap()
        close(onCancel)
    }

    // System back is Cancel (over a profile sheet, it would otherwise close the whole sheet).
    BackHandler { cancel() }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (reduceMotion) 1f else appeared.value.coerceIn(0f, 1f) }
                .background(p.night.copy(alpha = 0.45f))
                .clickable(
                    remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !closing,
                    onClickLabel = L("Cancel like"),
                    role = Role.Button,
                ) { cancel() }
                .semantics { contentDescription = L("Cancel like") },
        )

        NightSurface {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = DS.Space.xl),
                verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))

                Box(
                    Modifier.graphicsLayer {
                        val a = appeared.value
                        val s = if (reduceMotion) 1f else 0.9f + 0.1f * a
                        scaleX = s
                        scaleY = s
                        alpha = a.coerceIn(0f, 1f)
                    },
                ) {
                    LikedItem(target, focused)
                }

                Column(
                    Modifier.graphicsLayer {
                        val a = appeared.value
                        alpha = a.coerceIn(0f, 1f)
                        translationY = if (reduceMotion) 0f else (1f - a) * 20.dp.toPx()
                    },
                    verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.canvas, RoundedCornerShape(22.dp))
                            .padding(horizontal = DS.Space.lg, vertical = 13.dp)
                            .onFocusChanged { focused = it.isFocused },
                        enabled = !closing,
                        textStyle = TextStyles.body.copy(color = p.ink),
                        maxLines = 4,
                        cursorBrush = SolidColor(p.accentInk),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box {
                                if (message.isEmpty()) Text(L("Add a comment"), style = TextStyles.body, color = p.mute)
                                inner()
                            }
                        },
                    )
                    DrafftButton(
                        onClick = {
                            Haptics.success()
                            val text = message.trim()
                            close { onSend(text) }
                        },
                        kind = DrafftButtonKind.LIKE,
                        enabled = !closing,
                    ) {
                        DrafftIcon("heart", size = symbol(17f), tint = p.onLike)
                        Crossfade(hasMessage, label = "sendLike") { with ->
                            Text(if (with) L("Send like with comment") else L("Send like"), maxLines = 2)
                        }
                    }
                    Text(L("%s only sees it if you match.", name), style = TextStyles.footnote, color = Color.White.copy(alpha = 0.75f))
                }

                Spacer(Modifier.weight(1f))
            }
        }

        PressScaleButton(
            onClick = { if (!closing) cancel() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(DS.Space.lg)
                .size(44.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentDescription = L("Cancel"),
        ) {
            DrafftIcon("close", size = symbol(17f), tint = Color.White)
        }
    }
}

@Composable
private fun LikedItem(target: LikeTarget, focused: Boolean) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl)
    when (target) {
        is LikeTarget.Photo -> {
            val height by animateDpAsState(if (focused) 200.dp else 360.dp, Motion.snappy(), label = "likedPhoto")
            Photo(
                target.name,
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(shape),
            )
        }
        is LikeTarget.Prompt -> Column(
            Modifier
                .fillMaxWidth()
                .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .background(p.canvas, shape)
                .padding(DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            Text(target.prompt.questionText, style = TextStyles.subheadline.semibold, color = p.body)
            Text(target.prompt.answer, style = displayBold(26f), color = p.ink)
        }
    }
}

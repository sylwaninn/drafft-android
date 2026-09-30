package so.drafft.app.feature.verification

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.auth.AuthProblem
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DisplayFont
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

// Ports Drafft/Features/Verification/OneTimeCodeEntry.swift.

/** The code's digits: the display font at a fixed size (points), so six always fit their boxes. */
private const val CODE_DIGIT_SIZE = 26f

/** What the status line under the boxes shows. */
private sealed interface CodeStatus {
    data object Checking : CodeStatus
    data class Problem(val text: String, val needsHelp: Boolean) : CodeStatus
    data class Hint(val text: String) : CodeStatus
}

/**
 * The 6-digit code step, shared by every check (SMS at sign-up and in You, email for a new address
 * or a new password): where it went with a way to change it, six boxes, the status line, resend.
 * [accessory] sits between the status line and Resend.
 */
@Composable
fun OneTimeCodeEntry(
    /** "+33 6 12 34 56 78" or "you@example.com". */
    destination: String,
    code: String,
    onCode: (String) -> Unit,
    busy: Boolean,
    error: String?,
    needsHelp: Boolean = false,
    helpTopic: String,
    /** Under the boxes when nothing's wrong ("Check your messages..."). */
    hint: String,
    resendIn: Int,
    editTitle: String = L("Change"),
    boxFill: Color = DS.palette.field,
    onEdit: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
    accessory: @Composable () -> Unit = {},
) {
    val p = DS.palette
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val platform = LocalPlatformUi.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                L("Code sent to %s", destination),
                Modifier.weight(1f),
                style = TextStyles.subheadline.semibold,
                color = p.ink,
            )
            TextLinkButton(
                text = editTitle,
                onClick = onEdit,
                style = TextStyles.subheadline.semibold,
                color = p.accentInk,
                enabled = !busy,
            )
        }

        // Six boxes drawn by the field itself, each digit centred in its box. The field is real and on
        // screen, so the keyboard's code suggestion and pasting both land in it.
        val digitStyle = TextStyle(
            fontFamily = DisplayFont.extraBold,
            fontWeight = FontWeight.ExtraBold,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { CODE_DIGIT_SIZE.dp.toSp() },
            color = p.ink,
            textAlign = TextAlign.Center,
        ).monospacedDigits
        BasicTextField(
            value = code,
            onValueChange = onCode,
            modifier = platform.smsCodeAutofill(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .revealsOnFocus(focused)
                    .semantics { contentDescription = L("Verification code") },
            ),
            singleLine = true,
            // The caret would sit after the last digit, in the previous box: the current box's outline
            // says where the next digit goes.
            textStyle = digitStyle.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            focus.requestFocus()
                            keyboard?.show()
                        },
                ) {
                    Row(
                        Modifier.fillMaxSize().clearAndSetSemantics { },
                        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                    ) {
                        repeat(6) { i ->
                            val current = i == code.length && focused
                            val stroke = when {
                                error != null -> p.negative
                                current -> p.ink
                                else -> p.ink.copy(alpha = 0.2f)
                            }
                            val shape = RoundedCornerShape(DS.Radius.md)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(boxFill, shape)
                                    .border(if (current || error != null) 2.dp else 1.dp, stroke, shape),
                                contentAlignment = Alignment.Center,
                            ) {
                                code.getOrNull(i)?.let { Text(it.toString(), style = digitStyle) }
                            }
                        }
                    }
                    // Kept for the IME and the selection; drawn transparent over the boxes.
                    Box(Modifier.size(1.dp)) { inner() }
                }
            },
        )

        // While checking, the status line says so (the boxes keep the typed code).
        val status: CodeStatus = when {
            busy -> CodeStatus.Checking
            error != null -> CodeStatus.Problem(error, needsHelp)
            else -> CodeStatus.Hint(hint)
        }
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(Motion.snappy()) + slideInVertically(Motion.snappy()) { -it / 2 }).togetherWith(fadeOut(Motion.snappy()))
            },
            contentKey = { it::class },
            label = "codeStatus",
        ) { s ->
            when (s) {
                CodeStatus.Checking -> Row(
                    Modifier.defaultMinSize(minHeight = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = p.ink, strokeWidth = 2.dp)
                    Text(L("Checking the code…"), style = TextStyles.footnote, color = p.body)
                }
                is CodeStatus.Problem -> Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        DrafftIcon("danger-circle", size = 16.dp, tint = p.negative)
                        Text(s.text, style = TextStyles.footnote.medium, color = p.negative)
                    }
                    if (s.needsHelp) GetHelpButton(helpTopic)
                }
                // Body grey: mute is under 4.5:1 on the sage page.
                is CodeStatus.Hint -> Text(s.text, style = TextStyles.footnote, color = p.body)
            }
        }

        accessory()

        TextLinkButton(onClick = onResend, enabled = resendIn <= 0 && !busy) {
            RollingText(
                if (resendIn > 0) L("Resend code in 0:%s", String.format(Locale.ROOT, "%02d", resendIn)) else L("Resend code"),
                style = TextStyles.subheadline.semibold.monospacedDigits,
                color = if (resendIn > 0) p.body else p.accentInk,
                // The seconds change in place, nothing rolls or fades each tick; only the switch to
                // "Resend code" cross-fades.
                rollsDigits = false,
            )
        }
    }

    // The step appears once a code was asked for: the keyboard comes up with it, once the step has
    // faded in.
    LaunchedEffect(Unit) {
        delay(400.milliseconds)
        focus.requestFocus()
        keyboard?.show()
    }
}

/** The check went through: an accent disc and what's now on the account. */
@Composable
fun CodeVerifiedCard(
    title: String,
    detail: String,
    fill: Color = DS.palette.canvas,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    Row(
        modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(p.lime, CircleShape), contentAlignment = Alignment.Center) {
            DrafftIcon("check", size = 20.dp, tint = p.onLime)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyles.headline, color = p.ink)
            Text(detail, style = TextStyles.subheadline.monospacedDigits, color = p.body)
        }
    }
}

/** Too many wrong codes: paused until the team unlocks it (the footer offers Get help). */
@Composable
fun CodeLockedCard(message: String?, modifier: Modifier = Modifier) {
    val p = DS.palette
    Column(
        modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            DrafftIcon("lock-keyhole-minimalistic", size = 20.dp, tint = p.negative)
            Text(L("Verification paused"), style = TextStyles.headline, color = p.negative)
        }
        Text(message ?: L("Too many tries."), style = TextStyles.subheadline, color = p.body)
        Text(L("Our team can unlock it for you."), style = TextStyles.subheadline, color = p.body)
    }
}

/** The transition the verified card comes in with (`.scale(0.96).combined(with: .opacity)`). */
internal val codeCardEnter = fadeIn(Motion.snappy()) + scaleIn(Motion.snappy(), initialScale = 0.96f)

// Email code

/**
 * A check by email: fill the form, a 6-digit code goes out, typing the sixth digit checks it.
 * The host says what sending and checking mean (a new address, a new password). [scope] is the
 * screen's (automatic check, the resend countdown): see [rememberEmailCodeModel].
 */
@Stable
class EmailCodeModel(
    private val scope: CoroutineScope,
    /** How long an email code works, in seconds (`BackendConfig.emailCodeLifetime`). */
    private val emailCodeLifetime: Double = 3600.0,
) {
    enum class Stage { FORM, CODE, DONE, LOCKED }

    var stage by mutableStateOf(Stage.FORM)
        private set
    var code by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    /** On the form while sending, under the boxes once the code is out. */
    var error by mutableStateOf<String?>(null)
    var needsHelp by mutableStateOf(false)
        private set
    var resendIn by mutableIntStateOf(0)
        private set
    var attemptsLeft by mutableIntStateOf(5)
        private set

    /**
     * When the last code went out: Supabase answers the same for a mistyped code and an old one, so the
     * time says which it was.
     */
    private var sentAt: Instant = Instant.MIN
    var sentTo by mutableStateOf("")
        private set

    /** Host wording for a problem ("Your password is incorrect." rather than the log-in message). */
    var messages: Map<AuthProblem, String> = emptyMap()

    /** Problems with what was typed on the form, not the code: they send the person back to it. */
    var formProblems: Set<AuthProblem> = emptySet()

    private var sender: (suspend () -> Unit)? = null
    private var checker: (suspend (String) -> Unit)? = null
    private var timer: Job? = null

    /** Sends the code, then waits for it. [send] throws to keep the form up with its error. */
    suspend fun send(to: String, send: suspend () -> Unit, verify: suspend (String) -> Unit) {
        if (busy) return
        busy = true
        error = null
        try {
            send()
            sender = send
            checker = verify
            sentTo = to
            code = ""
            attemptsLeft = 5
            stage = Stage.CODE
            startResendTimer()
            Haptics.success()
        } catch (e: CancellationException) {
            busy = false
            throw e
        } catch (e: Exception) {
            error = message(AuthProblem(e))
            Haptics.warning()
        }
        busy = false
    }

    /** The code already went out with something else (sign-up): wait for it, [resend] sends another. */
    fun awaitCode(sentTo: String, resend: suspend () -> Unit, verify: suspend (String) -> Unit) {
        sender = resend
        checker = verify
        this.sentTo = sentTo
        code = ""
        attemptsLeft = 5
        stage = Stage.CODE
        startResendTimer()
    }

    fun enterCode(raw: String) {
        val clean = raw.filter(Char::isDigit).take(6)
        val wasShort = code.length < 6
        code = clean
        if (code.isNotEmpty()) error = null
        if (code.length == 6 && wasShort && stage == Stage.CODE) scope.launch { verify() }
    }

    suspend fun verify() {
        val checker = checker
        if (code.length != 6 || busy || checker == null) return
        busy = true
        error = null
        try {
            checker(code)
            stage = Stage.DONE
            timer?.cancel()
            Haptics.success()
        } catch (e: CancellationException) {
            busy = false
            throw e
        } catch (e: Exception) {
            code = ""
            Haptics.warning()
            val problem = AuthProblem(e)
            val age = Duration.between(sentAt, Instant.now()).toMillis() / 1000.0
            if (problem == AuthProblem.WRONG_CODE && age > emailCodeLifetime) {
                // Past its lifetime: not a typo, and not a try used up.
                error = L("This code has expired. Send a new one.")
            } else if (problem == AuthProblem.WRONG_CODE) {
                attemptsLeft -= 1
                if (attemptsLeft <= 0) {
                    stage = Stage.LOCKED
                    timer?.cancel()
                    error = L("Too many wrong codes. For your security, this change is paused.")
                    needsHelp = true
                } else {
                    error = if (attemptsLeft == 1) L("Wrong code. 1 try left.") else L("Wrong code. %d tries left.", attemptsLeft)
                }
            } else if (problem in formProblems) {
                edit(error = message(problem))
            } else {
                error = message(problem)
            }
        }
        busy = false
    }

    suspend fun resend() {
        val sender = sender
        if (resendIn != 0 || busy || sender == null) return
        busy = true
        error = null
        try {
            sender()
            code = ""
            startResendTimer()
            Haptics.success()
        } catch (e: CancellationException) {
            busy = false
            throw e
        } catch (e: Exception) {
            error = message(AuthProblem(e))
            Haptics.warning()
        }
        busy = false
    }

    /** Back to the form (Change, or the server turned the new value down). */
    fun edit(error: String? = null) {
        timer?.cancel()
        resendIn = 0
        stage = Stage.FORM
        code = ""
        this.error = error
        needsHelp = false
    }

    private fun message(problem: AuthProblem): String = messages[problem] ?: problem.message

    /** A code just went out: its lifetime and the Resend countdown start now. */
    private fun startResendTimer() {
        timer?.cancel()
        sentAt = Instant.now()
        resendIn = 30
        timer = scope.launch {
            while (resendIn > 0 && isActive) {
                delay(1.seconds)
                resendIn -= 1
            }
        }
    }
}

/** An [EmailCodeModel] living as long as the calling screen (the iPhone's `@State var flow = EmailCodeModel()`). */
@Composable
fun rememberEmailCodeModel(): EmailCodeModel {
    val scope = rememberCoroutineScope()
    val backend = koinInject<Backend>()
    return remember(scope) { EmailCodeModel(scope, backend.config.emailCodeLifetime) }
}

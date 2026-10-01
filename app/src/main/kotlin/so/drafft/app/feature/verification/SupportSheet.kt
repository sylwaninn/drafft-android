package so.drafft.app.feature.verification

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.koin.compose.koinInject
import so.drafft.app.feature.me.AccountSheet
import so.drafft.app.feature.me.ChoiceChip
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.backend.asObject
import so.drafft.core.data.backend.asString
import so.drafft.core.data.backend.parseJsonOrNull
import so.drafft.core.data.backend.toJsonElement
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.DrafftField
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.components.SheetDetent
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.limited
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

// Ports Drafft/Features/Verification/SupportSheet.swift.

/** Signed in, the reply goes to the account's email; signed out, to one typed here. */
private enum class Session { UNKNOWN, SIGNED_IN, SIGNED_OUT }

private val emailPattern = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")

/**
 * "Get help" from anywhere a check fails or an account is on hold: the topic is filled in, the person
 * adds a few words, and it goes to the team (backend `support`), which replies by email. Signed out (a
 * stuck sign-up or reset), the form also asks where to reply. The reference comes back from the server
 * and is emailed too. Signed out, the message carries a Cloudflare Turnstile token (TurnstileChallenge).
 * Without a topic it's the help center: the person picks one of [HelpTopics.all] first.
 *
 * Shown inside a `DrafftSheet`; Done closes it through [LocalSheetDismiss].
 */
@Composable
fun SupportSheet(
    topic: String? = null,
    /** Already written for the person (a purchase's reference); they can change it. */
    prefill: String = "",
    /** Sent with the message for the team (a transaction id), never shown. */
    details: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    TrackScreen(Screen.SUPPORT)
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val appInfo = koinInject<AppInfo>()
    val dismiss = LocalSheetDismiss.current
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    // The topic picked in the help center, none until the person taps one.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var replyEmail by rememberSaveable { mutableStateOf("") }
    var session by remember { mutableStateOf(Session.UNKNOWN) }
    var sending by remember { mutableStateOf(false) }
    var reference by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The server turned the reply address down: said on the field.
    var emailError by remember { mutableStateOf<String?>(null) }
    val captcha = rememberTurnstileChallenge()

    // Newlines count as empty too: the field is multi-line.
    val hasMessage = message.isNotBlank()
    val hasEmail = session == Session.SIGNED_IN || emailPattern.matches(replyEmail.trim())
    // Signed out, sending waits for a Turnstile token.
    val captchaReady = session == Session.SIGNED_IN || captcha.token != null
    val replyTo = if (session == Session.SIGNED_IN) app.email else replyEmail.trim()
    val isHelpCenter = topic == null
    val sentTopic = topic ?: picked

    suspend fun send() {
        sending = true
        error = null
        val signedIn = session == Session.SIGNED_IN
        val topic = sentTopic ?: ""
        try {
            val context = buildMap<String, Any?> {
                put("app", appInfo.version)
                put("screen", if (isHelpCenter) "Help center" else topic)
                app.moderation.hold?.let { put("hold", it.rawValue) }
                putAll(details)
            }
            val body = buildMap<String, Any?> {
                put("topic", topic)
                put("message", message.trim())
                put("language", app.language.code)
                put("context", context)
                if (!signedIn) {
                    put("email", replyTo)
                    captcha.token?.let { put("turnstileToken", it) }
                }
            }
            val data = backend.publicFunction("support", body.toJsonElement() as JsonObject)
            val answer = data.parseJsonOrNull().asObject
            // The topic is a sentence in the person's language: only where it was asked from goes.
            Telemetry.track(AnalyticsEvent.SupportContacted(if (isHelpCenter) "help_center" else "in_context", signedIn))
            Haptics.success()
            reference = answer?.get("reference").asString ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Backend.BackendError.Http) {
            Haptics.warning()
            Telemetry.unexpected(e, "support", "send")
            when {
                e.serverMessage.contains("captcha_not_configured") ->
                    error = L("Support can't take messages this way right now. Try again later.")
                e.serverMessage.contains("captcha_") -> error = L("The security check didn't go through. Try again.")
                // Signed out, the limit also runs per day: no promise of an hour.
                e.status == 429 -> error = L("You've sent several messages already. Try again later.")
                e.serverMessage == "invalid_email" -> emailError = L("That doesn't look like an email address. Check for typos.")
                e.serverMessage == "invalid_message" -> error = L("Your message is too long. Shorten it, then send it again.")
                else -> error = ServerMessage.text(e, offline = L("Your message couldn't be sent. Check your connection and try again."))
            }
        } catch (e: Exception) {
            Haptics.warning()
            Telemetry.unexpected(e, "support", "send")
            error = ServerMessage.text(e, offline = L("Your message couldn't be sent. Check your connection and try again."))
        } finally {
            // Single use: whatever the answer, the next send needs a fresh token.
            if (!signedIn) captcha.renew()
            sending = false
        }
    }

    LaunchedEffect(Unit) {
        if (message.isEmpty()) message = prefill
        session = if (backend.hasSession()) Session.SIGNED_IN else Session.SIGNED_OUT
        if (session == Session.SIGNED_OUT) captcha.start()
    }
    LaunchedEffect(captcha.failed) {
        val text = L("The security check couldn't load. Check your connection and try again.")
        if (captcha.failed) error = text else if (error == text) error = null
    }

    AccountSheet(
        title = if (isHelpCenter) L("Help center") else L("Get help"),
        actionTitle = if (reference != null) L("Done") else L("Send to support"),
        actionIcon = if (reference != null) "check" else "plain",
        enabled = reference != null ||
            (sentTopic != null && hasMessage && hasEmail && session != Session.UNKNOWN && captchaReady),
        loading = sending,
        error = error,
        finished = reference != null,
        hasChanges = reference == null && hasMessage,
        action = {
            if (reference != null) {
                dismiss()
            } else {
                scope.launch { send() }
            }
        },
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = reference,
            transitionSpec = {
                (fadeIn(Motion.bouncy()) + scaleIn(Motion.bouncy(), initialScale = 0.95f))
                    .togetherWith(fadeOut(Motion.bouncy()))
            },
            label = "supportSent",
        ) { sent ->
            if (sent != null) {
                SentCard(replyTo, sent)
            } else {
                SupportForm(
                    topic = topic,
                    picked = picked,
                    onPickedChange = { picked = it },
                    session = session,
                    accountEmail = app.email,
                    replyEmail = replyEmail,
                    onReplyEmailChange = {
                        replyEmail = it
                        emailError = null
                    },
                    emailError = emailError,
                    message = message,
                    // The support function's limit: typing stops there.
                    onMessageChange = { message = it.limited(MESSAGE_LIMIT) },
                )
            }
        }
        // The widget works out of sight; it only shows (in its own sheet) when Cloudflare asks.
        if (session == Session.SIGNED_OUT && !captcha.needsInteraction) {
            TurnstileView(
                captcha,
                Modifier
                    .size(1.dp)
                    .alpha(0.01f)
                    .clearAndSetSemantics { },
            )
        }
    }

    DrafftSheet(
        visible = captcha.needsInteraction,
        onDismissRequest = { captcha.needsInteraction = false },
        detent = SheetDetent.FIT,
    ) {
        TurnstileSheet(captcha)
    }
}

@Composable
private fun SentCard(replyTo: String, reference: String) {
    NightSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .background(DS.palette.night, RoundedCornerShape(DS.Radius.xl))
                .padding(DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(DS.palette.accentOnNight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("check", size = 24.dp, tint = DS.palette.onAccentOnNight)
            }
            Text(L("Message sent."), style = display(28f), color = Color.White)
            Text(
                L("We'll reply at %s. Your reference is %s.", replyTo, reference),
                style = TextStyles.subheadline,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun SupportForm(
    topic: String?,
    picked: String?,
    onPickedChange: (String?) -> Unit,
    session: Session,
    accountEmail: String,
    replyEmail: String,
    onReplyEmailChange: (String) -> Unit,
    emailError: String?,
    message: String,
    onMessageChange: (String) -> Unit,
) {
    val p = DS.palette
    val isHelpCenter = topic == null
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        if (topic != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
                    .padding(DS.Space.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("Topic"), style = TextStyles.subheadline.semibold, color = p.body)
                Spacer(Modifier.weight(1f))
                Text(topic, style = TextStyles.subheadline.semibold, color = p.ink)
            }
        } else {
            TopicPicker(picked, onPickedChange)
        }

        if (session == Session.SIGNED_OUT) {
            SheetBlock(title = L("Where should we reply?")) {
                DrafftField(
                    title = L("Email"),
                    text = replyEmail,
                    onTextChange = onReplyEmailChange,
                    prompt = L("you@example.com"),
                    error = emailError,
                    keyboard = KeyboardType.Email,
                )
            }
        }

        SheetBlock(title = if (isHelpCenter) L("How can we help?") else L("What happened?")) {
            MessageField(
                message,
                onMessageChange,
                placeholder = if (isHelpCenter) {
                    L("Describe your question. If something doesn't work, say what you tapped and what happened.")
                } else {
                    L("A few words help us fix it faster")
                },
            )
            if (session == Session.SIGNED_IN) {
                Text(L("We read every message and reply to %s.", accountEmail), style = TextStyles.footnote, color = p.mute)
            }
        }
    }
}

/**
 * Help center only: what the message is about, so it reaches the right person. Nothing picked
 * until the person taps a topic.
 */
@Composable
private fun TopicPicker(picked: String?, onPickedChange: (String?) -> Unit) {
    SheetBlock(title = L("What's it about?")) {
        FlowLayout(spacing = DS.Space.sm) {
            HelpTopics.all.forEach { t ->
                val on = picked == t
                ChoiceChip(t, on, onClick = {
                    Haptics.select()
                    onPickedChange(if (on) null else t)
                })
            }
        }
    }
}

/**
 * A multi-line field with DrafftField's box and focus ring. Grows with the message: room to explain
 * from the start, never a scrolling box.
 */
@Composable
private fun MessageField(message: String, onMessageChange: (String) -> Unit, placeholder: String) {
    val p = DS.palette
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Same focus ring as DrafftField.
    val border by animateColorAsState(if (focused) p.ink else p.ink.copy(alpha = 0.35f), Motion.gentle(), label = "messageBorder")
    val width by animateDpAsState(if (focused) 2.dp else 1.dp, Motion.gentle(), label = "messageBorderWidth")
    val shape = RoundedCornerShape(DS.Radius.md)
    Box(
        Modifier
            .fillMaxWidth()
            .revealsOnFocus(focused)
            .background(p.field, shape)
            .border(width, border, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(DS.Space.lg),
    ) {
        BasicTextField(
            value = message,
            onValueChange = onMessageChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyles.body.copy(color = p.ink),
            minLines = 8,
            cursorBrush = SolidColor(p.accentInk),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (message.isEmpty()) Text(placeholder, style = TextStyles.body, color = p.mute)
                    inner()
                }
            },
        )
    }
}

/** Small "Get help" link shown under an error. */
@Composable
fun GetHelpButton(topic: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    TextLinkButton(
        onClick = {
            Haptics.tap()
            show = true
        },
        modifier = modifier,
    ) {
        DrafftIcon("question-circle", size = 18.dp, tint = DS.palette.accentInk)
        Text(L("Get help"), style = TextStyles.subheadline.semibold, color = DS.palette.accentInk)
    }
    DrafftSheet(visible = show, onDismissRequest = { show = false }) {
        SupportSheet(topic = topic)
    }
}

/** The help center's topics, in the order people look for them. */
object HelpTopics {
    val safety: String get() = L("Safety & reports")

    val all: List<String>
        get() = listOf(
            L("Account & login"), L("Profile & photos"), L("Matches & chats"), L("Sessions"),
            L("drafft tempo & billing"), safety, L("Something doesn't work"), L("Something else"),
        )
}

/** A support topic, for a sheet shown while one is set (`.sheet(item:)`). */
data class HelpTopic(val id: String)

/** The support function's message limit. */
private const val MESSAGE_LIMIT = 4000

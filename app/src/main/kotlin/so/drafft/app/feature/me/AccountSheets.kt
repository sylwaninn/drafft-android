package so.drafft.app.feature.me

import androidx.activity.compose.BackHandler
import so.drafft.core.ui.components.InteractiveDismissDisabled
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.auth.AuthProblem
import so.drafft.app.feature.auth.LegalDoc
import so.drafft.app.feature.auth.PasswordRule
import so.drafft.app.feature.auth.Validation
import so.drafft.app.feature.verification.CodeLockedCard
import so.drafft.app.feature.verification.CodeVerifiedCard
import so.drafft.app.feature.verification.EmailCodeModel
import so.drafft.app.feature.verification.OneTimeCodeEntry
import so.drafft.app.feature.verification.SupportSheet
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.DrafftField
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/AccountSheets.swift. `SheetBlock` lives in core:ui.

/**
 * Shared chrome for account sheets: title, close on the right, content blocks, and a pinned primary
 * action that stays visible (disabled until it can run; only a real error under it).
 *
 * [error] is a real problem (turned down, failed), in red under the action, never why it's disabled.
 * [hasChanges]: something typed that closing would lose, so Close asks before discarding it.
 */
@Composable
fun AccountSheet(
    title: String,
    actionTitle: String,
    actionIcon: String? = null,
    destructive: Boolean = false,
    enabled: Boolean,
    loading: Boolean = false,
    error: String? = null,
    hasChanges: Boolean = false,
    action: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    InteractiveDismissDisabled(hasChanges)
    val dismiss = LocalSheetDismiss.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val p = DS.palette
    // System back is Close: with something typed, it asks before discarding it.
    BackHandler(enabled = hasChanges && !confirmDiscard) { confirmDiscard = true }

    SheetPage(
        title = title,
        scroll = scroll,
        modifier = modifier,
        onClose = { if (hasChanges) confirmDiscard = true else dismiss() },
        bottomBar = {
            Column(
                Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DestructiveAwareButton(
                    destructive = destructive,
                    enabled = enabled && !loading,
                    onClick = action,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .draftTrail(
                            RoundedCornerShape(DS.Radius.xl),
                            color = if (destructive) p.negative else p.lime,
                            step = DpOffset((-6).dp, 0.dp),
                        ),
                ) {
                    when {
                        loading -> ButtonSpinner(if (destructive) Color.White else p.onLime)
                        actionIcon != null -> {
                            DrafftIcon(actionIcon, size = symbolSize(TextStyles.body), tint = LocalContentColor.current)
                            Text(actionTitle, maxLines = 2, overflow = TextOverflow.Clip)
                        }
                        else -> Text(actionTitle, maxLines = 2, overflow = TextOverflow.Clip)
                    }
                }
                if (error != null) {
                    Text(error, style = TextStyles.footnote.medium, color = p.negative, textAlign = TextAlign.Center)
                }
            }
        },
    ) { bars ->
        FocusScrollView(state = scroll, contentPadding = bars) {
            Column(
                Modifier.padding(horizontal = DS.Space.lg, vertical = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
                content = content,
            )
        }
    }

    DrafftConfirm(
        visible = confirmDiscard,
        onDismissRequest = { confirmDiscard = false },
        icon = "trash-bin-minimalistic",
        title = L("Discard your changes?"),
        message = L("What you typed here will be lost."),
        cancelTitle = L("Keep editing"),
        actions = listOf(ConfirmAction(L("Discard changes"), ConfirmAction.Kind.DESTRUCTIVE) { dismiss() }),
    )
}

/** The primary button, or its red twin for a destructive action (white on red, dimmed when disabled). */
@Composable
private fun DestructiveAwareButton(
    destructive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!destructive) {
        DrafftButton(onClick = onClick, modifier = modifier, kind = DrafftButtonKind.PRIMARY, enabled = enabled) { content() }
        return
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.snappy(), label = "destructivePress")
    val shape = RoundedCornerShape(DS.Radius.xl)
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .background(DS.palette.negative, shape)
            .clip(shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DS.Space.xl, vertical = DS.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalTextStyle provides TextStyles.body.semibold.copy(textAlign = TextAlign.Center),
        ) { content() }
    }
}

/** The iPhone's `Toggle` with drafft's tint: the accent track when on (red for a destructive consent). */
@Composable
internal fun DrafftSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DS.palette.lime,
    enabled: Boolean = true,
) {
    val p = DS.palette
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = if (tint == p.lime) p.onLime else Color.White,
            checkedTrackColor = tint,
            checkedBorderColor = tint,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = p.ink.copy(alpha = 0.16f),
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = tint.copy(alpha = 0.45f),
            disabledCheckedThumbColor = Color.White,
            disabledUncheckedTrackColor = p.ink.copy(alpha = 0.08f),
            disabledUncheckedThumbColor = Color.White,
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** A choice as a capsule chip (reasons, goal ideas): the accent fill when picked, the page tone otherwise. */
@Composable
internal fun ChoiceChip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = DS.palette
    val fg by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "chipInk")
    val bg by animateColorAsState(if (on) p.lime else p.canvasSoft, Motion.select(), label = "chipFill")
    PressScaleButton(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .semantics { if (on) selected = true },
        scale = 0.94f,
    ) {
        Box(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .background(bg, CircleShape)
                .padding(horizontal = DS.Space.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(branded(text), style = TextStyles.footnote.semibold, color = fg, maxLines = 1, softWrap = false)
        }
    }
}

/** How a code step switches in: the code slides in from the trailing edge, the rest fades. */
private fun <S> codeStageTransition(isCode: (S) -> Boolean) =
    { scope: androidx.compose.animation.AnimatedContentTransitionScope<S> ->
        val enter = if (isCode(scope.targetState)) {
            fadeIn(Motion.snappy()) + slideInHorizontally(Motion.snappy()) { it }
        } else {
            fadeIn(Motion.snappy())
        }
        val exit = if (isCode(scope.initialState)) {
            fadeOut(Motion.snappy()) + slideOutHorizontally(Motion.snappy()) { it }
        } else {
            fadeOut(Motion.snappy())
        }
        enter togetherWith exit
    }

// MARK: - Email

/** New address and password, then the 6-digit code sent to the new address (as at sign-up). */
@Composable
fun ChangeEmailSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val dismiss = LocalSheetDismiss.current
    val scope = rememberCoroutineScope()
    var newEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val flow = remember(scope) { EmailCodeModel(scope) }
    var showHelp by remember { mutableStateOf(false) }

    val valid = Validation.isEmail(newEmail) && newEmail.lowercase() != app.email.lowercase() && password.isNotEmpty()

    val actionTitle = when (flow.stage) {
        EmailCodeModel.Stage.FORM -> L("Send code")
        EmailCodeModel.Stage.CODE -> L("Verify")
        EmailCodeModel.Stage.DONE -> L("Done")
        EmailCodeModel.Stage.LOCKED -> L("Get help")
    }

    val enabled = when (flow.stage) {
        EmailCodeModel.Stage.FORM -> valid
        EmailCodeModel.Stage.CODE -> flow.code.length == 6
        EmailCodeModel.Stage.DONE, EmailCodeModel.Stage.LOCKED -> true
    }

    // What's wrong with what was typed (nothing about what's missing: the form says it).
    val error: String? = when {
        flow.stage != EmailCodeModel.Stage.FORM -> null
        flow.error != null -> flow.error
        newEmail.isNotEmpty() && !Validation.isEmail(newEmail) -> L("That doesn't look like an email address.")
        newEmail.isNotEmpty() && newEmail.lowercase() == app.email.lowercase() -> L("That's already your email.")
        else -> null
    }

    fun send() {
        val email = newEmail.trim()
        val current = app.email
        val typed = password
        flow.messages = mapOf(AuthProblem.WRONG_CREDENTIALS to L("Your password is incorrect."))
        flow.formProblems = setOf(AuthProblem.EMAIL_TAKEN)
        scope.launch {
            flow.send(
                to = email,
                send = {
                    // The password proves it's them; the code proves the new address is theirs.
                    backend.signIn(email = current, password = typed)
                    backend.updateEmail(email)
                },
                verify = { code -> backend.confirmEmailChange(email, code = code) },
            )
        }
    }

    LaunchedEffect(flow.stage) { if (flow.stage == EmailCodeModel.Stage.DONE) app.email = flow.sentTo }

    AccountSheet(
        title = L("Email"),
        actionTitle = actionTitle,
        actionIcon = when (flow.stage) {
            EmailCodeModel.Stage.FORM -> "plain"
            EmailCodeModel.Stage.DONE -> "check"
            else -> null
        },
        enabled = enabled,
        loading = flow.busy,
        error = error,
        hasChanges = flow.stage == EmailCodeModel.Stage.CODE ||
            (flow.stage == EmailCodeModel.Stage.FORM && !(newEmail.isEmpty() && password.isEmpty())),
        action = {
            when (flow.stage) {
                EmailCodeModel.Stage.FORM -> send()
                EmailCodeModel.Stage.CODE -> scope.launch { flow.verify() }
                EmailCodeModel.Stage.DONE -> dismiss()
                EmailCodeModel.Stage.LOCKED -> showHelp = true
            }
        },
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = flow.stage,
            transitionSpec = codeStageTransition { it == EmailCodeModel.Stage.CODE },
            label = "emailStage",
        ) { stage ->
            when (stage) {
                EmailCodeModel.Stage.FORM -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                    SheetBlock(title = L("Current email")) {
                        Text(app.email, style = TextStyles.body.semibold, color = DS.palette.body)
                    }
                    SheetBlock(title = L("New email")) {
                        DrafftField(
                            title = L("Email"),
                            text = newEmail,
                            onTextChange = {
                                newEmail = it
                                if (flow.stage == EmailCodeModel.Stage.FORM) flow.error = null
                            },
                            prompt = L("you@example.com"),
                            keyboard = KeyboardType.Email,
                        )
                        DrafftField(
                            title = L("Your password"),
                            text = password,
                            onTextChange = {
                                password = it
                                if (flow.stage == EmailCodeModel.Stage.FORM) flow.error = null
                            },
                            prompt = L("To confirm it's you"),
                            isSecure = true,
                            submitLabel = ImeAction.Send,
                            onSubmit = { if (valid) send() },
                        )
                    }
                }
                EmailCodeModel.Stage.CODE -> SheetBlock {
                    OneTimeCodeEntry(
                        destination = flow.sentTo,
                        code = flow.code,
                        onCode = flow::enterCode,
                        busy = flow.busy,
                        error = flow.error,
                        needsHelp = flow.needsHelp,
                        helpTopic = L("Email change"),
                        hint = L("Check your inbox, and your spam folder. The code works for 1 hour."),
                        resendIn = flow.resendIn,
                        onEdit = { flow.edit() },
                        onResend = { scope.launch { flow.resend() } },
                    )
                }
                EmailCodeModel.Stage.DONE -> CodeVerifiedCard(title = L("Email updated"), detail = flow.sentTo)
                EmailCodeModel.Stage.LOCKED -> CodeLockedCard(message = flow.error)
            }
        }
    }

    if (showHelp) {
        DrafftSheet(onDismissRequest = { showHelp = false }) { SupportSheet(topic = L("Email change")) }
    }
}

// MARK: - Password

/** New password, then a 6-digit code sent to the account's email: the code proves it's them. */
@Composable
fun ChangePasswordSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val dismiss = LocalSheetDismiss.current
    val scope = rememberCoroutineScope()
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val flow = remember(scope) { EmailCodeModel(scope) }
    var showHelp by remember { mutableStateOf(false) }
    val p = DS.palette

    val rules = PasswordRule.all
    val passed = rules.count { it.test(new) }
    val valid = passed == rules.size && new == confirm

    val actionTitle = when (flow.stage) {
        EmailCodeModel.Stage.FORM -> L("Send code")
        EmailCodeModel.Stage.CODE -> L("Update password")
        EmailCodeModel.Stage.DONE -> L("Done")
        EmailCodeModel.Stage.LOCKED -> L("Get help")
    }

    val enabled = when (flow.stage) {
        EmailCodeModel.Stage.FORM -> valid
        EmailCodeModel.Stage.CODE -> flow.code.length == 6
        EmailCodeModel.Stage.DONE, EmailCodeModel.Stage.LOCKED -> true
    }

    // What's wrong with what was typed (nothing about what's missing: the form says it).
    val error: String? = when {
        flow.stage != EmailCodeModel.Stage.FORM -> null
        flow.error != null -> flow.error
        confirm.isNotEmpty() && new != confirm -> L("The two new passwords don't match.")
        else -> null
    }

    fun send() {
        val password = new
        // Rules the server holds that the form can't check: back to the form to pick another.
        flow.formProblems = setOf(AuthProblem.SAME_PASSWORD, AuthProblem.WEAK_PASSWORD)
        scope.launch {
            flow.send(
                to = app.email,
                send = { backend.sendReauthenticationCode() },
                verify = { code -> backend.updatePassword(password, code = code) },
            )
        }
    }

    AccountSheet(
        title = L("Password"),
        actionTitle = actionTitle,
        actionIcon = when (flow.stage) {
            EmailCodeModel.Stage.FORM -> "plain"
            EmailCodeModel.Stage.CODE -> "lock-keyhole-minimalistic"
            EmailCodeModel.Stage.DONE -> "check"
            else -> null
        },
        enabled = enabled,
        loading = flow.busy,
        error = error,
        hasChanges = flow.stage == EmailCodeModel.Stage.CODE ||
            (flow.stage == EmailCodeModel.Stage.FORM && !(new.isEmpty() && confirm.isEmpty())),
        action = {
            when (flow.stage) {
                EmailCodeModel.Stage.FORM -> send()
                EmailCodeModel.Stage.CODE -> scope.launch { flow.verify() }
                EmailCodeModel.Stage.DONE -> dismiss()
                EmailCodeModel.Stage.LOCKED -> showHelp = true
            }
        },
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = flow.stage,
            transitionSpec = codeStageTransition { it == EmailCodeModel.Stage.CODE },
            label = "passwordStage",
        ) { stage ->
            when (stage) {
                EmailCodeModel.Stage.FORM -> SheetBlock {
                    DrafftField(
                        title = L("New password"),
                        text = new,
                        onTextChange = {
                            new = it
                            if (flow.stage == EmailCodeModel.Stage.FORM) flow.error = null
                        },
                        prompt = L("New password"),
                        isSecure = true,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
                        rules.forEach { rule ->
                            val ok = rule.test(new)
                            val done = L("Done")
                            val notYet = L("Not yet")
                            Row(
                                Modifier.clearAndSetSemantics {
                                    contentDescription = rule.label
                                    stateDescription = if (ok) done else notYet
                                },
                                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The one check mark in the app (bigger than the label, easy to read at a glance).
                                CheckDisc(isOn = ok, size = 22.dp)
                                // One weight, done or not: the disc and the ink say it, the line never widens.
                                Text(rule.label, style = TextStyles.footnote.medium, color = if (ok) p.ink else p.body)
                            }
                        }
                    }
                    DrafftField(
                        title = L("Confirm new password"),
                        text = confirm,
                        onTextChange = {
                            confirm = it
                            if (flow.stage == EmailCodeModel.Stage.FORM) flow.error = null
                        },
                        prompt = L("Type it again"),
                        isSecure = true,
                        error = if (confirm.isNotEmpty() && confirm != new) L("Doesn't match yet.") else null,
                        submitLabel = ImeAction.Send,
                        onSubmit = { if (valid) send() },
                    )
                }
                EmailCodeModel.Stage.CODE -> SheetBlock {
                    OneTimeCodeEntry(
                        destination = flow.sentTo,
                        code = flow.code,
                        onCode = flow::enterCode,
                        busy = flow.busy,
                        error = flow.error,
                        needsHelp = flow.needsHelp,
                        helpTopic = L("Password change"),
                        hint = L("Check your inbox, and your spam folder. It's the code that confirms it's you."),
                        resendIn = flow.resendIn,
                        editTitle = L("Edit password"),
                        onEdit = { flow.edit() },
                        onResend = { scope.launch { flow.resend() } },
                    )
                }
                EmailCodeModel.Stage.DONE ->
                    CodeVerifiedCard(title = L("Password updated"), detail = L("Use it next time you log in on another device."))
                EmailCodeModel.Stage.LOCKED -> CodeLockedCard(message = flow.error)
            }
        }
    }

    if (showHelp) {
        DrafftSheet(onDismissRequest = { showHelp = false }) { SupportSheet(topic = L("Password change")) }
    }
}

// MARK: - Export

@Composable
fun ExportDataSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val p = DS.palette

    data class Item(val icon: String, val title: String, val detail: String)
    val included = listOf(
        Item("user-rounded", L("Profile"), L("Name, bio, sports, prompts, lifestyle")),
        Item("gallery-wide", L("Photos & voice"), L("Everything you've uploaded")),
        Item("dialog-2", L("Messages"), L("Your conversations with matches")),
        Item("calendar", L("Sessions"), L("Invites you sent and received")),
        Item("heart", L("Likes & matches"), L("Who you liked and matched with")),
    )

    val requested: Instant? = app.dataExportRequestedAt

    AccountSheet(
        title = L("Export my data"),
        actionTitle = if (requested == null) L("Email me my export") else L("Export requested"),
        actionIcon = if (requested == null) "letter" else "check",
        enabled = requested == null && !sending,
        loading = sending,
        error = exportError,
        action = {
            sending = true
            exportError = null
            scope.launch {
                try {
                    // The server keeps one open request and says when it was made.
                    backend.rpc("request_data_export", emptyMap<String, Any?>())
                    Haptics.success()
                    app.dataExportRequestedAt = Instant.now()
                } catch (e: Exception) {
                    Haptics.warning()
                    exportError = L("Your request couldn't be sent. Check your connection and try again.")
                } finally {
                    sending = false
                }
            }
        },
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = requested,
            transitionSpec = { fadeIn(Motion.bouncy()) togetherWith fadeOut(Motion.bouncy()) },
            label = "exportRequested",
        ) { at ->
            if (at != null) {
                SheetBlock {
                    IconLabel(L("Check your inbox"), "letter-opened", style = TextStyles.headline, color = p.ink)
                    Text(
                        L(
                            "We're preparing your export. A download link goes to %s, usually within 24 hours of %s. The link works for 7 days.",
                            app.email,
                            DateText.format("yMMMdjmm", at),
                        ),
                        style = TextStyles.subheadline,
                        color = p.body,
                    )
                }
            } else {
                SheetBlock {
                    Text(
                        branded(L("Get a copy of everything you've shared on drafft. We'll email you a download link to a file you can keep or open elsewhere.")),
                        style = TextStyles.subheadline,
                        color = p.body,
                    )
                }
            }
        }
        SheetBlock(title = L("What's included")) {
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                included.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(p.canvasSoft, CircleShape)
                                .clearAndSetSemantics { },
                            contentAlignment = Alignment.Center,
                        ) {
                            DrafftIcon(item.icon, size = 15.6.dp, tint = p.ink)
                        }
                        // The badge's centre sits on the title's first line.
                        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(item.title, style = TextStyles.subheadline.semibold, color = p.ink)
                            Text(item.detail, style = TextStyles.footnote, color = p.body)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Delete

/**
 * [withdrawsConsent]: opened from the sensitive data consent. drafft can't work without the gender,
 * so withdrawing the consent is deleting the account. The page says so, and offers no pause (it keeps
 * the data) and no reasons to pick (the reason is known).
 */
@Composable
fun DeleteAccountSheet(withdrawsConsent: Boolean = false, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var understood by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val p = DS.palette

    AccountSheet(
        title = if (withdrawsConsent) L("Sensitive data consent") else L("Delete account"),
        actionTitle = L("Delete my account"),
        actionIcon = "trash-bin-minimalistic",
        destructive = true,
        enabled = understood,
        loading = loading,
        error = failure,
        action = {
            loading = true
            failure = null
            scope.launch {
                try {
                    app.deleteAccount()
                    Haptics.success()
                    dismiss()
                } catch (e: Backend.BackendError.SignedOut) {
                    Haptics.warning()
                    loading = false
                    failure = L("You're logged out, so nothing was deleted. Log in again, then delete your account.")
                } catch (e: Exception) {
                    Haptics.warning()
                    loading = false
                    failure = L("We couldn't delete your account. Check your connection and try again.")
                }
            }
        },
        modifier = modifier,
    ) {
        if (withdrawsConsent) {
            ConsentBlock()
        } else {
            SheetBlock {
                Text(L("Here's what deleting removes."), style = display(26f), color = p.ink)
                Text(
                    L("Deleting removes your profile, photos, matches and messages for good. Your matches won't be able to reach you."),
                    style = TextStyles.subheadline,
                    color = p.body,
                )
            }
            PauseBlock()
            ReasonsBlock(reason, onReasonChange = { reason = it })
        }

        SheetBlock {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    L("I understand my account will be deleted for good."),
                    Modifier.weight(1f),
                    style = TextStyles.subheadline.semibold,
                    color = p.ink,
                )
                DrafftSwitch(
                    checked = understood,
                    onCheckedChange = {
                        understood = it
                        Haptics.select()
                    },
                    tint = p.negative,
                )
            }
        }
    }
}

@Composable
private fun ReasonsBlock(reason: String?, onReasonChange: (String?) -> Unit) {
    val reasons = listOf(L("I met someone"), L("I need a break"), L("Not enough people nearby"), L("Something else"))
    SheetBlock(title = L("Why are you leaving?")) {
        FlowLayout(spacing = DS.Space.sm) {
            reasons.forEach { r ->
                val on = reason == r
                ChoiceChip(r, on, onClick = {
                    Haptics.select()
                    onReasonChange(if (on) null else r)
                })
            }
        }
        Text(branded(L("Optional. It helps us make drafft better.")), style = TextStyles.footnote, color = DS.palette.mute)
    }
}

@Composable
private fun ConsentBlock() {
    val uriHandler = LocalUriHandler.current
    val p = DS.palette
    SheetBlock {
        Text(L("Withdrawing your consent means deleting your account."), style = display(26f), color = p.ink)
        Text(
            branded(L("drafft needs your gender and the genders you want to see to suggest anyone.")),
            style = TextStyles.subheadline,
            color = p.body,
        )
        Text(
            L("Deleting removes them with your profile, photos, matches and messages, for good."),
            style = TextStyles.subheadline,
            color = p.body,
        )
        DrafftButton(
            onClick = {
                Haptics.tap()
                uriHandler.openUri(LegalDoc.sensitiveData())
            },
            kind = DrafftButtonKind.TERTIARY,
        ) {
            DrafftIcon("arrow-right-up", size = symbolSize(TextStyles.body), tint = LocalContentColor.current)
            Text(branded(L("How drafft uses this data")), maxLines = 2)
        }
    }
}

@Composable
private fun PauseBlock() {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val scope = rememberCoroutineScope()
    var pausing by remember { mutableStateOf(false) }
    var pauseFailure by remember { mutableStateOf<String?>(null) }
    SheetBlock(title = L("Just need a break?")) {
        Text(L("Pausing hides you from Discover and keeps your matches and chats."), style = TextStyles.subheadline, color = DS.palette.body)
        DrafftButton(
            onClick = {
                Haptics.tap()
                pausing = true
                pauseFailure = null
                scope.launch {
                    // Closes only once the server has it: a pause that didn't save stays here to say so.
                    val failure = app.pauseNow()
                    pausing = false
                    if (failure != null) {
                        pauseFailure = failure
                    } else {
                        Haptics.success()
                        dismiss()
                    }
                }
            },
            kind = DrafftButtonKind.SECONDARY,
            enabled = !app.profilePaused && !pausing,
        ) {
            if (pausing) {
                ButtonSpinner(DS.palette.ink)
            } else {
                DrafftIcon("pause", size = symbolSize(TextStyles.body), tint = LocalContentColor.current)
                Text(if (app.profilePaused) L("Your profile is paused") else L("Pause my profile instead"), maxLines = 2)
            }
        }
        pauseFailure?.let {
            Text(it, style = TextStyles.footnote.semibold, color = DS.palette.negative)
        }
    }
}

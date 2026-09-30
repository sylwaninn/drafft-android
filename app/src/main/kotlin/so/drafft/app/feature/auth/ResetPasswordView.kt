package so.drafft.app.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.verification.CodeLockedCard
import so.drafft.app.feature.verification.EmailCodeModel
import so.drafft.app.feature.verification.OneTimeCodeEntry
import so.drafft.app.feature.verification.SupportSheet
import so.drafft.app.feature.verification.rememberEmailCodeModel
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftField
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion

// Port of Drafft/Features/Auth/ResetPasswordView.swift.

/**
 * Forgot password, all in the app: the email, then the 6-digit code sent to it, then the new password,
 * and the person is logged in. No link. The code step never says whether the address has an account
 * (no account enumeration): Auth sends nothing to an unknown address, and the code is simply wrong.
 */
@Composable
fun ResetPasswordView(email: String, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf(email) }
    val flow = rememberEmailCodeModel()
    var password by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<AuthProblem?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    val passedRules = PasswordRule.all.count { it.test(password) }
    val stage = flow.stage

    val title = when (stage) {
        EmailCodeModel.Stage.FORM -> L("Reset your password")
        EmailCodeModel.Stage.CODE, EmailCodeModel.Stage.LOCKED -> L("Check your inbox")
        EmailCodeModel.Stage.DONE -> L("Choose a new password")
    }
    val subtitle = when (stage) {
        EmailCodeModel.Stage.FORM -> L("Enter the email you signed up with. We'll send you a 6-digit code.")
        EmailCodeModel.Stage.CODE, EmailCodeModel.Stage.LOCKED -> L("Enter the 6-digit code we sent you.")
        EmailCodeModel.Stage.DONE -> L("You'll use it to log in from now on.")
    }
    val actionTitle = when (stage) {
        EmailCodeModel.Stage.FORM -> L("Send code")
        EmailCodeModel.Stage.CODE -> L("Continue")
        EmailCodeModel.Stage.DONE -> L("Save password")
        EmailCodeModel.Stage.LOCKED -> L("Get help")
    }
    val actionEnabled = when (stage) {
        EmailCodeModel.Stage.FORM -> Validation.isEmail(email)
        EmailCodeModel.Stage.CODE -> flow.code.length == 6
        EmailCodeModel.Stage.DONE -> passedRules == PasswordRule.all.size
        EmailCodeModel.Stage.LOCKED -> true
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                backend.updatePassword(password)
                saved = true
                Haptics.success()
                app.email = flow.sentTo
                // Someone who stopped mid sign-up goes back to it.
                val onboarded = try {
                    backend.isOnboarded()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true
                }
                focusManager.clearFocus()
                app.signIn(onboard = !onboarded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                problem = AuthProblem(e)
            } finally {
                saving = false
            }
        }
    }

    fun primary() {
        if (!actionEnabled) return
        when (flow.stage) {
            EmailCodeModel.Stage.FORM -> {
                focusManager.clearFocus()
                val address = email.trim()
                scope.launch {
                    flow.send(
                        to = address,
                        send = { backend.sendPasswordReset(to = address) },
                        verify = { code -> backend.verifyPasswordReset(address, code = code) },
                    )
                }
            }
            EmailCodeModel.Stage.CODE -> scope.launch { flow.verify() }
            EmailCodeModel.Stage.DONE -> save()
            EmailCodeModel.Stage.LOCKED -> showHelp = true
        }
    }

    AuthScaffold(
        title = title,
        subtitle = subtitle,
        actionTitle = actionTitle,
        actionEnabled = actionEnabled,
        loading = flow.busy || saving,
        action = ::primary,
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                val slides = setOf(EmailCodeModel.Stage.CODE, EmailCodeModel.Stage.DONE)
                val move = Motion.snappy<IntOffset>()
                val enter = if (targetState in slides) fadeIn(Motion.snappy()) + slideInHorizontally(move) { it } else fadeIn(Motion.snappy())
                val exit = if (initialState in slides) fadeOut(Motion.snappy()) + slideOutHorizontally(move) { it } else fadeOut(Motion.snappy())
                enter togetherWith exit
            },
            label = "resetStage",
        ) { s ->
            when (s) {
                EmailCodeModel.Stage.FORM -> DrafftField(
                    title = L("Email"),
                    text = email,
                    onTextChange = {
                        email = it
                        if (flow.stage == EmailCodeModel.Stage.FORM) flow.error = null
                    },
                    prompt = L("you@example.com"),
                    error = flow.error,
                    keyboard = KeyboardType.Email,
                    submitLabel = ImeAction.Send,
                    onSubmit = ::primary,
                )
                EmailCodeModel.Stage.CODE -> OneTimeCodeEntry(
                    destination = flow.sentTo,
                    code = flow.code,
                    onCode = flow::enterCode,
                    busy = flow.busy,
                    error = flow.error,
                    needsHelp = flow.needsHelp,
                    helpTopic = L("Password reset"),
                    hint = L("Check your inbox, and your spam folder. The code works for 1 hour."),
                    resendIn = flow.resendIn,
                    onEdit = { flow.edit() },
                    onResend = { scope.launch { flow.resend() } },
                )
                EmailCodeModel.Stage.DONE -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                    DrafftField(
                        title = L("New password"),
                        text = password,
                        onTextChange = {
                            password = it
                            problem = null
                        },
                        prompt = L("Create a password"),
                        isSecure = true,
                        error = problem?.message,
                        submitLabel = ImeAction.Done,
                        onSubmit = ::primary,
                    )
                    PasswordRules(password)
                }
                EmailCodeModel.Stage.LOCKED -> CodeLockedCard(flow.error)
            }
        }
    }

    DrafftSheet(visible = showHelp, onDismissRequest = { showHelp = false }) {
        SupportSheet(topic = L("Password reset"))
    }

    // The code signed in only to set the password: leaving before it's saved leaves no session behind.
    val leftStage by rememberUpdatedState(stage)
    val wasSaved by rememberUpdatedState(saved)
    DisposableEffect(Unit) {
        onDispose {
            if (leftStage == EmailCodeModel.Stage.DONE && !wasSaved) app.scope.launch { backend.signOut() }
        }
    }
}

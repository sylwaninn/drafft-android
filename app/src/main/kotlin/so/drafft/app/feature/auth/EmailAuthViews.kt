package so.drafft.app.feature.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.verification.CodeLockedCard
import so.drafft.app.feature.verification.EmailCodeModel
import so.drafft.app.feature.verification.OneTimeCodeEntry
import so.drafft.app.feature.verification.rememberEmailCodeModel
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftField
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.characterCount
import so.drafft.core.ui.navigation.LocalNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

/** Pushed from sign-up or log-in: the 6-digit code for [email]. */
internal data class ConfirmEmailRoute(val email: String)

/** Pushed from log-in: forgot password, starting from [email]. */
internal data class ResetPasswordRoute(val email: String)

/**
 * Shared chrome for the credential screens: sage canvas, 900 headline, form, sticky primary action.
 * [footnote]: small print under the action.
 */
@Composable
fun AuthScaffold(
    title: String,
    subtitle: String,
    actionTitle: String,
    actionEnabled: Boolean,
    loading: Boolean,
    action: () -> Unit,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    /** A problem that isn't about one field (no connection, a server error), in red under the action. */
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = DS.palette
    val scroll = rememberScrollState()
    val nav = LocalNavStack.current
    EdgeBars(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(p.canvasSoft),
        // The title scrolls under the back button: the same edge blur as every bar.
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircleButton("alt-arrow-left", onClick = { nav.pop() }, contentDescription = L("Back"))
            }
        },
        navigationEdge = true,
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DrafftButton(onClick = action, enabled = actionEnabled && !loading) {
                    if (loading) ButtonSpinner() else Text(actionTitle, maxLines = 2)
                }
                if (error != null) {
                    Text(error, style = TextStyles.footnote.medium, color = p.negative, textAlign = TextAlign.Center)
                }
                if (footnote != null) {
                    Text(footnote, style = TextStyles.caption, color = p.body, textAlign = TextAlign.Center)
                }
            }
        },
    ) { padding ->
        FocusScrollView(state = scroll, contentPadding = padding) {
            Column(
                Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.lg),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xxl),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                    Text(title, Modifier.semantics { heading() }, style = display(40f), color = p.ink)
                    Text(subtitle, style = TextStyles.body, color = p.body)
                }
                content()
            }
        }
    }
}

/** A small spinner in a primary button, in the button's own content colour. */
@Composable
internal fun ButtonSpinner() {
    CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
}

object Validation {
    private val email = Regex("[A-Z0-9a-z._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")

    fun isEmail(s: String): Boolean = email.matches(s)
}

class PasswordRule(val id: String, val label: String, val test: (String) -> Boolean) {
    companion object {
        val all: List<PasswordRule>
            get() = listOf(
                PasswordRule("len", L("At least 8 characters")) { it.characterCount >= 8 },
                PasswordRule("num", L("One number")) { s -> s.any(Char::isDigit) },
                PasswordRule("case", L("Upper and lower case")) { s -> s.any(Char::isUpperCase) && s.any(Char::isLowerCase) },
            )
    }
}

/** The password rules under a new password, each ticked (the one selection mark) once met. */
@Composable
internal fun PasswordRules(password: String) {
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
        PasswordRule.all.forEach { rule ->
            val ok = rule.test(password)
            val color by animateColorAsState(if (ok) DS.palette.ink else DS.palette.body, Motion.snappy(), label = "rule")
            val state = if (ok) L("Met") else L("Not met yet")
            Row(
                Modifier.clearAndSetSemantics {
                    contentDescription = rule.label
                    stateDescription = state
                },
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Met: the one selection mark (CheckDisc), not a tick glyph.
                CheckDisc(isOn = ok, size = 22.dp)
                Text(rule.label, style = TextStyles.footnote.medium, color = color)
            }
        }
    }
}

@Composable
fun SignUpView(modifier: Modifier = Modifier) {
    TrackScreen(Screen.EMAIL_SIGN_UP)
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var emailTouched by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    // From the server: on the field it's about.
    var problem by remember { mutableStateOf<AuthProblem?>(null) }
    val passwordFocus = remember { FocusRequester() }
    var emailFocused by remember { mutableStateOf(false) }

    val emailError: String? = when {
        problem == AuthProblem.EMAIL_TAKEN || problem == AuthProblem.INVALID_EMAIL -> problem?.message
        !emailTouched || email.isEmpty() || Validation.isEmail(email) -> null
        else -> L("That doesn't look like an email address. Check for typos.")
    }
    val passwordError: String? = problem?.takeIf { it == AuthProblem.WEAK_PASSWORD }?.message
    // Not about one field (no connection, too many emails, a server error): under the action.
    val formError: String? = problem?.takeIf { it != AuthProblem.EMAIL_TAKEN && it != AuthProblem.INVALID_EMAIL && it != AuthProblem.WEAK_PASSWORD }?.message
    val passedRules = PasswordRule.all.count { it.test(password) }
    val canSubmit = Validation.isEmail(email) && passedRules == PasswordRule.all.size

    fun submit() {
        emailTouched = true
        if (!canSubmit) return
        loading = true
        problem = null
        scope.launch {
            try {
                when (backend.signUp(email = email, password = password, language = app.language)) {
                    Backend.SignUpResult.SIGNED_IN -> {
                        Haptics.success()
                        app.email = email
                        focusManager.clearFocus()
                        app.signIn(onboard = true)
                    }
                    Backend.SignUpResult.CONFIRM_EMAIL -> {
                        Haptics.success()
                        nav.push(ConfirmEmailRoute(email))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                problem = AuthProblem(e)
            } finally {
                loading = false
            }
        }
    }

    AuthScaffold(
        title = L("Create your account"),
        subtitle = L("Your email stays private. Matches only see your first name."),
        actionTitle = L("Create account"),
        actionEnabled = canSubmit,
        loading = loading,
        action = ::submit,
        modifier = modifier,
        error = formError,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xl)) {
            DrafftField(
                title = L("Email"),
                text = email,
                onTextChange = {
                    email = it
                    if (problem != AuthProblem.WEAK_PASSWORD) problem = null
                },
                modifier = Modifier.onFocusChanged {
                    // Leaving the field counts as done with it.
                    if (emailFocused && !it.hasFocus) emailTouched = true
                    emailFocused = it.hasFocus
                },
                prompt = L("you@example.com"),
                error = emailError,
                keyboard = KeyboardType.Email,
                onSubmit = {
                    emailTouched = true
                    passwordFocus.requestFocus()
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                DrafftField(
                    title = L("Password"),
                    text = password,
                    onTextChange = {
                        password = it
                        if (problem != AuthProblem.EMAIL_TAKEN && problem != AuthProblem.INVALID_EMAIL) problem = null
                    },
                    modifier = Modifier.focusRequester(passwordFocus),
                    prompt = L("Create a password"),
                    isSecure = true,
                    error = passwordError,
                    submitLabel = ImeAction.Done,
                    onSubmit = ::submit,
                )
                PasswordRules(password)
            }
        }
    }
}

/**
 * After sign-up, when the account needs its email confirmed: the 6-digit code from the email.
 * The sixth digit checks it and signs in; Change goes back to the form.
 */
@Composable
fun ConfirmEmailView(email: String, modifier: Modifier = Modifier) {
    TrackScreen(Screen.EMAIL_CODE)
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val flow = rememberEmailCodeModel()

    AuthScaffold(
        title = L("Check your inbox"),
        subtitle = L("Enter the 6-digit code we sent you."),
        actionTitle = L("Continue"),
        actionEnabled = flow.stage == EmailCodeModel.Stage.CODE && flow.code.length == 6,
        loading = flow.busy,
        action = { scope.launch { flow.verify() } },
        modifier = modifier,
    ) {
        if (flow.stage == EmailCodeModel.Stage.LOCKED) {
            CodeLockedCard(flow.error)
        } else {
            OneTimeCodeEntry(
                destination = email,
                code = flow.code,
                onCode = flow::enterCode,
                busy = flow.busy,
                error = flow.error,
                needsHelp = flow.needsHelp,
                helpTopic = L("Create your account"),
                hint = L("Check your inbox, and your spam folder. The code works for 1 hour."),
                resendIn = flow.resendIn,
                onEdit = { nav.pop() },
                onResend = { scope.launch { flow.resend() } },
            )
        }
    }

    LaunchedEffect(Unit) {
        if (flow.stage != EmailCodeModel.Stage.FORM) return@LaunchedEffect
        flow.awaitCode(
            sentTo = email,
            resend = { backend.resendConfirmation(to = email) },
            verify = { code -> backend.confirmSignUp(email, code = code) },
        )
    }
    LaunchedEffect(flow.stage) {
        if (flow.stage != EmailCodeModel.Stage.DONE) return@LaunchedEffect
        app.email = email
        focusManager.clearFocus()
        app.signIn(onboard = true)
    }
}

@Composable
fun LogInView(modifier: Modifier = Modifier) {
    TrackScreen(Screen.EMAIL_LOG_IN)
    val app = LocalAppModel.current
    val backend = koinInject<Backend>()
    val nav = LocalNavStack.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    // Errors sit on the field they're about.
    var emailError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }
    // Not about one field (no connection, too many tries, a server error): under the action.
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    fun submit() {
        // Keyboard "Go" with a field still empty: move to it, no error.
        if (email.isEmpty()) {
            emailFocus.requestFocus()
            return
        }
        if (password.isEmpty()) {
            passwordFocus.requestFocus()
            return
        }
        if (!Validation.isEmail(email)) {
            emailError = L("That doesn't look like an email address. Check for typos.")
            emailFocus.requestFocus()
            Haptics.warning()
            return
        }
        loading = true
        formError = null
        scope.launch {
            try {
                backend.signIn(email = email, password = password)
                Haptics.success()
                app.email = email
                focusManager.clearFocus()
                // Someone who stopped mid sign-up goes back to it.
                // (The account read here is the one sign-in then uses: read once.)
                app.enterAfterLogIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                val problem = AuthProblem(e)
                when (problem) {
                    AuthProblem.EMAIL_NOT_CONFIRMED -> {
                        // A new code, then the code step (confirming it signs in and goes on to sign-up).
                        // Too many emails means a recent code is still on its way: the code step too,
                        // where Resend waits. Anything else is said, not a code screen without a code.
                        val resent = try {
                            backend.resendConfirmation(to = email)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AuthProblem(e)
                        }
                        if (resent == null || resent == AuthProblem.TOO_MANY_EMAILS) {
                            nav.push(ConfirmEmailRoute(email))
                        } else {
                            formError = resent.message
                        }
                    }
                    AuthProblem.WRONG_CREDENTIALS -> passwordError = problem.message
                    else -> formError = problem.message
                }
            } finally {
                loading = false
            }
        }
    }

    AuthScaffold(
        title = L("Welcome back"),
        subtitle = L("Log in to see who's new nearby."),
        actionTitle = L("Log in"),
        actionEnabled = email.isNotEmpty() && password.isNotEmpty(),
        loading = loading,
        action = ::submit,
        modifier = modifier,
        error = formError,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xl)) {
            DrafftField(
                title = L("Email"),
                text = email,
                onTextChange = {
                    email = it
                    emailError = null
                    formError = null
                },
                modifier = Modifier.focusRequester(emailFocus),
                prompt = L("you@example.com"),
                error = emailError,
                keyboard = KeyboardType.Email,
                onSubmit = { passwordFocus.requestFocus() },
            )
            Box {
                DrafftField(
                    title = L("Password"),
                    text = password,
                    onTextChange = {
                        password = it
                        passwordError = null
                        formError = null
                    },
                    modifier = Modifier.focusRequester(passwordFocus),
                    prompt = L("Your password"),
                    isSecure = true,
                    error = passwordError,
                    submitLabel = ImeAction.Go,
                    onSubmit = ::submit,
                )
                // On the password label's line, where people look for it. Its own page, pushed
                // like the rest of the auth flow (not an alert).
                val forgot = L("Forgot password")
                TextLinkButton(
                    L("Forgot?"),
                    onClick = { nav.push(ResetPasswordRoute(email)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (-12).dp)
                        .clearAndSetSemantics {
                            contentDescription = forgot
                            role = Role.Button
                            onClick {
                                nav.push(ResetPasswordRoute(email))
                                true
                            }
                        },
                    style = TextStyles.subheadline.semibold,
                )
            }
        }
    }
}

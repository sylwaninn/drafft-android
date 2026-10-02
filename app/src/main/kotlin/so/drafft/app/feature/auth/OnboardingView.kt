package so.drafft.app.feature.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import so.drafft.app.feature.me.PhotoSetCheck
import so.drafft.app.feature.me.PhotoSetHint
import so.drafft.app.feature.me.PromptPickerSheet
import so.drafft.app.feature.me.PromptSlot
import so.drafft.app.feature.me.ReorderablePhotoGrid
import so.drafft.app.feature.me.inputStyle
import so.drafft.app.feature.me.profileSaveFailure
import so.drafft.app.feature.profile.IcebreakerEditor
import so.drafft.app.feature.profile.LifestylePicker
import so.drafft.app.feature.profile.VoiceIntroRecorder
import so.drafft.app.feature.verification.HelpTopic
import so.drafft.app.feature.verification.PhoneVerificationView
import so.drafft.app.feature.verification.SupportSheet
import so.drafft.core.data.AppModel
import so.drafft.core.data.OnboardingProgress
import so.drafft.core.data.OnboardingStore
import so.drafft.core.data.audio.VoiceRecorder
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.backend.hasLifestyle
import so.drafft.core.data.location.Area
import so.drafft.core.data.location.AreaLocator
import so.drafft.core.data.location.OnDeviceAreaResolver
import so.drafft.core.data.location.ServerAreaResolver
import so.drafft.core.data.media.PhotoCompressor
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.platform.ForegroundReturns
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.LocationProvider
import so.drafft.core.data.platform.PermissionStatus
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.data.verification.FaceCheck
import so.drafft.core.data.verification.PhoneVerificationModel
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Audience
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.ConsentDraft
import so.drafft.core.model.L
import so.drafft.core.model.Localization
import so.drafft.core.model.TermsConsent
import so.drafft.core.model.Profile
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.model.Sport
import so.drafft.core.model.SportEntry
import so.drafft.core.model.Vitals
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.BottomBar
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftCheckbox
import so.drafft.core.ui.components.DrafftField
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.DrafftTextArea
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.PermissionButton
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SportPicker
import so.drafft.core.ui.components.TopBar
import so.drafft.core.ui.components.characterCount
import so.drafft.core.ui.components.limited
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Auth/OnboardingView.swift (FlowLayout lives in core:ui).

/**
 * One question per step, grouped in four chapters shown in the stepper: secure the
 * account, say who you are, how you move, then what people see.
 */
enum class OnboardingStep {
    LANGUAGE, RULES, PHONE,
    NAME, BIRTHDAY, GENDER, SHOW_ME, LIFESTYLE, AREA,
    SPORTS, RHYTHM,
    PHOTOS, BIO, VOICE, PROMPTS, ICEBREAKER, NOTIFICATIONS;

    val rawValue: Int get() = ordinal

    /** The step's id in analytics (`show_me`), the same on the iPhone. */
    val telemetryID: String get() = name.lowercase()

    enum class Chapter(val rawValue: String) {
        ACCOUNT("Account"), YOU("About you"), SPORTS("Sports"), PROFILE("Profile");

        val telemetryID: String get() = name.lowercase()

        val title: String
            get() = when (this) {
                ACCOUNT -> L("Account")
                YOU -> L("About you")
                SPORTS -> L("Sports")
                PROFILE -> L("Profile")
            }
    }

    val chapter: Chapter
        get() = when (this) {
            LANGUAGE, RULES, PHONE -> Chapter.ACCOUNT
            NAME, BIRTHDAY, GENDER, SHOW_ME, LIFESTYLE, AREA -> Chapter.YOU
            SPORTS, RHYTHM -> Chapter.SPORTS
            PHOTOS, BIO, VOICE, PROMPTS, ICEBREAKER, NOTIFICATIONS -> Chapter.PROFILE
        }
}

private val consentLog = java.util.logging.Logger.getLogger("so.drafft.consent")

/**
 * Sign-up flow. One step at a time, moved only by the buttons (no swipe between steps).
 * Nothing is pre-selected: every answer is the person's own. Optional steps keep Continue
 * disabled until something is filled in, and offer Skip instead.
 */
@Composable
fun OnboardingView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val store = koinInject<OnboardingStore>()
    val profileSync = koinInject<ProfileSync>()
    val notifications = koinInject<NotificationService>()
    val location = koinInject<LocationProvider>()
    val backend = koinInject<Backend>()
    val phone = koinInject<PhoneVerificationModel> { parametersOf(scope) }
    val state = remember {
        // Restored before the first frame, so a resumed sign-up opens on its step (no slide to it).
        val locator = AreaLocator(location, scope, ServerAreaResolver(backend, OnDeviceAreaResolver(location)))
        OnboardingState(app, store, profileSync, notifications, phone, locator, scope).also { it.restore() }
    }
    val reduceMotion = LocalReduceMotion.current
    val scrolls = remember { List(OnboardingStep.entries.size) { ScrollState(0) } }

    // One header for the whole flow, outside the sliding steps: it never moves with them, and the
    // stepper stays the same view, so its bars fill and its chapters change in place.
    TopBar(
        scroll = scrolls[state.step],
        bar = { Header(state) },
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
    ) { top ->
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = state.forward
                val move = Motion.snappy<IntOffset>()
                // Reduce Motion: a plain fade, no sideways slide.
                if (reduceMotion) {
                    fadeIn(Motion.snappy()) togetherWith fadeOut(Motion.snappy())
                } else {
                    (slideInHorizontally(move) { if (forward) it else -it } + fadeIn(Motion.snappy()))
                        .togetherWith(slideOutHorizontally(move) { if (forward) -it else it } + fadeOut(Motion.snappy()))
                }
            },
            label = "onboardingStep",
        ) { index ->
            StepContent(state, OnboardingStep.entries[index], scrolls[index], top)
        }
    }

    // The saved language, applied once composed (it redraws the whole app).
    LaunchedEffect(Unit) {
        val saved = state.restoredLanguage ?: return@LaunchedEffect
        if (app.language != saved) app.language = saved
    }
    LaunchedEffect(phone) {
        snapshotFlow { phone.stage }.drop(1).collect { if (it == PhoneVerificationModel.Stage.VERIFIED) state.save() }
    }
    // Each step shown, for the sign-up funnel (the first one of a resumed sign-up says so).
    LaunchedEffect(state.step) { state.stepShown() }
}

// MARK: - State

/** Everything sign-up holds while it runs (the Swift view's `@State`), and what it does with it. */
@Stable
private class OnboardingState(
    val app: AppModel,
    val store: OnboardingStore,
    val profileSync: ProfileSync,
    val notifications: NotificationService,
    val phone: PhoneVerificationModel,
    val locator: AreaLocator,
    val scope: CoroutineScope,
) {
    var step by mutableIntStateOf(0)
    var forward by mutableStateOf(true)
    var birthday by mutableStateOf<Instant?>(null)
    var consent by mutableStateOf(ConsentDraft())

    /** The terms version the server recorded with the consent this sign-up (`accept_terms`). */
    var recordedTerms by mutableStateOf<String?>(null)
    var recordingConsent by mutableStateOf(false)
    var consentError by mutableStateOf<String?>(null)
    // The language the app is drawn in now (the saved one, else the phone's), so the check matches the screen.
    var language by mutableStateOf(Localization.language)
    var identity by mutableStateOf<String?>(null)
    var interestedIn by mutableStateOf<Set<String>>(emptySet())
    var showHelp by mutableStateOf<HelpTopic?>(null)
    var area by mutableStateOf<Area?>(null)
    var name by mutableStateOf("")
    var sports by mutableStateOf<List<SportEntry>>(emptyList())
    var photos by mutableStateOf<List<String>>(emptyList())

    /** Face check on the first photo: null while checking or with no photo. */
    var mainFace by mutableStateOf<FaceCheck.Result?>(null)

    val photosCheck: PhotoSetCheck get() = PhotoSetCheck.of(photos, mainFace, app.photoModeration)

    var voice by mutableStateOf<VoiceRecorder.Recording?>(null)
    var icebreaker by mutableStateOf(Icebreaker.Kind.TWO_TRUTHS.blank)

    /** Written prompts (up to 3): a question from the library and their answer. */
    var prompts by mutableStateOf<List<ProfilePrompt>>(emptyList())

    /** Lifestyle answers. */
    var lifestyle by mutableStateOf(Vitals.blank)
    var bio by mutableStateOf("")
    var pickingPrompt by mutableStateOf<PromptSlot?>(null)
    var promptFocus by mutableStateOf<Int?>(null)

    /** The last step sends the profile to the server: spinner, then the server's reason if it refuses. */
    var finishing by mutableStateOf(false)
    var finishError by mutableStateOf<String?>(null)

    /** A picked photo that couldn't be opened, until the next pick. */
    var photoError by mutableStateOf<String?>(null)
    var restored = false
    var confirmLeave by mutableStateOf(false)
    var furthest = 0

    /** The language saved with the progress, for the app to switch to once sign-up shows. */
    var restoredLanguage: AppLanguage? = null

    /** When this sign-up was opened, and the step on screen was shown (monotonic, for durations). */
    private val openedAt = System.nanoTime()
    private var stepShownAt = System.nanoTime()
    private var resumedStep: Int? = null

    fun stepShown() {
        // The state is rebuilt (a language change, the activity recreated) on the step it was on: counted once per arrival.
        if (step == OnboardingState.shownStep) return
        OnboardingState.shownStep = step
        stepShownAt = System.nanoTime()
        val resumed = resumedStep == step
        resumedStep = null
        Telemetry.track(AnalyticsEvent.OnboardingStepViewed(current.telemetryID, current.chapter.telemetryID, step, resumed))
    }

    private fun stepCompleted(skipped: Boolean) {
        val seconds = ((System.nanoTime() - stepShownAt) / 1_000_000_000).toInt()
        Telemetry.track(AnalyticsEvent.OnboardingStepCompleted(current.telemetryID, current.chapter.telemetryID, step, skipped, seconds))
    }

    val steps = OnboardingStep.entries
    val current: OnboardingStep get() = steps[step]

    /** The phone check is mandatory: never skippable. */
    val skippable: Boolean get() = current in optional

    val age: Int?
        get() = birthday?.let { Period.between(it.atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now()).years }
    val isAdult: Boolean get() = (age ?: 0) >= 18

    val canContinue: Boolean get() = complete(current)

    val bioText: String get() = bio.trim()

    val answeredPrompts: List<ProfilePrompt> get() = prompts.filter { it.answer.isNotBlank() }

    /** `resuming`: photos kept from last time count as checked until the photos step checks them again. */
    fun complete(s: OnboardingStep, resuming: Boolean = false): Boolean = when (s) {
        OnboardingStep.LANGUAGE -> true
        // Once the server holds the consent for these terms, the boxes no longer matter.
        OnboardingStep.RULES -> consent.isComplete || TermsConsent.isCurrent(recordedTerms)
        OnboardingStep.PHONE -> phone.stage == PhoneVerificationModel.Stage.VERIFIED
        OnboardingStep.NAME -> name.isNotBlank()
        OnboardingStep.BIRTHDAY -> birthday != null && isAdult
        OnboardingStep.GENDER -> identity != null
        OnboardingStep.SHOW_ME -> interestedIn.isNotEmpty()
        OnboardingStep.AREA -> area != null
        OnboardingStep.SPORTS, OnboardingStep.RHYTHM -> sports.isNotEmpty()
        OnboardingStep.PHOTOS -> if (resuming) photos.isNotEmpty() else photosCheck == PhotoSetCheck.READY
        OnboardingStep.VOICE -> voice != null
        OnboardingStep.PROMPTS -> answeredPrompts.isNotEmpty()
        OnboardingStep.LIFESTYLE -> lifestyle.hasLifestyle
        OnboardingStep.BIO -> bioText.isNotEmpty() && bio.characterCount <= 200
        OnboardingStep.ICEBREAKER -> icebreaker.isComplete
        OnboardingStep.NOTIFICATIONS -> notifications.isAllowed
    }

    /** Why the step can't go on yet (`error`: a real problem, the only kind shown under the button). */
    val blockedReason: Pair<String, Boolean>?
        get() {
            finishError?.let { if (step == steps.size - 1) return it to true }
            consentError?.let { if (current == OnboardingStep.RULES) return it to true }
            return when (current) {
                OnboardingStep.LANGUAGE -> null
                OnboardingStep.RULES -> if (complete(OnboardingStep.RULES)) null else L("Accept the rules and terms to continue.") to false
                OnboardingStep.PHONE -> {
                    // Sending or checking: the spinner says it, no reason needed.
                    if (phone.busy || phone.primaryEnabled) return null
                    when (phone.stage) {
                        PhoneVerificationModel.Stage.ENTER_NUMBER ->
                            (if (phone.number.isEmpty()) L("Enter your mobile number.") else L("That number looks incomplete.")) to false
                        PhoneVerificationModel.Stage.ENTER_CODE -> L("Enter the 6-digit code we sent you.") to false
                        else -> null
                    }
                }
                OnboardingStep.NAME -> if (complete(OnboardingStep.NAME)) null else L("Add your first name.") to false
                OnboardingStep.BIRTHDAY -> when {
                    birthday == null -> L("Pick your birthday.") to false
                    isAdult -> null
                    else -> L("drafft is for people 18 and over.") to true
                }
                OnboardingStep.GENDER -> if (identity == null) L("Pick the one that fits you best.") to false else null
                OnboardingStep.SHOW_ME -> if (interestedIn.isEmpty()) L("Pick at least one.") to false else null
                OnboardingStep.LIFESTYLE -> if (lifestyle.hasLifestyle) null else L("Answer one, or skip it for now.") to false
                OnboardingStep.BIO -> when {
                    bio.characterCount > 200 -> L("Keep it under 200 characters.") to true
                    bioText.isEmpty() -> L("Write a few words, or skip it for now.") to false
                    else -> null
                }
                OnboardingStep.AREA ->
                    if (area == null && locator.state == AreaLocator.State.Locating) L("Finding your area…") to false else null
                OnboardingStep.SPORTS -> if (sports.isEmpty()) L("Pick at least one sport.") to false else null
                OnboardingStep.RHYTHM -> null
                OnboardingStep.PHOTOS -> photoError?.let { it to true } ?: photosCheck.reason?.let { it to photosCheck.needsAction }
                OnboardingStep.VOICE -> if (voice == null) L("Record your intro, or skip it for now.") to false else null
                OnboardingStep.PROMPTS -> if (answeredPrompts.isEmpty()) L("Answer a prompt, or skip it for now.") to false else null
                OnboardingStep.ICEBREAKER -> if (icebreaker.isComplete) null else L("Finish your prompt, or skip it for now.") to false
                OnboardingStep.NOTIFICATIONS ->
                    if (notifications.isDenied) L("Notifications are off in iPhone Settings. Skip for now.") to false else null
            }
        }

    fun go(target: Int) {
        forward = target > step
        step = target
        furthest = maxOf(furthest, target)
        save()
    }

    fun advance() {
        if (current == OnboardingStep.RULES && !TermsConsent.isCurrent(recordedTerms)) {
            recordConsent()
            return
        }
        // Continue on a complete step, Skip on an optional one left empty.
        if (step >= steps.size - 1) {
            stepCompleted(skipped = !complete(current))
            finish()
            return
        }
        stepCompleted(skipped = !complete(current))
        if (steps[step + 1].chapter != current.chapter) Haptics.success() else Haptics.tap()
        go(step + 1)
    }

    /** Both consents go to the server before anything personal is asked; the step moves on once they're recorded, or says why not. */
    private fun recordConsent() {
        consentError = null
        recordingConsent = true
        scope.launch {
            try {
                profileSync.acceptTerms()
                Telemetry.track(AnalyticsEvent.TermsAccepted(TermsConsent.VERSION, during = "sign_up"))
                recordedTerms = TermsConsent.VERSION
                recordingConsent = false
                advance()
            } catch (e: CancellationException) {
                recordingConsent = false
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                recordingConsent = false
                Telemetry.track(AnalyticsEvent.OnboardingStepBlocked(current.telemetryID, Telemetry.reason(e)))
                Telemetry.unexpected(e, "onboarding", "accept_terms")
                when (val f = profileSync.termsFailure(e)) {
                    TermsConsent.Failure.SignOut -> app.endSession()
                    is TermsConsent.Failure.Message -> consentError = f.text
                }
            }
        }
    }

    fun phonePrimary() {
        when (phone.stage) {
            PhoneVerificationModel.Stage.ENTER_NUMBER -> scope.launch { phone.sendCode() }
            PhoneVerificationModel.Stage.ENTER_CODE -> scope.launch { phone.verify() }
            PhoneVerificationModel.Stage.VERIFIED -> advance()
            PhoneVerificationModel.Stage.LOCKED -> showHelp = HelpTopic(L("Phone verification"))
        }
    }

    // Resume

    /** Saves everything answered so far, so an unfinished sign-up resumes here. */
    fun save() {
        store.save(
            OnboardingProgress(
                name = name,
                language = language.code,
                birthday = birthday,
                termsVersion = recordedTerms,
                verifiedPhone = if (phone.stage == PhoneVerificationModel.Stage.VERIFIED) phone.displayNumber else null,
                identity = identity,
                interestedIn = interestedIn.toList(),
                area = area?.name,
                sports = sports.map { OnboardingProgress.SavedSport(it.sport.id, it.perWeek) },
                photos = photos,
                voicePath = voice?.url,
                voiceDuration = voice?.duration ?: 0.0,
                prompts = prompts.map { OnboardingProgress.SavedPrompt(it.question, it.answer) },
                bio = bio,
                lifestyle = listOf(lifestyle.chronotype, lifestyle.diet, lifestyle.drinks, lifestyle.smokes),
                furthest = furthest,
            ),
        )
    }

    /** Back where they stopped: the first mandatory step not done yet (usually the SMS), otherwise the furthest step reached. */
    fun restore() {
        if (restored) return
        val p = store.load()
        if (p == null) {
            restored = true
            return
        }
        name = p.name
        AppLanguage.fromCode(p.language)?.let { l ->
            language = l
            restoredLanguage = l
        }
        birthday = p.birthday
        // Ticked again only if the server recorded them for the terms shown now.
        recordedTerms = p.termsVersion
        consent = ConsentDraft.restored(recordedVersion = p.termsVersion)
        p.verifiedPhone?.let(phone::restoreVerified)
        identity = p.identity
        interestedIn = p.interestedIn.toSet()
        p.area?.let { area = Area(name = it, city = it) }
        sports = p.sports.mapNotNull { s -> Sport.fromId(s.sport)?.let { SportEntry(sport = it, perWeek = s.perWeek) } }
        photos = p.photos.filter { java.io.File(it).exists() }
        prompts = p.prompts.map { ProfilePrompt(question = it.question, answer = it.answer) }
        bio = p.bio
        if (p.lifestyle.size == 4) {
            lifestyle = lifestyle.copy(chronotype = p.lifestyle[0], diet = p.lifestyle[1], drinks = p.lifestyle[2], smokes = p.lifestyle[3])
        }
        val path = p.voicePath
        if (path != null && java.io.File(path).exists()) voice = VoiceRecorder.Recording(url = path, duration = p.voiceDuration, levels = emptyList())
        furthest = p.furthest
        restored = true
        val firstMissing = steps.firstOrNull { it !in optional && !complete(it, resuming = true) }
        val target = minOf(firstMissing?.rawValue ?: p.furthest, p.furthest)
        forward = true
        // Clamped: a saved step from an older, longer flow must not index past the steps.
        step = (languageSwitchStep ?: target).coerceIn(0, steps.size - 1)
        // Not when the state is only rebuilt on the step already shown (see shownStep).
        if (languageSwitchStep == null && OnboardingState.shownStep != step) {
            resumedStep = step
            Telemetry.track(AnalyticsEvent.OnboardingResumed(current.telemetryID, step))
        }
        languageSwitchStep = null
    }

    /** The new profile holds only what the person answered: nothing from the demo profile. */
    fun finish() {
        val p = Profile(
            id = "me",
            name = name.trim(),
            age = age ?: 18,
            pronouns = null,
            birthday = birthday,
            gender = Audience.fromAnswer(identity),
            neighborhood = area?.name ?: "",
            distanceKm = 0.0,
            portrait = photos.firstOrNull() ?: "",
            photos = photos.drop(1),
            sports = sports,
            voiceIntro = voice?.url,
            voiceDuration = voice?.duration ?: 0.0,
            icebreaker = if (icebreaker.isComplete) icebreaker else Icebreaker.Kind.TWO_TRUTHS.blank,
            favoriteSpot = "",
            bio = if (bio.characterCount <= 200) bioText else "",
            goal = "",
            vitalsOverride = lifestyle,
            promptsOverride = answeredPrompts,
        )
        app.language = language
        app.phoneNumber = phone.displayNumber
        // Lost from a restored draft: back to its step, where Continue waits for it.
        val birthday = birthday ?: run {
            Haptics.warning()
            go(OnboardingStep.BIRTHDAY.rawValue)
            return
        }
        val signUp = ProfileSync.SignUp(
            name = p.name, birthday = birthday, gender = identity, interestedIn = interestedIn,
            neighborhood = area?.name ?: "", location = locator.blurred, bio = p.bio,
            lifestyle = lifestyle, icebreaker = if (icebreaker.isComplete) icebreaker else null, sports = sports,
            prompts = answeredPrompts, photos = photos,
            voice = voice?.let { ProfileSync.Voice(url = it.url, duration = it.duration, levels = it.levels) },
            language = language,
        )
        finishError = null
        finishing = true
        scope.launch {
            // The profile goes to the server first; without a session it fails and says so.
            try {
                profileSync.finish(signUp)
            } catch (e: CancellationException) {
                finishing = false
                throw e
            } catch (e: ProfileSync.SyncError.Refused) {
                finishing = false
                Haptics.warning()
                Telemetry.track(AnalyticsEvent.OnboardingFailed(Telemetry.reason(e)))
                if (e.code == "terms_required") {
                    // The server has no consent on record (the one noted on this phone was lost there):
                    // back to the rules step, unticked, to record it again.
                    consentLog.severe("complete_onboarding: terms_required although the sign-up recorded them")
                    recordedTerms = null
                    consent = ConsentDraft()
                    consentError = ServerMessage.text(forCode = "terms_required")
                    go(OnboardingStep.RULES.rawValue)
                } else {
                    finishError = profileSaveFailure(e, photosCheck)
                }
                return@launch
            } catch (e: Exception) {
                Haptics.warning()
                Telemetry.track(AnalyticsEvent.OnboardingFailed(Telemetry.reason(e)))
                Telemetry.unexpected(e, "onboarding", "finish")
                finishError = profileSaveFailure(e, photosCheck)
                finishing = false
                return@launch
            }
            finishing = false
            Haptics.success()
            // Counts and yes/no only: never the answers themselves (gender, who to meet, lifestyle).
            Telemetry.track(
                AnalyticsEvent.OnboardingCompleted(
                    photos = photos.size, sports = sports.size, prompts = answeredPrompts.size, hasVoice = voice != null,
                    hasBio = p.bio.isNotEmpty(), hasIcebreaker = icebreaker.isComplete, answeredLifestyle = lifestyle.hasLifestyle,
                    notificationsAllowed = notifications.isAllowed,
                    minutes = ((System.nanoTime() - openedAt) / 60_000_000_000).toInt(),
                ),
            )
            app.finishOnboarding(p)
        }
    }

    companion object {
        val optional: Set<OnboardingStep> = setOf(
            OnboardingStep.LIFESTYLE, OnboardingStep.BIO, OnboardingStep.VOICE,
            OnboardingStep.PROMPTS, OnboardingStep.ICEBREAKER, OnboardingStep.NOTIFICATIONS,
        )

        val oldestBirthday: Instant get() = ZonedDateTime.now().minusYears(100).toInstant()

        /**
         * The step a language change was made on. On Android the whole app is rebuilt in a newly picked
         * language (the root is keyed on it), so sign-up restores from its saved progress: this brings
         * it back on the same step, where the iPhone simply redraws in place.
         */
        var languageSwitchStep: Int? = null

        /**
         * The step last counted as shown (`onboarding_step_viewed`), kept across the state's rebuilds like
         * the iPhone's `OnboardingFunnel`, which outlives its view's redraws: a step, and a resume on it,
         * is counted once per arrival. Leaving sign-up on purpose starts over ([forgetFunnel]).
         */
        var shownStep: Int? = null

        fun forgetFunnel() {
            shownStep = null
        }
    }
}

// MARK: - Chrome

@Composable
private fun Header(state: OnboardingState) {
    val chapters = remember {
        OnboardingStep.Chapter.entries.map { c -> ChapterSteps(c.title, OnboardingStep.entries.count { it.chapter == c }) }
    }
    OnboardingHeader(
        chapters = chapters,
        step = state.step,
        skippable = state.skippable,
        confirmLeave = state.confirmLeave,
        onConfirmLeaveChange = { state.confirmLeave = it },
        back = { state.go(state.step - 1) },
        skip = state::advance,
        leave = {
            // Leaving on purpose starts over: nothing is kept.
            state.store.clear()
            OnboardingState.forgetFunnel()
            state.app.signOut()
        },
    )
}

/**
 * Every step: its own scroll view with the Continue bar attached as a pinned bottom bar (the header is
 * pinned once, on the whole flow), so content scrolls under both with the progressive blur.
 */
@Composable
private fun Page(
    state: OnboardingState,
    scroll: ScrollState,
    top: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    BottomBar(
        scroll = scroll,
        bar = { Footer(state, Modifier.padding(top = DS.Space.md)) },
    ) { bottom ->
        FocusScrollView(
            state = scroll,
            contentPadding = PaddingValues(top = top.calculateTopPadding(), bottom = bottom.calculateBottomPadding()),
        ) {
            Column(
                Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.lg, bottom = DS.Space.xl),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xxl),
                content = content,
            )
        }
    }
}

@Composable
private fun Footer(state: OnboardingState, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = DS.Space.sm), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        // Typing a prompt answer: no Continue above the keyboard (it would jump to the next
        // step before the other prompts). The field has its own check button instead.
        if (!(state.current == OnboardingStep.PROMPTS && state.promptFocus != null)) {
            PrimaryButton(state, Modifier.padding(horizontal = DS.Space.xl))
            // Only a real error under Continue: the step itself says what's missing.
            val reason = state.blockedReason?.takeIf { it.second }
            AnimatedVisibility(visible = reason != null, enter = fadeIn(Motion.gentle()), exit = fadeOut(Motion.gentle())) {
                val last = remember { arrayOfNulls<String>(1) }
                if (reason != null) last[0] = reason.first
                Text(
                    branded(last[0].orEmpty()),
                    Modifier.fillMaxWidth().padding(horizontal = DS.Space.xl),
                    style = TextStyles.footnote.medium,
                    color = DS.palette.negative,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    val topic = state.showHelp
    val shownTopic = remember { arrayOfNulls<HelpTopic>(1) }
    if (topic != null) shownTopic[0] = topic
    DrafftSheet(visible = topic != null, onDismissRequest = { state.showHelp = null }) {
        shownTopic[0]?.let { SupportSheet(topic = it.id) }
    }
}

@Composable
private fun PrimaryButton(state: OnboardingState, modifier: Modifier) {
    val phone = state.phone
    val locator = state.locator
    val openSettings = LocalPlatformUi.current.rememberOpenAppSettings()
    when {
        state.current == OnboardingStep.PHONE -> DrafftButton(onClick = state::phonePrimary, modifier = modifier, enabled = phone.primaryEnabled) {
            if (phone.busy) ButtonSpinner() else Text(phone.primaryTitle, maxLines = 2)
        }
        state.current == OnboardingStep.AREA && state.area == null -> DrafftButton(
            onClick = {
                // Where they train: denied sends them to Settings, otherwise ask and locate.
                when {
                    state.area != null -> state.advance()
                    locator.state == AreaLocator.State.Denied -> openSettings()
                    else -> locator.locate()
                }
            },
            modifier = modifier,
            enabled = locator.state != AreaLocator.State.Locating,
        ) {
            if (locator.state == AreaLocator.State.Locating) {
                ButtonSpinner()
            } else {
                DrafftIcon("map-point", size = (17f * 1.2f).dp, tint = LocalContentColor.current)
                Text(if (locator.state == AreaLocator.State.Denied) L("Open Settings") else L("Allow location"), maxLines = 2)
            }
        }
        state.current == OnboardingStep.NOTIFICATIONS && state.notifications.permission != PermissionStatus.ALLOWED ->
            PermissionButton(state.notifications, askTitle = L("Turn on notifications"), symbol = "bell", modifier = modifier)
        else -> DrafftButton(
            onClick = state::advance,
            modifier = modifier,
            enabled = state.canContinue && !state.finishing && !state.recordingConsent,
        ) {
            if (state.finishing || state.recordingConsent) {
                ButtonSpinner()
            } else {
                Text(if (state.step == state.steps.size - 1) L("Start swiping") else L("Continue"), maxLines = 2)
            }
        }
    }
}

// MARK: - Layout

@Composable
private fun StepTitle(title: String, sub: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Text(title, Modifier.semantics { heading() }, style = display(36f), color = DS.palette.ink)
        Text(branded(sub), style = TextStyles.body, color = DS.palette.body)
    }
}

/** Field label, same everywhere in the flow (matches DrafftField's title). */
@Composable
private fun FieldLabel(text: String) {
    Text(text, style = TextStyles.subheadline.semibold, color = DS.palette.ink)
}

/** Hint or error under a field, same everywhere in the flow. */
@Composable
private fun Hint(text: String, error: Boolean = false) {
    if (error) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.Top) {
            DrafftIcon("danger-circle", Modifier.padding(top = 1.dp), size = (13f * 1.2f).dp, tint = DS.palette.negative)
            Text(branded(text), style = TextStyles.footnote.medium, color = DS.palette.negative)
        }
    } else {
        // Body grey: mute is under 4.5:1 on the sage page.
        Text(branded(text), style = TextStyles.footnote, color = DS.palette.body)
    }
}

/** A white block on the page. */
internal fun Modifier.block(color: androidx.compose.ui.graphics.Color, radius: Dp = DS.Radius.xl): Modifier =
    fillMaxWidth().background(color, RoundedCornerShape(radius))

@Composable
private fun Fact(icon: String, title: String, text: String) {
    Row(
        Modifier.semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        IconDisc(icon)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyles.subheadline.semibold, color = DS.palette.ink)
            Text(branded(text), style = TextStyles.footnote, color = DS.palette.body)
        }
    }
}

/** An ink glyph on a sage disc (decorative icons in rows). */
@Composable
private fun IconDisc(icon: String) {
    Box(Modifier.size(32.dp).background(DS.palette.canvasSoft, CircleShape), contentAlignment = Alignment.Center) {
        // `.footnote.weight(.bold)`.
        DrafftIcon(icon, size = (13f * 1.2f).dp, tint = DS.palette.ink)
    }
}

// MARK: - Steps

@Composable
private fun StepContent(state: OnboardingState, step: OnboardingStep, scroll: ScrollState, top: PaddingValues) {
    Page(state, scroll, top) {
        when (step) {
            OnboardingStep.LANGUAGE -> LanguageStep(state)
            OnboardingStep.RULES -> RulesStep(state)
            OnboardingStep.PHONE -> PhoneStep(state)
            OnboardingStep.NAME -> NameStep(state)
            OnboardingStep.BIRTHDAY -> BirthdayStep(state)
            OnboardingStep.GENDER -> GenderStep(state)
            OnboardingStep.SHOW_ME -> ShowMeStep(state)
            OnboardingStep.AREA -> AreaStep(state)
            OnboardingStep.LIFESTYLE -> LifestyleStep(state)
            OnboardingStep.BIO -> BioStep(state)
            OnboardingStep.SPORTS -> SportsStep(state)
            OnboardingStep.RHYTHM -> RhythmStep(state)
            OnboardingStep.PHOTOS -> PhotosStep(state)
            OnboardingStep.VOICE -> VoiceStep(state)
            OnboardingStep.PROMPTS -> PromptsStep(state)
            OnboardingStep.ICEBREAKER -> IcebreakerStep(state)
            OnboardingStep.NOTIFICATIONS -> NotificationsStep(state)
        }
    }
}

@Composable
private fun NameStep(state: OnboardingState) {
    StepTitle(L("What should we call you?"), L("First name only. It's what your matches see."))
    DrafftField(
        title = L("First name"),
        text = state.name,
        onTextChange = { state.name = it },
        prompt = L("Alex"),
        submitLabel = ImeAction.Done,
        limit = 40,
    )
}

@Composable
private fun BirthdayStep(state: OnboardingState) {
    StepTitle(L("When's your birthday?"), L("Your profile shows your age, never the date."))
    // Typed in three boxes (no wheel, no made-up starting date), then the age it gives, or why not.
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
        FieldLabel(L("Birthday"))
        val oldest = remember { OnboardingState.oldestBirthday }
        BirthdateField(date = state.birthday, onDateChange = { state.birthday = it }, oldest = oldest)
        val age = state.age
        if (age != null && state.birthday != null) {
            if (state.isAdult) Hint(L("Your profile will show %d.", age)) else Hint(L("You need to be 18 or older to use drafft."), error = true)
        } else {
            Hint(L("drafft is for people 18 and over."))
        }
    }
}

/** Community rules, then the two required consents (unchecked by default), before anything personal is asked. */
@Composable
private fun RulesStep(state: OnboardingState) {
    StepTitle(L("A few ground rules"), L("drafft works because everyone plays fair."))
    Column(
        Modifier.block(DS.palette.canvas).padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
    ) {
        Fact("user-rounded", L("Be yourself"), L("Your own photos, your real first name and your real age."))
        Fact("user-block", L("Respect first"), L("Kind in chat, clear about what you want. No means no."))
        Fact("running", L("Meet where others train"), L("First sessions happen in public places: a park, a club, a court."))
        Fact("flag", L("Report anything off"), L("Two taps from any profile or chat. Every report is reviewed."))
    }
    ConsentChecks(draft = state.consent, onDraftChange = { state.consent = it; state.consentError = null })
}

/** Reaches [amount] past the leading edge (SwiftUI's negative leading padding). */
internal fun Modifier.leadingOutset(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.minWidth) else constraints
    val placeable = measurable.measure(wide)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-extra, 0) }
}

/** First question: the app's language, preselected from the phone (English otherwise). */
@Composable
private fun LanguageStep(state: OnboardingState) {
    StepTitle(L("Pick your language"), L("We've set it to your phone's language. You can change it anytime in You."))
    ChoiceBlock {
        AppLanguage.entries.forEachIndexed { i, l ->
            if (i > 0) RowDivider()
            ChoiceRow(title = l.displayName, isOn = state.language == l) {
                Haptics.select()
                state.language = l
                // The whole app redraws in the new language (the root is keyed on it): kept first so
                // sign-up comes back on this step with this choice.
                state.save()
                OnboardingState.languageSwitchStep = state.step
                // The rest of sign-up switches to it straight away, one frame after the row so the
                // selection shows at once.
                state.scope.launch {
                    yield()
                    state.app.language = l
                }
            }
        }
    }
}

@Composable
private fun PhoneStep(state: OnboardingState) {
    StepTitle(L("What's your number?"), L("Everyone on drafft verifies a phone number. It keeps fake accounts out."))
    PhoneVerificationView(model = state.phone)
}

@Composable
private fun NotificationsStep(state: OnboardingState) {
    StepTitle(L("Don't miss a match"), L("Get told when someone likes you back, writes to you, or a session is coming up."))
    Column(
        Modifier.block(DS.palette.canvas).padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        listOf(
            "heart" to L("New matches and likes"),
            "chat-round-line" to L("Messages, without the text unless you want it"),
            "alarm" to L("Session reminders, the evening before and an hour before"),
        ).forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.CenterVertically) {
                IconDisc(icon)
                Text(text, style = TextStyles.subheadline, color = DS.palette.ink)
            }
        }
    }
    when {
        state.notifications.isAllowed -> Hint(L("Notifications are on. Fine-tune them anytime in You › Notifications."))
        state.notifications.isDenied -> Hint(L("Notifications are off. You can turn them on later in iPhone Settings."), error = true)
        else -> Hint(L("You choose what you hear about in You › Notifications."))
    }
}

@Composable
private fun GenderStep(state: OnboardingState) {
    StepTitle(L("Which describes you best?"), L("You can't change it later. If it's ever wrong, write to the help center in You."))
    ChoiceRows(listOf("Woman", "Man", "Non-binary"), isOn = { state.identity == it }) { o ->
        state.identity = if (state.identity == o) null else o
    }
}

@Composable
private fun ShowMeStep(state: OnboardingState) {
    StepTitle(L("Who do you want to meet?"), L("Pick as many as you like. This never shows on your profile."))
    ChoiceRows(listOf("Women", "Men", "Non-binary people", "Everyone"), isOn = { it in state.interestedIn }) { o ->
        if (o == "Everyone") {
            state.interestedIn = if (o in state.interestedIn) emptySet() else setOf("Everyone")
            return@ChoiceRows
        }
        val set = state.interestedIn - "Everyone"
        state.interestedIn = if (o in set) set - o else set + o
    }
}

/** The options are stored in English; this is what the rows show. */
private fun choiceTitle(option: String): String = when (option) {
    "Woman" -> L("Woman")
    "Man" -> L("Man")
    "Non-binary" -> L("Non-binary")
    "Women" -> L("Women")
    "Men" -> L("Men")
    "Non-binary people" -> L("Non-binary people")
    "Everyone" -> L("Everyone")
    else -> option
}

/** A white block of rows, each with the one selection mark (`CheckDisc`). Same as the language list. */
@Composable
private fun ChoiceRows(options: List<String>, isOn: (String) -> Boolean, toggle: (String) -> Unit) {
    ChoiceBlock {
        options.forEachIndexed { i, o ->
            if (i > 0) RowDivider()
            ChoiceRow(title = choiceTitle(o), isOn = isOn(o)) {
                Haptics.select()
                toggle(o)
            }
        }
    }
}

@Composable
private fun ChoiceBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.block(DS.palette.canvas).clip(RoundedCornerShape(DS.Radius.xl)), content = content)
}

@Composable
private fun ChoiceRow(title: String, isOn: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { selected = isOn }
            .padding(horizontal = DS.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = TextStyles.body.medium, color = DS.palette.ink)
        CheckDisc(isOn = isOn)
    }
}

/** The system list separator, inset like the rows' text. */
@Composable
private fun RowDivider() {
    Box(Modifier.padding(start = DS.Space.lg).fillMaxWidth().height(0.5.dp).background(DS.palette.hairline))
}

/**
 * Where they train: location is required (at least while using the app). No typing: the
 * area comes from a blurred position, resolved by the server. Denied: Continue stays off
 * and the footer sends them to Settings.
 */
@Composable
private fun AreaStep(state: OnboardingState) {
    val p = DS.palette
    val locator = state.locator
    val foreground = koinInject<ForegroundReturns>()
    StepTitle(L("Where do you train?"), L("drafft needs your location to show people near you. We show your area, never your address."))
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
        FieldLabel(L("Your area"))
        val denied = locator.state == AreaLocator.State.Denied
        val shape = RoundedCornerShape(DS.Radius.md)
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .background(p.field, shape)
                .border(if (denied) 2.dp else 1.dp, if (denied) p.negative else p.ink.copy(alpha = 0.35f), shape)
                .padding(horizontal = DS.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val area = state.area
            when {
                area != null -> {
                    CheckDisc(isOn = true)
                    Text(area.name, style = TextStyles.body.semibold, color = p.ink)
                }
                locator.state == AreaLocator.State.Locating -> {
                    CircularProgressIndicator(Modifier.size(20.dp), color = p.ink, strokeWidth = 2.dp)
                    Text(L("Finding your area…"), style = TextStyles.body, color = p.body)
                }
                else -> {
                    DrafftIcon("map-point-remove", size = (17f * 1.2f).dp, tint = p.mute)
                    Text(L("Location not shared yet"), style = TextStyles.body, color = p.mute)
                }
            }
        }
        when (locator.state) {
            AreaLocator.State.Denied ->
                Hint(L("Location is off for drafft. Turn it on in Settings (While Using the App is enough) to continue."), error = true)
            AreaLocator.State.Failed -> Hint(L("We couldn't find your area. Try again."), error = true)
            else -> Unit
        }
    }
    LocationFacts()

    LaunchedEffect(locator) {
        snapshotFlow { locator.state }.collect { s ->
            if (s is AreaLocator.State.Found && state.area != s.area) {
                Haptics.success()
                state.area = s.area
            }
        }
    }
    LaunchedEffect(foreground) {
        // Back from Settings: try again.
        foreground.returns.collect {
            val s = locator.state
            if (state.area == null && (s == AreaLocator.State.Denied || s == AreaLocator.State.Failed)) locator.locate()
        }
    }
}

/** How location works, plainly: when it updates, how it's blurred, what others see. */
@Composable
private fun LocationFacts() {
    Column(
        Modifier.block(DS.palette.canvas).padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
    ) {
        Fact("map-point", L("Read once, not tracked"), L("Your area comes from where you are right now. drafft doesn't follow your moves or track you in the background."))
        Fact("radial-blur", L("Blurred before it leaves your phone"), L("Your position is rounded to about 1 km. Your exact spot is never sent or stored."))
        Fact("eye", L("What others see"), L("Your area, like a district, and a distance rounded to the kilometre. Never your address."))
    }
}

/** Step 1 of 2 for sports: pick them. */
@Composable
private fun SportsStep(state: OnboardingState) {
    StepTitle(L("How do you move?"), L("Pick up to 5 sports. Next, how often you do each."))
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        SportPicker(
            selected = state.sports.map { it.sport },
            onToggle = { s ->
                if (state.sports.any { it.sport == s }) {
                    state.sports = state.sports.filterNot { it.sport == s }
                } else if (state.sports.size < 5) {
                    state.sports = state.sports + SportEntry(sport = s)
                }
            },
            limit = 5,
        )
        Hint(if (state.sports.size >= 5) L("That's 5. Remove one to pick another.") else L("%d of 5 picked", state.sports.size))
    }
}

/** Step 2 of 2 for sports: how often. */
@Composable
private fun RhythmStep(state: OnboardingState) {
    StepTitle(L("How often?"), L("Sessions a week for each sport. Rough is fine."))
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        state.sports.forEach { entry ->
            Row(
                Modifier
                    .block(DS.palette.canvas, DS.Radius.lg)
                    .padding(start = DS.Space.lg, end = DS.Space.sm, top = DS.Space.sm, bottom = DS.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon(entry.sport.symbol, size = (17f * 1.2f).dp, tint = DS.palette.ink)
                    Text(entry.sport.displayName, style = TextStyles.headline, color = DS.palette.ink)
                }
                FrequencyStepper(
                    value = entry.perWeek,
                    onValueChange = { v -> state.sports = state.sports.map { if (it.sport == entry.sport) it.copy(perWeek = v) else it } },
                    sport = entry.sport.displayName,
                )
            }
        }
    }
}

@Composable
private fun PhotosStep(state: OnboardingState) {
    val store = state.store
    val moderation = koinInject<PhotoModeration>()
    val scope = rememberCoroutineScope()
    val pick = LocalPlatformUi.current.rememberPhotoPicker { data ->
        scope.launch {
            state.photoError = null
            // A photo that can't be read (a cloud copy offline), or a format that won't decode: said, never a
            // pick that does nothing.
            val path = data?.let { PhotoCompressor.savePicked(it) } ?: run {
                Haptics.warning()
                state.photoError = L("This photo couldn't be opened. Pick another one, or check your connection.")
                return@launch
            }
            val kept = store.persist(path)
            // Sent to the backend: compressed, uploaded, then judged by moderation (the tile shows it).
            moderation.submit(kept)
            state.photos = state.photos + kept
            Haptics.success()
        }
    }
    StepTitle(L("Show you in motion"), L("Add at least one clear face photo and one mid-session. Up to 6."))
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        ReorderablePhotoGrid(
            photos = state.photos,
            onPhotosChange = { state.photos = it },
            slots = 6,
            addButton = { AddPhotoTile(onClick = pick) },
            onRemove = { i ->
                // A draft on the server until sign-up ends: taken off the grid, it goes.
                moderation.discard(listOf(state.photos[i]))
                state.photos = state.photos.filterIndexed { j, _ -> j != i }
            },
            canRemoveLast = true,
        )
        PhotoSetHint(state.photosCheck)
    }
    // The first photo must show a face: it's the one people see first.
    LaunchedEffect(state.photos.firstOrNull() ?: "") {
        state.mainFace = null
        val face = PhotoSetCheck.face(state.photos.firstOrNull(), moderation)
        // The first photo changed meanwhile: its own check answers.
        if (!isActive) return@LaunchedEffect
        state.mainFace = face
    }
    // A sign-up resumed after the app was closed: each photo's verdict read again (or sent again).
    LaunchedEffect(state.photos) {
        state.photoError = null
        state.photos.forEach(moderation::ensureChecked)
    }
}

@Composable
private fun AddPhotoTile(onClick: () -> Unit) {
    val p = DS.palette
    val dashColor = p.ink.copy(alpha = 0.25f)
    val fill = p.canvas.copy(alpha = p.canvas.alpha * 0.6f)
    val addPhoto = L("Add photo")
    Box(
        Modifier
            .fillMaxSize()
            .pressScale(onClick)
            .drawBehind {
                val r = CornerRadius(DS.Radius.lg.toPx())
                drawRoundRect(fill, cornerRadius = r)
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    dashColor,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = r,
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .clearAndSetSemantics {
                contentDescription = addPhoto
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // `.title2.weight(.semibold)`.
        DrafftIcon("add", size = (22f * 1.2f).dp, tint = p.ink)
    }
}

@Composable
private fun VoiceStep(state: OnboardingState) {
    StepTitle(L("Say hi, out loud"), L("A 15-second voice intro. No pressure, you can redo it."))
    VoiceIntroRecorder(result = state.voice, onResultChange = { state.voice = it })
    // In a block, like every text on the page (no loose text on the canvas).
    Column(
        Modifier.block(DS.palette.canvas).padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        FieldLabel(L("Stuck? Talk about"))
        // One symbol per idea (never the same one repeated down a list).
        listOf(
            "sun" to L("What your perfect Sunday session looks like"),
            "flag-2" to L("The race you'd love to finish"),
            "chef-hat" to L("Your post-workout food ritual"),
        ).forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                DrafftIcon(icon, size = (15f * 1.2f).dp, tint = DS.palette.body)
                Text(text, style = TextStyles.subheadline, color = DS.palette.body)
            }
        }
    }
}

/** Right after "what are you training for": the rest of your routine, all optional. */
@Composable
private fun LifestyleStep(state: OnboardingState) {
    StepTitle(L("Your routine"), L("Shown on your profile. Answer what you like, leave the rest."))
    Box(Modifier.block(DS.palette.canvas).padding(DS.Space.xl)) {
        LifestylePicker(vitals = state.lifestyle, onVitalsChange = { state.lifestyle = it }, gender = Audience.fromAnswer(state.identity))
    }
}

/** Right after the photos: the two lines under your name. */
@Composable
private fun BioStep(state: OnboardingState) {
    StepTitle(L("A few words about you"), L("Two lines under your name: how you train, and what you're like after."))
    DrafftTextArea(
        title = L("Bio"),
        text = state.bio,
        onTextChange = { state.bio = it },
        prompt = L("Weekday dawn runner, weekend long rides…"),
    )
}

/**
 * Written prompts: pick a question, answer it. Up to 3, all optional. Nothing is filled in
 * for the person.
 */
@Composable
private fun PromptsStep(state: OnboardingState) {
    val p = DS.palette
    val focusManager = LocalFocusManager.current
    StepTitle(L("Answer a prompt"), L("Up to 3 short answers on your profile. People like one to start a chat."))
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        state.prompts.forEachIndexed { i, prompt ->
            androidx.compose.runtime.key(prompt.id) {
                PromptCard(
                    state = state,
                    index = i,
                    prompt = prompt,
                    onRemove = {
                        Haptics.tap()
                        focusManager.clearFocus()
                        state.promptFocus = null
                        state.prompts = state.prompts.filterNot { it.id == prompt.id }
                    },
                    onDone = {
                        Haptics.tap()
                        focusManager.clearFocus()
                        state.promptFocus = null
                    },
                )
            }
        }
        if (state.prompts.size < 3) {
            PressScaleButton(
                onClick = {
                    Haptics.select()
                    // The question is chosen in the picker; the card only appears once one is picked.
                    state.pickingPrompt = PromptSlot(state.prompts.size)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .background(p.canvas, RoundedCornerShape(DS.Radius.lg)),
                scale = 0.98f,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    DrafftIcon("add", size = (15f * 1.2f).dp, tint = p.accentInk)
                    Text(
                        if (state.prompts.isEmpty()) L("Choose a prompt") else L("Add another prompt"),
                        style = TextStyles.subheadline.semibold,
                        color = p.accentInk,
                    )
                }
            }
        }
    }
    val slot = state.pickingPrompt
    val shown = remember { arrayOfNulls<PromptSlot>(1) }
    if (slot != null) shown[0] = slot
    DrafftSheet(visible = slot != null, onDismissRequest = { state.pickingPrompt = null }) {
        val s = shown[0] ?: return@DrafftSheet
        PromptPickerSheet(
            current = state.prompts.getOrNull(s.id)?.question,
            used = state.prompts.map { it.question }.toSet(),
            onPick = { q ->
                state.prompts = if (s.id in state.prompts.indices) {
                    state.prompts.mapIndexed { j, pr -> if (j == s.id) pr.copy(question = q) else pr }
                } else {
                    state.prompts + ProfilePrompt(question = q, answer = "")
                }
            },
        )
    }
}

@Composable
private fun PromptCard(
    state: OnboardingState,
    index: Int,
    prompt: ProfilePrompt,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val p = DS.palette
    val focused = state.promptFocus == index
    val changeLabel = L("Question: %s. Change it", prompt.questionText)
    val removeLabel = L("Remove this prompt")
    val doneLabel = L("Done with this answer")
    // Bound by question, not position: removing a card never reads a stale index.
    fun setAnswer(v: String) {
        state.prompts = state.prompts.map { if (it.id == prompt.id) it.copy(answer = v) else it }
    }
    Column(
        Modifier
            .block(p.canvas, DS.Radius.lg)
            .padding(DS.Space.md),
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .defaultMinSize(minHeight = 44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        state.pickingPrompt = PromptSlot(index)
                    }
                    .clearAndSetSemantics {
                        contentDescription = changeLabel
                        role = Role.Button
                    },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(prompt.questionText, Modifier.weight(1f, fill = false), style = TextStyles.subheadline.semibold, color = p.body)
                DrafftIcon("chevrons-up-down", size = (11f * 1.2f).dp, tint = p.body)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onRemove)
                    .clearAndSetSemantics {
                        contentDescription = removeLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("trash-bin-minimalistic", size = (15f * 1.2f).dp, tint = p.body)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.Bottom) {
            val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
            Box(
                Modifier
                    .weight(1f)
                    .inputStyle(focused, fill = p.canvasSoft)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { focusRequester.requestFocus() }
                    .revealsOnFocus(focused),
                contentAlignment = Alignment.TopStart,
            ) {
                val style = TextStyles.body.semibold
                BasicTextField(
                    value = prompt.answer,
                    onValueChange = { setAnswer(it.limited(300)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .padding(vertical = DS.Space.sm + 2.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) state.promptFocus = index else if (state.promptFocus == index) state.promptFocus = null
                        },
                    textStyle = style.copy(color = p.ink),
                    minLines = 2,
                    maxLines = 5,
                    cursorBrush = SolidColor(p.accentInk),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box {
                            if (prompt.answer.isEmpty()) Text(L("Your answer"), style = style, color = p.mute)
                            inner()
                        }
                    },
                )
            }
            AnimatedVisibility(
                visible = focused,
                enter = scaleIn(Motion.snappy()) + fadeIn(Motion.snappy()),
                exit = scaleOut(Motion.snappy()) + fadeOut(Motion.snappy()),
            ) {
                // Done with this answer: closes the keyboard, stays on the step.
                PressScaleButton(
                    onClick = onDone,
                    modifier = Modifier.size(44.dp).background(p.lime, CircleShape),
                    contentDescription = doneLabel,
                ) {
                    DrafftIcon("check", size = (17f * 1.2f).dp, tint = p.onLime)
                }
            }
        }
    }
}

@Composable
private fun IcebreakerStep(state: OnboardingState) {
    StepTitle(L("Give them an easy opener"), L("Pick a format for your interactive prompt. Matches play it and reply in one tap."))
    IcebreakerEditor(icebreaker = state.icebreaker, onIcebreakerChange = { state.icebreaker = it })
}

// MARK: - Frequency stepper

/** Compact − n× a week + control. */
@Composable
fun FrequencyStepper(value: Int, onValueChange: (Int) -> Unit, sport: String, modifier: Modifier = Modifier) {
    val label = L("%s sessions per week", sport)
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = "$value"
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 1f..7f, steps = 5)
            setProgress { target ->
                val v = Math.round(target).coerceIn(1, 7)
                if (v != value) onValueChange(v)
                true
            }
        },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton("minus", enabled = value > 1) { onValueChange(value - 1) }
        RollingText(
            if (value >= 7) L("Every day") else L("%d× a week", value),
            modifier = Modifier.widthIn(min = 84.dp),
            style = TextStyles.subheadline.bold.monospacedDigits.copy(textAlign = TextAlign.Center),
            color = DS.palette.ink,
            countsDown = false,
        )
        StepperButton("add", enabled = value < 7) { onValueChange(value + 1) }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, action: () -> Unit) {
    PressScaleButton(
        onClick = {
            Haptics.select()
            action()
        },
        modifier = Modifier.size(44.dp),
        enabled = enabled,
    ) {
        Box(Modifier.size(36.dp).background(DS.palette.canvasSoft, CircleShape), contentAlignment = Alignment.Center) {
            // `.footnote.weight(.heavy)`.
            DrafftIcon(symbol, size = (13f * 1.2f).dp, tint = if (enabled) DS.palette.ink else DS.palette.mute)
        }
    }
}

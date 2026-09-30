package so.drafft.app.feature.me

import so.drafft.core.ui.components.InteractiveDismissDisabled
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.auth.FrequencyStepper
import so.drafft.app.feature.profile.IcebreakerEditor
import so.drafft.app.feature.profile.LifestylePicker
import so.drafft.app.feature.profile.VoiceIntroRecorder
import so.drafft.core.data.audio.VoiceRecorder
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.media.PhotoCompressor
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.model.SportEntry
import so.drafft.core.model.Vitals
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.AdaptiveRow
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DraftGlyph
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.DrafftTextArea
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SportPicker
import so.drafft.core.ui.components.SportsLine
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.components.limited
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.navigation.NavStack
import so.drafft.core.ui.navigation.NavStackHost
import so.drafft.core.ui.navigation.rememberNavStack
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/EditProfileView.swift.

/**
 * Pages follow the profile as others read it: who you are and what you're after, then how you move,
 * then what you say in your own words. (`EditProfileView.Page` on iOS.)
 */
enum class EditProfilePage(val rawValue: String) {
    PHOTOS("photos"), IDENTITY("identity"), LIFESTYLE("lifestyle"), SPORTS("sports"),
    GOAL("goal"), BIO("bio"), PROMPTS("prompts"), VOICE("voice");

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            PHOTOS -> L("Photos")
            BIO -> L("Bio")
            PROMPTS -> L("Prompts")
            VOICE -> L("Voice intro")
            SPORTS -> L("Sports")
            GOAL -> L("Training for")
            IDENTITY -> L("Name & age")
            LIFESTYLE -> L("Lifestyle")
        }

    val icon: String
        get() = when (this) {
            PHOTOS -> "photo.on.rectangle"
            BIO -> "text.quote"
            PROMPTS -> "quote.bubble.fill"
            VOICE -> "waveform"
            SPORTS -> "figure.run"
            GOAL -> "flag.checkered"
            IDENTITY -> "person.text.rectangle"
            LIFESTYLE -> "leaf.fill"
        }
}

/** Which field has the keyboard (`EditProfileView.Field` on iOS). */
private sealed interface EditField {
    data object Name : EditField
    data object Goal : EditField
    data class Prompt(val index: Int) : EditField
}

/** Edit profile's draft: nothing changes until "Save changes". */
@Stable
private class EditProfileState(profile: Profile) {
    var original by mutableStateOf(profile)
    var draft by mutableStateOf(profile)
    var vitals by mutableStateOf(profile.vitals ?: Vitals.blank)
    var prompts by mutableStateOf(profile.prompts)
    var voice by mutableStateOf<VoiceRecorder.Recording?>(null)
    var confirmDiscard by mutableStateOf(false)
    var saved by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var pickingPrompt by mutableStateOf<Int?>(null)
    var focus by mutableStateOf<EditField?>(null)

    val edited: Profile
        get() {
            var p = draft.copy(
                vitalsOverride = vitals,
                promptsOverride = prompts.filter { it.answer.isNotBlank() },
            )
            voice?.let { p = p.copy(voiceIntro = it.url, voiceDuration = it.duration) }
            return p
        }

    // Compared as on the iPhone, where the draft's vitals are set and the saved ones may be nil.
    val hasChanges: Boolean
        get() = draft != original || vitals != original.vitals || prompts != original.prompts || voice != null

    val canSave: Boolean
        get() = hasChanges && draft.name.isNotBlank() && draft.sports.isNotEmpty() &&
            (draft.icebreaker.isComplete || draft.icebreaker.isBlank)

    val allPhotos: List<String> get() = listOf(draft.portrait) + draft.photos

    fun setPhotos(list: List<String>) {
        val first = list.firstOrNull() ?: return
        draft = draft.copy(portrait = first, photos = list.drop(1))
    }

    /** One-line preview of what's in each page. */
    fun summary(page: EditProfilePage): String = when (page) {
        EditProfilePage.PHOTOS -> if (allPhotos.size == 1) L("1 photo") else L("%d photos", allPhotos.size)
        EditProfilePage.BIO -> draft.bio.ifEmpty { L("Add a few words about how you move") }
        EditProfilePage.IDENTITY -> "${draft.name.ifEmpty { L("No name") }}, ${draft.age}"
        EditProfilePage.LIFESTYLE -> lifestyleSummary
        EditProfilePage.SPORTS -> draft.sports.joinToString(", ") { it.sport.displayName }
        EditProfilePage.VOICE -> if (voice != null || draft.voiceIntro != null) L("Recorded") else L("Not recorded yet")
        EditProfilePage.PROMPTS -> when {
            draft.icebreaker.isBlank && prompts.isEmpty() -> L("Add a prompt")
            draft.icebreaker.isBlank -> L("%d written", prompts.size)
            else -> L("%s, %d written", draft.icebreaker.kind.title, prompts.size)
        }
        EditProfilePage.GOAL -> draft.goal.ifEmpty { L("Add a goal") }
    }

    /** Pages that need attention before saving. */
    fun needsAttention(page: EditProfilePage): Boolean = when (page) {
        EditProfilePage.IDENTITY -> draft.name.isBlank()
        EditProfilePage.BIO -> draft.bio.length > 200
        EditProfilePage.SPORTS -> draft.sports.isEmpty()
        EditProfilePage.PROMPTS -> !draft.icebreaker.isComplete && !draft.icebreaker.isBlank
        else -> false
    }

    private val lifestyleSummary: String
        get() {
            val drinks = when (vitals.drinks) {
                "" -> ""
                "Never" -> L("No alcohol")
                "Rarely" -> L("Drinks rarely")
                "Socially" -> L("Drinks socially")
                "Post-race only" -> L("Drinks post-race only")
                else -> vitals.drinks
            }
            val parts = listOf(Vitals.label(vitals.chronotype), Vitals.label(vitals.diet), drinks).filter { it.isNotEmpty() }
            return if (parts.isEmpty()) L("Not filled in") else parts.joinToString(", ")
        }

    val footerHint: String
        get() = saveError ?: when {
            saved -> L("Your profile is up to date.")
            !hasChanges -> L("Make a change to save it.")
            draft.sports.isEmpty() -> L("Add at least one sport.")
            draft.name.isBlank() -> L("Add your first name.")
            !draft.icebreaker.isComplete && !draft.icebreaker.isBlank -> L("Finish your interactive prompt.")
            else -> L("Saves everything you've changed.")
        }

    /** A prompt's answer, by the prompt's id. */
    fun setAnswer(id: String, value: String) {
        prompts = prompts.map { if (it.id == id) it.copy(answer = value) else it }
    }
}

private val groups: List<Pair<String, List<EditProfilePage>>>
    get() = listOf(
        L("The basics") to listOf(EditProfilePage.PHOTOS, EditProfilePage.IDENTITY, EditProfilePage.LIFESTYLE),
        L("Your sport") to listOf(EditProfilePage.SPORTS, EditProfilePage.GOAL),
        L("In your words") to listOf(EditProfilePage.BIO, EditProfilePage.PROMPTS, EditProfilePage.VOICE),
    )

private const val ROOT = "editProfile"

/**
 * Edit your own profile. Works on a draft; nothing changes until "Save changes". Present it in a
 * `DrafftSheet`: its pages push inside the sheet.
 */
@Composable
fun EditProfileView(profile: Profile, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val profileSync = koinInject<ProfileSync>()
    val moderation = koinInject<PhotoModeration>()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val state = remember { EditProfileState(profile) }
    val stack = rememberNavStack(ROOT)

    val close = { if (state.hasChanges) state.confirmDiscard = true else dismiss() }
    InteractiveDismissDisabled(state.hasChanges || state.saving)

    // Saves the whole draft. From a sub-page it returns to the list; from the list it closes the editor.
    val save: () -> Unit = save@{
        if (!state.canSave || state.saving) return@save
        state.focus = null
        focusManager.clearFocus()
        val result = state.edited
        val previous = state.original
        val recorded = state.voice
        state.saveError = null
        state.saving = true
        scope.launch {
            try {
                // Saved on the server first; nothing changes in the app if it fails (signed out included).
                try {
                    profileSync.save(result, previous, recorded?.let { ProfileSync.Voice(it.url, it.duration, it.levels) })
                } catch (e: Exception) {
                    Haptics.warning()
                    state.saveError = when (e) {
                        is Backend.BackendError -> e.message
                        is ProfileSync.SyncError -> e.message
                        else -> null
                    } ?: L("Couldn't connect. Check your connection and try again.")
                    return@launch
                }
                Haptics.success()
                app.me = result
                state.saved = true
                val fromSubPage = stack.canPop
                delay(150)
                if (fromSubPage) {
                    state.original = result
                    state.draft = result
                    state.voice = null
                    state.saved = false
                    stack.popToRoot()
                } else {
                    dismiss()
                }
            } finally {
                state.saving = false
            }
        }
    }

    val pickPhoto = LocalPlatformUi.current.rememberPhotoPicker { data ->
        scope.launch {
            val path = PhotoCompressor.savePicked(data) ?: return@launch
            state.setPhotos(state.allPhotos + path)
            Haptics.success()
            // Sent to the backend: compressed, uploaded, then judged by moderation (the tile shows it).
            moderation.submit(path)
        }
    }

    LaunchedEffect(state.draft) { state.saveError = null }

    NavStackHost(stack, modifier) { route ->
        val footer: @Composable () -> Unit = { Footer(state, save) }
        if (route is EditProfilePage) {
            SubPage(route, state, stack, close, footer, pickPhoto)
        } else {
            ListPage(state, stack, close, footer)
        }
    }

    DrafftConfirm(
        visible = state.confirmDiscard,
        onDismissRequest = { state.confirmDiscard = false },
        icon = "trash",
        title = L("Discard your changes?"),
        message = L("What you changed since your last save will be lost."),
        cancelTitle = L("Keep editing"),
        actions = listOf(ConfirmAction(L("Discard changes"), ConfirmAction.Kind.DESTRUCTIVE) { dismiss() }),
    )

    state.pickingPrompt?.let { slot ->
        DrafftSheet(onDismissRequest = { state.pickingPrompt = null }) {
            PromptPickerSheet(
                current = state.prompts.getOrNull(slot)?.question,
                used = state.prompts.map { it.question }.toSet(),
                onPick = { q ->
                    if (slot in state.prompts.indices) {
                        state.prompts = state.prompts.mapIndexed { i, p -> if (i == slot) p.copy(question = q) else p }
                    } else if (state.prompts.size < 3) {
                        // A new prompt joins only once its question is picked.
                        state.prompts = state.prompts + ProfilePrompt(question = q, answer = "")
                    }
                },
            )
        }
    }
}

@Composable
private fun ListPage(state: EditProfileState, stack: NavStack, close: () -> Unit, footer: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    SheetPage(L("Edit profile"), scroll, onClose = close, bottomBar = footer) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(horizontal = DS.Space.lg, vertical = DS.Space.sm),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            groups.forEach { (title, pages) ->
                key(title) { CategoryGroup(title, pages, state) { stack.push(it) } }
            }
        }
    }
}

@Composable
private fun CategoryGroup(title: String, pages: List<EditProfilePage>, state: EditProfileState, open: (EditProfilePage) -> Unit) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(horizontal = DS.Space.lg),
    ) {
        Text(
            title,
            Modifier
                .padding(top = DS.Space.lg, bottom = DS.Space.xs)
                .semantics { heading() },
            style = TextStyles.footnote.bold,
            color = p.mute,
        )
        pages.forEachIndexed { i, page ->
            val attention = state.needsAttention(page)
            val needsAttention = L("Needs attention")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open(page) }
                    .padding(vertical = DS.Space.md),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DraftGlyph(page.icon, size = 40.dp, fill = p.canvasSoft, glyph = p.ink)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(page.title, style = TextStyles.body.semibold, color = p.ink)
                    if (page == EditProfilePage.SPORTS && state.draft.sports.isNotEmpty()) {
                        SportsLine(state.draft.sports.map { it.sport }, style = TextStyles.footnote, color = p.body)
                    } else {
                        Text(
                            state.summary(page),
                            style = TextStyles.footnote,
                            color = if (attention) p.negative else p.body,
                            // A name is never truncated: the identity line wraps instead.
                            maxLines = if (page == EditProfilePage.IDENTITY) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (attention) {
                    DrafftIcon(
                        "exclamationmark.circle.fill",
                        size = symbolSize(TextStyles.body),
                        tint = p.negative,
                        contentDescription = needsAttention,
                    )
                }
                DrafftIcon("chevron.right", size = symbolSize(TextStyles.footnote), tint = p.mute)
            }
            if (i < pages.size - 1) Hairline(start = 52.dp)
        }
    }
}

@Composable
private fun SubPage(
    page: EditProfilePage,
    state: EditProfileState,
    stack: NavStack,
    close: () -> Unit,
    footer: @Composable () -> Unit,
    pickPhoto: () -> Unit,
) {
    val scroll = rememberScrollState()
    // Every page keeps its validate button in view, disabled until there's something valid to save.
    SheetPage(
        title = page.title,
        scroll = scroll,
        onClose = close,
        leading = { GlassCircleButton("chevron.left", onClick = { stack.pop() }, contentDescription = L("Back")) },
        bottomBar = footer,
    ) { bars ->
        FocusScrollView(state = scroll, contentPadding = bars) {
            Column(
                Modifier.padding(horizontal = DS.Space.lg, vertical = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            ) {
                when (page) {
                    EditProfilePage.PHOTOS ->
                        Block(L("Photos"), page.icon, note = L("Hold a photo, then drag to reorder")) { PhotosGrid(state, pickPhoto) }
                    EditProfilePage.BIO ->
                        Block(L("Your bio"), page.icon, note = L("Optional")) {
                            DrafftTextArea(
                                title = L("Bio"),
                                text = state.draft.bio,
                                onTextChange = { state.draft = state.draft.copy(bio = it) },
                                prompt = L("Weekday dawn runner, weekend long rides…"),
                                fill = DS.palette.canvasSoft,
                                showsTitle = false,
                            )
                        }
                    EditProfilePage.IDENTITY -> Block(L("Name & age"), page.icon) { IdentitySection(state) }
                    EditProfilePage.LIFESTYLE ->
                        Block(L("Lifestyle"), page.icon, note = L("Shown on your profile")) {
                            LifestylePicker(vitals = state.vitals, onVitalsChange = { state.vitals = it })
                        }
                    EditProfilePage.SPORTS -> Block(L("Your sports"), page.icon, note = L("Up to 5")) { SportsSection(state) }
                    EditProfilePage.VOICE ->
                        Block(L("Voice intro"), page.icon) {
                            VoiceIntroRecorder(result = state.voice, onResultChange = { state.voice = it }, framed = false)
                        }
                    EditProfilePage.PROMPTS -> {
                        Block(L("Interactive prompt"), "hand.tap.fill", note = L("What you write is what they see")) {
                            IcebreakerEditor(
                                icebreaker = state.draft.icebreaker,
                                onIcebreakerChange = { state.draft = state.draft.copy(icebreaker = it) },
                            )
                        }
                        Block(L("Written prompts"), page.icon, note = L("Up to 3")) { PromptsSection(state) }
                    }
                    EditProfilePage.GOAL -> Block(L("What's your next goal?"), page.icon) { GoalSection(state) }
                }
            }
        }
    }
}

// MARK: Photos

@Composable
private fun PhotosGrid(state: EditProfileState, pickPhoto: () -> Unit) {
    val p = DS.palette
    ReorderablePhotoGrid(
        photos = state.allPhotos,
        onPhotosChange = { state.setPhotos(it) },
        slots = 6,
        addButton = {
            PressScaleButton(
                onClick = pickPhoto,
                modifier = Modifier
                    .fillMaxSize()
                    .background(p.canvasSoft, RoundedCornerShape(DS.Radius.lg))
                    .dashedBorder(p.ink.copy(alpha = 0.2f), DS.Radius.lg),
                scale = 0.97f,
                contentDescription = L("Add photo"),
            ) {
                DrafftIcon("plus", size = 24.dp, tint = p.ink)
            }
        },
        onRemove = { i ->
            val list = state.allPhotos.toMutableList()
            list.removeAt(i)
            state.setPhotos(list)
        },
    )
}

/** The add tile's dashed outline (1.5 pt, dashes of 6 with gaps of 5). */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehindDashed(color, radius)

private fun Modifier.drawBehindDashed(color: Color, radius: Dp): Modifier =
    this.then(
        Modifier.drawWithCache {
            val stroke = 1.5.dp.toPx()
            val r = radius.toPx()
            val style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            onDrawBehind {
                val inset = stroke / 2
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - inset),
                    style = style,
                )
            }
        },
    )

// MARK: About

@Composable
private fun IdentitySection(state: EditProfileState) {
    val p = DS.palette
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
            LabeledField(
                title = L("First name"),
                text = state.draft.name,
                onTextChange = { state.draft = state.draft.copy(name = it.limited(40)) },
                field = EditField.Name,
                state = state,
            )
            Text(L("Only your first name is shown."), style = TextStyles.footnote, color = p.mute)
        }
        Hairline()
        // The birthday is set once, at sign-up (the server keeps it from changing).
        LockedField(
            title = L("Birthday"),
            value = state.draft.birthday?.let { birthday ->
                // Kept at midnight UTC: shown as that calendar day whatever the phone's time zone.
                val day = birthday.atZone(ZoneOffset.UTC).toLocalDate().atTime(12, 0).atZone(DateText.zone).toInstant()
                DateText.format("yMMMMd", day)
            } ?: L("%d years old", state.draft.age),
            hint = L("Set at sign-up. It can't be changed."),
        )
    }
}

// MARK: Sports

@Composable
private fun SportsSection(state: EditProfileState) {
    val p = DS.palette
    Column(Modifier.animateContentSize(Motion.snappy()), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        state.draft.sports.forEach { entry ->
            key(entry.sport) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.canvasSoft, RoundedCornerShape(DS.Radius.lg))
                        .padding(DS.Space.md),
                    verticalArrangement = Arrangement.spacedBy(DS.Space.md),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.CenterVertically) {
                        DraftGlyph(entry.sport.symbol, size = 40.dp)
                        Text(entry.sport.displayName, Modifier.weight(1f), style = displayBold(20f), color = p.ink)
                        if (state.draft.sports.size > 1) {
                            PressScaleButton(
                                onClick = {
                                    Haptics.tap()
                                    val s = entry.sport
                                    state.draft = state.draft.copy(sports = state.draft.sports.filter { it.sport != s })
                                },
                                modifier = Modifier.size(44.dp),
                                scale = 1f,
                                contentDescription = L("Remove %s", entry.sport.displayName),
                            ) {
                                DrafftIcon("trash", size = symbolSize(TextStyles.subheadline), tint = p.body)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(L("How often"), Modifier.weight(1f), style = TextStyles.subheadline.semibold, color = p.body)
                        FrequencyStepper(
                            value = entry.perWeek,
                            onValueChange = { v ->
                                state.draft = state.draft.copy(
                                    sports = state.draft.sports.map { if (it.sport == entry.sport) it.copy(perWeek = v) else it },
                                )
                            },
                            sport = entry.sport.displayName,
                        )
                    }
                }
            }
        }
        SportPicker(
            selected = state.draft.sports.map { it.sport },
            onToggle = { s ->
                val sports = state.draft.sports
                if (sports.any { it.sport == s }) {
                    state.draft = state.draft.copy(sports = sports.filter { it.sport != s })
                } else if (sports.size < 5) {
                    state.draft = state.draft.copy(sports = sports + SportEntry(sport = s))
                }
            },
            modifier = Modifier.padding(top = DS.Space.xs),
            limit = 5,
            chipBackground = p.canvasSoft,
            collapsedCount = 16,
        )
    }
}

// MARK: Prompts

@Composable
private fun PromptsSection(state: EditProfileState) {
    val p = DS.palette
    val focusManager = LocalFocusManager.current
    Column(Modifier.animateContentSize(Motion.snappy()), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        state.prompts.forEachIndexed { i, prompt ->
            key(prompt.id) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.canvasSoft, RoundedCornerShape(DS.Radius.lg))
                        .padding(DS.Space.md),
                    verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                    state.pickingPrompt = i
                                },
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(prompt.questionText, Modifier.weight(1f, fill = false), style = TextStyles.subheadline.semibold, color = p.body)
                            DrafftIcon("chevron.up.chevron.down", size = symbolSize(TextStyles.caption2), tint = p.body)
                        }
                        PressScaleButton(
                            onClick = {
                                Haptics.tap()
                                state.focus = null
                                focusManager.clearFocus()
                                state.prompts = state.prompts.filter { it.id != prompt.id }
                            },
                            modifier = Modifier.size(44.dp),
                            scale = 1f,
                            contentDescription = L("Remove prompt"),
                        ) {
                            DrafftIcon("trash", size = symbolSize(TextStyles.subheadline), tint = p.body)
                        }
                    }
                    InputField(
                        text = prompt.answer,
                        onTextChange = { state.setAnswer(prompt.id, it.limited(300)) },
                        prompt = L("Your answer"),
                        field = EditField.Prompt(i),
                        state = state,
                        style = TextStyles.body.semibold,
                        minLines = 2,
                        maxLines = 5,
                        fill = p.canvas,
                    )
                }
            }
        }
        if (state.prompts.size < 3) {
            PressScaleButton(
                onClick = {
                    Haptics.select()
                    // Nothing pre-picked: the library opens with no selection, and the prompt is only
                    // added once a question is chosen (closing adds nothing).
                    state.pickingPrompt = state.prompts.size
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .background(p.canvasSoft, RoundedCornerShape(DS.Radius.lg)),
                scale = 0.97f,
            ) {
                IconLabel(L("Add a prompt"), "plus", style = TextStyles.subheadline.semibold, color = p.accentInk)
            }
        }
    }
}

// MARK: Goal

@Composable
private fun GoalSection(state: EditProfileState) {
    val p = DS.palette
    val goalIdeas = listOf(
        L("First 10k"), L("Sub-4h marathon"), L("Climb a 7a"), L("Swim 2k non-stop"),
        L("Ride 100k in a day"), L("Hold a 2 min plank"), L("First trail race"), L("Win the club tournament"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Text(
            L("It shows on your profile as “Training for…”. Big or small, it's a great conversation starter."),
            style = TextStyles.footnote,
            color = p.body,
        )
        InputField(
            text = state.draft.goal,
            onTextChange = { state.draft = state.draft.copy(goal = it.limited(200)) },
            prompt = L("e.g. Sub-4h marathon in Berlin"),
            field = EditField.Goal,
            state = state,
            style = TextStyles.body.semibold,
            minLines = 1,
            maxLines = 3,
            imeAction = ImeAction.Done,
        )
        Text(L("Need inspiration?"), style = TextStyles.footnote.semibold, color = p.mute)
        FlowLayout(spacing = DS.Space.sm) {
            goalIdeas.forEach { o ->
                ChoiceChip(o, on = o == state.draft.goal, onClick = {
                    Haptics.select()
                    state.draft = state.draft.copy(goal = o)
                })
            }
        }
    }
}

// MARK: Footer

@Composable
private fun Footer(state: EditProfileState, save: () -> Unit) {
    val p = DS.palette
    val hintColor by animateColorAsState(
        if (state.saveError != null || (state.hasChanges && !state.canSave)) p.negative else p.body,
        Motion.snappy(),
        label = "footerHint",
    )
    Column(
        Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrafftButton(
            onClick = save,
            modifier = Modifier
                .padding(start = 12.dp)
                .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
            enabled = (state.canSave || state.saved) && !state.saving,
        ) {
            if (state.saving) {
                ButtonSpinner(p.onLime)
            } else {
                AnimatedContent(
                    targetState = state.saved,
                    transitionSpec = { fadeIn(Motion.snappy()) togetherWith fadeOut(Motion.snappy()) },
                    label = "saved",
                ) { saved ->
                    IconLabel(
                        if (saved) L("Saved") else L("Save changes"),
                        if (saved) "checkmark" else "arrow.down.circle.fill",
                        style = TextStyles.body.semibold,
                        color = LocalContentColor.current,
                        maxLines = 2,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = state.footerHint,
            transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
            label = "footerHint",
        ) { hint ->
            Text(hint, style = TextStyles.footnote, color = hintColor, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

// MARK: Chrome

@Composable
private fun Block(
    title: String,
    icon: String,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        // The note shares the title's line while both fit, under it otherwise.
        AdaptiveRow(
            leading = {
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    DrafftIcon(icon, size = symbolSize(TextStyles.footnote), tint = p.ink)
                    Text(title, Modifier.semantics { heading() }, style = TextStyles.headline, color = p.ink)
                }
            },
            trailing = {
                if (note != null) Text(note, style = TextStyles.footnote, color = p.mute)
            },
        )
        content()
    }
}

/** A field with its label above (subheadline semibold, body grey). */
@Composable
private fun LabeledField(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    field: EditField,
    state: EditProfileState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Text(title, style = TextStyles.subheadline.semibold, color = DS.palette.body)
        InputField(
            text = text,
            onTextChange = onTextChange,
            prompt = title,
            field = field,
            state = state,
            style = TextStyles.body,
            minLines = 1,
            maxLines = 1,
            capitalization = KeyboardCapitalization.Words,
        )
    }
}

/** A text field in [inputStyle]: the whole box takes the tap, and it scrolls clear of the keyboard. */
@Composable
private fun InputField(
    text: String,
    onTextChange: (String) -> Unit,
    prompt: String,
    field: EditField,
    state: EditProfileState,
    style: TextStyle,
    minLines: Int,
    maxLines: Int,
    fill: Color = DS.palette.canvasSoft,
    imeAction: ImeAction = ImeAction.Default,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    val p = DS.palette
    val focus = remember { FocusRequester() }
    val focused = state.focus == field
    val focusManager = LocalFocusManager.current
    Box(
        Modifier
            .inputStyle(focused, fill)
            .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .revealsOnFocus(focused),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = DS.Space.sm + 2.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    if (it.isFocused) state.focus = field else if (state.focus == field) state.focus = null
                },
            textStyle = style.copy(color = p.ink),
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            cursorBrush = SolidColor(p.accentInk),
            keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(prompt, style = style, color = p.mute)
                    inner()
                }
            },
        )
    }
}

/**
 * Sage input with an ink ring when focused. Height comes from the field's own frame (not padding),
 * so the whole box takes touches.
 */
@Composable
fun Modifier.inputStyle(focused: Boolean, fill: Color = DS.palette.canvasSoft): Modifier {
    val ring by animateColorAsState(if (focused) DS.palette.ink else Color.Transparent, Motion.snappy(), label = "inputRing")
    val shape = RoundedCornerShape(DS.Radius.md)
    return this
        .fillMaxWidth()
        .background(fill, shape)
        .border(1.5.dp, ring, shape)
        .padding(vertical = 2.dp, horizontal = DS.Space.md)
        .defaultMinSize(minHeight = 48.dp)
}

/** The sheet a prompt is picked in: which prompt (by index) it's for. */
data class PromptSlot(val id: Int)

/**
 * A value you can see but not change, laid out like the fields around it (same label, same box), but
 * plainly disabled: a greyed box sunk into the block instead of the white of a live field, greyed
 * text, a lock, and why it's locked underneath.
 */
@Composable
private fun LockedField(title: String, value: String, hint: String) {
    val p = DS.palette
    val cantChange = L("Can't be changed")
    Column(
        Modifier.semantics(mergeDescendants = true) { stateDescription = cantChange },
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            Text(title, style = TextStyles.subheadline.semibold, color = p.body)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(p.ink.copy(alpha = 0.06f), RoundedCornerShape(DS.Radius.md))
                    .padding(vertical = 2.dp, horizontal = DS.Space.md)
                    .defaultMinSize(minHeight = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, Modifier.weight(1f), style = TextStyles.body, color = p.mute)
                DrafftIcon("lock.fill", size = symbolSize(TextStyles.footnote), tint = p.mute)
            }
        }
        Text(hint, style = TextStyles.footnote, color = p.mute)
    }
}

package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/ProposeSessionSheet.swift.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.app.feature.me.SafetyTipRows
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.SessionProposal
import so.drafft.core.model.Sport
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

/** The native time sheet: a new time, or changing time #index. */
private data class TimeSheetTarget(val index: Int?, val date: Instant?) {
    val id: String get() = "${index ?: -1}-${date?.epochSecond ?: 0}"
}

private const val MAX_OPTIONS = 3

/**
 * Session invite composer: sport, pitch, and one or more exact dates and times. Lets you pick
 * any other time. A live recap above the button always states exactly what will be sent.
 * Sheet content: present it in a `DrafftSheet`.
 */
@Composable
fun ProposeSessionSheet(
    profile: Profile,
    me: Profile,
    sport: Sport? = null,
    sendTitle: String = L("Send session invite"),
    sendHint: String? = null,
    /** Set when answering an invite with other times. */
    counterTo: SessionProposal? = null,
    onSend: (SessionProposal) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val dismiss = LocalSheetDismiss.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Prefer a slot of the preset sport, ideally one you both already train in.
    val initialSport = remember(profile.id, counterTo?.id) {
        val shared = profile.sports.firstOrNull { e -> me.sports.any { it.sport == e.sport } }?.sport
        counterTo?.sport ?: sport ?: shared ?: profile.sports.firstOrNull()?.sport ?: me.sports.firstOrNull()?.sport ?: Sport.RUNNING
    }
    var chosen by rememberSaveable(profile.id) { mutableStateOf(initialSport) }
    // A sport only one of you does starts as a discovery session.
    var discovery by rememberSaveable(profile.id) {
        val theyDo = profile.sports.any { it.sport == initialSport }
        val iDo = me.sports.any { it.sport == initialSport }
        mutableStateOf(
            counterTo?.discovery
                ?: if (theyDo && iDo) null else if (theyDo) SessionProposal.Discovery.THEY_TEACH else SessionProposal.Discovery.I_TEACH,
        )
    }
    var title by rememberSaveable(profile.id) { mutableStateOf(counterTo?.title ?: "") }
    var sending by remember { mutableStateOf(false) }
    // The times offered (1 to 3). The other person picks one or suggests others.
    // Nothing picked yet: no time card until the person adds one.
    var options by remember { mutableStateOf(emptyList<Instant>()) }
    // Captures the time when the sheet opens: the sheet never reads `options` by index again
    // (removing a time while it was open read past the end and crashed).
    var timeSheet by remember { mutableStateOf<TimeSheetTarget?>(null) }

    // MARK: Derived

    /** Sports you do that they don't: you can introduce them. */
    val youTeach = me.sports.map { it.sport }.filter { s -> profile.sports.none { it.sport == s } }

    /** Sports they do that you don't: they can show you. */
    val theyTeach = profile.sports.map { it.sport }.filter { s -> me.sports.none { it.sport == s } }

    /** What gets sent: the options in time order. */
    val outgoingOptions = options.sorted()
    val sessionDate = outgoingOptions.firstOrNull() ?: Instant.now()
    val isCounter = counterTo != null

    /** Two times that are the same. */
    val hasDuplicate = options.map { it.epochSecond / 60 }.toSet().size < options.size

    /** A picked time that has since passed. */
    val hasPast = options.any { it.isBefore(Instant.now()) }
    val canSend = !sending && options.isNotEmpty() && !hasPast && !hasDuplicate

    /** The line under the send button: why it's disabled (red for a real error), or the hint. */
    val sendReason: Pair<String, Boolean>? = when {
        options.isEmpty() -> L("Pick at least one time to send.") to false
        hasPast -> L("One of your times has passed.") to true
        hasDuplicate -> L("Two of your times are the same.") to true
        else -> sendHint?.let { it to false }
    }

    fun compose(index: Int?) {
        Haptics.select()
        timeSheet = TimeSheetTarget(index, index?.let { options.getOrNull(it) })
    }

    fun removeTime(date: Instant) {
        options = options.filterNot { abs(it.epochSecond - date.epochSecond) < 60 }
    }

    fun send() {
        if (!canSend) return
        focusManager.clearFocus()
        Haptics.success()
        sending = true
        val proposal = SessionProposal(
            sport = chosen,
            options = outgoingOptions,
            title = title.trim(),
            note = "",
            discovery = discovery,
        )
        scope.launch {
            delay(120)
            onSend(proposal)
            dismiss()
        }
    }

    val scroll = rememberScrollState()
    EdgeBars(
        scroll,
        modifier.fillMaxSize().background(p.canvasSoft),
        // Close sits on the right, like every other sheet in the app.
        topBar = { SheetNavBar("", onClose = dismiss) },
        bottomBar = {
            Footer(
                sport = chosen,
                discovery = discovery,
                options = options,
                outgoingOptions = outgoingOptions,
                sessionDate = sessionDate,
                title = title,
                isCounter = isCounter,
                sending = sending,
                sendTitle = sendTitle,
                canSend = canSend,
                sendReason = sendReason,
                onSend = { send() },
            )
        },
        navigationEdge = true,
    ) { padding ->
        FocusScrollView(state = scroll, contentPadding = padding) {
            Column(
                Modifier
                    .padding(horizontal = DS.Space.lg)
                    .padding(top = DS.Space.sm, bottom = DS.Space.xl),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            ) {
                Header(profile, counterTo)
                if (counterTo != null) {
                    // Other times: the session stays as it is, only the times change.
                    FixedSession(counterTo)
                } else {
                    Block(L("Sport"), "figure.run") {
                        SportPicker(profile, me, theyTeach, youTeach, chosen, discovery) { s, d ->
                            Haptics.select()
                            chosen = s
                            discovery = d
                        }
                    }
                    Block(L("Pitch it"), "quote.bubble", trailing = L("Optional")) {
                        TitlePicker(title, { title = it }, pitchIdeas(chosen, discovery), chosen, discovery)
                    }
                }
                Block(L("When"), "calendar", trailing = L("Up to %d times", MAX_OPTIONS)) {
                    SlotsEditor(profile, options, onCompose = { compose(it) })
                }
                Block(L("Meet safely"), "shield.lefthalf.filled") { SafetyTipRows() }
            }
        }
    }

    timeSheet?.let { target ->
        key(target.id) {
            DrafftSheet(onDismissRequest = { timeSheet = null }) {
                SessionTimeSheet(
                    initial = target.date,
                    taken = options.filter { o -> target.date?.let { abs(o.epochSecond - it.epochSecond) >= 60 } ?: true },
                    onSave = { d ->
                        val old = target.date
                        val kept = if (old != null) options.filterNot { abs(it.epochSecond - old.epochSecond) < 60 } else options
                        options = (kept + d).sorted()
                    },
                    onRemove = target.date?.let { old -> { removeTime(old) } },
                )
            }
        }
    }
}

private fun pitchIdeas(sport: Sport, discovery: SessionProposal.Discovery?): List<String> = when (discovery) {
    SessionProposal.Discovery.I_TEACH -> listOf(
        L("First %s session? I'll show you the basics.", sport.inSentence),
        L("Try %s with me, zero pressure?", sport.inSentence),
        L("Beginner-friendly %s, I'll bring the gear?", sport.inSentence),
    )
    SessionProposal.Discovery.THEY_TEACH -> listOf(
        L("Teach me %s? I'm a total beginner.", sport.inSentence),
        L("My first %s session, be gentle?", sport.inSentence),
        L("Show me your %s moves?", sport.inSentence),
    )
    null -> SessionProposal.titleIdeas(sport)
}

// MARK: Sections

@Composable
private fun Header(profile: Profile, counterTo: SessionProposal?) {
    val p = DS.palette
    // Nothing floats on the sage ground: the header is a white block like the sections below.
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile.portrait, size = 52.dp, ring = true)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (counterTo == null) L("Train with %s", profile.name) else L("Suggest other times"),
                Modifier.semantics { heading() },
                style = display(22f),
                color = p.ink,
                // Long names wrap to a second line instead of being cut.
                maxLines = 2,
            )
            Text(
                if (counterTo == null) L("Meet up and move together.") else L("Offer a few times that work for you."),
                style = TextStyles.subheadline,
                color = p.body,
            )
        }
    }
}

/**
 * Every sport either of you does, once, tagged by who knows it. Shared sports come first,
 * then the ones they could show you, then the ones you could show them. Picking a tile
 * sets the discovery mode on its own, so there's nothing else to choose.
 */
@Composable
private fun SportPicker(
    profile: Profile,
    me: Profile,
    theyTeach: List<Sport>,
    youTeach: List<Sport>,
    sport: Sport,
    discovery: SessionProposal.Discovery?,
    onSelect: (Sport, SessionProposal.Discovery?) -> Unit,
) {
    val shared = profile.sports.map { it.sport }.filter { s -> me.sports.any { it.sport == s } }
    val tiles = shared.map { it to null } +
        theyTeach.map { it to SessionProposal.Discovery.THEY_TEACH } +
        youTeach.map { it to SessionProposal.Discovery.I_TEACH }
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { (s, d) ->
                    key(s) {
                        SportTile(profile, me, s, d, on = sport == s && discovery == d, Modifier.weight(1f)) { onSelect(s, d) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SportTile(
    profile: Profile,
    me: Profile,
    s: Sport,
    d: SessionProposal.Discovery?,
    on: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = DS.palette
    // The faces on a sport tile: you then them for a shared sport, otherwise whoever does it.
    val faces = when (d) {
        null -> listOf(me.portrait, profile.portrait)
        SessionProposal.Discovery.THEY_TEACH -> listOf(profile.portrait)
        SessionProposal.Discovery.I_TEACH -> listOf(me.portrait)
    }
    // For TalkBack only: on screen, each sport shows the faces of who does it.
    val spokenTag = when (d) {
        null -> L("you both do it")
        SessionProposal.Discovery.THEY_TEACH -> L("%s could show you", profile.name)
        SessionProposal.Discovery.I_TEACH -> L("you could show %s", profile.name)
    }
    // Selected = solid accent fill, like a chip: reads in light and dark, no frame.
    val fill by animateColorAsState(if (on) p.lime else p.canvasSoft, Motion.select(), label = "tileFill")
    val ink by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "tileInk")
    val disc by animateColorAsState(if (on) p.onLimeWash else p.canvas, Motion.select(), label = "tileDisc")
    Column(
        modifier
            .pressScale(onClick, scale = 0.97f)
            .background(fill, RoundedCornerShape(DS.Radius.lg))
            .clearAndSetSemantics {
                contentDescription = "${s.displayName}, $spokenTag"
                selected = on
            }
            .padding(DS.Space.md),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).background(disc, CircleShape), contentAlignment = Alignment.Center) {
                DrafftIcon(s.symbol, size = symbol(16f), tint = ink)
            }
            Spacer(Modifier.weight(1f))
            CheckDisc(on, onLimeFill = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            // Sport names are never cut.
            Text(s.displayName, style = TextStyles.headline, color = ink, maxLines = 2)
            // Who does it, in faces: both of you, only them, or only you. No words needed.
            Faces(faces, ringColor = fill)
        }
    }
}

/** Read-only recap of the invite being answered: sport and pitch are theirs, only times change. */
@Composable
private fun FixedSession(original: SessionProposal) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl)
    NightSurface {
        Column(
            Modifier
                .fillMaxWidth()
                // A sheet: plain night.
                .background(p.night, shape)
                .border(1.dp, p.blockEdge, shape)
                .padding(DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            // The sport as a small lime tag; the pitch gets the full width below it.
            Row(
                Modifier
                    .background(p.accentOnNight, CircleShape)
                    .defaultMinSize(minHeight = 30.dp)
                    .padding(horizontal = DS.Space.md),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrafftIcon(original.sport.symbol, size = symbol(13f), tint = p.onAccentOnNight)
                Text(
                    if (original.discovery == null) original.sport.displayName else L("%s discovery", original.sport.displayName),
                    style = TextStyles.footnote.bold,
                    color = p.onAccentOnNight,
                )
            }

            Text(original.displayTitle, Modifier.fillMaxWidth(), style = displayBold(26f), color = Color.White)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("They offered"), style = TextStyles.caption.bold, color = Color.White.copy(alpha = 0.55f))
                original.options.forEach { d ->
                    Text(
                        slotText(d),
                        style = TextStyles.subheadline.semibold.monospacedDigits.copy(textDecoration = TextDecoration.LineThrough),
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

private fun slotText(d: Instant): String = L("%s at %s", DateText.weekdayDayMonth(d), DateText.time(d))

/** Up to three time cards. "Add a time" and each card open the native date and time sheet. */
@Composable
private fun SlotsEditor(profile: Profile, options: List<Instant>, onCompose: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Row(
            Modifier.animateContentSize(Motion.snappy()),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        ) {
            options.forEachIndexed { i, d -> TimeCard(i, d, Modifier.weight(1f)) { onCompose(i) } }
            if (options.size < MAX_OPTIONS) AddCard(options.isEmpty(), Modifier.weight(1f)) { onCompose(null) }
            repeat(maxOf(0, MAX_OPTIONS - options.size - 1)) {
                Spacer(Modifier.weight(1f).defaultMinSize(minHeight = 112.dp))
            }
        }
        // A time that has passed is explained under the send button.
        Text(
            if (options.isEmpty()) {
                L("Offer up to %d times. %s picks one, or suggests others.", MAX_OPTIONS, profile.name)
            } else {
                L("Tap a time to change it. %s picks one, or suggests others.", profile.name)
            },
            style = TextStyles.footnote,
            color = DS.palette.mute,
        )
    }
}

/** A picked time: weekday, big day number, month, and the hour. Tap to change it. */
@Composable
private fun TimeCard(i: Int, d: Instant, modifier: Modifier, onClick: () -> Unit) {
    val p = DS.palette
    val today = d.atZone(DateText.zone).toLocalDate() == LocalDate.now(DateText.zone)
    Column(
        modifier
            .pressScale(onClick, scale = 0.96f, onClickLabel = L("Change or remove this time"))
            .clearAndSetSemantics { contentDescription = L("Time %d, %s", i + 1, slotText(d)) }
            .defaultMinSize(minHeight = 112.dp)
            .background(p.canvasSoft, RoundedCornerShape(DS.Radius.lg))
            .padding(vertical = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (today) L("Today") else DateText.format("EEE", d), style = TextStyles.caption.bold, color = p.body)
        Text(DateText.format("d", d), style = display(30f), color = p.ink)
        Text(DateText.format("MMM", d), style = TextStyles.caption, color = p.body)
        Text(
            DateText.time(d),
            Modifier
                .padding(top = DS.Space.xs)
                .background(p.canvas, CircleShape)
                .defaultMinSize(minHeight = 26.dp)
                .padding(horizontal = DS.Space.sm, vertical = 3.dp),
            style = TextStyles.subheadline.bold.monospacedDigits,
            color = p.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddCard(first: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = DS.palette
    val dash = p.ink.copy(alpha = 0.22f)
    Column(
        modifier
            .pressScale(onClick, scale = 0.96f)
            .defaultMinSize(minHeight = 112.dp)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    dash,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(DS.Radius.lg.toPx()),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                )
            },
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrafftIcon("plus", size = symbol(20f), tint = p.accentInk)
        Text(
            if (first) L("Add a time") else L("Add another"),
            style = TextStyles.caption.semibold.copy(textAlign = TextAlign.Center),
            color = p.accentInk,
        )
    }
}

// MARK: Pitch (a one-line hook for the session)

@Composable
private fun TitlePicker(
    title: String,
    onTitle: (String) -> Unit,
    ideas: List<String>,
    sport: Sport,
    discovery: SessionProposal.Discovery?,
) {
    val p = DS.palette
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.Radius.md)
    val border by animateColorAsState(if (focused) p.ink else Color.Transparent, Motion.snappy(), label = "pitchBorder")
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(p.canvasSoft, shape)
                .border(1.5.dp, border, shape),
        ) {
            BasicTextField(
                value = title,
                onValueChange = onTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DS.Space.md, vertical = 13.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .revealsOnFocus(focused)
                    .semantics { contentDescription = L("Pitch") },
                textStyle = TextStyles.body.semibold.copy(color = p.ink),
                maxLines = 3,
                cursorBrush = SolidColor(p.accentInk),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    // Placeholder drawn as wrapping text, so the field grows to fit it.
                    Box {
                        if (title.isEmpty()) {
                            Text(
                                ideas.firstOrNull().orEmpty(),
                                Modifier.clearAndSetSemantics { },
                                style = TextStyles.body.semibold,
                                color = p.mute,
                            )
                        }
                        inner()
                    }
                },
            )
            if (title.isNotEmpty()) {
                PressScaleButton(
                    onClick = { onTitle("") },
                    modifier = Modifier.align(Alignment.TopEnd).size(44.dp),
                    scale = 1f,
                    contentDescription = L("Clear pitch"),
                ) {
                    DrafftIcon("xmark.circle.fill", size = symbol(17f), tint = p.mute)
                }
            }
        }

        Text(L("Or steal one of these"), style = TextStyles.footnote.semibold, color = p.mute)
        // Fresh ideas when the sport or discovery changes.
        AnimatedContent(
            sport to discovery,
            transitionSpec = { fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle())) },
            label = "pitchIdeas",
        ) { (s, d) ->
            val shown = if (s == sport && d == discovery) ideas else pitchIdeas(s, d)
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                shown.forEach { idea -> IdeaRow(idea, on = title == idea) { onTitle(if (title == idea) "" else idea) } }
            }
        }
    }
}

@Composable
private fun IdeaRow(idea: String, on: Boolean, onClick: () -> Unit) {
    val p = DS.palette
    val fill by animateColorAsState(if (on) p.lime else p.canvasSoft, Motion.select(), label = "ideaFill")
    val ink by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "ideaInk")
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale({
                Haptics.select()
                onClick()
            }, scale = 0.98f)
            .semantics(mergeDescendants = true) { selected = on }
            .background(fill, RoundedCornerShape(DS.Radius.md))
            .defaultMinSize(minHeight = 44.dp)
            .padding(DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        // Icon sits on the first line, even when the idea wraps.
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(16.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            Crossfade(on, label = "ideaIcon") { picked ->
                DrafftIcon(if (picked) "checkmark" else "sparkles", size = symbol(13f), tint = ink)
            }
        }
        Text(idea, Modifier.weight(1f), style = TextStyles.subheadline.semibold, color = ink)
    }
}

/** Recap of exactly what will be sent, then the action. */
@Composable
private fun Footer(
    sport: Sport,
    discovery: SessionProposal.Discovery?,
    options: List<Instant>,
    outgoingOptions: List<Instant>,
    sessionDate: Instant,
    title: String,
    isCounter: Boolean,
    sending: Boolean,
    sendTitle: String,
    canSend: Boolean,
    sendReason: Pair<String, Boolean>?,
    onSend: () -> Unit,
) {
    val p = DS.palette
    Column(
        Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.lg, bottom = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(p.lime, CircleShape), contentAlignment = Alignment.Center) {
                Crossfade(sport, label = "recapSport") { s -> DrafftIcon(s.symbol, size = symbol(15f), tint = p.onLime) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                val what = if (discovery == null) sport.displayName else L("%s discovery", sport.displayName)
                val day = DateText.weekdayShortDayMonth(sessionDate)
                val time = DateText.time(sessionDate)
                RollingText(
                    when {
                        options.isEmpty() -> L("%s, no time yet", what)
                        outgoingOptions.size > 1 -> L("%s, %d time options", what, outgoingOptions.size)
                        else -> L("%s, %s at %s", what, day, time)
                    },
                    style = TextStyles.subheadline.bold,
                    color = p.ink,
                )
                Crossfade(title, label = "recapPitch") { t ->
                    Text(
                        t.ifEmpty { if (isCounter) L("Same session, new times") else L("No pitch yet") },
                        style = TextStyles.footnote,
                        color = p.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        DrafftButton(
            onClick = onSend,
            modifier = Modifier
                .padding(start = 12.dp)
                .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
            enabled = canSend,
        ) {
            if (sending) {
                DrafftIcon("checkmark", size = symbol(17f), tint = p.onLime)
                Text(L("Sent"))
            } else {
                DrafftIcon("paperplane.fill", size = symbol(17f), tint = p.onLime)
                Text(if (options.size > 1) L("%s (%d times)", sendTitle, options.size) else sendTitle, maxLines = 2)
            }
        }

        // Keeps its height when empty, so the button never moves.
        Crossfade(sendReason, label = "sendReason") { reason ->
            Text(
                reason?.first ?: " ",
                Modifier.fillMaxWidth(),
                style = TextStyles.caption,
                color = if (reason?.second == true) p.negative else p.body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: Chrome

/** White block with its own small title; nothing floats on the sage ground. */
@Composable
private fun Block(title: String, icon: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            DrafftIcon(icon, size = symbol(13f), tint = p.ink)
            Text(title, Modifier.weight(1f).semantics { heading() }, style = TextStyles.headline, color = p.ink)
            if (trailing != null) Text(trailing, style = TextStyles.footnote, color = p.mute)
        }
        content()
    }
}

/**
 * Small overlapping portraits of who does a sport ("you both", "them", "you"), said without text.
 * Each face sits in a ring of the tile's own colour, so an overlap reads as a cut, not a border.
 */
@Composable
private fun Faces(portraits: List<String>, ringColor: Color) {
    val side = 22.dp
    Layout(
        content = {
            portraits.forEach { name ->
                Box(Modifier.background(ringColor, CircleShape).padding(2.dp)) {
                    Photo(name, Modifier.size(side).clip(CircleShape), side = side)
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val overlap = 7.dp.roundToPx()
        val width = placeables.sumOf { it.width } - overlap * maxOf(0, placeables.size - 1)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(maxOf(0, width), height) {
            var x = 0
            placeables.forEach {
                it.place(x, 0)
                x += it.width - overlap
            }
        }
    }
}

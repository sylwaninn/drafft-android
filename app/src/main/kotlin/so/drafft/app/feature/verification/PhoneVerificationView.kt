package so.drafft.app.feature.verification

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.Collator
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.verification.PhoneCountry
import so.drafft.core.data.verification.PhoneVerificationModel
import so.drafft.core.model.L
import so.drafft.core.model.appLocale
import so.drafft.core.ui.components.BlurredNavigationEdge
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.SheetDetent
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

// Ports Drafft/Features/Verification/PhoneVerificationView.swift.

/**
 * Phone number, then the 6-digit code. The primary action lives in the host's pinned footer
 * (model.primaryTitle / primaryEnabled); this view shows the fields, the states and the errors.
 */
@Composable
fun PhoneVerificationView(model: PhoneVerificationModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // No focus on arrival (the page opens whole); the code step takes focus itself once the
    // person has asked for a code.
    AnimatedContent(
        targetState = model.stage,
        modifier = modifier,
        transitionSpec = {
            val enter = if (targetState == PhoneVerificationModel.Stage.VERIFIED) codeCardEnter else fadeIn(Motion.snappy())
            enter.togetherWith(fadeOut(Motion.snappy()))
        },
        label = "phoneStage",
    ) { stage ->
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
            when (stage) {
                PhoneVerificationModel.Stage.ENTER_NUMBER -> NumberField(model)
                PhoneVerificationModel.Stage.ENTER_CODE -> OneTimeCodeEntry(
                    destination = model.displayNumber,
                    code = model.code,
                    onCode = model::enterCode,
                    busy = model.busy,
                    error = model.error,
                    needsHelp = model.needsHelp,
                    helpTopic = L("Phone verification"),
                    hint = L("Check your messages. The code works for 10 minutes."),
                    resendIn = model.resendIn,
                    onEdit = model::changeNumber,
                    onResend = { scope.launch { model.resend() } },
                )
                PhoneVerificationModel.Stage.VERIFIED -> CodeVerifiedCard(title = L("Number verified"), detail = model.displayNumber)
                PhoneVerificationModel.Stage.LOCKED -> CodeLockedCard(message = model.error)
            }
        }
    }
}

@Composable
private fun NumberField(model: PhoneVerificationModel) {
    val p = DS.palette
    var focused by remember { mutableStateOf(false) }
    var pickingCountry by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val error = model.error
    val country = model.country
    val border by animateColorAsState(
        when {
            error != null -> p.negative
            focused -> p.ink
            else -> p.ink.copy(alpha = 0.35f)
        },
        Motion.snappy(), label = "numberBorder",
    )
    val width by animateDpAsState(if (focused || error != null) 2.dp else 1.dp, Motion.snappy(), label = "numberBorderWidth")
    val shape = RoundedCornerShape(DS.Radius.md)

    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
        Text(L("Mobile number"), style = TextStyles.subheadline.semibold, color = p.ink)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(p.field, shape)
                .border(width, border, shape),
        ) {
            // A plain button opening a sheet: the system menu morphed out of the field and
            // flashed white across its border.
            val label = L("Country code, %s %s", country.name, country.dial)
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        Haptics.tap()
                        pickingCountry = true
                    }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = label
                    }
                    .padding(horizontal = DS.Space.md),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${country.flag} ${country.dial}",
                    Modifier.clearAndSetSemantics { },
                    style = TextStyles.body.semibold.monospacedDigits,
                    color = p.ink,
                )
                DrafftIcon("chevron.down", Modifier.clearAndSetSemantics { }, size = 14.dp, tint = p.body)
            }
            Box(
                Modifier
                    .padding(vertical = DS.Space.sm)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(p.hairline),
            )
            BasicTextField(
                value = model.number,
                onValueChange = { model.number = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .revealsOnFocus(focused),
                textStyle = TextStyles.body.monospacedDigits.copy(color = p.ink),
                singleLine = true,
                cursorBrush = SolidColor(p.accentInk),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    // The whole box, padding included, focuses the field.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                            .padding(horizontal = DS.Space.md),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (model.number.isEmpty()) Text(country.example, style = TextStyles.body.monospacedDigits, color = p.mute, maxLines = 1)
                        inner()
                    }
                },
            )
        }
        ErrorOrHint(model.error, L("We'll text you a code. Your number never shows on your profile."))
    }

    DrafftSheet(visible = pickingCountry, onDismissRequest = { pickingCountry = false }, detent = SheetDetent.LARGE) {
        CountryPickerSheet(selection = model.country, onSelectionChange = { model.country = it })
    }
}

/**
 * The error or the hint, then Get help: always there on this step, even before anything
 * fails (a number the list can't take, a text that never comes).
 */
@Composable
private fun ErrorOrHint(error: String?, default: String) {
    val p = DS.palette
    Column {
        AnimatedContent(
            targetState = error,
            transitionSpec = {
                (fadeIn(Motion.snappy()) + slideInVertically(Motion.snappy()) { -it / 2 }).togetherWith(fadeOut(Motion.snappy()))
            },
            label = "numberError",
        ) { e ->
            if (e != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    DrafftIcon("exclamationmark.circle.fill", size = 16.dp, tint = p.negative)
                    Text(e, style = TextStyles.footnote.medium, color = p.negative)
                }
            } else {
                // Body grey: mute is under 4.5:1 on the sage page.
                Text(default, style = TextStyles.footnote, color = p.body)
            }
        }
        GetHelpButton(topic = L("Phone verification"))
    }
}

/** Every country code as a searchable list in a sheet, by name in the app's language. */
@Composable
fun CountryPickerSheet(
    selection: PhoneCountry,
    onSelectionChange: (PhoneCountry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val dismiss = LocalSheetDismiss.current
    var query by remember { mutableStateOf("") }
    // Sorted once per sheet: the names follow the app's language.
    val countries = remember {
        val collator = Collator.getInstance(appLocale)
        PhoneCountry.all.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
    val q = query.trim()
    val results = remember(q, countries) {
        countries.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.dial.contains(q) }
    }
    val list = rememberLazyListState()

    BlurredNavigationEdge(
        scroll = list,
        modifier = modifier,
        navigationBar = { SheetNavBar(title = L("Country code"), onClose = dismiss) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            SearchField(query, { query = it }, Modifier.padding(horizontal = DS.Space.lg, vertical = DS.Space.sm))
            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                        DrafftIcon("magnifyingglass", size = 40.dp, tint = p.mute)
                        Text(L("No results"), style = TextStyles.title3.semibold, color = p.ink)
                    }
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = DS.Space.lg,
                        end = DS.Space.lg,
                        bottom = padding.calculateBottomPadding() + DS.Space.lg,
                    ),
                ) {
                    items(results, key = { it.id }) { c ->
                        val first = c == results.first()
                        val last = c == results.last()
                        val corner = DS.Radius.md
                        val rowShape = RoundedCornerShape(
                            topStart = if (first) corner else 0.dp,
                            topEnd = if (first) corner else 0.dp,
                            bottomStart = if (last) corner else 0.dp,
                            bottomEnd = if (last) corner else 0.dp,
                        )
                        Column(Modifier.background(p.canvas, rowShape)) {
                            CountryRow(c, c == selection) {
                                Haptics.select()
                                onSelectionChange(c)
                                dismiss()
                            }
                            if (!last) HorizontalDivider(Modifier.padding(start = DS.Space.lg), thickness = 0.5.dp, color = p.hairline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(c: PhoneCountry, selected: Boolean, onClick: () -> Unit) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.flag, Modifier.clearAndSetSemantics { }, style = TextStyles.body)
        Text(c.name, Modifier.weight(1f), style = TextStyles.body, color = p.ink)
        Text(c.dial, style = TextStyles.body.monospacedDigits, color = p.body)
        // The one selection mark (CheckDisc), not a tick.
        CheckDisc(isOn = selected)
    }
}

/** The search field under the bar, always shown (`.searchable(placement: .navigationBarDrawer(.always))`). */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = DS.palette
    val focus = remember { FocusRequester() }
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(p.ink.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(horizontal = DS.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrafftIcon("magnifyingglass", size = 18.dp, tint = p.mute)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focus),
            textStyle = TextStyles.body.copy(color = p.ink),
            singleLine = true,
            cursorBrush = SolidColor(p.accentInk),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(L("Country or code"), style = TextStyles.body, color = p.mute, maxLines = 1)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(0.dp))
    }
}

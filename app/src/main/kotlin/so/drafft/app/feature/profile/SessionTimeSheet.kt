package so.drafft.app.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlinx.coroutines.flow.drop
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

/**
 * Native date and time pickers in their own sheet: the Material calendar for the day (no past days),
 * the Material time picker for the time (5-minute steps). The time only joins the invite on "Add".
 * Sheet content: present it in a `DrafftSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTimeSheet(
    initial: Instant?,
    taken: List<Instant>,
    onSave: (Instant) -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val dismiss = LocalSheetDismiss.current
    val isNew = initial == null
    val zone = DateText.zone
    val start = remember(initial) {
        initial?.atZone(zone)
            ?: ZonedDateTime.now(zone).plusDays(1).withHour(18).withMinute(30).withSecond(0).withNano(0)
    }
    val today = remember { LocalDate.now(zone) }
    val todayMillis = remember(today) { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = today.year..(today.year + 5),
        selectableDates = remember(todayMillis) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis
                override fun isSelectableYear(year: Int) = year >= today.year
            }
        },
    )
    val timeState = rememberTimePickerState(start.hour, start.minute, is24Hour = rememberIs24Hour())
    var showsClock by rememberSaveable { mutableStateOf(false) }

    val date by remember {
        derivedStateOf {
            val day = dateState.selectedDateMillis
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                ?: start.toLocalDate()
            ZonedDateTime.of(day, LocalTime.of(timeState.hour, timeState.minute), zone).toInstant()
        }
    }
    val isTaken = taken.any { abs(it.epochSecond - date.epochSecond) < 60 }
    val isPast = date.isBefore(Instant.now())

    // 5-minute steps: the dial snaps to the nearest step.
    LaunchedEffect(timeState) {
        snapshotFlow { timeState.minute }.collect { m ->
            if (m % 5 != 0) timeState.minute = ((m + 2) / 5 * 5) % 60
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { date }.drop(1).collect { Haptics.select() }
    }

    val scroll = rememberScrollState()
    EdgeBars(
        scroll,
        modifier.fillMaxSize().background(p.canvasSoft),
        topBar = { SheetNavBar(if (isNew) L("New time") else L("Change time"), onClose = dismiss) },
        bottomBar = {
            Column(
                Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val day = DateText.weekdayShortDayMonth(date)
                val time = DateText.time(date)
                DrafftButton(
                    onClick = {
                        Haptics.success()
                        onSave(date)
                        dismiss()
                    },
                    enabled = !(isTaken || isPast),
                ) {
                    RollingText(if (isNew) L("Add %s at %s", day, time) else L("Save %s at %s", day, time), maxLines = 2)
                }
                // Keeps its height when empty, so the button never moves.
                Text(
                    if (isTaken) L("You already offer this time.") else if (isPast) L("This time has passed.") else " ",
                    style = TextStyles.footnote,
                    color = p.negative,
                    textAlign = TextAlign.Center,
                )
            }
        },
        navigationEdge = true,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            DatePicker(
                dateState,
                Modifier
                    .fillMaxWidth()
                    .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
                    .padding(DS.Space.sm),
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = Color.Transparent,
                    selectedDayContainerColor = p.lime,
                    selectedDayContentColor = p.onLime,
                    todayContentColor = p.accentInk,
                    todayDateBorderColor = p.accentInk,
                ),
            )

            Column(Modifier.fillMaxWidth().background(p.canvas, RoundedCornerShape(DS.Radius.xl))) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 60.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { showsClock = !showsClock }
                        .padding(horizontal = DS.Space.lg),
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("clock-circle", size = symbol(17f), tint = p.ink)
                    Text(L("Time"), Modifier.weight(1f).semantics { heading() }, style = TextStyles.headline, color = p.ink)
                    // The compact picker's pill: tap it for the clock.
                    Text(
                        DateText.time(date),
                        Modifier
                            .background(p.canvasSoft, CircleShape)
                            .padding(horizontal = DS.Space.md, vertical = 7.dp),
                        style = TextStyles.body.semibold.monospacedDigits,
                        color = if (showsClock) p.accentInk else p.ink,
                    )
                }
                AnimatedVisibility(showsClock, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    TimePicker(
                        timeState,
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = DS.Space.lg),
                        colors = TimePickerDefaults.colors(
                            clockDialColor = p.canvasSoft,
                            selectorColor = p.lime,
                            timeSelectorSelectedContainerColor = p.lime,
                            timeSelectorSelectedContentColor = p.onLime,
                            timeSelectorUnselectedContainerColor = p.canvasSoft,
                            timeSelectorUnselectedContentColor = p.ink,
                            periodSelectorSelectedContainerColor = p.lime,
                            periodSelectorSelectedContentColor = p.onLime,
                        ),
                    )
                }
            }

            if (onRemove != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                            Haptics.tap()
                            onRemove()
                            dismiss()
                        },
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.sm, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("trash-bin-minimalistic", size = symbol(17f), tint = p.negative)
                    Text(L("Remove this time"), style = TextStyles.body.semibold, color = p.negative)
                }
            }
        }
    }
}

/** Whether the app's language writes times on a 24-hour clock ("18:30", not "6:30 PM"). */
@Composable
private fun rememberIs24Hour(): Boolean = remember {
    val afternoon = ZonedDateTime.now(DateText.zone).withHour(13).withMinute(0).toInstant()
    DateText.time(afternoon).contains("13")
}

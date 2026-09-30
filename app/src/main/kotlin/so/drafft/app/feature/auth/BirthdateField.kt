package so.drafft.app.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.appLocale
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Auth/BirthdateField.swift.

/**
 * Birthday typed, not scrolled: three boxes (day, month, year, in the app language's order) over one
 * hidden number-pad field, so eight digits in a row fill them and delete walks back. Nothing is set
 * until the date is complete and real; no made-up starting date is ever shown.
 *
 * [oldest]: oldest accepted birthday; anything earlier (or in the future) reads as a typo.
 */
@Composable
fun BirthdateField(
    date: Instant?,
    onDateChange: (Instant?) -> Unit,
    oldest: Instant,
    modifier: Modifier = Modifier,
) {
    val order = remember(appLocale) { BirthdateParts.order(appLocale) }
    var digits by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val p = DS.palette

    fun range(part: BirthdateParts.Part): IntRange = BirthdateParts.range(part, order)

    fun enter(raw: String) {
        digits = raw.filter(Char::isDigit).take(8)
        if (digits.length != 8) {
            onDateChange(null)
            invalid = false
            return
        }
        fun value(part: BirthdateParts.Part): Int = digits.substring(range(part).first, range(part).last + 1).toIntOrNull() ?: 0
        // Real day only (no 31/02), and a birthday a person can have.
        val d = try {
            LocalDate.of(value(BirthdateParts.Part.YEAR), value(BirthdateParts.Part.MONTH), value(BirthdateParts.Part.DAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: DateTimeException) {
            null
        }
        if (d != null && !d.isAfter(Instant.now()) && !d.isBefore(oldest)) {
            onDateChange(d)
            invalid = false
            Haptics.select()
        } else {
            onDateChange(null)
            invalid = true
            Haptics.warning()
        }
    }

    LaunchedEffect(Unit) {
        if (date != null && digits.isEmpty()) digits = BirthdateParts.digits(date, order)
        focus.requestFocus()
    }

    val accessibilityValue = date?.let {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(appLocale).format(it.atZone(ZoneOffset.UTC).toLocalDate())
    } ?: digits
    val birthdayLabel = L("Birthday")

    Column(modifier.animateContentSize(Motion.snappy()), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() },
        ) {
            BasicTextField(
                value = TextFieldValue(digits, TextRange(digits.length)),
                onValueChange = { enter(it.text) },
                modifier = Modifier
                    .matchParentSize()
                    .alpha(0.02f)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .revealsOnFocus(focused)
                    .semantics {
                        contentDescription = birthdayLabel
                        stateDescription = accessibilityValue
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                cursorBrush = SolidColor(p.field),
            )
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                order.forEachIndexed { i, part ->
                    if (i > 0) Text("/", style = TextStyles.title3.semibold, color = p.mute)
                    DateBox(part, digits, range(part), focused, invalid, order.last() == part)
                }
            }
        }

        AnimatedVisibility(
            visible = invalid,
            enter = fadeIn(Motion.snappy()) + slideInVertically(Motion.snappy()) { -it },
            exit = fadeOut(Motion.snappy()) + slideOutVertically(Motion.snappy()) { -it },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                DrafftIcon("danger-circle", size = (13f * 1.2f).dp, tint = p.negative)
                Text(L("Check the date: this one doesn't exist."), style = TextStyles.footnote.medium, color = p.negative)
            }
        }
    }
}

@Composable
private fun RowScope.DateBox(
    part: BirthdateParts.Part,
    digits: String,
    range: IntRange,
    focused: Boolean,
    invalid: Boolean,
    isLast: Boolean,
) {
    val p = DS.palette
    val typed = digits.drop(range.first).take(range.last - range.first + 1)
    val current = focused && (if (digits.length < 8) digits.length in range else isLast)
    val placeholder = when (part) {
        BirthdateParts.Part.DAY -> L("DD")
        BirthdateParts.Part.MONTH -> L("MM")
        BirthdateParts.Part.YEAR -> L("YYYY")
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = p.ink)) { append(typed) }
        withStyle(SpanStyle(color = p.mute)) { append(placeholder.drop(typed.length)) }
    }
    val shape = RoundedCornerShape(DS.Radius.md)
    val border = when {
        invalid -> p.negative
        current -> p.ink
        else -> p.ink.copy(alpha = 0.2f)
    }
    Box(
        // The year takes the room of its four digits.
        (if (part == BirthdateParts.Part.YEAR) Modifier.weight(1f) else Modifier.width(88.dp))
            .defaultMinSize(minHeight = 60.dp)
            .heightIn(min = 60.dp)
            .background(p.field, shape)
            .border(if (invalid || current) 2.dp else 1.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = displayBold(26f).monospacedDigits, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
    }
}

/** The three parts of a birthday and how the digits typed map onto them. */
object BirthdateParts {
    enum class Part { DAY, MONTH, YEAR }

    /** Day, month and year in the order the app's language writes dates (MM/DD/YYYY in English). */
    fun order(locale: Locale): List<Part> {
        val format = runCatching {
            DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        }.getOrDefault("dd/MM/yyyy")
        val parts = ArrayList<Part>(3)
        for (c in format) {
            val part = when (c) {
                'd' -> Part.DAY
                'M', 'L' -> Part.MONTH
                'y', 'u' -> Part.YEAR
                else -> null
            }
            if (part != null && part !in parts) parts += part
        }
        return if (parts.size == 3) parts else listOf(Part.DAY, Part.MONTH, Part.YEAR)
    }

    fun range(part: Part, order: List<Part>): IntRange {
        var start = 0
        for (p in order) {
            val length = if (p == Part.YEAR) 4 else 2
            if (p == part) return start until start + length
            start += length
        }
        return IntRange.EMPTY
    }

    /**
     * A birthday is a calendar day, not an instant: kept at midnight UTC, the way the server writes
     * it back (`yyyy-MM-dd`). Local midnight in Paris was the previous day in UTC.
     */
    fun digits(date: Instant, order: List<Part>): String {
        val d = date.atZone(ZoneOffset.UTC).toLocalDate()
        return order.joinToString("") { part ->
            when (part) {
                Part.DAY -> String.format(Locale.ROOT, "%02d", d.dayOfMonth)
                Part.MONTH -> String.format(Locale.ROOT, "%02d", d.monthValue)
                Part.YEAR -> String.format(Locale.ROOT, "%04d", d.year)
            }
        }
    }
}

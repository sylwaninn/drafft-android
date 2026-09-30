package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/LifestylePicker.swift.

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Vitals
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

/**
 * Lifestyle answers (rhythm, food, drinking, smoking). Nothing is picked for the person, each
 * question is optional, and tapping the selected chip clears it. Used in sign-up and Edit profile.
 */
@Composable
fun LifestylePicker(
    vitals: Vitals,
    onVitalsChange: (Vitals) -> Unit,
    /** Unselected chip fill (sage on a white block). */
    chipFill: Color = DS.palette.canvasSoft,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
        Group(L("Early bird or night owl?"), listOf("Very early bird", "Early bird", "Night owl"), vitals.chronotype, chipFill) {
            onVitalsChange(vitals.copy(chronotype = it))
        }
        Group(L("How you eat"), listOf("Omnivore", "Flexitarian", "Vegetarian", "Vegan", "Pescatarian"), vitals.diet, chipFill) {
            onVitalsChange(vitals.copy(diet = it))
        }
        Group(L("Drinking"), listOf("Never", "Rarely", "Socially", "Post-race only"), vitals.drinks, chipFill) {
            onVitalsChange(vitals.copy(drinks = it))
        }
        Group(L("Smoking"), listOf("Never", "Sometimes", "Yes"), vitals.smokes, chipFill) {
            onVitalsChange(vitals.copy(smokes = it))
        }
    }
}

/** [options] are the stored answers (English); chips show them translated. */
@Composable
private fun Group(title: String, options: List<String>, value: String, chipFill: Color, onPick: (String) -> Unit) {
    val p = DS.palette
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Text(title, style = TextStyles.subheadline.semibold, color = p.ink)
        FlowLayout(spacing = DS.Space.sm) {
            options.forEach { o ->
                val on = value == o
                val label = Vitals.label(o)
                val bg by animateColorAsState(if (on) p.lime else chipFill, Motion.select(), label = "lifestyleFill")
                val fg by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "lifestyleInk")
                Box(
                    Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .pressScale({
                            Haptics.select()
                            onPick(if (on) "" else o)
                        }, scale = 0.94f)
                        .semantics {
                            contentDescription = "$title $label"
                            selected = on
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        Modifier
                            .background(bg, CircleShape)
                            .defaultMinSize(minHeight = 36.dp)
                            .padding(horizontal = DS.Space.md, vertical = 9.dp),
                        style = TextStyles.footnote.semibold,
                        color = fg,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** At least one lifestyle answer given. */
val Vitals.hasLifestyle: Boolean get() = !listOf(chronotype, diet, drinks, smokes).all { it.isEmpty() }

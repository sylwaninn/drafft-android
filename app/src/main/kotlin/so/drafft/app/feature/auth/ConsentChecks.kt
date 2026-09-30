package so.drafft.app.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.Brand
import so.drafft.core.model.ConsentDraft
import so.drafft.core.model.L
import so.drafft.core.ui.components.DrafftCheckbox
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded

// Port of Drafft/Features/Auth/ConsentChecks.swift.

/**
 * The two required consents, unchecked until the person ticks them, each in its own white block:
 * the terms (the documents named in the sentence are links), then the use of sensitive data (gender,
 * the genders someone wants to see, lifestyle answers, which can reveal sexual orientation, health or
 * beliefs), which the privacy policy bases on explicit consent. Links open getdrafft.com in the
 * browser. Sign-up and `TermsConsentView` share it.
 */
@Composable
fun ConsentChecks(draft: ConsentDraft, onDraftChange: (ConsentDraft) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Column(Modifier.block(DS.palette.canvas).padding(vertical = DS.Space.sm, horizontal = DS.Space.lg)) {
            val sentence = termsText()
            Check(isOn = draft.terms, onToggle = { onDraftChange(draft.copy(terms = !draft.terms)) }, sentence = sentence)
        }
        Column(Modifier.block(DS.palette.canvas).padding(vertical = DS.Space.sm, horizontal = DS.Space.lg)) {
            Check(
                isOn = draft.sensitiveData,
                onToggle = { onDraftChange(draft.copy(sensitiveData = !draft.sensitiveData)) },
                sentence = sensitiveText(),
                detail = L("This can reveal your sexual orientation, health or beliefs, so drafft asks first."),
            )
        }
    }
}

/** Survives rotation: the two boxes as a pair of booleans. */
internal val ConsentSaver = androidx.compose.runtime.saveable.Saver<ConsentDraft, List<Boolean>>(
    save = { listOf(it.terms, it.sensitiveData) },
    restore = { ConsentDraft(it[0], it[1]) },
)

/** The checkbox toggles; the sentence next to it carries the links. */
@Composable
private fun Check(isOn: Boolean, onToggle: () -> Unit, sentence: AnnotatedString, detail: String? = null) {
    val p = DS.palette
    Row(
        Modifier.leadingOutset(DS.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        verticalAlignment = Alignment.Top,
    ) {
        DrafftCheckbox(
            isOn,
            Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) {
                    Haptics.select()
                    onToggle()
                }
                .semantics {
                    contentDescription = sentence.text
                    selected = isOn
                },
        )
        Column(
            // Level with the box on top, the same room under the last line.
            Modifier.padding(vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
        ) {
            Text(sentence, style = TextStyles.subheadline, color = p.ink)
            if (detail != null) Text(branded(detail), style = TextStyles.footnote, color = p.body)
        }
    }
}

@Composable
private fun linkStyles(): TextLinkStyles =
    TextLinkStyles(SpanStyle(color = DS.palette.accentInk, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))

/** One sentence for translators; the document names in it become the links. */
@Composable
private fun termsText(): AnnotatedString {
    val styles = linkStyles()
    val sentence = L("I'm 18 or older and I accept the %s, the %s and the %s.", LegalDoc.TERMS.title, LegalDoc.PRIVACY.title, LegalDoc.COMMUNITY.title)
    return remember(sentence, styles) {
        buildAnnotatedString {
            append(sentence)
            LegalDoc.accepted.forEach { doc -> link(sentence, doc.title, doc.url(), styles) }
        }
    }
}

@Composable
private fun sensitiveText(): AnnotatedString {
    val styles = linkStyles()
    val name = L("sensitive data")
    val sentence = L("I agree that drafft uses my %s: my gender, the genders I want to see and my lifestyle, if I fill it in.", name)
    return remember(sentence, styles) {
        buildAnnotatedString {
            append(sentence)
            link(sentence, name, LegalDoc.sensitiveData(), styles)
            // The brand, one weight up, as everywhere in running text.
            val brand = sentence.indexOf(Brand.NAME)
            if (brand >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), brand, brand + Brand.NAME.length)
        }
    }
}

/**
 * The placeholders put the words in the sentence; a translation that drops one has no link (the
 * catalog tests catch it before it ships).
 */
private fun AnnotatedString.Builder.link(sentence: String, words: String, url: String, styles: TextLinkStyles) {
    val at = sentence.indexOf(words)
    if (at < 0) return
    addLink(LinkAnnotation.Url(url, styles), at, at + words.length)
}

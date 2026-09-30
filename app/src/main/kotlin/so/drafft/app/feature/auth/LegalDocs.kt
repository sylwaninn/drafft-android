package so.drafft.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import so.drafft.core.model.L
import so.drafft.core.ui.components.BlurredNavigationEdge
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded

// Port of Drafft/Features/Auth/LegalDocs.swift.

/** Legal documents shown before sign-up. Demo copy: placeholders until legal provides the texts. */
enum class LegalDoc(val rawValue: String) {
    TERMS("terms"), PRIVACY("privacy"), COMMUNITY("community");

    val id: LegalDoc get() = this

    val title: String
        get() = when (this) {
            TERMS -> L("Terms of Use")
            PRIVACY -> L("Privacy Policy")
            COMMUNITY -> L("Community Guidelines")
        }

    val sections: List<Pair<String, String>>
        get() = when (this) {
            TERMS -> listOf(
                L("Who can use drafft") to L("You must be 18 or older and use your real identity. One account per person."),
                L("Your account") to L("You verify a phone number. Keep your login details private."),
                L("Paid features") to L("drafft tempo renews until you cancel. Boosts and super likes are one-time purchases."),
                L("Ending your account") to L("You can delete your account at any time from You › Delete account."),
            )
            PRIVACY -> listOf(
                L("What we collect") to L("Your profile, photos, voice intro, messages, your phone number and an approximate area."),
                L("What we never show") to L("Your exact location and your phone number."),
                L("Your rights") to L("Export or delete your data at any time from You › Privacy & data. Contact our data protection officer through support."),
                L("How long we keep it") to L("Until you delete your account, then 30 days in backups."),
            )
            COMMUNITY -> listOf(
                L("Be real") to L("Recent photos of you, your own voice, your real age."),
                L("Be respectful") to L("No harassment, hate or sexual content without consent."),
                L("Meet safely") to L("First sessions in public places. Tell a friend where you're going."),
                L("Report") to L("Report anything that feels wrong. Reports are confidential."),
            )
        }

    companion object {
        fun fromRaw(raw: String?): LegalDoc? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** A legal document in a sheet: its sections in blocks, the title inline, Close top-right. Present it in a `DrafftSheet`. */
@Composable
fun LegalDocSheet(doc: LegalDoc, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    BlurredNavigationEdge(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        navigationBar = { SheetNavBar(doc.title) },
        // The sheet already sits below the status bar and above the navigation bar.
        windowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            doc.sections.forEach { (title, body) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl))
                        .padding(DS.Space.lg),
                    verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
                ) {
                    Text(branded(title, brandWeight = FontWeight.ExtraBold), style = TextStyles.headline, color = DS.palette.ink)
                    Text(branded(body), style = TextStyles.body, color = DS.palette.body)
                }
            }
        }
    }
}

package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/ReportSheet.swift.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.exceptions.HttpRequestException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.me.AccountSheet
import so.drafft.core.data.backend.Safety
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

/**
 * "Report or block": why (required to report), a few words if they help, then the report goes to the
 * safety team (`report_user`) and the person is blocked. Or just block, without a report. They're
 * never told. One sheet: nothing opens after it. Sheet content: present it in a `DrafftSheet`.
 */
@Composable
fun ReportSheet(
    profile: Profile,
    /** Called once reported or blocked: the caller closes what shows the person. */
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val dismiss = LocalSheetDismiss.current
    val safety = koinInject<Safety>()
    val scope = rememberCoroutineScope()
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailsFocused by remember { mutableStateOf(false) }

    suspend fun send() {
        val picked = reason ?: return
        sending = true
        error = null
        try {
            safety.report(profile, picked.rawValue, details)
            Haptics.success()
            dismiss()
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Haptics.warning()
            // A refusal (daily limit, account on hold...) says why; the connection only when it's the cause.
            // The iPhone's URLError: no connection or a timeout, also from a token refresh (Supabase wraps it).
            val offline = e is HttpRequestException || generateSequence<Throwable>(e) { it.cause }.any { it is IOException }
            error = ServerMessage.text(e) ?: if (offline) {
                L("Your report couldn't be sent. Check your connection and try again.")
            } else {
                ServerMessage.generic
            }
        } finally {
            sending = false
        }
    }

    fun justBlock() {
        Haptics.success()
        dismiss()
        onDone()
    }

    AccountSheet(
        title = L("Report or block"),
        actionTitle = L("Report and block"),
        actionIcon = "flag",
        destructive = true,
        enabled = reason != null && !sending,
        loading = sending,
        error = error,
        hasChanges = reason != null || details.isNotEmpty(),
        action = { scope.launch { send() } },
        modifier = modifier,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                SheetBlock {
                    Text(
                        L("%s won't be notified, and you won't see each other again.", profile.name),
                        style = TextStyles.subheadline,
                        color = p.body,
                    )
                }
                SheetBlock(title = L("What's wrong?")) {
                    Column(Modifier.fillMaxWidth().background(p.field, RoundedCornerShape(DS.Radius.md))) {
                        ReportReason.entries.forEachIndexed { i, r ->
                            if (i > 0) {
                                Box(
                                    Modifier
                                        .padding(start = DS.Space.lg)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(p.hairline),
                                )
                            }
                            ReasonRow(r, on = reason == r) {
                                Haptics.select()
                                reason = r
                            }
                        }
                    }
                }
                SheetBlock(title = L("Anything else? (optional)")) {
                    val shape = RoundedCornerShape(DS.Radius.md)
                    val border by animateColorAsState(if (detailsFocused) p.ink else p.ink.copy(alpha = 0.35f), Motion.gentle(), label = "detailsBorder")
                    val width by animateDpAsState(if (detailsFocused) 2.dp else 1.dp, Motion.gentle(), label = "detailsWidth")
                    BasicTextField(
                        value = details,
                        onValueChange = { details = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.field, shape)
                            .border(width, border, shape)
                            .padding(DS.Space.lg)
                            .onFocusChanged { detailsFocused = it.isFocused }
                            .revealsOnFocus(detailsFocused),
                        textStyle = TextStyles.body.copy(color = p.ink),
                        minLines = 3,
                        maxLines = 6,
                        cursorBrush = SolidColor(p.accentInk),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box {
                                if (details.isEmpty()) Text(L("What happened, in a few words"), style = TextStyles.body, color = p.mute)
                                inner()
                            }
                        },
                    )
                }
                TextLinkButton(
                    text = L("Just block, without a report"),
                    onClick = { justBlock() },
                    color = p.negative,
                    style = TextStyles.body.semibold,
                    enabled = !sending,
                    fullWidth = true,
                )
            }
        },
    )
}

@Composable
private fun ReasonRow(r: ReportReason, on: Boolean, onPick: () -> Unit) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onPick)
            .semantics(mergeDescendants = true) { selected = on }
            .padding(horizontal = DS.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.title, Modifier.weight(1f), style = TextStyles.body.medium, color = p.ink)
        CheckDisc(on)
    }
}

/** The reasons the server knows (`public.report_reason`). */
enum class ReportReason(val rawValue: String) {
    FAKE("fake"),
    INAPPROPRIATE_PHOTOS("inappropriate_photos"),
    HARASSMENT("harassment"),
    SPAM("spam"),
    UNDERAGE("underage"),
    OTHER("other"),
    ;

    val title: String
        get() = when (this) {
            FAKE -> L("Fake profile or scam")
            INAPPROPRIATE_PHOTOS -> L("Inappropriate photos")
            HARASSMENT -> L("Harassment or threats")
            SPAM -> L("Spam or selling")
            UNDERAGE -> L("Under 18")
            OTHER -> L("Something else")
        }
}

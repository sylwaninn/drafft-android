package so.drafft.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.me.DeleteAccountSheet
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.ConsentDraft
import so.drafft.core.model.L
import so.drafft.core.model.TermsConsent
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.BottomBar
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

/**
 * An account that signed up before the consent was recorded on the server, or before the current
 * terms, is asked at each open until it accepts, after the location gate (`MainTabs`): the same two
 * checks as sign-up, then `accept_terms`. No close button: the only other way out is deleting the
 * account, since drafft can't work without the gender.
 */
@Composable
fun TermsConsentView(modifier: Modifier = Modifier) {
    TrackScreen(Screen.TERMS_CONSENT)
    val app = LocalAppModel.current
    val profileSync = koinInject<ProfileSync>()
    val scope = rememberCoroutineScope()
    var consent by rememberSaveable(stateSaver = ConsentSaver) { mutableStateOf(ConsentDraft()) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    fun accept() {
        failure = null
        saving = true
        scope.launch {
            try {
                profileSync.acceptTerms()
                Telemetry.track(AnalyticsEvent.TermsAccepted(TermsConsent.VERSION, during = "gate"))
                Haptics.success()
                app.termsConsent = TermsConsent.Gate.ACCEPTED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                Telemetry.unexpected(e, "account", "accept_terms")
                when (val f = profileSync.termsFailure(e)) {
                    TermsConsent.Failure.SignOut -> app.endSession()
                    is TermsConsent.Failure.Message -> failure = f.text
                }
            } finally {
                saving = false
            }
        }
    }

    BottomBar(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        bar = {
            Column(
                Modifier.padding(horizontal = DS.Space.xl).padding(top = DS.Space.md),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
            ) {
                DrafftButton(onClick = ::accept, modifier = Modifier.fillMaxWidth(), enabled = consent.isComplete && !saving) {
                    if (saving) ButtonSpinner() else Text(L("Accept and continue"), maxLines = 2)
                }
                failure?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth(),
                        style = TextStyles.footnote.medium,
                        color = DS.palette.negative,
                        textAlign = TextAlign.Center,
                    )
                }
                // Quiet: the way out for someone who doesn't agree.
                TextLinkButton(
                    L("Delete my account"),
                    onClick = { deleting = true },
                    Modifier.fillMaxWidth(),
                    color = DS.palette.body,
                    style = TextStyles.footnote.semibold,
                    fullWidth = true,
                )
            }
        },
    ) { bottom ->
        FocusScrollView(
            state = scroll,
            contentPadding = PaddingValues(
                start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.xxl, bottom = bottom.calculateBottomPadding() + DS.Space.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(DS.Space.xxl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                Text(L("Before you carry on."), Modifier.semantics { heading() }, style = display(36f), color = DS.palette.ink)
                Text(
                    branded(L("drafft now keeps a record of your consent. Tick both to keep using the app.")),
                    style = TextStyles.body,
                    color = DS.palette.body,
                )
            }
            ConsentChecks(draft = consent, onDraftChange = { consent = it })
        }
    }

    DrafftSheet(visible = deleting, onDismissRequest = { deleting = false }) { DeleteAccountSheet() }
}

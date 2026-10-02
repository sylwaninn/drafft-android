package so.drafft.app.feature.me

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import so.drafft.app.feature.verification.SupportSheet
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.model.L
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.bannerSurface
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

/**
 * Top banner for a purchase the store confirmed that the server hasn't credited yet
 * (`PurchaseCredit`). Never in the way: it doesn't block the screen, swipes up to go away, and never
 * says the payment went through. After 10 minutes it offers to contact support; once the wallet has
 * the purchase it says so, then leaves.
 */
@Composable
fun PurchaseCreditBanner(
    state: PurchaseCredit.Banner,
    pending: PurchaseCredit.Pending?,
    onContact: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The clock the "slow" check reads, every 15 s.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val slow = state == PurchaseCredit.Banner.ADDING && pending != null &&
        now - pending.date >= PurchaseCredit.slowAfter.inWholeMilliseconds

    // Written while dragging and read only when drawing: the drag never recomposes the banner.
    var dragY by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 30.dp.toPx() }

    Box(
        modifier
            .padding(horizontal = DS.Space.md)
            .graphicsLayer { translationY = minOf(0f, dragY) }
            .draggable(
                state = rememberDraggableState { dragY += it },
                orientation = Orientation.Vertical,
                onDragStopped = {
                    if (dragY < -threshold) {
                        onDismiss()
                    } else {
                        animate(dragY, 0f, animationSpec = Motion.snappy()) { v, _ -> dragY = v }
                    }
                },
            ),
    ) {
        Content(state, slow, onContact, onDismiss)
    }
}

@Composable
private fun Content(state: PurchaseCredit.Banner, slow: Boolean, onContact: () -> Unit, onDismiss: () -> Unit) {
    val p = DS.palette
    val dismissLabel = L("Dismiss")
    NightSurface {
        Row(
            Modifier
                .fillMaxWidth()
                .bannerSurface()
                .padding(DS.Space.md)
                .padding(end = DS.Space.sm)
                .semantics(mergeDescendants = !slow) {
                    customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true })
                },
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
                Text(title(state, slow), style = TextStyles.headline, color = Color.White)
                if (slow) {
                    PressScaleButton(
                        onClick = {
                            Haptics.tap()
                            onContact()
                        },
                        scale = 0.97f,
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                    ) {
                        Text(
                            L("Contact us"),
                            Modifier.align(Alignment.CenterStart),
                            style = TextStyles.subheadline.semibold,
                            color = p.accentOnNight,
                        )
                    }
                }
            }
        }
    }
}

private fun title(state: PurchaseCredit.Banner, slow: Boolean): String = when {
    state == PurchaseCredit.Banner.CREDITED -> L("Your purchase is on your account.")
    slow -> L("This is taking longer than usual.")
    else -> L("We're adding your purchase to your account.")
}

@Composable
private fun Badge(state: PurchaseCredit.Banner) {
    val p = DS.palette
    Box(
        Modifier
            .size(48.dp)
            .background(p.accentOnNight, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (state == PurchaseCredit.Banner.CREDITED) {
            DrafftIcon("check", size = 24.dp, tint = p.onAccentOnNight)
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), color = p.onAccentOnNight, strokeWidth = 2.dp, trackColor = Color.Transparent)
        }
    }
}

/**
 * Opens "Get help" above whatever is on screen, filled in for a purchase that hasn't reached the
 * account: the topic and the store reference.
 *
 * On Android the sheet is drawn by [Host], which the app places once at its root (next to the top
 * overlay); it also answers the banner's "Contact us" (`PurchaseCredit.supportRequests`).
 */
object PurchaseHelpPresenter {
    private class Request(val pending: PurchaseCredit.Pending?)

    private var request by mutableStateOf<Request?>(null)

    fun show(pending: PurchaseCredit.Pending?) {
        request = Request(pending)
    }

    /** The prefilled message: the store's reference when there is one. */
    fun message(pending: PurchaseCredit.Pending?): String =
        pending?.transactionID?.let { L("My purchase hasn't reached my account. Reference: %s", it) }
            ?: L("My purchase hasn't reached my account.")

    fun details(pending: PurchaseCredit.Pending?): Map<String, String> = buildMap {
        if (pending != null) {
            put("product", pending.productID)
            pending.transactionID?.let { put("transaction", it) }
        }
    }

    @Composable
    fun Host() {
        val credit = koinInject<PurchaseCredit>()
        LaunchedEffect(credit) { credit.supportRequests.collect { show(it) } }
        val shown = request ?: return
        DrafftSheet(onDismissRequest = { if (request === shown) request = null }) {
            SupportSheet(topic = L("Purchase"), prefill = message(shown.pending), details = details(shown.pending))
        }
    }
}

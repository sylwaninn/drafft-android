package so.drafft.app.feature.me

import so.drafft.core.ui.components.InteractiveDismissDisabled
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.math.BigDecimal
import java.math.MathContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.auth.LegalDoc
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.AppLifecycle
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.store.CustomerInfo
import so.drafft.core.data.store.Package
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.data.store.Store
import so.drafft.core.data.store.TempoPlan
import so.drafft.core.data.store.TempoSubscription
import so.drafft.core.model.Brand
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.BottomBar
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.components.SheetDetent
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.Wordmark
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/PaywallView.swift (PaywallView and SubscriptionSheet). The Swift
// `PaywallView.Plan` lives in core:data as `TempoPlan`, next to `TempoSubscription`.
//
// Billing wording: the catalog only has the App Store / Apple Account copy for the auto-renewal
// terms and the subscription links (no Google Play strings yet), so that copy is shown as written;
// the links themselves open Google Play's subscriptions page.

/** Google Play's page for the account's subscriptions (the App Store's `apps.apple.com/account/subscriptions`). */
private const val PLAY_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"

private data class Perk(val icon: String, val title: String, val detail: String)

/**
 * drafft tempo paywall. Plans and prices come from Google Play through RevenueCat (`Store`).
 * Presented in a sheet ([LocalSheetDismiss] closes it).
 *
 * [onUnlocked] is called after a successful purchase, e.g. to perform the undo that opened the
 * paywall. [unlockedTitle] is the next step offered once subscribed (on the purchase confirmation).
 */
@Composable
fun PaywallView(
    onUnlocked: () -> Unit = {},
    headline: String = L("Take it back."),
    pitch: String = L("Undo is part of drafft tempo, along with a few things that get you to a first session faster."),
    unlockedTitle: String = L("Continue"),
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val store = koinInject<Store>()
    val credit = koinInject<PurchaseCredit>()
    val scope = rememberCoroutineScope()
    val p = DS.palette

    // Nothing chosen on arrival: the person picks a plan.
    var plan by remember { mutableStateOf<TempoPlan?>(null) }
    var purchasing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    // Outcome of a purchase or restore that didn't unlock anything, said plainly.
    var notice by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    var receipt by remember { mutableStateOf<PurchaseReceipt?>(null) }
    InteractiveDismissDisabled(purchasing || receipt != null)
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { store.load() }
    // The iPhone turns off the swipe-down while a purchase runs; here system back waits too.
    BackHandler(enabled = purchasing || receipt != null) {}

    fun say(text: String) {
        notice = text
    }

    // Restores from Google Play, for the signed-in account. Unlocks once the server has drafft
    // tempo on the account's wallet.
    fun restore() {
        Haptics.tap()
        restoring = true
        notice = null
        scope.launch {
            try {
                val info = store.restore()
                val sub = store.subscription(info)
                if (sub != null) {
                    app.subscription = sub
                    val product = info.entitlements[Store.TEMPO_ENTITLEMENT]?.productIdentifier ?: Store.TEMPO_ENTITLEMENT
                    val restored = PurchaseCredit.Pending(
                        transactionID = null, productID = product, date = System.currentTimeMillis(),
                        target = PurchaseCredit.Pending.Target.Tempo,
                    )
                    if (!credit.confirmed(restored, app)) return@launch
                    Haptics.success()
                    receipt = PurchaseReceipt(PurchaseReceipt.Item.Tempo(sub))
                } else {
                    Haptics.warning()
                    say(L("No drafft tempo purchase on this Apple ID."))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                say(L("Couldn't reach the App Store. Try again."))
            } finally {
                restoring = false
            }
        }
    }

    fun purchase() {
        val picked = plan ?: return
        val pkg = store.tempo[picked] ?: return
        Haptics.tap()
        purchasing = true
        notice = null
        scope.launch {
            try {
                when (val outcome = store.purchase(pkg)) {
                    Store.Outcome.Cancelled -> Unit
                    is Store.Outcome.Purchased -> {
                        // Confirmed by Google Play: the server is asked to turn drafft tempo on at once
                        // (it also credits the first weekly boost); slow, a banner at the top takes over.
                        // Nothing is unlocked on the device's word alone.
                        val price = pkg.storeProduct.localizedPriceString
                        val sub = store.subscription(outcome.info)
                            ?: TempoSubscription(plan = picked, billing = picked.billing(price))
                        app.subscription = sub
                        val bought = PurchaseCredit.Pending(
                            transactionID = outcome.transactionID,
                            productID = pkg.storeProduct.productIdentifier,
                            date = System.currentTimeMillis(),
                            target = PurchaseCredit.Pending.Target.Tempo,
                        )
                        if (!credit.confirmed(bought, app)) return@launch
                        receipt = PurchaseReceipt(PurchaseReceipt.Item.Tempo(sub))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                say(L("The purchase didn't go through. You haven't been charged."))
            } finally {
                purchasing = false
            }
        }
    }

    val perks = listOf(
        Perk("arrow.uturn.backward", L("Undo your last swipe"), L("Swiped too fast? Bring them back.")),
        Perk("heart.text.square", L("See who liked you"), L("Match instantly with people already into you.")),
        Perk("infinity", L("Unlimited likes"), L("No daily cap, like everyone you'd train with.")),
        Perk("bolt.fill", L("Weekly boost"), L("One free boost every week: 30 minutes at the top of decks near you.")),
    )

    Box(modifier.fillMaxSize().background(p.night)) {
        NightSurface {
            BottomBar(
                scroll = scroll,
                // The sheet already sits below the status bar and above the navigation bar.
                windowInsets = WindowInsets(0.dp),
                bar = {
                    PaywallFooter(
                        plan = plan,
                        purchasing = purchasing,
                        restoring = restoring,
                        isLinked = store.isLinked,
                        notice = notice,
                        onPurchase = ::purchase,
                        onRestore = ::restore,
                        onLegal = { uriHandler.openUri(it.url()) },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(padding)
                        .padding(horizontal = DS.Space.xl)
                        .padding(top = DS.Space.xxxl, bottom = DS.Space.xl),
                    verticalArrangement = Arrangement.spacedBy(DS.Space.xxl),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                        // The tier lockup: the wordmark, then "tempo" in the same face and size,
                        // lowercase, in the accent.
                        TierLockup(size = 26f)
                        Text(
                            headline,
                            Modifier.semantics { heading() },
                            style = display(52f),
                            color = p.accentOnNight,
                        )
                        Text(
                            branded(pitch, tierColor = p.accentOnNight),
                            style = TextStyles.body,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
                        perks.forEach { perk ->
                            Row(
                                Modifier.semantics(mergeDescendants = true) {},
                                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    Modifier.size(40.dp).background(p.accentOnNight, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    DrafftIcon(perk.icon, size = 20.dp, tint = p.onAccentOnNight)
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(perk.title, style = TextStyles.headline, color = Color.White)
                                    Text(perk.detail, style = TextStyles.subheadline, color = Color.White.copy(alpha = 0.65f))
                                }
                            }
                        }
                    }

                    Plans(store = store, selected = plan, onSelect = { plan = it }, onRetry = { scope.launch { store.load() } })
                }
            }
        }

        PressScaleButton(
            onClick = dismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(DS.Space.lg)
                .size(44.dp)
                .background(Color.White.copy(alpha = 0.14f), CircleShape),
            contentDescription = L("Not now"),
        ) {
            DrafftIcon("xmark", size = 20.dp, tint = Color.White)
        }
    }

    // Over the paywall; closing it closes both, then the unlocked action runs.
    receipt?.let { r ->
        DrafftSheet(
            onDismissRequest = {
                receipt = null
                dismiss()
                app.scope.launch {
                    delay(150)
                    onUnlocked()
                }
            },
            detent = SheetDetent.FIT,
            raised = true,
        ) {
            PurchaseConfirmation(receipt = r, primaryTitle = unlockedTitle, primary = LocalSheetDismiss.current)
        }
    }
}

/** "drafft tempo": the wordmark, then "tempo" in the display face at the same size, in the accent on night. */
@Composable
private fun TierLockup(size: Float, modifier: Modifier = Modifier) {
    val p = DS.palette
    Row(
        modifier.clearAndSetSemantics { contentDescription = Brand.TIER_NAME },
        horizontalArrangement = Arrangement.spacedBy((size * 0.28f).dp),
    ) {
        Wordmark(Modifier.alignByBaseline(), size = size, color = Color.White, trail = p.accentOnNight.copy(alpha = 0.5f))
        Text(
            Brand.TIER,
            Modifier.alignByBaseline(),
            style = display(size).copy(letterSpacing = (-0.02).em),
            color = p.accentOnNight,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The plans Google Play returned, or why there are none yet. They show once purchases are linked to
 * the account too: a plan bought before that would never be credited.
 */
@Composable
private fun Plans(store: Store, selected: TempoPlan?, onSelect: (TempoPlan) -> Unit, onRetry: () -> Unit) {
    val p = DS.palette
    val available = TempoPlan.entries.mapNotNull { plan -> store.tempo[plan]?.let { plan to it } }
    when {
        available.isNotEmpty() && store.isLinked -> {
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                available.forEach { (plan, pkg) ->
                    PlanRow(plan, pkg, tag = tag(store, plan), on = selected == plan, onClick = {
                        Haptics.select()
                        onSelect(plan)
                    })
                }
            }
        }
        store.state == Store.LoadState.FAILED -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(DS.Radius.xl))
                    .padding(DS.Space.lg),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            ) {
                Text(
                    L("Plans couldn't load. Check your connection and try again."),
                    style = TextStyles.subheadline,
                    color = Color.White.copy(alpha = 0.75f),
                )
                TextLinkButton(L("Try again"), onClick = onRetry, color = p.accentOnNight, style = TextStyles.subheadline.semibold)
            }
        }
        else -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 120.dp)
                    .semantics { contentDescription = L("Loading plans") },
                contentAlignment = Alignment.Center,
            ) { Spinner(Color.White) }
        }
    }
}

/** "Most popular" on 6 months; on 12 months, the saving against paying monthly. */
private fun tag(store: Store, plan: TempoPlan): String? = when (plan) {
    TempoPlan.MONTH -> null
    TempoPlan.SIX_MONTHS -> L("Most popular")
    TempoPlan.YEAR -> {
        val monthly = store.tempo[TempoPlan.MONTH]?.storeProduct?.price
        val perMonth = store.tempo[TempoPlan.YEAR]?.storeProduct?.pricePerMonth
        if (monthly == null || perMonth == null || monthly.signum() <= 0) {
            null
        } else {
            val pct = ((BigDecimal.ONE - perMonth.divide(monthly, MathContext.DECIMAL64)).toDouble() * 100).roundToInt()
            if (pct > 0) L("Save %d%%", pct) else null
        }
    }
}

@Composable
private fun PlanRow(plan: TempoPlan, pkg: Package, tag: String?, on: Boolean, onClick: () -> Unit) {
    val p = DS.palette
    val product = pkg.storeProduct
    val fill by animateColorAsState(if (on) p.selectedOnNight else Color.White.copy(alpha = 0.07f), Motion.select(), label = "planFill")
    val tagView: @Composable () -> Unit = {
        if (tag != null) {
            Text(
                tag,
                Modifier
                    .background(p.accentOnNight, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = TextStyles.caption2.bold,
                color = p.onAccentOnNight,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    PressScaleButton(
        onClick = onClick,
        scale = 0.98f,
        modifier = Modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(DS.Radius.xl))
            .semantics { selected = on },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(DS.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckDisc(on, ring = Color.White.copy(alpha = 0.4f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The tag sits after the title, on the same line; it only drops under it at the largest text sizes.
                FirstThatFits(
                    first = {
                        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(plan.title, style = TextStyles.headline, color = Color.White, maxLines = 1, softWrap = false)
                            tagView()
                        }
                    },
                    second = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(plan.title, style = TextStyles.headline, color = Color.White)
                            tagView()
                        }
                    },
                )
                Text(plan.total(product.localizedPriceString), style = TextStyles.footnote, color = Color.White.copy(alpha = 0.6f))
            }
            // "7,33 €/month" on one line; stacked only when it can't fit.
            val perMonth = product.localizedPricePerMonth ?: product.localizedPriceString
            FirstThatFits(
                first = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(perMonth, Modifier.alignByBaseline(), style = displayBold(20f), color = Color.White, maxLines = 1, softWrap = false)
                        Text(L("/ month"), Modifier.alignByBaseline(), style = TextStyles.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
                    }
                },
                second = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(perMonth, style = displayBold(20f), color = Color.White, maxLines = 1, softWrap = false)
                        Text(L("/ month"), style = TextStyles.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1, softWrap = false)
                    }
                },
            )
        }
    }
}

@Composable
private fun PaywallFooter(
    plan: TempoPlan?,
    purchasing: Boolean,
    restoring: Boolean,
    isLinked: Boolean,
    notice: String?,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onLegal: (LegalDoc) -> Unit,
) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DS.Space.xl)
            .padding(top = DS.Space.md, bottom = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrafftButton(onClick = onPurchase, enabled = plan != null && !purchasing && isLinked) {
            if (purchasing) {
                Spinner(p.onAccentOnNight, Modifier.semantics { contentDescription = L("Adding it to your account") })
            } else {
                Text(branded(L("Get drafft tempo"), brandWeight = FontWeight.ExtraBold, tierColor = p.night))
            }
        }

        // Why it's disabled, only while it is: no empty line under the button once a plan is picked.
        AnimatedVisibility(plan == null, enter = fadeIn(Motion.select()), exit = fadeOut(Motion.select())) {
            Text(
                L("Pick a plan to continue."),
                Modifier.padding(top = DS.Space.xs),
                style = TextStyles.footnote,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }

        // One row while it fits; otherwise restore on its own line above the two documents.
        CompositionLocalProvider(LocalContentColor provides Color.White.copy(alpha = 0.7f)) {
            LegalLinks(
                restoring = restoring,
                restoreEnabled = !restoring && !purchasing && isLinked,
                spinner = Color.White,
                onRestore = onRestore,
                onLegal = onLegal,
            )
        }

        AnimatedVisibility(notice != null, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
            // The last notice stays drawn while it fades out.
            val last = remember { arrayOfNulls<String>(1) }
            if (notice != null) last[0] = notice
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DrafftIcon("info.circle.fill", size = 16.dp, tint = Color.White)
                Text(last[0].orEmpty(), style = TextStyles.footnote.semibold, color = Color.White)
            }
        }

        // 0.6 at least: 0.45 white on night fell under 4.5:1.
        // Store terms for auto-renewable subscriptions.
        Text(
            L("Payment is charged to your Apple Account. The subscription renews automatically at the same price unless you cancel it at least 24 hours before the end of the period. Manage or cancel it in your App Store settings."),
            style = TextStyles.caption,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Restore purchases, Terms, Privacy: one row while it fits, otherwise restore above the two documents. */
@Composable
private fun LegalLinks(
    restoring: Boolean,
    restoreEnabled: Boolean,
    spinner: Color,
    onRestore: () -> Unit,
    onLegal: (LegalDoc) -> Unit,
) {
    val restoreLink: @Composable () -> Unit = {
        FooterLink(
            text = L("Restore purchases"),
            onClick = onRestore,
            enabled = restoreEnabled,
            busy = restoring,
            spinner = spinner,
            description = if (restoring) L("Restoring purchases") else L("Restore purchases"),
        )
    }
    val termsLink: @Composable () -> Unit = {
        FooterLink(L("Terms"), onClick = { onLegal(LegalDoc.TERMS) }, description = LegalDoc.TERMS.title)
    }
    val privacyLink: @Composable () -> Unit = {
        FooterLink(L("Privacy"), onClick = { onLegal(LegalDoc.PRIVACY) }, description = LegalDoc.PRIVACY.title)
    }
    FirstThatFits(
        first = {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
                restoreLink()
                termsLink()
                privacyLink()
            }
        },
        second = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                restoreLink()
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
                    termsLink()
                    privacyLink()
                }
            }
        },
    )
}

/** A plain text button (footnote semibold, the content colour), its whole 44 pt row the target. */
@Composable
private fun FooterLink(
    text: String,
    onClick: () -> Unit,
    description: String,
    enabled: Boolean = true,
    busy: Boolean = false,
    spinner: Color = Color.White,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.alpha(if (busy) 0f else if (enabled) 1f else 0.5f),
            style = TextStyles.footnote.semibold,
            color = LocalContentColor.current,
            maxLines = 1,
            softWrap = false,
        )
        if (busy) Spinner(spinner)
    }
}

/** The iPhone's `ProgressView()`: a small indeterminate ring. */
@Composable
private fun Spinner(color: Color, modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(20.dp), color = color, strokeWidth = 2.dp, trackColor = Color.Transparent)
}

/**
 * `ViewThatFits(in: .horizontal)` with two candidates: [first] when its ideal width fits the width
 * offered, [second] otherwise.
 */
@Composable
private fun FirstThatFits(
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    Layout(contents = listOf(first, second), modifier = modifier) { (a, b), constraints ->
        val lead = a.firstOrNull()
        val fits = lead == null || !constraints.hasBoundedWidth ||
            lead.maxIntrinsicWidth(Constraints.Infinity) <= constraints.maxWidth
        val placeable = (if (fits) a else b).firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(placeable?.width ?: 0, placeable?.height ?: 0) { placeable?.place(0, 0) }
    }
}

// MARK: - Subscription (You › drafft tempo)

private data class IncludedPerk(val icon: String, val title: String)

/**
 * The active subscription, as the store reports it. Plan, price, next renewal (or end date once
 * cancelled), what's included, and the way to change or cancel it: the store's own subscription
 * page, since billing belongs to the store. Presented in a sheet ([LocalSheetDismiss] closes it).
 */
@Composable
fun SubscriptionSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val uriHandler = LocalUriHandler.current
    val store = koinInject<Store>()
    val lifecycle = koinInject<AppLifecycle>()
    val scope = rememberCoroutineScope()
    var managing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    // What the store reports. Expired closes the page, since the drafft tempo row only shows while
    // subscribed.
    fun apply(info: CustomerInfo) {
        val sub = store.subscription(info)
        if (sub == null) {
            dismiss()
            app.scope.launch {
                delay(250)
                app.subscription = null
            }
        } else {
            app.subscription = sub
        }
    }

    suspend fun refresh() {
        if (store.reportsLinkedAccount) {
            try {
                apply(store.customerInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        app.loadWallet()
    }

    fun restore() {
        Haptics.tap()
        restoring = true
        scope.launch {
            try {
                val info = store.restore()
                apply(info)
                app.loadWallet()
                Haptics.success()
                restoreResult = L("Your subscription is up to date.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.warning()
                restoreResult = L("Couldn't reach the App Store. Try again.")
            } finally {
                restoring = false
            }
        }
    }

    // Back from Google Play's page: read what the store now says (cancelled, plan change). The
    // iPhone's `manageSubscriptionsSheet` reports its closing; here the app leaving and coming back
    // to the front marks it.
    LaunchedEffect(managing) {
        if (!managing) return@LaunchedEffect
        repeat(50) {
            if (!lifecycle.isActive()) return@repeat
            delay(100)
        }
        while (!lifecycle.isActive()) delay(250)
        managing = false
        refresh()
    }

    EdgeBars(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        topBar = { SheetNavBar(title = L("drafft tempo"), onClose = dismiss) },
        bottomBar = {
            SubscriptionFooter(onManage = {
                Haptics.tap()
                managing = true
                uriHandler.openUri(PLAY_SUBSCRIPTIONS_URL)
            })
        },
        navigationEdge = true,
        // The sheet already sits below the status bar and above the navigation bar.
        windowInsets = WindowInsets(0.dp),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(horizontal = DS.Space.lg)
                .padding(top = DS.Space.sm, bottom = DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            app.subscription?.let { sub ->
                SubscriptionStatus(sub)
                Included()
                Billing(
                    restoring = restoring,
                    restoreResult = restoreResult,
                    onOpenStore = { uriHandler.openUri(PLAY_SUBSCRIPTIONS_URL) },
                    onRestore = ::restore,
                    onLegal = { uriHandler.openUri(it.url()) },
                )
            }
        }
    }

}

@Composable
private fun SubscriptionStatus(sub: TempoSubscription) {
    val p = DS.palette
    val pillText = if (sub.willRenew) p.onAccentOnNight else p.night
    val pillFill by animateColorAsState(if (sub.willRenew) p.accentOnNight else Color.White, Motion.snappy(), label = "statusPill")
    val pillInk by animateColorAsState(pillText, Motion.snappy(), label = "statusPillInk")
    // Sheet block: plain night.
    NightBlock(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierLockup(size = 22f)
                Spacer(Modifier.weight(1f).width(DS.Space.sm))
                Box(
                    Modifier
                        .defaultMinSize(minHeight = 24.dp)
                        .background(pillFill, CircleShape)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (sub.willRenew) L("Active") else L("Ending"),
                        style = TextStyles.caption.bold,
                        color = pillInk,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(sub.billing, style = TextStyles.headline, color = Color.White)
                val ends = DateText.format("yMMMMd", sub.periodEnds)
                AnimatedContent(
                    targetState = if (sub.willRenew) L("Renews on %s", ends) else L("Cancelled. You keep everything until %s.", ends),
                    transitionSpec = { fadeIn(Motion.snappy()).togetherWith(fadeOut(Motion.snappy())) },
                    label = "renewal",
                ) { text ->
                    Text(text, style = TextStyles.subheadline, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun Included() {
    val p = DS.palette
    val perks = listOf(
        IncludedPerk("arrow.uturn.backward", L("Undo your last swipe")),
        IncludedPerk("heart.text.square", L("See who liked you")),
        IncludedPerk("infinity", L("Unlimited likes")),
        IncludedPerk("bolt.fill", L("One free boost every week")),
    )
    SheetBlock(title = L("Included")) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
            perks.forEach { perk ->
                Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).background(p.canvasSoft, CircleShape), contentAlignment = Alignment.Center) {
                        DrafftIcon(perk.icon, size = 16.dp, tint = p.ink)
                    }
                    Text(perk.title, style = TextStyles.subheadline.semibold, color = p.ink)
                }
            }
        }
    }
}

@Composable
private fun Billing(
    restoring: Boolean,
    restoreResult: String?,
    onOpenStore: () -> Unit,
    onRestore: () -> Unit,
    onLegal: (LegalDoc) -> Unit,
) {
    val p = DS.palette
    SheetBlock(title = L("Billing")) {
        Text(
            branded(L("Your subscription is billed through your Apple Account and renews automatically unless you cancel it at least 24 hours before the end of the current period. To change your plan or cancel, go to your App Store subscriptions. Deleting drafft doesn't cancel it.")),
            style = TextStyles.subheadline,
            color = p.body,
        )
        TextLinkButton(onClick = onOpenStore) {
            DrafftIcon("arrow.up.right", size = 18.dp, tint = p.accentInk)
            Text(L("Open App Store subscriptions"), style = TextStyles.subheadline.semibold, color = p.accentInk)
        }
        CompositionLocalProvider(LocalContentColor provides p.body) {
            LegalLinks(
                restoring = restoring,
                restoreEnabled = !restoring,
                spinner = p.ink,
                onRestore = onRestore,
                onLegal = onLegal,
            )
        }
        AnimatedVisibility(restoreResult != null, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
            val last = remember { arrayOfNulls<String>(1) }
            if (restoreResult != null) last[0] = restoreResult
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DrafftIcon("checkmark.circle.fill", size = 16.dp, tint = p.positiveDeep)
                Text(last[0].orEmpty(), style = TextStyles.footnote.semibold, color = p.positiveDeep)
            }
        }
    }
}

@Composable
private fun SubscriptionFooter(onManage: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DS.Space.xl)
            .padding(top = DS.Space.md),
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrafftButton(L("Manage subscription"), onClick = onManage, kind = DrafftButtonKind.DARK)
        Text(
            L("Change plan or cancel in Apple's subscription settings."),
            style = TextStyles.footnote,
            color = DS.palette.body,
            textAlign = TextAlign.Center,
        )
    }
}

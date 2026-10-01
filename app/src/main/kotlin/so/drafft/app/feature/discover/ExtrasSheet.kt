package so.drafft.app.feature.discover

import so.drafft.core.ui.components.InteractiveDismissDisabled
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.me.PaywallView
import so.drafft.app.feature.me.PurchaseConfirmation
import so.drafft.app.feature.me.PurchaseReceipt
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.store.Package
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.data.store.Store
import so.drafft.core.model.DiscoverFilters
import so.drafft.core.model.L
import so.drafft.core.model.PackPhotos
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SheetDetent
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.navigation.NavStackHost
import so.drafft.core.ui.navigation.rememberNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import kotlin.math.roundToInt

// Port of Drafft/Features/Discover/ExtrasSheet.swift.

/** `ExtrasSheet.Tab`: which extra the sheet sells. */
object ExtrasSheet {
    enum class Tab(val rawValue: String) {
        BOOST("boost"), SUPER_LIKE("superLike"), LIKES("likes");

        val title: String
            get() = when (this) {
                BOOST -> L("Boosts")
                SUPER_LIKE -> L("Super likes")
                LIKES -> L("Likes")
            }

        val symbol: String
            get() = when (this) {
                BOOST -> "bolt"
                SUPER_LIKE -> "heart"
                LIKES -> "user-heart"
            }
    }

    /** A pack as the store sells it: localized price, and the unit price worked out from it. */
    data class Pack(val count: Int, val pkg: Package) {
        val id: Int get() = count
        val price: String get() = pkg.storeProduct.localizedPriceString
        val amount: BigDecimal get() = pkg.storeProduct.price
        val each: String?
            get() {
                if (count <= 1) return null
                val unit = amount.divide(BigDecimal(count), MathContext.DECIMAL64)
                return L("%s each", pkg.storeProduct.format(unit))
            }
    }
}

typealias ExtrasSheetTab = ExtrasSheet.Tab

private enum class ExtrasRoute { ROOT, STORE }

/**
 * Boosts, super likes or today's likes: one extra per sheet (boosts from the header, super likes
 * or likes when you run out). The hero shows what the extra does to your place in the deck;
 * below it, packs as a quiet list. One primary action at a time in the pinned footer.
 * Present it in a `DrafftSheet`.
 */
@Composable
fun ExtrasSheet(tab: ExtrasSheet.Tab, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val reduceMotion = LocalReduceMotion.current
    val store = koinInject<Store>()
    val credit = koinInject<PurchaseCredit>()
    val scope = rememberCoroutineScope()
    var pack by remember { mutableStateOf<ExtrasSheet.Pack?>(null) }
    var purchasing by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    val lifted = remember { Animatable(if (reduceMotion) 1f else 0f) }
    var receipt by remember { mutableStateOf<PurchaseReceipt?>(null) }
    InteractiveDismissDisabled(purchasing || receipt != null)
    /** Set by the confirmation's "Boost now": launch once it has closed. */
    var boostAfterReceipt by remember { mutableStateOf(false) }
    /** A purchase that didn't go through, said plainly under the button. */
    var failure by remember { mutableStateOf<String?>(null) }
    /** Boosts only: the store pushed from the launch page ("Get more boosts"). */
    val stack = rememberNavStack(ExtrasRoute.ROOT)

    val packs = when (tab) {
        ExtrasSheet.Tab.BOOST -> store.boosts
        ExtrasSheet.Tab.SUPER_LIKE -> store.superLikes
        ExtrasSheet.Tab.LIKES -> emptyList()
    }.map { ExtrasSheet.Pack(Store.count(it), it) }

    // Boosts keep buying and launching apart: with boosts in hand (or one running) the sheet
    // opens on the launch page, whose only action is "Boost now"; the store is a separate page
    // behind "Get more boosts". With none left, the sheet opens straight on the store, and once
    // a pack is bought it turns into the launch page.
    val opensOnLaunch = tab == ExtrasSheet.Tab.BOOST && (app.boosts > 0 || app.isBoosting())

    LaunchedEffect(Unit) { store.load() }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            delay(100)
            lifted.animateTo(1f, Motion.springOf(0.45, 0.7f))
        }
    }

    fun launchBoost() {
        dismiss()
        app.scope.launch {
            delay(250)
            app.startBoost()
        }
    }

    fun buy() {
        val chosen = pack ?: return
        val item = if (tab == ExtrasSheet.Tab.BOOST) AppModel.Consumable.BOOST else AppModel.Consumable.SUPER_LIKE
        val before = app.balance(item)
        val target: PurchaseCredit.Pending.Target = if (item == AppModel.Consumable.BOOST) {
            PurchaseCredit.Pending.Target.Boosts(atLeast = before + chosen.count)
        } else {
            PurchaseCredit.Pending.Target.SuperLikes(atLeast = before + chosen.count)
        }
        Haptics.tap()
        purchasing = true
        failure = null
        // On the model's scope: closing the sheet never abandons a purchase halfway.
        app.scope.launch {
            try {
                val transactionID = try {
                    val outcome = store.purchase(chosen.pkg)
                    if (outcome !is Store.Outcome.Purchased) return@launch
                    outcome.transactionID
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Haptics.warning()
                    failure = Store.PurchaseProblem.from(e).message(restorable = false)
                    return@launch
                }
                // Confirmed by the store: the server is asked to credit the pack at once. Slow, the
                // button frees and a banner at the top takes over. The count shown is always the
                // wallet's, never one made up on the device.
                val purchase = PurchaseCredit.Pending(
                    transactionID = transactionID,
                    productID = chosen.pkg.storeProduct.productIdentifier,
                    date = System.currentTimeMillis(),
                    target = target,
                )
                if (!credit.confirmed(purchase, app)) return@launch
                pack = null
                // Boosts: back to the launch page, now showing the new count.
                if (stack.canPop) stack.pop()
                receipt = PurchaseReceipt(
                    if (tab == ExtrasSheet.Tab.BOOST) {
                        PurchaseReceipt.Item.Boosts(count = chosen.count, price = chosen.price, balance = app.boosts)
                    } else {
                        PurchaseReceipt.Item.SuperLikes(count = chosen.count, price = chosen.price, balance = app.superLikes)
                    },
                )
            } finally {
                purchasing = false
            }
        }
    }

    /** Super likes: back to the deck. Boosts: stay on the launch page, or launch right away. */
    fun afterReceipt() {
        if (tab == ExtrasSheet.Tab.SUPER_LIKE) {
            dismiss()
            return
        }
        if (!boostAfterReceipt) return
        boostAfterReceipt = false
        launchBoost()
    }

    val hero: @Composable () -> Unit = {
        Hero(
            tab = tab,
            lifted = { lifted.value },
            onClose = dismiss,
        )
    }

    NavStackHost(stack, modifier) { route ->
        if (route == ExtrasRoute.ROOT && opensOnLaunch) {
            LaunchPage(
                hero = hero,
                launchButton = { LaunchButton(onLaunch = ::launchBoost) },
                onMore = {
                    Haptics.tap()
                    stack.push(ExtrasRoute.STORE)
                },
            )
        } else {
            val pushed = route == ExtrasRoute.STORE
            StorePage(
                tab = tab,
                pushed = pushed,
                hero = hero,
                packs = packs,
                store = store,
                pack = pack,
                onPick = { p ->
                    Haptics.select()
                    pack = if (pack == p) null else p
                },
                onBack = { stack.pop() },
                onClose = dismiss,
                footer = {
                    Footer(
                        tab = tab,
                        failure = failure,
                        primary = {
                            if (tab == ExtrasSheet.Tab.LIKES) {
                                if (app.isPremium) {
                                    DrafftButton(L("Keep swiping"), onClick = dismiss)
                                } else {
                                    DrafftButton(L("Get unlimited likes"), onClick = { showPaywall = true })
                                }
                            } else {
                                DrafftButton(onClick = ::buy, enabled = pack != null && !purchasing && store.isLinked) {
                                    val chosen = pack
                                    when {
                                        purchasing -> CircularProgressIndicator(
                                            Modifier
                                                .size(22.dp)
                                                .semantics { contentDescription = L("Adding it to your account") },
                                            color = DS.palette.onLime,
                                            strokeWidth = 2.5.dp,
                                        )
                                        chosen != null -> Text(buyTitle(tab, chosen), maxLines = 2)
                                        else -> Text(L("Choose a pack"), maxLines = 2)
                                    }
                                }
                            }
                        },
                    )
                },
            )
        }
    }

    // Over this sheet: closing it comes back here (boosts: to the launch page).
    receipt?.let { r ->
        DrafftSheet(
            onDismissRequest = {
                receipt = null
                afterReceipt()
            },
            detent = SheetDetent.FIT,
            raised = true,
        ) {
            val close = LocalSheetDismiss.current
            if (tab == ExtrasSheet.Tab.BOOST && !app.isBoosting()) {
                PurchaseConfirmation(
                    receipt = r,
                    primaryTitle = L("Boost now"),
                    primary = {
                        boostAfterReceipt = true
                        close()
                    },
                    secondaryTitle = L("Later"),
                    secondary = close,
                )
            } else {
                PurchaseConfirmation(receipt = r, primaryTitle = L("Got it"), primary = close)
            }
        }
    }

    DrafftSheet(visible = showPaywall, onDismissRequest = { showPaywall = false }) {
        PaywallView(
            onUnlocked = dismiss,
            headline = L("No cap on likes."),
            pitch = L("Unlimited likes come with drafft tempo, along with a free boost every week."),
            unlockedTitle = L("Keep swiping"),
        )
    }
}

// MARK: Pages

/** Use a boost you own. Nothing to buy here. */
@Composable
private fun LaunchPage(hero: @Composable () -> Unit, launchButton: @Composable () -> Unit, onMore: () -> Unit) {
    val scroll = rememberScrollState()
    val p = DS.palette
    EdgeBars(
        scroll = scroll,
        modifier = Modifier.fillMaxSize().background(p.canvasSoft),
        bottomBar = {
            Column(
                Modifier.padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.md, bottom = DS.Space.xs),
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
            ) {
                launchButton()
                PressScaleButton(onClick = onMore, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp), scale = 0.97f) {
                    Text(L("Get more boosts"), style = TextStyles.body.semibold, color = p.ink)
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.lg, bottom = DS.Space.xl),
        ) { hero() }
    }
}

/** Buy a pack. Nothing to launch here. Pushed from the launch page, it skips the hero. */
@Composable
private fun StorePage(
    tab: ExtrasSheet.Tab,
    pushed: Boolean,
    hero: @Composable () -> Unit,
    packs: List<ExtrasSheet.Pack>,
    store: Store,
    pack: ExtrasSheet.Pack?,
    onPick: (ExtrasSheet.Pack) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    footer: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    EdgeBars(
        scroll = scroll,
        modifier = Modifier.fillMaxSize().background(DS.palette.canvasSoft),
        topBar = if (pushed) {
            {
                SheetNavBar(
                    if (tab == ExtrasSheet.Tab.BOOST) L("Get more boosts") else L("Get more super likes"),
                    onClose = onClose,
                    leading = { GlassCircleButton("alt-arrow-left", onClick = onBack, contentDescription = L("Back")) },
                )
            }
        } else {
            null
        },
        bottomBar = footer,
        navigationEdge = true,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(start = DS.Space.lg, end = DS.Space.lg, top = if (pushed) DS.Space.sm else DS.Space.lg, bottom = DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            if (!pushed) hero()
            // Packs show once purchases are linked to the account: one bought before that
            // would never be credited.
            if (tab != ExtrasSheet.Tab.LIKES && (packs.isEmpty() || !store.isLinked)) {
                PacksUnavailable(store.state == Store.LoadState.FAILED, onRetry = { scope.launch { store.load() } })
            } else if (packs.isNotEmpty()) {
                PackList(tab, packs, pack, titled = !pushed, onPick = onPick)
            }
        }
    }
}

/** "Boost now", or disabled with the reason while one runs. */
@Composable
private fun LaunchButton(onLaunch: () -> Unit) {
    val app = LocalAppModel.current
    val now = rememberSecondTicker(app.boostEndsAt)
    val running = app.isBoosting(now)
    DrafftButton(onClick = onLaunch, enabled = !running && app.boosts != 0) {
        DrafftIcon("bolt", size = (17f * 1.2f).dp, tint = LocalContentColor.current)
        Text(if (running) L("Boost running") else L("Boost now"), maxLines = 2)
    }
}

// MARK: Hero

/** What the extra does, shown literally: your card rising out of the pack to the front. */
@Composable
private fun Hero(tab: ExtrasSheet.Tab, lifted: () -> Float, onClose: () -> Unit) {
    val app = LocalAppModel.current
    val p = DS.palette
    val isSuper = tab == ExtrasSheet.Tab.SUPER_LIKE
    val blockFill = if (isSuper) p.negative else p.night
    val accent = if (isSuper) Color.White else p.accentOnNight
    val shape = RoundedCornerShape(DS.Radius.xl)
    val headline = when (tab) {
        ExtrasSheet.Tab.BOOST -> L("Lead the pack.")
        ExtrasSheet.Tab.SUPER_LIKE -> L("Stand out.")
        ExtrasSheet.Tab.LIKES -> if (app.isPremium) L("No cap.") else L("Out of likes.")
    }
    val explanation = when (tab) {
        ExtrasSheet.Tab.BOOST -> L("For 30 minutes, people nearby see your profile before anyone else's.")
        ExtrasSheet.Tab.SUPER_LIKE -> L("They see you first, with a red heart on your profile. It doesn't use a daily like.")
        ExtrasSheet.Tab.LIKES -> if (app.isPremium) L("drafft tempo has no daily limit.") else L("Your likes refill at midnight, or go unlimited with drafft tempo.")
    }
    NightSurface {
        Column(
            Modifier
                .fillMaxWidth()
                // Sheet hero: plain fill.
                .background(blockFill, shape)
                .border(1.dp, p.blockEdge, shape)
                .padding(start = DS.Space.xl, end = DS.Space.xl, bottom = DS.Space.xl, top = DS.Space.xl - DS.Space.sm),
            verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Inventory(tab)
                Spacer(Modifier.weight(1f))
                PressScaleButton(onClick = onClose, modifier = Modifier.size(44.dp), contentDescription = L("Close")) {
                    Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        DrafftIcon("close", size = (17f * 1.2f).dp, tint = Color.White)
                    }
                }
            }

            PackFan(lifted, Modifier.fillMaxWidth().padding(vertical = DS.Space.sm)) { Badge(tab) }

            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                Text(headline, Modifier.semantics { heading() }, style = display(40f), color = accent)
                Text(
                    branded(explanation, tierColor = if (isSuper) Color.White else p.accentOnNight),
                    style = TextStyles.body,
                    color = Color.White.copy(alpha = if (isSuper) 0.9f else 0.72f),
                )
            }

            if (tab == ExtrasSheet.Tab.BOOST) BoostStatus()
        }
    }
}

/** What you have left, as a quiet pill. */
@Composable
private fun Inventory(tab: ExtrasSheet.Tab) {
    val app = LocalAppModel.current
    val text = when (tab) {
        ExtrasSheet.Tab.BOOST -> if (app.boosts == 1) L("1 boost left") else L("%d boosts left", app.boosts)
        ExtrasSheet.Tab.SUPER_LIKE -> if (app.superLikes == 1) L("1 super like left") else L("%d super likes left", app.superLikes)
        ExtrasSheet.Tab.LIKES -> likesText(app)
    }
    Row(
        Modifier
            .padding(top = 6.dp)
            .defaultMinSize(minHeight = 32.dp)
            .background(Color.White.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tab == ExtrasSheet.Tab.SUPER_LIKE) {
            SuperLikeMark(size = 11.dp, color = Color.White)
        } else {
            DrafftIcon(tab.symbol, size = (12f * 1.2f).dp, tint = Color.White)
        }
        RollingText(text, style = TextStyles.footnote.bold.monospacedDigits, color = Color.White, maxLines = 1)
    }
}

/** Likes left today as the server counts them; the daily allowance until it's read. */
private fun likesText(app: AppModel): String {
    if (app.isPremium) return L("Unlimited likes")
    val left = app.likesLeft ?: return L("%d likes a day", AppModel.DAILY_LIKES)
    return L("%d of %d likes left", left, AppModel.DAILY_LIKES)
}

@Composable
private fun Badge(tab: ExtrasSheet.Tab) {
    val p = DS.palette
    when (tab) {
        ExtrasSheet.Tab.SUPER_LIKE -> Box(
            Modifier
                .size(34.dp)
                .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                .background(p.negative, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SuperLikeMark(Modifier.offset(x = (-3).dp), size = 13.dp, color = Color.White)
        }
        ExtrasSheet.Tab.BOOST, ExtrasSheet.Tab.LIKES -> {
            // Likes stay green whatever the brand accent.
            val likes = tab == ExtrasSheet.Tab.LIKES
            Box(
                Modifier
                    .size(34.dp)
                    .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
                    .background(if (likes) p.like else p.accentOnNight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon(tab.symbol, size = (15f * 1.2f).dp, tint = if (likes) p.onLike else p.onAccentOnNight)
            }
        }
    }
}

/** While a boost runs: the time left and a draining lane. */
@Composable
private fun BoostStatus() {
    val app = LocalAppModel.current
    val now = rememberSecondTicker(app.boostEndsAt)
    val end = app.boostEndsAt ?: return
    if (!app.isBoosting(now)) return
    val left = Duration.between(now, end).toMillis() / 1000.0
    val fraction by animateFloatAsState(
        (left / AppModel.BOOST_DURATION_SECONDS).toFloat().coerceIn(0f, 1f),
        Motion.gentle(),
        label = "boostLane",
    )
    val p = DS.palette
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("You're at the front"), style = TextStyles.headline, color = Color.White)
            Spacer(Modifier.weight(1f))
            val s = maxOf(0, left.toInt())
            RollingText(
                String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60),
                style = displayBold(22f).monospacedDigits,
                color = p.accentOnNight,
                countsDown = true,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color.White.copy(alpha = 0.12f), CircleShape)) {
            Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(p.accentOnNight, CircleShape))
        }
    }
}

// MARK: Packs

/**
 * Packs as a clean radio list in one white block: count and unit on the left, price and
 * unit price on the right, hairlines between rows. The picked row takes the accent fill.
 */
@Composable
private fun PackList(
    tab: ExtrasSheet.Tab,
    packs: List<ExtrasSheet.Pack>,
    pack: ExtrasSheet.Pack?,
    titled: Boolean,
    onPick: (ExtrasSheet.Pack) -> Unit,
) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.xs),
    ) {
        if (titled) {
            Text(
                if (tab == ExtrasSheet.Tab.BOOST) L("Get boosts") else L("Get more super likes"),
                Modifier.padding(start = DS.Space.md, end = DS.Space.md, top = DS.Space.md, bottom = DS.Space.sm),
                style = TextStyles.headline,
                color = p.ink,
            )
        }
        packs.forEachIndexed { i, pk ->
            if (i > 0) {
                val touchesPick = pack == pk || pack == packs[i - 1]
                Box(
                    Modifier
                        .padding(start = DS.Space.md + 34.dp, end = DS.Space.md)
                        .fillMaxWidth()
                        .height(1.dp)
                        .graphicsLayer { alpha = if (touchesPick) 0f else 1f }
                        .background(p.hairline),
                )
            }
            PackRow(tab, pk, packs, on = pack == pk, best = pk == packs.last(), onPick = onPick)
        }
    }
}

/** "Save 28%" against the single-unit price of the smallest pack. */
private fun saving(p: ExtrasSheet.Pack, packs: List<ExtrasSheet.Pack>): String? {
    val first = packs.firstOrNull() ?: return null
    if (p == first || first.amount.signum() <= 0) return null
    val unit = first.amount.divide(BigDecimal(first.count), MathContext.DECIMAL64)
    val full = unit.multiply(BigDecimal(p.count))
    if (full.signum() <= 0) return null
    val pct = ((BigDecimal.ONE - p.amount.divide(full, MathContext.DECIMAL64)).toDouble() * 100).roundToInt()
    return if (pct > 0) L("Save %d%%", pct) else null
}

private fun buyTitle(tab: ExtrasSheet.Tab, p: ExtrasSheet.Pack): String =
    if (tab == ExtrasSheet.Tab.BOOST) {
        if (p.count == 1) L("Buy 1 boost for %s", p.price) else L("Buy %d boosts for %s", p.count, p.price)
    } else {
        if (p.count == 1) L("Buy 1 super like for %s", p.price) else L("Buy %d super likes for %s", p.count, p.price)
    }

/** "3 boosts", "1 super like": the count with its unit, as one string. */
private fun packName(tab: ExtrasSheet.Tab, count: Int): String =
    if (tab == ExtrasSheet.Tab.BOOST) {
        if (count == 1) L("1 boost") else L("%d boosts", count)
    } else {
        if (count == 1) L("1 super like") else L("%d super likes", count)
    }

@Composable
private fun PackRow(
    tab: ExtrasSheet.Tab,
    pk: ExtrasSheet.Pack,
    packs: List<ExtrasSheet.Pack>,
    on: Boolean,
    best: Boolean,
    onPick: (ExtrasSheet.Pack) -> Unit,
) {
    val p = DS.palette
    // Selected: solid accent, like every other selection (a pale wash vanished on the sheet's well).
    val fill by animateColorAsState(if (on) p.lime else p.lime.copy(alpha = 0f), Motion.select(), label = "packFill")
    val ink by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "packInk")
    val soft by animateColorAsState(if (on) p.onLime else p.mute, Motion.select(), label = "packDetail")
    val singular = if (tab == ExtrasSheet.Tab.BOOST) L("boost") else L("super like")
    val plural = if (tab == ExtrasSheet.Tab.BOOST) L("boosts") else L("super likes")
    val description = if (best) L("%s, %s, best value", packName(tab, pk.count), pk.price) else L("%s, %s", packName(tab, pk.count), pk.price)
    val shape = RoundedCornerShape(DS.Radius.lg)
    PressScaleButton(
        onClick = { onPick(pk) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .semantics { selected = on },
        scale = 0.98f,
        contentDescription = description,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(fill, shape)
                .padding(DS.Space.md)
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckDisc(on, onLimeFill = true)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Count, unit and badge on one line; the badge drops under only at the largest text sizes.
                val name: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${pk.count}", Modifier.alignByBaseline(), style = displayBold(22f), color = ink, maxLines = 1, softWrap = false)
                        Text(if (pk.count == 1) singular else plural, Modifier.alignByBaseline(), style = TextStyles.body.semibold, color = ink, maxLines = 1, softWrap = false)
                    }
                }
                val badge: @Composable () -> Unit = {
                    if (best) {
                        // Inverted on the selected (accent) row so the badge never melts into it.
                        Box(
                            Modifier
                                .defaultMinSize(minHeight = 18.dp)
                                .background(if (on) p.onLime else p.lime, CircleShape)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(L("Best value"), style = TextStyles.caption2.heavy, color = if (on) p.accentInk else p.onLime, maxLines = 1, softWrap = false)
                        }
                    }
                }
                FirstThatFits {
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        name()
                        badge()
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        name()
                        badge()
                    }
                }
                // "Save 28%, 2,99 € each" on one line under the name; "Single" for the one-unit pack.
                val detail = listOfNotNull(saving(pk, packs), pk.each).joinToString(", ")
                Text(
                    detail.ifEmpty { L("Single") },
                    style = TextStyles.caption.semibold.monospacedDigits,
                    color = soft,
                    maxLines = 2,
                )
            }

            Text(pk.price, style = TextStyles.headline.monospacedDigits, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/** Packs still loading, or the store couldn't be reached. */
@Composable
private fun PacksUnavailable(failed: Boolean, onRetry: () -> Unit) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl)
    if (failed) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.canvas, shape)
                .padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        ) {
            Text(L("Packs couldn't load. Check your connection and try again."), style = TextStyles.subheadline, color = p.body)
            TextLinkButton(L("Try again"), onClick = onRetry, color = p.accentInk, style = TextStyles.subheadline.semibold)
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 160.dp)
                .background(p.canvas, shape)
                .semantics { contentDescription = L("Loading packs") },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = p.ink)
        }
    }
}

// MARK: Footer

/** Store footer: buy the picked pack. */
@Composable
private fun Footer(tab: ExtrasSheet.Tab, failure: String?, primary: @Composable () -> Unit) {
    val p = DS.palette
    Column(
        Modifier.padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.md, bottom = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        primary()
        Text(
            failure ?: if (tab == ExtrasSheet.Tab.LIKES) L("Unlimited likes come with drafft tempo.") else L("One-time purchase, never expires."),
            style = TextStyles.caption,
            color = if (failure == null) p.body else p.negative,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: Pack fan

private val othersAngles = floatArrayOf(-14f, 0f, 14f)
private val othersXs = floatArrayOf(-78f, 0f, 78f)

/**
 * Three dimmed athletes (`PackPhotos`, the pile you stand out from, so your own gender), fanned;
 * yours lifts out in front with the extra's badge.
 */
@Composable
private fun PackFan(lifted: () -> Float, modifier: Modifier = Modifier, badge: @Composable () -> Unit) {
    val app = LocalAppModel.current
    // Picked once per opening.
    val others = remember { PackPhotos.pick(DiscoverFilters.audienceOf(app.me)) }
    val grey = remember { Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) } }
    val shape = RoundedCornerShape(DS.Radius.lg)
    Box(modifier.height(170.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        others.forEachIndexed { i, portrait ->
            Box(
                Modifier
                    .size(84.dp, 112.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        rotationZ = othersAngles[i]
                        translationX = othersXs[i].dp.toPx()
                        translationY = (if (i == 1) -6f else 8f).dp.toPx()
                        alpha = 0.4f
                    }
                    .clip(shape)
                    .drawWithContent {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(Rect(Offset.Zero, size), grey)
                            drawContent()
                            canvas.restore()
                        }
                    },
            ) {
                Photo(portrait, Modifier.fillMaxSize(), side = 84.dp)
            }
        }
        Box(
            Modifier
                .size(108.dp, 144.dp)
                .graphicsLayer {
                    val v = lifted()
                    val s = 0.82f + 0.18f * v
                    scaleX = s
                    scaleY = s
                    translationY = (30f + (-4f - 30f) * v).dp.toPx()
                    rotationZ = -3f * v
                }
                .shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f)),
        ) {
            Photo(app.publicMe.portrait, Modifier.fillMaxSize().clip(shape), side = 108.dp)
            // The badge sits inside the card's corner: nothing hangs off a block.
            Box(Modifier.align(Alignment.TopEnd).padding(DS.Space.sm)) { badge() }
        }
    }
}

package so.drafft.app.feature.me

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.delay
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.store.TempoSubscription
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.ui.components.AdaptiveRow
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SparkPlus
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/PurchaseConfirmation.swift.

/** What was just bought, as the store confirmed it. */
@Immutable
data class PurchaseReceipt(
    val item: Item,
    val id: UUID = UUID.randomUUID(),
) {
    sealed interface Item {
        data class Tempo(val subscription: TempoSubscription) : Item
        data class Boosts(val count: Int, val price: String, val balance: Int) : Item
        data class SuperLikes(val count: Int, val price: String, val balance: Int) : Item
    }
}

/**
 * The moment after a purchase: presented over the screen the purchase was made on, sized to its
 * content. It says what was added, what it does, a receipt-like recap and one clear next step. The
 * presenter decides what the buttons do (and closes it).
 *
 * Present it in `DrafftSheet(detent = SheetDetent.FIT, raised = true)`: raised surface (white, lifted
 * grey in dark mode), since it's often shown over the night paywall and a dark sheet on a dark page
 * would lose its edges. It paints that surface itself too. The sheet keeps its own corner radius.
 */
@Composable
fun PurchaseConfirmation(
    receipt: PurchaseReceipt,
    primaryTitle: String,
    primary: () -> Unit,
    secondaryTitle: String? = null,
    secondary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val reduceMotion = LocalReduceMotion.current
    var arrived by remember { mutableStateOf(false) }
    val arrival by animateFloatAsState(
        if (arrived) 1f else 0f,
        if (reduceMotion) snap() else Motion.bouncy(),
        label = "purchaseMark",
    )
    LaunchedEffect(Unit) {
        Haptics.success()
        if (!reduceMotion) delay(80)
        arrived = true
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(p.sheetRaised)
            .padding(horizontal = DS.Space.xl)
            .padding(top = DS.Space.xxl, bottom = DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
            Mark(
                receipt.item,
                Modifier.graphicsLayer {
                    val s = 0.6f + 0.4f * arrival
                    scaleX = s
                    scaleY = s
                    alpha = arrival.coerceIn(0f, 1f)
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                Title(receipt.item)
                Text(branded(message(receipt.item)), style = TextStyles.body, color = p.body)
            }
        }

        Recap(receipt.item)

        Column(
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DrafftButton(
                primaryTitle,
                onClick = {
                    Haptics.tap()
                    primary()
                },
                modifier = Modifier
                    .padding(start = 12.dp)
                    .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
            )

            if (secondaryTitle != null) {
                PressScaleButton(
                    onClick = {
                        Haptics.tap()
                        secondary()
                    },
                    scale = 0.97f,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        secondaryTitle,
                        style = TextStyles.body.semibold,
                        color = p.ink,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                footnote(receipt.item),
                Modifier.fillMaxWidth(),
                style = TextStyles.caption,
                color = p.body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: Content

@Composable
private fun Mark(item: PurchaseReceipt.Item, modifier: Modifier = Modifier) {
    val p = DS.palette
    val disc = Modifier
        .padding(start = 18.dp)
    when (item) {
        is PurchaseReceipt.Item.Tempo -> Box(
            modifier
                .then(disc)
                .draftTrail(CircleShape, step = DpOffset((-9).dp, 0.dp))
                .size(72.dp)
                .background(p.lime, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 32.dp, height = 23.dp).background(p.onLime, SparkPlus()))
        }
        is PurchaseReceipt.Item.Boosts -> Box(
            modifier
                .then(disc)
                .draftTrail(CircleShape, step = DpOffset((-9).dp, 0.dp))
                .size(72.dp)
                .background(p.lime, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("bolt", size = 34.dp, tint = p.onLime)
        }
        is PurchaseReceipt.Item.SuperLikes -> Box(
            modifier
                .then(disc)
                .draftTrail(CircleShape, color = p.negative, step = DpOffset((-9).dp, 0.dp))
                .size(72.dp)
                .background(p.negative, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            SuperLikeMark(Modifier.offset(x = (-5).dp), size = 26.dp, color = Color.White)
        }
    }
}

@Composable
private fun Title(item: PurchaseReceipt.Item) {
    val p = DS.palette
    val heading = Modifier.semantics { heading() }
    when (item) {
        is PurchaseReceipt.Item.Tempo ->
            Text(branded(L("You're on drafft tempo."), tierColor = p.accentInk), heading, style = display(34f), color = p.ink)
        is PurchaseReceipt.Item.Boosts ->
            Text(
                if (item.count == 1) L("Boost added.") else L("%d boosts added.", item.count),
                heading,
                style = display(34f),
                color = p.ink,
            )
        is PurchaseReceipt.Item.SuperLikes ->
            Text(
                if (item.count == 1) L("Super like added.") else L("%d super likes added.", item.count),
                heading,
                style = display(34f),
                color = p.ink,
            )
    }
}

private fun message(item: PurchaseReceipt.Item): String = when (item) {
    is PurchaseReceipt.Item.Tempo ->
        L("Undo, see who liked you, unlimited likes and a free boost every week. Everything is on, starting now.")
    is PurchaseReceipt.Item.Boosts ->
        L("Each boost puts you first in decks near you for 30 minutes. Use them when you like: they never expire.")
    is PurchaseReceipt.Item.SuperLikes ->
        L("They see you first, with a red heart on your profile. Use them when you like: they never expire.")
}

// The catalog's wording names Apple for the receipt email; Google Play sends it on Android.
private fun footnote(item: PurchaseReceipt.Item): String = when (item) {
    is PurchaseReceipt.Item.Tempo -> L("Apple emails your receipt. Manage your subscription anytime in You.")
    is PurchaseReceipt.Item.Boosts, is PurchaseReceipt.Item.SuperLikes -> L("Apple emails your receipt.")
}

/** Receipt-like recap: what, how much, and what happens next. */
@Composable
private fun Recap(item: PurchaseReceipt.Item) {
    val p = DS.palette
    val rows: List<Pair<String, String>> = when (item) {
        is PurchaseReceipt.Item.Tempo -> listOf(
            L("Length") to item.subscription.plan.title,
            L("Price") to item.subscription.billing,
            L("Renews") to DateText.format("yMMMMd", item.subscription.periodEnds),
        )
        is PurchaseReceipt.Item.Boosts -> listOf(
            L("Added") to (if (item.count == 1) L("1 boost") else L("%d boosts", item.count)),
            L("You now have") to (if (item.balance == 1) L("1 boost") else L("%d boosts", item.balance)),
            L("Paid") to item.price,
        )
        is PurchaseReceipt.Item.SuperLikes -> listOf(
            L("Added") to (if (item.count == 1) L("1 super like") else L("%d super likes", item.count)),
            L("You now have") to (if (item.balance == 1) L("1 super like") else L("%d super likes", item.balance)),
            L("Paid") to item.price,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            // The page tone, not the sheet's flipped one: this sheet isn't marked as a sheet surface on the iPhone.
            .background(p.sage, RoundedCornerShape(DS.Radius.lg))
            .padding(horizontal = DS.Space.lg),
    ) {
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
            AdaptiveRow(
                Modifier
                    .padding(vertical = DS.Space.md)
                    .semantics(mergeDescendants = true) {},
                spacing = DS.Space.md,
                leading = { Text(label, style = TextStyles.subheadline, color = p.body) },
                trailing = { Text(value, style = TextStyles.subheadline.semibold.monospacedDigits, color = p.ink) },
            )
        }
    }
}

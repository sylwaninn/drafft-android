package so.drafft.core.data.store

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.Flow

// Ports Drafft/Services/Store.swift: the interface and the store's values. RevenueCat (Google Play)
// implements it in src/android (`RevenueCatStore`).

/** A product as the store sells it, priced and formatted for the person's country. */
data class StoreProduct(
    /** "so.drafft.app.boost.5", or a subscription's "so.drafft.app.tempo.monthly:base". */
    val productIdentifier: String,
    /** "12,99 €" */
    val localizedPriceString: String,
    val price: BigDecimal,
    val currencyCode: String,
    /** A subscription's price for one month (the yearly plan's saving), null for a pack. */
    val pricePerMonth: BigDecimal? = null,
    val localizedPricePerMonth: String? = null,
) {
    /** An amount in the product's currency, formatted like its price ("2,60 €"). */
    fun format(amount: BigDecimal): String {
        val format = java.text.NumberFormat.getCurrencyInstance(so.drafft.core.model.appLocale)
        runCatching { format.currency = java.util.Currency.getInstance(currencyCode) }
        return format.format(amount)
    }
}

/** A product offered by an offering (RevenueCat's `Package`). [native] is the store's own object. */
class Package(
    val identifier: String,
    val storeProduct: StoreProduct,
    val native: Any,
) {
    override fun equals(other: Any?) = other is Package && other.identifier == identifier &&
        other.storeProduct.productIdentifier == storeProduct.productIdentifier
    override fun hashCode() = identifier.hashCode() * 31 + storeProduct.productIdentifier.hashCode()
}

/** One entitlement as the store reports it. */
data class EntitlementInfo(
    val identifier: String,
    val isActive: Boolean,
    val willRenew: Boolean,
    val productIdentifier: String,
    val latestPurchaseDate: Instant?,
    val expirationDate: Instant?,
)

/** What the store knows about the purchases of the account it reports for. */
data class CustomerInfo(
    val originalAppUserId: String,
    val entitlements: Map<String, EntitlementInfo>,
)

/**
 * In-app purchases, through RevenueCat. Prices are never written in the app: Google Play sets them,
 * RevenueCat's offerings decide which products show, and the store formats them for the person's
 * country.
 *
 * - `default` offering: the drafft tempo plans (entitlement `drafft_tempo`).
 * - `boosts` and `super_likes` offerings: the consumable packs. A pack's size is the last part of its
 *   product ID (`so.drafft.app.boost.5` is 5 boosts).
 *
 * The observed properties are snapshot state: screens read them directly.
 */
interface Store {
    enum class LoadState { IDLE, LOADING, LOADED, FAILED }

    val state: LoadState
    val tempo: Map<TempoPlan, Package>
    val boosts: List<Package>
    val superLikes: List<Package>

    /**
     * The account purchases are made for: the Supabase user id, lowercased, as RevenueCat's app user id.
     * The server's webhook credits that id's wallet and ignores any other (an anonymous purchase would be
     * paid and never credited), so nothing can be bought while this is null.
     */
    val linkedUserID: String?
    val isLinked: Boolean get() = linkedUserID != null

    sealed class StoreError(message: String) : Exception(message) {
        data object NotLinked : StoreError("not linked") { private fun readResolve(): Any = NotLinked }
    }

    /**
     * Links RevenueCat to the signed-in account (log in). Called at every sign-in or restored session,
     * and again before a purchase if it hadn't gone through (offline).
     */
    suspend fun link(): Boolean

    /** Sign-out and account deletion: purchases on this device stop following the account. */
    suspend fun unlink()

    /**
     * Links the account, then fetches the offerings. Does nothing while a load runs or once both are
     * done; a failed load (either part) runs again.
     */
    suspend fun load()

    /** Confirmed by the store, with its transaction (the reference support asks for). */
    sealed interface Outcome {
        data class Purchased(val info: CustomerInfo, val transactionID: String?) : Outcome
        data object Cancelled : Outcome
    }

    /**
     * Only for the linked account. What was bought shows once the server has credited it (the wallet),
     * never from here. Needs an activity on screen (Google Play's purchase sheet).
     */
    suspend fun purchase(pkg: Package): Outcome

    suspend fun restore(): CustomerInfo

    /** The store's current word for the linked account. */
    suspend fun customerInfo(): CustomerInfo

    /**
     * RevenueCat's customer info updates (the iPhone's `customerInfoStream`). It also reports the
     * anonymous user, before log-in and after log-out: check [reportsLinkedAccount].
     */
    val customerInfoStream: Flow<CustomerInfo>

    /** Whether RevenueCat is reporting for the linked account right now. */
    val reportsLinkedAccount: Boolean

    /**
     * drafft tempo's details as the store reports them (plan, price, renewal), or null when it isn't
     * active. Whether it's on for the account comes from the server (`AppModel.isPremium`).
     */
    fun subscription(from: CustomerInfo): TempoSubscription? {
        val e = from.entitlements[TEMPO_ENTITLEMENT] ?: return null
        if (!e.isActive) return null
        val plan = TempoPlan.fromProductID(e.productIdentifier) ?: return null
        val price = tempo[plan]?.storeProduct?.localizedPriceString
        val base = TempoSubscription(plan = plan, started = e.latestPurchaseDate ?: Instant.now(),
            billing = price?.let(plan::billing) ?: plan.title)
        return base.copy(periodEnds = e.expirationDate ?: base.periodEnds, willRenew = e.willRenew)
    }

    companion object {
        const val TEMPO_ENTITLEMENT = "drafft_tempo"

        /** How many boosts or super likes a pack holds. */
        fun count(of: Package): Int =
            of.storeProduct.productIdentifier.substringBefore(':').split('.').last().toIntOrNull() ?: 1
    }
}

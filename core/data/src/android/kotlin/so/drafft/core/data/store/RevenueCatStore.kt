package so.drafft.core.data.store

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import java.lang.ref.WeakReference
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.appLocale
import com.revenuecat.purchases.CustomerInfo as RCCustomerInfo
import com.revenuecat.purchases.Offering as RCOffering
import com.revenuecat.purchases.Package as RCPackage

/**
 * [Store] on RevenueCat and Google Play (Drafft/Services/Store.swift). Configured once, at launch
 * ([configure]), before anything reads purchases. Google Play's purchase sheet needs an activity: the
 * store follows the one on screen by itself.
 */
class RevenueCatStore(
    private val context: Context,
    private val backend: Backend,
    private val isDebugBuild: Boolean,
) : Store {
    override var state: Store.LoadState by mutableStateOf(Store.LoadState.IDLE)
        private set
    override var tempo: Map<TempoPlan, Package> by mutableStateOf(emptyMap())
        private set
    override var boosts: List<Package> by mutableStateOf(emptyList())
        private set
    override var superLikes: List<Package> by mutableStateOf(emptyList())
        private set
    override var linkedUserID: String? by mutableStateOf(null)
        private set

    /** Bumped on sign-out: a log-in still on its way for the previous account is undone. */
    private var linkGeneration = 0

    private var activity: WeakReference<Activity>? = null

    private val purchases: Purchases get() = Purchases.sharedInstance

    /** Once, at launch, before anything reads purchases. */
    fun configure() {
        if (isDebugBuild) Purchases.logLevel = LogLevel.DEBUG
        // Public SDK key (safe in the app): RevenueCat project "drafft" or "drafft staging".
        val key = backend.config.revenueCatAPIKey
        // Without a key (REVENUECAT_API_KEY empty in config/<flavor>.properties) the SDK refuses to configure and would stop the app at
        // launch: purchases stay off instead (the store reads as unavailable).
        if (!Purchases.isConfigured && key.isNotBlank()) {
            runCatching { Purchases.configure(PurchasesConfiguration.Builder(context, key).build()) }
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ActivityTracker())
    }

    override suspend fun link(): Boolean {
        val id = backend.userID?.toString()?.lowercase()
        if (id == null || !Purchases.isConfigured) {
            linkedUserID = null
            return false
        }
        if (linkedUserID == id && purchases.appUserID == id) return true
        val generation = linkGeneration
        try {
            if (purchases.appUserID != id) purchases.awaitLogIn(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.unexpected(e, "purchase", "link")
            linkedUserID = null
            return false
        }
        // Signed out while it ran: this device no longer belongs to that account.
        if (generation != linkGeneration) {
            if (purchases.appUserID == id) runCatching { purchases.awaitLogOut() }
            return false
        }
        linkedUserID = id
        return true
    }

    override suspend fun unlink() {
        linkGeneration += 1
        linkedUserID = null
        if (!Purchases.isConfigured || purchases.isAnonymous) return
        try {
            purchases.awaitLogOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    override suspend fun load() {
        if (state == Store.LoadState.LOADING || (state == Store.LoadState.LOADED && isLinked)) return
        state = Store.LoadState.LOADING
        val linked = link()
        if (tempo.isEmpty() && boosts.isEmpty() && superLikes.isEmpty()) {
            try {
                val offerings = purchases.awaitOfferings()
                val plans = mutableMapOf<TempoPlan, Package>()
                for (pkg in offerings.current?.availablePackages.orEmpty()) {
                    TempoPlan.fromProductID(pkg.product.id)?.let { plans[it] = pkg.toPackage() }
                }
                tempo = plans
                boosts = packs(offerings["boosts"])
                superLikes = packs(offerings["super_likes"])
            } catch (e: CancellationException) {
                state = Store.LoadState.FAILED
                throw e
            } catch (e: Exception) {
                state = Store.LoadState.FAILED
                Telemetry.track(AnalyticsEvent.ProductsLoadFailed())
                Telemetry.unexpected(e, "purchase", "load_offerings")
                return
            }
        }
        val empty = tempo.isEmpty() && boosts.isEmpty() && superLikes.isEmpty()
        state = if (linked && !empty) Store.LoadState.LOADED else Store.LoadState.FAILED
    }

    override suspend fun purchase(pkg: Package): Store.Outcome {
        val productID = pkg.storeProduct.productIdentifier
        val kind = AnalyticsEvent.ProductKind.of(productID)
        Telemetry.track(AnalyticsEvent.PurchaseStarted(kind, productID))
        val outcome = try {
            purchaseLinked(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.track(AnalyticsEvent.PurchaseFailed(kind, productID, Store.PurchaseProblem.from(e).name.lowercase()))
            Telemetry.unexpected(e, "purchase", "purchase", mapOf("product_id" to productID))
            throw e
        }
        Telemetry.track(
            when (outcome) {
                is Store.Outcome.Purchased -> AnalyticsEvent.PurchaseCompleted(kind, productID, pkg.storeProduct.currencyCode)
                Store.Outcome.Cancelled -> AnalyticsEvent.PurchaseCancelled(kind, productID)
            },
        )
        return outcome
    }

    private suspend fun purchaseLinked(pkg: Package): Store.Outcome {
        if (!link()) throw Store.StoreError.NotLinked
        val native = pkg.native as RCPackage
        // No purchase sheet without an activity on screen: nothing was asked of Google Play.
        val activity = activity?.get() ?: throw Store.StoreError.Failed(Store.PurchaseProblem.NOT_CHARGED)
        return try {
            val result = purchases.awaitPurchase(PurchaseParams.Builder(activity, native).build())
            // Google Play's order id (GPA.…): the reference support asks for, like the App Store's transaction id.
            Store.Outcome.Purchased(result.customerInfo.toInfo(), result.storeTransaction.orderId)
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) Store.Outcome.Cancelled else throw Store.StoreError.Failed(problem(e.code), e)
        } catch (e: PurchasesException) {
            if (e.code == PurchasesErrorCode.PurchaseCancelledError) {
                Store.Outcome.Cancelled
            } else {
                throw Store.StoreError.Failed(problem(e.code), e)
            }
        }
    }

    /** The Swift `PurchaseProblem(error)`'s cases, from RevenueCat's code. */
    private fun problem(code: PurchasesErrorCode): Store.PurchaseProblem = when (code) {
        PurchasesErrorCode.PaymentPendingError -> Store.PurchaseProblem.PENDING
        PurchasesErrorCode.PurchaseNotAllowedError -> Store.PurchaseProblem.NOT_ALLOWED
        PurchasesErrorCode.ProductAlreadyPurchasedError -> Store.PurchaseProblem.ALREADY_OWNED
        PurchasesErrorCode.PurchaseInvalidError, PurchasesErrorCode.ProductNotAvailableForPurchaseError,
        PurchasesErrorCode.IneligibleError, PurchasesErrorCode.OperationAlreadyInProgressError,
        -> Store.PurchaseProblem.NOT_CHARGED
        else -> Store.PurchaseProblem.UNCONFIRMED
    }

    override suspend fun restore(): CustomerInfo {
        try {
            if (!link()) throw Store.StoreError.NotLinked
            val info = purchases.awaitRestore().toInfo()
            Telemetry.track(AnalyticsEvent.PurchasesRestored(found = info.entitlements[Store.TEMPO_ENTITLEMENT]?.isActive == true))
            return info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Telemetry.track(AnalyticsEvent.RestoreFailed())
            Telemetry.unexpected(e, "purchase", "restore")
            throw e
        }
    }

    override suspend fun customerInfo(): CustomerInfo = purchases.awaitCustomerInfo().toInfo()

    override val customerInfoStream: Flow<CustomerInfo> = callbackFlow {
        if (!Purchases.isConfigured) {
            awaitClose()
            return@callbackFlow
        }
        purchases.updatedCustomerInfoListener = com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener { info ->
            trySend(info.toInfo())
        }
        awaitClose { purchases.updatedCustomerInfoListener = null }
    }

    override val reportsLinkedAccount: Boolean
        get() {
            val id = linkedUserID ?: return false
            return purchases.appUserID == id
        }

    // Conversions

    private fun packs(offering: RCOffering?): List<Package> =
        offering?.availablePackages.orEmpty().map { it.toPackage() }.sortedBy { Store.count(it) }

    private fun RCPackage.toPackage(): Package {
        val price = product.price
        val perMonth = product.pricePerMonth(appLocale)
        return Package(
            identifier = identifier,
            storeProduct = StoreProduct(
                productIdentifier = product.id,
                localizedPriceString = price.formatted,
                price = BigDecimal.valueOf(price.amountMicros, 6),
                currencyCode = price.currencyCode,
                pricePerMonth = perMonth?.let { BigDecimal.valueOf(it.amountMicros, 6) },
                localizedPricePerMonth = perMonth?.formatted,
            ),
            native = this,
        )
    }

    private fun RCCustomerInfo.toInfo() = CustomerInfo(
        originalAppUserId = originalAppUserId,
        entitlements = entitlements.all.mapValues { (_, e) ->
            EntitlementInfo(
                identifier = e.identifier,
                isActive = e.isActive,
                willRenew = e.willRenew,
                productIdentifier = e.productIdentifier,
                latestPurchaseDate = e.latestPurchaseDate.toInstant(),
                expirationDate = e.expirationDate?.toInstant(),
            )
        },
    )

    /** The activity on screen, for Google Play's purchase sheet. */
    private inner class ActivityTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            this@RevenueCatStore.activity = WeakReference(activity)
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (this@RevenueCatStore.activity?.get() === activity) this@RevenueCatStore.activity = null
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}

package so.drafft.core.data.store

import java.time.Instant
import java.time.ZoneId
import so.drafft.core.model.L

/** drafft tempo's subscription lengths. */
enum class TempoPlan {
    MONTH, SIX_MONTHS, YEAR;

    val id: TempoPlan get() = this

    val title: String
        get() = when (this) {
            MONTH -> L("1 month")
            SIX_MONTHS -> L("6 months")
            YEAR -> L("12 months")
        }

    val months: Int
        get() = when (this) {
            MONTH -> 1
            SIX_MONTHS -> 6
            YEAR -> 12
        }

    /** How the subscription is billed, with the store's localized price. */
    fun total(price: String): String = when (this) {
        MONTH -> L("%s billed monthly", price)
        SIX_MONTHS -> L("%s every 6 months", price)
        YEAR -> L("%s billed yearly", price)
    }

    /** How the active subscription reads in You > drafft tempo. */
    fun billing(price: String): String = when (this) {
        MONTH -> L("%s a month", price)
        SIX_MONTHS -> L("%s every 6 months", price)
        YEAR -> L("%s a year", price)
    }

    companion object {
        /** From a store product ID: `so.drafft.app.tempo.monthly`, `.sixmonths`, `.yearly` (the part
         * after the last dot, and before the `:` of a Google Play base offer). */
        fun fromProductID(productID: String): TempoPlan? = when (productID.substringBefore(':').substringAfterLast('.')) {
            "monthly" -> MONTH
            "sixmonths" -> SIX_MONTHS
            "yearly" -> YEAR
            else -> null
        }
    }
}

/**
 * What the store says about the subscription: its length, when it started, the end of the current
 * period, and whether it renews (turned off when cancelled in the store).
 */
data class TempoSubscription(
    val plan: TempoPlan,
    val started: Instant = Instant.now(),
    /** How the price reads ("12,99 EUR a month"), from the store product. */
    val billing: String,
    val periodEnds: Instant = started.atZone(ZoneId.systemDefault()).plusMonths(plan.months.toLong()).toInstant(),
    val willRenew: Boolean = true,
)

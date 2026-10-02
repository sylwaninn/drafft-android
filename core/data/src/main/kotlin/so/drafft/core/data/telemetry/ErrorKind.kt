package so.drafft.core.data.telemetry

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.data.media.MediaUploadError
import so.drafft.core.data.store.Store
import so.drafft.core.data.verification.VerificationError

/**
 * What kind of failure an error is, for [Telemetry.unexpected]. Only the [reportable] kinds become
 * Sentry events (and can alert someone): a phone going offline or the server refusing with a reason the
 * person reads on screen is the app working as it should.
 */
enum class ErrorKind(val id: String, val reportable: Boolean) {
    CANCELLED("cancelled", false),
    OFFLINE("offline", false),
    SIGNED_OUT("signed_out", false),
    /** The server said no with a code the app explains (`daily_like_limit`, `paused`...). */
    REFUSED("refused", false),
    RATE_LIMITED("rate_limited", false),
    /** The store's own outcomes (waiting for approval, not allowed, already owned, not charged). */
    STORE_DECLINED("store_declined", false),
    /** A 4xx without a code the app knows: the app and the server disagree on something. */
    CLIENT_CONTRACT("client_contract", true),
    /** A 5xx: the server failed. */
    SERVER("server", true),
    /** A purchase Google Play may have charged without RevenueCat confirming it. */
    STORE_UNCONFIRMED("store_unconfirmed", true),
    UNEXPECTED("unexpected", true);

    companion object {
        fun of(error: Throwable): ErrorKind {
            // A timeout is a CancellationException too, but nobody went away: the call took too long.
            if (error is TimeoutCancellationException) return OFFLINE
            if (error is CancellationException || isCancelledCall(error)) return CANCELLED
            if (ServerMessage.isSignedOut(error)) return SIGNED_OUT
            if (error is ProfileSync.SyncError.Refused) return REFUSED
            if (error is ProfileSync.SyncError.NotLoaded) return REFUSED
            if (error is Store.StoreError.NotLinked || error is Store.StoreError.Offline) return OFFLINE
            if (error is MediaUploadError.TicketExpired) return SIGNED_OUT
            if (error is MediaUploadError.Http) return http(error.status, "")
            if (error is VerificationError) {
                return when (error) {
                    VerificationError.Network -> OFFLINE
                    // Nothing the person did: the SMS or the line check failed on the way.
                    VerificationError.SendFailed, VerificationError.CheckUnavailable -> SERVER
                    else -> REFUSED
                }
            }
            if (error is Backend.EmailAlreadyRegistered || error is Backend.PhoneAlreadyRegistered) return REFUSED
            // Supabase Auth's refusals (a wrong password, an expired code): the screen says why.
            if (error is AuthRestException) {
                return when {
                    error.statusCode == 429 -> RATE_LIMITED
                    error.statusCode >= 500 -> SERVER
                    else -> REFUSED
                }
            }
            // Any other REST error (PostgREST...): the same rules as a response of the backend.
            if (error is RestException) return http(error.statusCode, error.error)
            if (error is Store.StoreError.Failed) {
                return if (error.problem == Store.PurchaseProblem.UNCONFIRMED) STORE_UNCONFIRMED else STORE_DECLINED
            }
            if (error is Backend.BackendError.Http) return http(error.status, error.serverMessage)
            if (ServerMessage.code(error) != null) return REFUSED
            // A certificate or TLS failure reached a server (or something posing as one): not the connection.
            if (isSecurityFailure(error)) return UNEXPECTED
            if (ServerMessage.isOffline(error)) return OFFLINE
            return UNEXPECTED
        }

        /** A response that wasn't 2xx, from its status and the server's code or message. */
        fun http(status: Int, message: String): ErrorKind = when {
            status == 429 -> RATE_LIMITED
            status >= 500 -> SERVER
            // An expired or revoked token: the session refresh and the sign-out handle it.
            status == 401 -> SIGNED_OUT
            // A code (one word) is a refusal the server meant (`not_found`, `already_swiped`),
            // whether or not the app has words for it. A sentence is the database failing. Read
            // lowercased, as a short code (`PrivacyGuard.isCode`).
            PrivacyGuard.isCode(message.lowercase()) -> REFUSED
            else -> CLIENT_CONTRACT
        }

        /** TLS or certificate trouble anywhere in the cause chain. */
        private fun isSecurityFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }
            .any { it is SSLException || it is CertificateException }

        /** The HTTP client's own way to say a call was cancelled: an IOException, not a CancellationException. */
        private fun isCancelledCall(error: Throwable): Boolean = generateSequence(error) { it.cause }
            .any { it is IOException && it.message.orEmpty().let { m -> m.equals("canceled", true) || m.equals("cancelled", true) } }
    }
}

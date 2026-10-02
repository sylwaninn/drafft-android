package so.drafft.core.data.backend

import io.github.jan.supabase.exceptions.HttpRequestException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import so.drafft.core.data.media.MediaUploadError
import so.drafft.core.model.L


/**
 * What people read when the server turns something down. Database functions put a stable code in
 * `hint` (`private.fail`), Edge Functions in `code`; the known ones get their own words, anything
 * else (a constraint without a hint, an unexpected reply) a generic line, never the raw reply.
 */
object ServerMessage {
    val generic: String get() = L("Something went wrong. Try again in a moment.")

    /** The server's code for a refusal, if it gave one. */
    fun code(of: Throwable): String? = when (of) {
        is Backend.BackendError.Http -> of.serverMessage.takeIf(::isCode)
        is MediaUploadError.Rejected -> of.code
        else -> null
    }

    /** The words for a refusal the app knows, or nil (not a refusal, or a code it doesn't know). */
    fun text(of: Throwable): String? = code(of)?.let(::text)

    /**
     * The request never reached the server (no connection, a timeout): the only time "check your
     * connection" is the right advice. A server that answered with an error is not a connection problem.
     */
    fun isOffline(error: Throwable): Boolean =
        error !is CancellationException &&
            generateSequence(error) { it.cause }.any { it is IOException || it is HttpRequestException }

    /**
     * What to say when a call fails: the refusal's own words when the app knows its code, the logged-out
     * line when the session is gone, `offline` when the request never got through, else `fallback`.
     */
    fun text(of: Throwable, offline: String, fallback: String = generic): String {
        // A profile save's own reasons (a photo that didn't go, a profile that wasn't read).
        if (of is ProfileSync.SyncError) return of.message
        text(of)?.let { return it }
        if (of is Backend.BackendError.SignedOut) return of.message
        return if (isOffline(of)) offline else fallback
    }

    /** The session is gone: no token on the device, or an edge function's 401 `unauthenticated`. */
    fun isSignedOut(error: Throwable): Boolean =
        error is Backend.BackendError.SignedOut || code(error) == "unauthenticated"

    /**
     * An action that failed, in words: the known refusal's, the connection's only when the request
     * never got through, else the generic line. Never the server's reply.
     */
    fun failure(of: Throwable): String = when {
        isOffline(of) -> L("Couldn't connect. Check your connection and try again.")
        of is Backend.BackendError -> of.message
        else -> text(of) ?: generic
    }

    /** A code is one word (`media_limit`); anything with a space is a sentence from the server. */
    fun isCode(message: String): Boolean = message.isNotEmpty() && !message.contains(" ")

    fun text(forCode: String): String? = when (forCode) {
        // complete_onboarding
        "name_required" -> L("Add your first name.")
        "underage" -> L("You need to be 18 or older to use drafft.")
        "gender_required" -> L("Pick the one that fits you best.")
        "sport_required" -> L("Add at least one sport.")
        "photo_required" -> L("Add at least one photo.")
        // The first photo isn't approved yet, was refused, or shows no face (server check).
        "portrait_required" -> L("Put a clear photo of your face first.")
        "birthdate_locked" -> L("Your birthday can't be changed.")
        "email_unconfirmed" -> L("Confirm your email first.")
        "phone_required" -> L("Verify your phone number first.")
        // complete_onboarding without accept_terms, and accept_terms without the consent (the app
        // always sends it, so only another client gets sensitive_consent_required)
        "terms_required" -> L("Accept the terms and give your consent to continue.")
        "sensitive_consent_required" -> L("drafft needs your consent to use your gender. Tick it to continue.")
        // Photos and videos (add_profile_media, request_media_review, media-upload-url)
        "media_limit" -> L("You've reached the photo limit. Remove one, then try again.")
        "not_reviewable" -> L("This photo has already been reviewed.")
        "too_large" -> L("This file is too large.")
        "unsupported_type" -> L("This file type isn't supported.")
        // Account state
        "moderated" -> L("Your account is on hold.")
        "paused" -> L("Your profile is paused")
        "onboarding_required" -> L("Finish your profile first.")
        // An edge function without a valid session (it expired, or was ended on another device).
        "unauthenticated" -> L("You've been logged out. Log in again to continue.")
        // Discover and safety
        "not_eligible", "invalid_target" -> L("This profile isn't available.")
        "location_required" -> L("Share your location to see people nearby.")
        "daily_like_limit" -> L("You're out of likes for today.")
        "no_super_likes" -> L("You're out of super likes.")
        "cannot_undo" -> L("This swipe can't be undone any more.")
        "no_boost" -> L("No boost left, or one is already running.")
        "not_visible" -> L("Nobody can see your profile yet, so a boost wouldn't reach anyone.")
        "report_limit" -> L("You've sent several reports already. Try again later.")
        // Sessions (propose_session, counter_session, respond_session, cancel_session)
        "invalid_options" -> L("Pick 1 to 3 times that haven't passed.")
        "invalid_pick" -> L("Pick one of the times offered.")
        "cannot_respond", "cannot_counter" -> L("This invite has already been answered.")
        "cannot_cancel" -> L("This session is already closed.")
        else -> null
    }
}

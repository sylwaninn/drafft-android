package so.drafft.core.data.backend

import so.drafft.core.data.media.MediaUploadError
import so.drafft.core.model.L

// Ports Drafft/Services/Backend/ServerMessage.swift.

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

    /** A code is one word (`media_limit`); anything with a space is a sentence from the server. */
    fun isCode(message: String): Boolean = message.isNotEmpty() && !message.contains(" ")

    fun text(forCode: String): String? = when (forCode) {
        // complete_onboarding
        "name_required" -> L("Add your first name.")
        "underage" -> L("You need to be 18 or older to use drafft.")
        "gender_required" -> L("Pick the one that fits you best.")
        "sport_required" -> L("Add at least one sport.")
        "photo_required" -> L("Add at least one photo.")
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
        // Discover and safety
        "not_eligible" -> L("This profile isn't available.")
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

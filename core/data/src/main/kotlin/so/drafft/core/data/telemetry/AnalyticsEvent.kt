package so.drafft.core.data.telemetry

import so.drafft.core.model.MessageContent

/**
 * Every product event the app sends, in one place: the tracked events (docs/telemetry.md) as code.
 *
 * Naming: `object_action`, snake_case, past tense (`profile_swiped`, `purchase_completed`), the way
 * PostHog recommends. Properties are numbers, booleans and codes from enums, never what people type or
 * the sensitive data of their profile ([PrivacyGuard] drops it anyway). An event's name and its
 * properties' names are a contract with the dashboards: add new ones, don't rename.
 *
 * Both drafft apps send these events under the same names, so a funnel spans both platforms.
 */
sealed class AnalyticsEvent(val name: String, vararg props: Pair<String, Any?>) {
    val properties: Map<String, Any?> = props.toMap()

    // MARK: Values

    enum class AuthMethod { EMAIL }
    enum class SwipeAction { LIKE, PASS, SUPER_LIKE }
    enum class SwipeSource { DECK, LIKES, PROFILE }
    enum class MatchSource { MY_SWIPE, THEIR_LIKE }
    enum class MessageKind { TEXT, PHOTO, VIDEO, VOICE, FILE, SESSION, ICEBREAKER_REPLY, PHOTO_REPLY }
    enum class ProductKind {
        TEMPO, BOOST, SUPER_LIKE;

        companion object {
            /** From the store's product id: `so.drafft.app.tempo.monthly:base`, `so.drafft.app.boost.5`. */
            fun of(productID: String): ProductKind = when {
                productID.contains("boost") -> BOOST
                productID.contains("super") -> SUPER_LIKE
                else -> TEMPO
            }
        }
    }
    enum class SessionResponse { ACCEPTED, DECLINED }
    enum class Permission { LOCATION, NOTIFICATIONS, CAMERA, MICROPHONE, PHOTOS, CALENDAR }
    enum class PermissionResult { GRANTED, DENIED, ALREADY_GRANTED, BLOCKED }

    companion object {
        fun kind(of: MessageContent): MessageKind = when (of) {
            is MessageContent.Text -> MessageKind.TEXT
            is MessageContent.Photo -> MessageKind.PHOTO
            is MessageContent.Video -> MessageKind.VIDEO
            is MessageContent.Voice -> MessageKind.VOICE
            is MessageContent.File -> MessageKind.FILE
            is MessageContent.Session -> MessageKind.SESSION
            is MessageContent.IcebreakerReply -> MessageKind.ICEBREAKER_REPLY
            is MessageContent.PhotoReply -> MessageKind.PHOTO_REPLY
        }
    }

    // MARK: Account

    /** The account exists (its email still to confirm, or already signed in): sign-up's first step. */
    class AccountCreated(method: AuthMethod) : AnalyticsEvent("account_created", "method" to method)
    class SignUpFailed(reason: String) : AnalyticsEvent("sign_up_failed", "reason" to reason)
    class EmailConfirmed : AnalyticsEvent("email_confirmed")
    class EmailCodeResent : AnalyticsEvent("email_code_resent")
    class LoggedIn(method: AuthMethod) : AnalyticsEvent("logged_in", "method" to method)
    class LogInFailed(reason: String) : AnalyticsEvent("log_in_failed", "reason" to reason)
    class PasswordResetRequested : AnalyticsEvent("password_reset_requested")
    class PasswordResetCompleted : AnalyticsEvent("password_reset_completed")
    class LoggedOut : AnalyticsEvent("logged_out")
    /** The session ended without the person logging out (revoked, expired, deleted elsewhere). */
    class SessionEnded(reason: String) : AnalyticsEvent("session_ended", "reason" to reason)
    class AccountDeleted : AnalyticsEvent("account_deleted")
    class AccountDeleteFailed(reason: String) : AnalyticsEvent("account_delete_failed", "reason" to reason)
    class EmailChanged : AnalyticsEvent("email_changed")
    class PasswordChanged : AnalyticsEvent("password_changed")
    class DataExportRequested : AnalyticsEvent("data_export_requested")
    class TermsAccepted(version: String, during: String) : AnalyticsEvent("terms_accepted", "terms_version" to version, "during" to during)
    class AnalyticsConsentChanged(value: AnalyticsConsent) : AnalyticsEvent("analytics_consent_changed", "consent" to value.id)
    class AccountHeld : AnalyticsEvent("account_held")

    // MARK: Sign-up (onboarding)

    class OnboardingStepViewed(step: String, chapter: String, index: Int, resumed: Boolean) :
        AnalyticsEvent("onboarding_step_viewed", "step" to step, "chapter" to chapter, "step_index" to index, "resumed" to resumed)

    class OnboardingStepCompleted(step: String, chapter: String, index: Int, skipped: Boolean, seconds: Int) :
        AnalyticsEvent(
            "onboarding_step_completed",
            "step" to step, "chapter" to chapter, "step_index" to index, "skipped" to skipped, "seconds_on_step" to seconds,
        )

    class OnboardingStepBlocked(step: String, reason: String) :
        AnalyticsEvent("onboarding_step_blocked", "step" to step, "reason" to reason)

    class OnboardingResumed(step: String, index: Int) : AnalyticsEvent("onboarding_resumed", "step" to step, "step_index" to index)

    /** Counts and yes/no only: never the answers. */
    class OnboardingCompleted(
        photos: Int, sports: Int, prompts: Int, hasVoice: Boolean, hasBio: Boolean, hasIcebreaker: Boolean,
        answeredLifestyle: Boolean, notificationsAllowed: Boolean, minutes: Int,
    ) : AnalyticsEvent(
        "onboarding_completed",
        "photos_count" to photos, "sports_count" to sports, "prompts_count" to prompts, "has_voice_intro" to hasVoice,
        "has_bio" to hasBio, "has_icebreaker" to hasIcebreaker, "answered_lifestyle" to answeredLifestyle,
        "notifications_allowed" to notificationsAllowed, "minutes_this_session" to minutes,
    )

    class OnboardingFailed(reason: String) : AnalyticsEvent("onboarding_failed", "reason" to reason)

    // MARK: Phone

    class PhoneCodeSent(during: String, resend: Boolean) : AnalyticsEvent("phone_code_sent", "during" to during, "resend" to resend)
    class PhoneCodeFailed(during: String, reason: String) : AnalyticsEvent("phone_code_failed", "during" to during, "reason" to reason)
    class PhoneVerified(during: String) : AnalyticsEvent("phone_verified", "during" to during)
    class PhoneVerificationFailed(during: String, reason: String) :
        AnalyticsEvent("phone_verification_failed", "during" to during, "reason" to reason)

    // MARK: Discover

    class DeckLoaded(cards: Int, mode: String, exhausted: Boolean, seconds: Double) :
        AnalyticsEvent("deck_loaded", "cards_count" to cards, "mode" to mode, "exhausted" to exhausted, "load_seconds" to seconds)

    class DeckLoadFailed(reason: String) : AnalyticsEvent("deck_load_failed", "reason" to reason)
    class DeckEmptyShown(exhausted: Boolean) : AnalyticsEvent("deck_empty_shown", "exhausted" to exhausted)

    class ProfileSwiped(
        action: SwipeAction, source: SwipeSource, withOpener: Boolean, premium: Boolean, likesLeft: Int?, deckSize: Int,
    ) : AnalyticsEvent(
        "profile_swiped",
        "action" to action, "source" to source, "with_opener" to withOpener, "is_premium" to premium,
        "likes_left" to likesLeft, "deck_size" to deckSize,
    )

    class SwipeRefused(action: SwipeAction, reason: String) : AnalyticsEvent("swipe_refused", "action" to action, "reason" to reason)
    class SwipeUndone(action: SwipeAction) : AnalyticsEvent("swipe_undone", "action" to action)
    class DailyLikeLimitReached : AnalyticsEvent("daily_like_limit_reached")
    class ProfileViewed(source: String, hasVoice: Boolean, photos: Int) :
        AnalyticsEvent("profile_viewed", "source" to source, "has_voice_intro" to hasVoice, "photos_count" to photos)

    /** Distance and sports only: who someone wants to meet is sensitive, and never sent. */
    class FiltersChanged(maxDistanceKm: Int, sports: Int, sharedSportsOnly: Boolean) :
        AnalyticsEvent("filters_changed", "max_distance_km" to maxDistanceKm, "sports_count" to sports, "shared_sports_only" to sharedSportsOnly)
    class BoostStarted(left: Int) : AnalyticsEvent("boost_started", "boosts_left" to left)
    class BoostFailed(reason: String) : AnalyticsEvent("boost_failed", "reason" to reason)
    class VoiceIntroPlayed(where: String) : AnalyticsEvent("voice_intro_played", "where" to where)
    class IcebreakerAnswered : AnalyticsEvent("icebreaker_answered")

    // MARK: Likes and matches

    class LikesViewed(count: Int, premium: Boolean) : AnalyticsEvent("likes_viewed", "likes_count" to count, "is_premium" to premium)
    class MatchCreated(source: MatchSource) : AnalyticsEvent("match_created", "source" to source)
    class MatchScreenAction(action: String) : AnalyticsEvent("match_screen_action", "action" to action)
    class Unmatched : AnalyticsEvent("unmatched")
    class MatchEnded : AnalyticsEvent("match_ended")

    // MARK: Chat

    class ChatOpened(unread: Int, messages: Int) : AnalyticsEvent("chat_opened", "unread_count" to unread, "messages_count" to messages)
    /** [isFirst]: null when the phone can't tell (only the latest messages are loaded). */
    class MessageSent(kind: MessageKind, isReply: Boolean, isFirst: Boolean?, durationSeconds: Int?) :
        AnalyticsEvent(
            "message_sent",
            "kind" to kind, "is_reply" to isReply, "is_first_message" to isFirst, "duration_seconds" to durationSeconds,
        )

    class MessageFailed(kind: MessageKind, reason: String) : AnalyticsEvent("message_failed", "kind" to kind, "reason" to reason)
    class MessageRetried : AnalyticsEvent("message_retried")
    class MessageReacted(removed: Boolean) : AnalyticsEvent("message_reacted", "removed" to removed)
    class MessageDeleted : AnalyticsEvent("message_deleted")
    class ChatMuted(muted: Boolean) : AnalyticsEvent("chat_muted", "muted" to muted)
    class ChatMarkedUnread : AnalyticsEvent("chat_marked_unread")

    // MARK: Sessions (meeting to train together)

    class SessionProposed(sport: String?, options: Int) : AnalyticsEvent("session_proposed", "sport" to sport, "options_count" to options)
    class SessionCountered(options: Int) : AnalyticsEvent("session_countered", "options_count" to options)
    class SessionResponded(response: SessionResponse) : AnalyticsEvent("session_responded", "response" to response)
    class SessionCancelled : AnalyticsEvent("session_cancelled")
    class SessionActionFailed(action: String, reason: String) :
        AnalyticsEvent("session_action_failed", "action" to action, "reason" to reason)

    class SessionAddedToCalendar : AnalyticsEvent("session_added_to_calendar")

    // MARK: Purchases

    /** [fromScreen]: where it was opened (Discover's undo, Likes, You...). */
    class PaywallViewed(kind: ProductKind, fromScreen: Screen?) :
        AnalyticsEvent("paywall_viewed", "kind" to kind, "from_screen" to fromScreen?.id)
    class PaywallDismissed(kind: ProductKind, purchased: Boolean) :
        AnalyticsEvent("paywall_dismissed", "kind" to kind, "purchased" to purchased)

    class ProductsLoadFailed : AnalyticsEvent("products_load_failed")
    class PurchaseStarted(kind: ProductKind, productID: String) :
        AnalyticsEvent("purchase_started", "kind" to kind, "product_id" to productID)

    /** The store confirmed it. Revenue itself comes from RevenueCat's own PostHog integration. */
    class PurchaseCompleted(kind: ProductKind, productID: String, currency: String?) :
        AnalyticsEvent("purchase_completed", "kind" to kind, "product_id" to productID, "currency" to currency?.lowercase())

    class PurchaseCancelled(kind: ProductKind, productID: String) :
        AnalyticsEvent("purchase_cancelled", "kind" to kind, "product_id" to productID)

    class PurchaseFailed(kind: ProductKind, productID: String, problem: String) :
        AnalyticsEvent("purchase_failed", "kind" to kind, "product_id" to productID, "problem" to problem)

    class PurchaseCredited(seconds: Int) : AnalyticsEvent("purchase_credited", "seconds_to_credit" to seconds)
    class PurchasesRestored(found: Boolean) : AnalyticsEvent("purchases_restored", "found" to found)
    class RestoreFailed : AnalyticsEvent("restore_failed")
    class SubscriptionManageOpened : AnalyticsEvent("subscription_manage_opened")

    // MARK: Own profile

    /** Which parts changed (codes: `photos`, `bio`, `sports`...), never their content. */
    class ProfileEdited(fields: List<String>) : AnalyticsEvent("profile_edited", "fields" to fields, "fields_count" to fields.size)
    class ProfileEditFailed(reason: String) : AnalyticsEvent("profile_edit_failed", "reason" to reason)
    /** A picked photo starts its upload (a retry counts again: [retry]). */
    class PhotoUploadStarted(where: String, retry: Boolean) :
        AnalyticsEvent("photo_upload_started", "where" to where, "retry" to retry)

    class PhotoUploadFailed(reason: String) : AnalyticsEvent("photo_upload_failed", "reason" to reason)
    class PhotoRemoved : AnalyticsEvent("photo_removed")
    class PhotoModerated(verdict: String) : AnalyticsEvent("photo_moderated", "verdict" to verdict)
    class PhotoReviewRequested : AnalyticsEvent("photo_review_requested")
    class VoiceIntroRecorded(seconds: Int, where: String) :
        AnalyticsEvent("voice_intro_recorded", "duration_seconds" to seconds, "where" to where)

    class ProfilePaused(paused: Boolean) : AnalyticsEvent("profile_paused", "paused" to paused)
    class SelfieVerificationStarted : AnalyticsEvent("selfie_verification_started")
    class SelfieVerificationSubmitted : AnalyticsEvent("selfie_verification_submitted")
    class SelfieVerificationFailed(reason: String) : AnalyticsEvent("selfie_verification_failed", "reason" to reason)

    // MARK: Safety

    class UserBlocked : AnalyticsEvent("user_blocked")
    class UserUnblocked : AnalyticsEvent("user_unblocked")
    /** The report's category, never its details. */
    class UserReported(reason: String) : AnalyticsEvent("user_reported", "reason" to reason)
    class ReportFailed(reason: String) : AnalyticsEvent("report_failed", "reason" to reason)

    // MARK: Settings, permissions, notifications

    class LanguageChanged(language: String, during: String) : AnalyticsEvent("language_changed", "language" to language, "during" to during)
    class PermissionRequested(permission: Permission, result: PermissionResult, during: String) :
        AnalyticsEvent("permission_requested", "permission" to permission, "result" to result, "during" to during)

    class NotificationSettingChanged(setting: String, enabled: Boolean) :
        AnalyticsEvent("notification_setting_changed", "setting" to setting, "enabled" to enabled)

    /** [routed]: the tap opened the push's own place (false: Discover, for want of a known kind or of its id). */
    class PushOpened(kind: String, routed: Boolean) :
        AnalyticsEvent("push_opened", "kind" to kind, "routed" to routed)
    class PushReceived(kind: String, inForeground: Boolean) :
        AnalyticsEvent("push_received", "kind" to kind, "in_foreground" to inForeground)

    /** [doc]: `terms`, `privacy`, `community`, `notice`, `sensitive_data`. */
    class LegalDocOpened(doc: String) : AnalyticsEvent("legal_doc_opened", "doc" to doc)
    class SupportContacted(topic: String, signedIn: Boolean) :
        AnalyticsEvent("support_contacted", "topic" to topic, "signed_in" to signedIn)

    class ShareTapped(what: String) : AnalyticsEvent("share_tapped", "what" to what)
}

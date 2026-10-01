package so.drafft.core.data.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthSessionMissingException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.L

// Ports Drafft/Services/Backend/Backend.swift.

/**
 * The drafft backend: Supabase Auth for the account (email + password; the session is kept on the
 * phone and refreshes itself), plus the plain HTTPS calls the app makes as the signed-in person
 * (RPCs, Edge Functions, a few table reads and writes).
 */
class Backend(
    val config: BackendConfig,
    /** Auth and Realtime. Calls that don't touch the network don't wait on anything. */
    val client: SupabaseClient,
    private val http: HttpClient,
) {
    sealed class BackendError(cause: Throwable? = null) : Exception(null, cause) {
        /** The status, and the server's code (`hint`, else its `code`) or, without one, its message. */
        class Http(val status: Int, val serverMessage: String) : BackendError()

        object SignedOut : BackendError() {
            private fun readResolve(): Any = SignedOut
        }

        /** Shown on screen: the known code's words, or a generic line (never the server's reply). */
        override val message: String
            get() = when (this) {
                is Http -> ServerMessage.text(forCode = serverMessage) ?: ServerMessage.generic
                SignedOut -> L("You've been logged out. Log in again to continue.")
            }
    }

    /** The server's refusals the whole app reacts to (the iPhone posts them on `NotificationCenter`). */
    enum class Event {
        /** An action was turned down because the profile is paused. */
        PROFILE_PAUSED_BY_SERVER,

        /** An action was turned down because the account is on hold (moderation). */
        ACCOUNT_HELD_BY_SERVER,
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Posts one of the app-wide refusals (the media uploader reports `moderated` too). */
    fun post(event: Event) {
        _events.tryEmit(event)
    }

    /** Whether someone is signed in on this device (waits for the saved session to load at launch). */
    suspend fun hasSession(): Boolean {
        client.auth.awaitInitialization()
        return client.auth.currentSessionOrNull() != null
    }

    /** The signed-in person's id (the profile id everywhere on the server). */
    val userID: UUID?
        get() = client.auth.currentUserOrNull()?.id?.let(::uuidOrNull)

    /**
     * A valid access token (refreshed first when it's about to expire). Signed out only when Auth turned
     * the session down; a refresh that couldn't reach it (offline, a server error) throws that error,
     * and the session stays.
     */
    suspend fun accessToken(): String {
        client.auth.awaitInitialization()
        val session = client.auth.currentSessionOrNull() ?: throw BackendError.SignedOut
        if (session.expiresAt.epochSeconds - System.currentTimeMillis() / 1000 > TOKEN_MARGIN_SECONDS) return session.accessToken
        try {
            client.auth.refreshCurrentSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw if (refusesSession(e)) BackendError.SignedOut else e
        }
        return client.auth.currentSessionOrNull()?.accessToken ?: throw BackendError.SignedOut
    }

    // Account

    enum class SignUpResult { SIGNED_IN, CONFIRM_EMAIL }

    class EmailAlreadyRegistered : Exception()

    /**
     * A new account. With email confirmation on, there's no session until the 6-digit code in the
     * email is typed in (`confirmSignUp`). `language` starts the profile in it, so the confirmation
     * email (backend auth-email) is already in that language.
     */
    suspend fun signUp(email: String, password: String, language: AppLanguage): SignUpResult {
        val user = client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject { put("language", language.code) }
        }
        val session = client.auth.currentSessionOrNull()
        // An address that already has an account: Supabase doesn't say so (that would tell who's signed up),
        // it answers like a new sign-up with no identity and sends nothing. The app says it plainly.
        if (session == null && user?.identities?.isEmpty() == true) throw EmailAlreadyRegistered()
        return if (session == null) SignUpResult.CONFIRM_EMAIL else SignUpResult.SIGNED_IN
    }

    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** The 6-digit code from the sign-up email: the account is confirmed and signed in. */
    suspend fun confirmSignUp(email: String, code: String) {
        client.auth.verifyEmailOtp(type = OtpType.Email.SIGNUP, email = email, token = code)
    }

    suspend fun resendConfirmation(to: String) {
        client.auth.resendEmail(OtpType.Email.SIGNUP, to)
    }

    /**
     * Emails a 6-digit code to reset the password (backend auth-email, recovery). Auth answers the same
     * whether or not the address has an account.
     */
    suspend fun sendPasswordReset(to: String) {
        client.auth.resetPasswordForEmail(to)
    }

    /** The code from `sendPasswordReset`: signs in, so the new password can be set (`updatePassword`). */
    suspend fun verifyPasswordReset(email: String, code: String) {
        client.auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email, token = code)
    }

    /** Whether the signed-in person finished sign-up (`profiles.onboarded_at`). */
    suspend fun isOnboarded(): Boolean {
        val rows = myProfile(select = "onboarded_at").parseJsonOrNull().asArray
        return rows?.firstOrNull().asObject?.get("onboarded_at").asString != null
    }

    /** Emails a 6-digit code to the new address (the "Change email address" template shows `{{ .Token }}`). */
    suspend fun updateEmail(email: String) {
        client.auth.updateUser { this.email = email }
    }

    /** The code from `updateEmail`: the address switches once it checks out. */
    suspend fun confirmEmailChange(email: String, code: String) {
        client.auth.verifyEmailOtp(type = OtpType.Email.EMAIL_CHANGE, email = email, token = code)
    }

    /** Emails a 6-digit code to the current address, to prove it's them before a password change. */
    suspend fun sendReauthenticationCode() {
        client.auth.reauthenticate()
    }

    /** After a password reset code (the code proved it's them). */
    suspend fun updatePassword(password: String) {
        client.auth.updateUser { this.password = password }
    }

    /** A new password, with the code from `sendReauthenticationCode`. */
    suspend fun updatePassword(password: String, code: String) {
        client.auth.updateUser {
            this.password = password
            nonce = code
        }
    }

    /**
     * Texts a 6-digit code to the number: the phone-code function checks the account (email confirmed),
     * the limits and the line, then starts the Supabase Auth phone change. Refusals keep the server's code.
     */
    suspend fun updatePhone(e164: String) {
        function("phone-code", jsonOf("phone" to e164))
    }

    suspend fun confirmPhoneChange(e164: String, code: String) {
        client.auth.verifyPhoneOtp(type = OtpType.Phone.PHONE_CHANGE, phone = e164, token = code)
    }

    /**
     * This device's Auth session (the `session_id` claim of its access token): a `session_revoked`
     * event names the sessions that ended.
     */
    val sessionID: String?
        get() {
            val token = client.auth.currentSessionOrNull()?.accessToken ?: return null
            val parts = token.split(".")
            if (parts.size != 3) return null
            val claims = attemptOrNull { Base64.getUrlDecoder().decode(parts[1]) }?.parseJsonOrNull().asObject ?: return null
            return claims["session_id"].asString?.lowercase()
        }

    /** Signs out on this device only (other devices stay signed in). */
    suspend fun signOut() {
        attempt { client.auth.signOut(SignOutScope.LOCAL) }
    }

    // Calls

    /** POST /rest/v1/rpc/<name>, as the signed-in person. */
    suspend fun rpc(name: String, body: JsonObject = JsonObject(emptyMap())): ByteArray =
        request(HttpMethod.Post, "rest/v1/rpc/$name", body)

    suspend fun rpc(name: String, body: Map<String, Any?>): ByteArray = rpc(name, body.toJsonElement() as JsonObject)

    /** POST /functions/v1/<name>, as the signed-in person. */
    suspend fun function(name: String, body: JsonObject = JsonObject(emptyMap())): ByteArray =
        request(HttpMethod.Post, "functions/v1/$name", body)

    /**
     * POST /functions/v1/<name>, signed in or not: with the person's token when there is one (the
     * function reads it), with only the app's key otherwise (support, from a stuck sign-up).
     */
    suspend fun publicFunction(name: String, body: JsonObject = JsonObject(emptyMap())): ByteArray =
        request(HttpMethod.Post, "functions/v1/$name", body, requiresSession = false)

    /** GET on a table with PostgREST filters, e.g. `profile_media?id=eq.<id>&select=status`. */
    suspend fun select(pathAndQuery: String): ByteArray = request(HttpMethod.Get, "rest/v1/$pathAndQuery", null)

    /** PATCH the person's own profile row (only the columns the server lets the app write). */
    suspend fun updateMyProfile(fields: JsonObject) {
        val id = userID ?: throw BackendError.SignedOut
        request(HttpMethod.Patch, "rest/v1/profiles?id=eq.$id", fields)
    }

    /** The person's own profile row, with these columns (comma-separated). A JSON array of one. */
    suspend fun myProfile(select: String): ByteArray {
        val id = userID ?: throw BackendError.SignedOut
        return request(HttpMethod.Get, "rest/v1/profiles?id=eq.$id&select=$select", null)
    }

    // HTTP

    private suspend fun request(
        method: HttpMethod,
        path: String,
        json: JsonElement?,
        requiresSession: Boolean = true,
    ): ByteArray {
        val bearer = if (requiresSession || hasSession()) accessToken() else null
        val response = http.request(config.url.trimEnd('/') + "/" + path) {
            this.method = method
            header("apikey", config.publishableKey)
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            // Content-Type goes with the body (Ktor sets it from the content).
            setBody(TextContent(json?.toString() ?: "", ContentType.Application.Json))
        }
        val status = response.status.value
        val data = response.readRawBytes()
        if (status !in 200..299) {
            val body = data.parseJsonOrNull().asObject
            // PostgREST sends `"hint": null` when there's no code: skip it, not stop at it.
            val message = listOf("hint", "msg", "message", "code").firstNotNullOfOrNull { body?.get(it).asString }
            // On hold (moderation): a database function's hint or an edge function's 403 `moderated`.
            if (body?.get("hint").asString == "moderated" || (status == 403 && body?.get("code").asString == "moderated")) {
                post(Event.ACCOUNT_HELD_BY_SERVER)
            } else if (saysPaused(status, body)) {
                post(Event.PROFILE_PAUSED_BY_SERVER)
            }
            throw BackendError.Http(status, message ?: data.copyOf(minOf(data.size, 200)).decodeToString())
        }
        return data
    }

    companion object {
        /**
         * Whether Auth turned the session down for good (none saved, revoked, expired, the account gone),
         * as opposed to not answering: only that ends a session.
         */
        fun refusesSession(error: Throwable): Boolean = error is AuthSessionMissingException ||
            (error is RestException && error.statusCode in 400..499 && error.statusCode != 429)

        /** A token with less than this left is refreshed before it's sent. */
        private const val TOKEN_MARGIN_SECONDS = 30

        /**
         * The server refused because the profile is paused: a database function's hint `paused`, an
         * edge function's 403 with code `paused`, or the chat service's "user is banned" (the pause ban).
         */
        fun saysPaused(status: Int, body: JsonObject?): Boolean {
            val hint = body?.get("hint").asString
            val code = body?.get("code").asString
            val first = listOf("message", "msg", "error").firstOrNull { body?.containsKey(it) == true }
            val text = (first?.let { body?.get(it) }.takeUnless { it is JsonNull }.asString ?: "").lowercase()
            return hint == "paused" || (status == 403 && code == "paused") || text.contains("user is banned")
        }

        /**
         * The Supabase client of the app: Auth, with the session kept in [store] across launches, and
         * Realtime. An unconfigured build (see [BackendConfig.missing]) gets a placeholder address, so
         * the app can start and say what's missing instead of crashing.
         */
        fun createClient(config: BackendConfig, store: KeyValueStore): SupabaseClient = createSupabaseClient(
            supabaseUrl = config.url.ifBlank { "https://unconfigured.invalid" },
            supabaseKey = config.publishableKey.ifBlank { "unconfigured" },
        ) {
            install(Auth) {
                sessionManager = KeyValueSessionManager(store)
            }
            install(Realtime)
        }
    }
}

private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** A UUID written the standard way (Swift's `UUID(uuidString:)`), or null. */
fun uuidOrNull(text: String?): UUID? = text?.takeIf(uuidPattern::matches)?.let { UUID.fromString(it) }

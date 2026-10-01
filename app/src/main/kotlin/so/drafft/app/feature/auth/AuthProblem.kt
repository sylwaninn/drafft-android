package so.drafft.app.feature.auth

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import java.io.IOException
import so.drafft.core.data.backend.Backend
import so.drafft.core.model.L

// Port of Drafft/Features/Auth/AuthProblem.swift.

/** What went wrong with an account call, in words people can act on. */
enum class AuthProblem {
    WRONG_CREDENTIALS, EMAIL_NOT_CONFIRMED, EMAIL_TAKEN, INVALID_EMAIL, WEAK_PASSWORD, TOO_MANY_EMAILS, TOO_MANY_TRIES, SAME_PASSWORD,
    WRONG_CODE, OFFLINE, OTHER;

    val message: String
        get() = when (this) {
            WRONG_CREDENTIALS -> L("Email or password is incorrect. Try again or reset your password.")
            EMAIL_NOT_CONFIRMED -> L("Confirm your email first: enter the code we sent you.")
            EMAIL_TAKEN -> L("There's already an account with this email. Log in instead.")
            INVALID_EMAIL -> L("That doesn't look like an email address. Check for typos.")
            WEAK_PASSWORD -> L("Pick a stronger password.")
            TOO_MANY_EMAILS -> L("Too many emails sent. Wait a few minutes and try again.")
            TOO_MANY_TRIES -> L("Too many tries. Wait a few minutes and try again.")
            SAME_PASSWORD -> L("Pick something different from your current password.")
            WRONG_CODE -> L("Wrong or expired code. Check it, or send a new one.")
            OFFLINE -> L("Couldn't connect. Check your connection and try again.")
            OTHER -> L("Something went wrong. Try again in a moment.")
        }

    companion object {
        /** The Swift `AuthProblem(error)`. */
        operator fun invoke(error: Throwable): AuthProblem = from(error)

        fun from(error: Throwable): AuthProblem = when {
            error is Backend.EmailAlreadyRegistered -> EMAIL_TAKEN
            error is AuthRestException && error.error == "email_address_invalid" -> INVALID_EMAIL
            error is AuthRestException -> from(error.errorCode)
            // The iPhone's URLError: no connection, a timeout, the server unreachable.
            error is HttpRequestException || generateSequence(error) { it.cause }.any { it is IOException } -> OFFLINE
            else -> OTHER
        }

        private fun from(code: AuthErrorCode?): AuthProblem = when (code) {
            // An account closed for good (kept for members' safety) can't log in: said like a wrong password,
            // which tells nothing about the account.
            AuthErrorCode.InvalidCredentials, AuthErrorCode.UserBanned -> WRONG_CREDENTIALS
            AuthErrorCode.EmailNotConfirmed -> EMAIL_NOT_CONFIRMED
            AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists -> EMAIL_TAKEN
            AuthErrorCode.WeakPassword -> WEAK_PASSWORD
            AuthErrorCode.OverEmailSendRateLimit -> TOO_MANY_EMAILS
            AuthErrorCode.OverRequestRateLimit -> TOO_MANY_TRIES
            AuthErrorCode.SamePassword -> SAME_PASSWORD
            // Supabase answers the same for a mistyped code and an old one.
            AuthErrorCode.OtpExpired, AuthErrorCode.ReauthenticationNotValid -> WRONG_CODE
            else -> OTHER
        }
    }
}

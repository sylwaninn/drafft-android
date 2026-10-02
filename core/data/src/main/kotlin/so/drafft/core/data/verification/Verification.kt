package so.drafft.core.data.verification

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.google.i18n.phonenumbers.metadata.DefaultMetadataDependenciesProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import java.io.IOException
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.attempt
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.ScreenTracker
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.L
import so.drafft.core.model.appLocale

// Ports Drafft/Services/Verification.swift (the face check's Android side is `AndroidFaceCheck`).

// Contracts

/** [code]: the case's stable name (`tooManyCodes`), kept through minification for analytics. */
sealed class VerificationError(val code: String) : Exception(code) {
    data object InvalidNumber : VerificationError("invalidNumber") { private fun readResolve(): Any = InvalidNumber }
    data object SendFailed : VerificationError("sendFailed") { private fun readResolve(): Any = SendFailed }
    data object WrongCode : VerificationError("wrongCode") { private fun readResolve(): Any = WrongCode }
    data object Expired : VerificationError("expired") { private fun readResolve(): Any = Expired }
    data object TooManyAttempts : VerificationError("tooManyAttempts") { private fun readResolve(): Any = TooManyAttempts }
    data object Network : VerificationError("network") { private fun readResolve(): Any = Network }
    data object NumberTaken : VerificationError("numberTaken") { private fun readResolve(): Any = NumberTaken }

    // phone-code's refusals: the account's email isn't confirmed, too many codes, not a mobile line,
    // the line couldn't be checked (Twilio Lookup down: nothing is sent).
    data object EmailUnconfirmed : VerificationError("emailUnconfirmed") { private fun readResolve(): Any = EmailUnconfirmed }
    data object TooManyCodes : VerificationError("tooManyCodes") { private fun readResolve(): Any = TooManyCodes }
    data object UnsupportedLine : VerificationError("unsupportedLine") { private fun readResolve(): Any = UnsupportedLine }
    data object CheckUnavailable : VerificationError("checkUnavailable") { private fun readResolve(): Any = CheckUnavailable }

    /** The case's name as an event property: `tooManyCodes` becomes `too_many_codes`. */
    val reason: String get() = code.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    /** The words under the number field. */
    override val message: String
        get() = when (this) {
            NumberTaken -> L("This number is already used by another drafft account.")
            EmailUnconfirmed -> L("Confirm your email first, then add your number.")
            TooManyCodes -> L("Too many codes sent. Try again later.")
            InvalidNumber -> L("That doesn't look like a mobile number.")
            UnsupportedLine -> L("This number can't get codes. Use a mobile number.")
            CheckUnavailable -> L("We couldn't check this number right now. Try again in a moment.")
            Network -> L("Couldn't connect. Check your connection and try again.")
            else -> L("We couldn't text this number. Check it, or get help if it keeps failing.")
        }

    companion object {
        /** phone-code's stable codes (backend _shared/phone_code.ts). */
        private val serverCodes: Map<String, VerificationError> = mapOf(
            "email_unconfirmed" to EmailUnconfirmed,
            "sms_limit" to TooManyCodes,
            "phone_invalid" to InvalidNumber,
            "phone_unsupported" to UnsupportedLine,
            "phone_taken" to NumberTaken,
            "phone_check_unavailable" to CheckUnavailable,
        )

        fun from(error: Throwable): VerificationError = when {
            error is VerificationError -> error
            error is IOException -> Network
            error is Backend.PhoneAlreadyRegistered -> NumberTaken
            error is AuthRestException && error.errorCode == AuthErrorCode.PhoneExists -> NumberTaken
            error is Backend.BackendError.Http -> serverCodes[error.serverMessage] ?: SendFailed
            // Supabase answers the same for a mistyped code and an old one.
            error is AuthRestException &&
                (error.errorCode == AuthErrorCode.OtpExpired || error.errorCode == AuthErrorCode.ReauthenticationNotValid) -> WrongCode
            error.isOffline() -> Network
            else -> SendFailed
        }

        private fun Throwable.isOffline(): Boolean = generateSequence(this) { it.cause }.any { it is IOException }
    }
}

interface PhoneVerifying {
    /** Texts a 6-digit code to an E.164 number. */
    suspend fun sendCode(to: String)
    suspend fun verify(code: String, e164: String)

    /** The number already verified on the account (E.164), if any. */
    suspend fun verifiedNumber(): String?
}

/**
 * The real one: the phone-code function, then Supabase Auth's phone change. Sets the number on the
 * account once the code checks out, at sign-up as in You.
 */
class BackendPhoneVerifier(private val backend: Backend) : PhoneVerifying {
    override suspend fun sendCode(to: String) {
        try {
            backend.updatePhone(to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VerificationError.from(e)
        }
    }

    override suspend fun verify(code: String, e164: String) {
        try {
            backend.confirmPhoneChange(e164, code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VerificationError.from(e)
        }
    }

    /**
     * Read from the server (a number verified on another device, or before a reinstall), else from the
     * saved session. Supabase Auth keeps it without the "+".
     */
    override suspend fun verifiedNumber(): String? {
        val auth = backend.client.auth
        val fresh = attempt { auth.retrieveUserForCurrentSession(updateSession = true) }
        val user = fresh ?: auth.currentUserOrNull() ?: return null
        val phone = user.phone
        if (user.phoneConfirmedAt == null || phone.isNullOrEmpty()) return null
        return "+" + phone.filter(Char::isDigit)
    }
}

// Phone

/**
 * A country at the phone step: every region Google's libphonenumber knows, searchable by name or dial
 * code. Which lines get a code is the server's call (Twilio Lookup, mobile lines only).
 */
data class PhoneCountry(
    /** ISO 3166 region code ("FR"). */
    val region: String,
    /** "+33" */
    val dial: String,
    /** A mobile number of the country, written the national way: the field's placeholder. */
    val example: String,
) {
    val id: String get() = region

    /** The region's flag, from its two letters. */
    val flag: String
        get() = buildString { region.uppercase().forEach { appendCodePoint(127_397 + it.code) } }

    /** Country name in the app's language. */
    val name: String
        get() = runCatching { Locale.Builder().setRegion(region).build().getDisplayCountry(appLocale) }
            .getOrNull().orEmpty().ifEmpty { region }

    companion object {
        /** Loads the metadata once, on first use. */
        val phoneNumbers: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

        val all: List<PhoneCountry> by lazy { phoneNumbers.supportedRegions.mapNotNull(::country) }

        fun country(region: String): PhoneCountry? {
            if (region.length != 2) return null
            val code = phoneNumbers.getCountryCodeForRegion(region)
            if (code == 0) return null
            val example = phoneNumbers.getExampleNumberForType(region, PhoneNumberType.MOBILE)
                ?.let { phoneNumbers.format(it, PhoneNumberFormat.NATIONAL) }
            return PhoneCountry(region, "+$code", example ?: "")
        }

        /** The phone's region, else France. */
        val initial: PhoneCountry
            get() = country(Locale.getDefault().country) ?: country("FR") ?: all[0]

        /**
         * A number the phone step takes: valid for the country, and a mobile line (or one that may be, as
         * in the US). Digits only, typed the national way ("06 12..." or "6 12...").
         */
        fun mobileNumber(national: String, country: PhoneCountry): PhoneNumber? {
            val digits = national.filter(Char::isDigit)
            if (digits.isEmpty()) return null
            val number = try {
                phoneNumbers.parse(digits, country.region)
            } catch (e: NumberParseException) {
                return null
            }
            if (!phoneNumbers.isValidNumber(number) || country.dial != "+${number.countryCode}") return null
            val type = phoneNumbers.getNumberType(number)
            return number.takeIf { type == PhoneNumberType.MOBILE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE }
        }

        /**
         * The number typed the international way ("+44 7...", "0044 7...", or the exit code of the country
         * picked, like 011 from the US): the country its calling code names, and the national part after
         * it, formatted when complete ("07700 900123"). Null when it's typed the national way ("06 12...",
         * "6 12..."): the picked country reads it. Every country libphonenumber knows, shared codes included
         * (+1 US or Canada, +44 UK or Jersey, +7 Russia or Kazakhstan: the number itself says which).
         */
        fun international(typed: String, current: PhoneCountry): Pair<PhoneCountry, String>? {
            val digits = typed.filter { it in '0'..'9' }
            val first = typed.firstOrNull { !it.isWhitespace() }
            val rest = when {
                first == '+' || first == '\uFF0B' -> digits
                else -> exitCodeLength(digits, current.region)?.let { digits.drop(it) } ?: return null
            }
            // Calling codes are 1 to 3 digits and none is the start of another (E.164): the first match is it.
            for (length in 1..3) {
                if (rest.length < length) break
                val code = rest.take(length).toInt()
                if (phoneNumbers.getRegionCodesForCountryCode(code).isEmpty()) continue
                val national = rest.drop(length)
                val full = try {
                    phoneNumbers.parse("+$rest", null)
                } catch (e: NumberParseException) {
                    null
                }
                val region = full?.let { phoneNumbers.getRegionCodeForNumber(it) }?.takeIf { it != "ZZ" }
                    ?: if (phoneNumbers.getCountryCodeForRegion(current.region) == code) current.region
                    else phoneNumbers.getRegionCodeForCountryCode(code)
                val country = country(region) ?: return null
                val formatted = full?.takeIf { phoneNumbers.isValidNumber(it) }
                    ?.let { phoneNumbers.format(it, PhoneNumberFormat.NATIONAL) } ?: national
                return country to formatted
            }
            return null
        }

        /**
         * How many leading digits are the picked country's exit code (00 in most of the world, 011 in
         * North America...), from libphonenumber's metadata. Null when the digits don't start with it.
         */
        private fun exitCodeLength(digits: String, region: String): Int? {
            val pattern = runCatching {
                DefaultMetadataDependenciesProvider.getInstance().phoneNumberMetadataSource
                    .getMetadataForRegion(region)?.internationalPrefix
            }.getOrNull() ?: return null
            val match = Regex("^(?:$pattern)").find(digits) ?: return null
            val length = match.value.length
            return length.takeIf { it in 1 until digits.length }
        }

        /** The account's number as Supabase Auth keeps it ("33612345678"), written the international way. */
        fun display(stored: String): String {
            val e164 = "+" + stored.filter(Char::isDigit)
            val number = try {
                phoneNumbers.parse(e164, null)
            } catch (e: NumberParseException) {
                return e164
            }
            return phoneNumbers.format(number, PhoneNumberFormat.INTERNATIONAL)
        }
    }
}

/**
 * Front-end state machine for phone verification: number, code, verified, or locked. Sign-up and You
 * share it. [scope] is the screen's (automatic verification, the resend countdown).
 */
class PhoneVerificationModel(
    private val service: PhoneVerifying,
    /** How long an SMS code works, in seconds (`BackendConfig.smsCodeLifetime`). */
    private val smsCodeLifetime: Double,
    private val scope: CoroutineScope,
) {
    enum class Stage { ENTER_NUMBER, ENTER_CODE, VERIFIED, LOCKED }

    var stage: Stage by mutableStateOf(Stage.ENTER_NUMBER)
        private set

    private var _country by mutableStateOf(PhoneCountry.initial)
    var country: PhoneCountry
        get() = _country
        set(value) {
            _country = value
            parse()
        }

    private var _number by mutableStateOf("")

    /**
     * What the field shows. Typed or pasted the international way, the country follows at once and the
     * field keeps the national part ("+33 6 12..." becomes France and "06 12...").
     */
    var number: String
        get() = _number
        set(value) {
            error = null
            val found = PhoneCountry.international(value, country)
            if (found != null) {
                _number = found.second
                if (found.first != country) country = found.first else parse()
            } else {
                _number = value
                parse()
            }
        }

    /** The typed number, once it's a mobile number of the country. */
    private var parsed: PhoneNumber? by mutableStateOf(null)

    /** Set through [enterCode] (digits only, max 6). */
    var code: String by mutableStateOf("")
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** Shown with a L("Get help") button (sending failed, too many tries). */
    var needsHelp: Boolean by mutableStateOf(false)
        private set
    var resendIn: Int by mutableIntStateOf(0)
        private set
    var attemptsLeft: Int by mutableIntStateOf(5)
        private set
    var verifiedNumber: String? by mutableStateOf(null)
        private set

    /** A number that doesn't count as new (changing: the current one). */
    var currentNumber: String? by mutableStateOf(null)

    var restoredDisplay: String? by mutableStateOf(null)
        private set

    private var timer: Job? = null

    /**
     * When the last code went out: Supabase answers the same for a mistyped code and an old one, so the
     * time says which it was.
     */
    private var sentAt: Instant = Instant.MIN

    fun enterCode(raw: String) {
        val clean = raw.filter(Char::isDigit).take(6)
        val wasShort = code.length < 6
        code = clean
        if (code.isNotEmpty()) error = null
        if (code.length == 6 && wasShort && stage == Stage.ENTER_CODE) scope.launch { verify() }
    }

    private fun parse() {
        val found = PhoneCountry.mobileNumber(number, country)
        parsed = found
        // A shared calling code: the complete number says which country it is (+1 787 is Puerto Rico).
        val region = found?.let { PhoneCountry.phoneNumbers.getRegionCodeForNumber(it) }
        if (region != null && region != country.region) PhoneCountry.country(region)?.let { country = it }
    }

    val e164: String
        get() = parsed?.let { PhoneCountry.phoneNumbers.format(it, PhoneNumberFormat.E164) }
            ?: (country.dial + number.filter(Char::isDigit))
    val numberValid: Boolean get() = parsed != null
    val isSameAsCurrent: Boolean get() = currentNumber == e164

    /** "+33 6 12 34 56 78" */
    val displayNumber: String
        get() {
            restoredDisplay?.let { if (number.isEmpty()) return it }
            return parsed?.let { PhoneCountry.phoneNumbers.format(it, PhoneNumberFormat.INTERNATIONAL) } ?: e164
        }

    val primaryTitle: String
        get() = when (stage) {
            Stage.ENTER_NUMBER -> L("Send code")
            Stage.ENTER_CODE -> L("Verify")
            Stage.VERIFIED -> L("Continue")
            Stage.LOCKED -> L("Get help")
        }

    val primaryEnabled: Boolean
        get() = when (stage) {
            Stage.ENTER_NUMBER -> numberValid && !isSameAsCurrent && !busy
            Stage.ENTER_CODE -> code.length == 6 && !busy
            Stage.VERIFIED, Stage.LOCKED -> true
        }

    suspend fun sendCode() {
        if (!numberValid) {
            error = VerificationError.InvalidNumber.message
            return
        }
        busy = true
        error = null
        needsHelp = false
        // Already the account's verified number (a sign-up started over, a reinstall): nothing to send,
        // Supabase wouldn't text it anyway. The step is done.
        if (service.verifiedNumber() == e164) {
            verifiedNumber = e164
            stage = Stage.VERIFIED
            busy = false
            Haptics.success()
            return
        }
        val resend = stage == Stage.ENTER_CODE
        try {
            service.sendCode(e164)
            code = ""
            stage = Stage.ENTER_CODE
            startResendTimer()
            Telemetry.track(AnalyticsEvent.PhoneCodeSent(during, resend))
            Haptics.success()
        } catch (e: CancellationException) {
            busy = false
            throw e
        } catch (e: Exception) {
            val failure = e as? VerificationError ?: VerificationError.SendFailed
            Telemetry.track(AnalyticsEvent.PhoneCodeFailed(during, failure.reason))
            Telemetry.unexpected(e, "phone", "send_code")
            error = failure.message
            // Nothing to fix on the number there: waiting, or confirming the email, is the way.
            needsHelp = failure !in listOf(
                VerificationError.TooManyCodes, VerificationError.EmailUnconfirmed,
                VerificationError.Network, VerificationError.CheckUnavailable,
            )
            Haptics.warning()
        }
        busy = false
    }

    suspend fun verify() {
        if (code.length != 6 || busy) return
        busy = true
        error = null
        try {
            try {
                service.verify(code, e164)
            } catch (e: VerificationError.WrongCode) {
                val expired = sentAt == Instant.MIN ||
                    java.time.Duration.between(sentAt, Instant.now()).toMillis() / 1000.0 > smsCodeLifetime
                throw if (expired) VerificationError.Expired else e
            }
            verifiedNumber = e164
            stage = Stage.VERIFIED
            timer?.cancel()
            Telemetry.track(AnalyticsEvent.PhoneVerified(during))
            Haptics.success()
        } catch (e: VerificationError.Expired) {
            Telemetry.track(AnalyticsEvent.PhoneVerificationFailed(during, e.reason))
            error = L("This code has expired. Send a new one.")
            code = ""
            Haptics.warning()
        } catch (e: VerificationError.NumberTaken) {
            // Verified on another account meanwhile: no code fixes that, the number has to change.
            Telemetry.track(AnalyticsEvent.PhoneVerificationFailed(during, e.reason))
            changeNumber()
            error = e.message
            Haptics.warning()
        } catch (e: CancellationException) {
            busy = false
            throw e
        } catch (e: VerificationError) {
            Telemetry.track(AnalyticsEvent.PhoneVerificationFailed(during, e.reason))
            if (e == VerificationError.WrongCode) {
                wrongCode()
            } else {
                // SendFailed and CheckUnavailable are the server failing: ErrorKind tells what needs a fix.
                Telemetry.unexpected(e, "phone", "verify")
                // Not the code's fault (offline, a server error): no try used up, the same code can go again.
                error = if (e == VerificationError.Network) e.message else L("Something went wrong. Try again in a moment.")
                needsHelp = e != VerificationError.Network
                Haptics.warning()
            }
        } catch (e: Exception) {
            Telemetry.track(AnalyticsEvent.PhoneVerificationFailed(during, Telemetry.reason(e)))
            Telemetry.unexpected(e, "phone", "verify")
            wrongCode()
        }
        busy = false
    }

    /** Where the check runs (`onboarding`, `phone_verification`), for analytics. */
    private val during: String get() = ScreenTracker.currentID

    /** A wrong code uses up a try; the last one locks the step. */
    private fun wrongCode() {
        attemptsLeft -= 1
        code = ""
        Haptics.warning()
        if (attemptsLeft <= 0) {
            stage = Stage.LOCKED
            error = L("Too many wrong codes. For your security, verification is paused.")
            needsHelp = true
        } else {
            error = if (attemptsLeft == 1) L("Wrong code. 1 try left.") else L("Wrong code. %d tries left.", attemptsLeft)
        }
    }

    suspend fun resend() {
        if (resendIn != 0) return
        sendCode()
    }

    /** Resuming a sign-up whose number was already verified. */
    fun restoreVerified(display: String) {
        verifiedNumber = display
        restoredDisplay = display
        stage = Stage.VERIFIED
    }

    fun changeNumber() {
        timer?.cancel()
        stage = Stage.ENTER_NUMBER
        code = ""
        error = null
        needsHelp = false
    }

    /** A code just went out: its lifetime and the Resend countdown start now. */
    private fun startResendTimer() {
        timer?.cancel()
        sentAt = Instant.now()
        resendIn = 30
        timer = scope.launch {
            while (resendIn > 0 && isActive) {
                delay(1.seconds)
                resendIn -= 1
            }
        }
    }
}

// Face on the main photo

/**
 * On-device check that a photo shows a face big enough to be recognised (the iPhone's Vision; ML Kit
 * face detection on Android, `AndroidFaceCheck`, installed as [engine] at launch).
 */
object FaceCheck {
    enum class Result { FACE, NO_FACE, TOO_SMALL }

    fun interface Engine {
        /** Normalized face boxes' areas (width × height, each in 0...1 of the upright photo). */
        suspend fun faceAreas(photo: String): List<Double>
    }

    @Volatile
    var engine: Engine = Engine { emptyList() }

    /** [photo] is a file path (or a bundled image name). */
    suspend fun check(photo: String): Result {
        val biggest = engine.faceAreas(photo).maxOrNull() ?: return Result.NO_FACE
        // At least ~2% of the frame: a face you could actually recognise.
        return if (biggest >= 0.02) Result.FACE else Result.TOO_SMALL
    }
}

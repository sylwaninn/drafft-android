package so.drafft.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

data class SportEntry(
    val sport: Sport,
    /** Sessions per week, set in onboarding and Edit profile. */
    val perWeek: Int = 1,
) {
    val id: Sport get() = sport
    val perWeekText: String get() = if (perWeek >= 7) L("Every day") else L("%d× a week", perWeek)
}

object Weekday {
    val short = listOf("M", "T", "W", "T", "F", "S", "S")
    val names = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val abbr = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}

/** The interactive prompt on a profile: something a viewer can play with, then reply to. */
sealed interface Icebreaker {
    /** Two truths and a lie: viewer guesses which one is invented. */
    data class TwoTruths(val statements: List<String>, val lieIndex: Int) : Icebreaker

    /** Setup and punchline; the punchline is revealed on tap. */
    data class Joke(val setup: String, val punchline: String) : Icebreaker

    /** A strong opinion the viewer agrees or disagrees with. */
    data class HotTake(val text: String) : Icebreaker

    /** Two options: the viewer picks one, then sees which one they picked. */
    data class ThisOrThat(val question: String, val options: List<String>, val pick: Int) : Icebreaker

    /** A question about them with three answers, one right: the viewer guesses. */
    data class Guess(val question: String, val options: List<String>, val answer: Int) : Icebreaker

    enum class Kind(val id: String) {
        TWO_TRUTHS("twoTruths"), JOKE("joke"), HOT_TAKE("hotTake"), THIS_OR_THAT("thisOrThat"), GUESS("guess");

        val title: String
            get() = when (this) {
                TWO_TRUTHS -> L("Two truths, one lie")
                JOKE -> L("Bad joke")
                HOT_TAKE -> L("Hot take")
                THIS_OR_THAT -> L("This or that")
                GUESS -> L("Guess about me")
            }

        val detail: String
            get() = when (this) {
                TWO_TRUTHS -> L("They spot the lie")
                JOKE -> L("They tap for the punchline")
                HOT_TAKE -> L("They agree or disagree")
                THIS_OR_THAT -> L("They pick a side, then see yours")
                GUESS -> L("They guess the right answer")
            }

        /** SF Symbol name (see `DrafftIcon`). */
        val symbol: String
            get() = when (this) {
                TWO_TRUTHS -> "eyes"
                JOKE -> "theatermasks.fill"
                HOT_TAKE -> "flame.fill"
                THIS_OR_THAT -> "arrow.left.arrow.right"
                GUESS -> "questionmark.bubble.fill"
            }

        /** Starting content when switching to this kind in the editor. */
        val blank: Icebreaker
            get() = when (this) {
                TWO_TRUTHS -> TwoTruths(listOf("", "", ""), lieIndex = -1)
                JOKE -> Joke("", "")
                HOT_TAKE -> HotTake("")
                THIS_OR_THAT -> ThisOrThat("", listOf("", ""), pick = -1)
                GUESS -> Guess("", listOf("", "", ""), answer = -1)
            }

        companion object {
            fun fromId(id: String?): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    val kind: Kind
        get() = when (this) {
            is TwoTruths -> Kind.TWO_TRUTHS
            is Joke -> Kind.JOKE
            is HotTake -> Kind.HOT_TAKE
            is ThisOrThat -> Kind.THIS_OR_THAT
            is Guess -> Kind.GUESS
        }

    /** Nothing written yet (a skipped prompt): hidden on the profile, allowed when saving. */
    val isBlank: Boolean
        get() {
            fun empty(s: String) = s.isBlank()
            return when (this) {
                is TwoTruths -> statements.all(::empty)
                is Joke -> empty(setup) && empty(punchline)
                is HotTake -> empty(text)
                is ThisOrThat -> empty(question) && options.all(::empty)
                is Guess -> empty(question) && options.all(::empty)
            }
        }

    /** Every field filled in. The choice (lie, pick, right answer) starts at -1: nothing chosen for the person. */
    val isComplete: Boolean
        get() {
            fun ok(s: String) = s.isNotBlank()
            return when (this) {
                is TwoTruths -> statements.all(::ok) && lieIndex in statements.indices
                is Joke -> ok(setup) && ok(punchline)
                is HotTake -> ok(text)
                is ThisOrThat -> ok(question) && options.all(::ok) && pick in options.indices
                is Guess -> ok(question) && options.all(::ok) && answer in options.indices
            }
        }
}

data class Profile(
    val id: String,
    val name: String,
    val age: Int,
    val pronouns: String? = null,
    /** Your own profile only: the birthday set at sign-up (midnight UTC, see `BirthdateField`). */
    val birthday: Instant? = null,
    /** Their own answer at sign-up (the server's `gender`). Null when unknown: see [DiscoverFilters.audienceOf]. */
    val gender: Audience? = null,
    val neighborhood: String = "",
    val distanceKm: Double = 0.0,
    /** The first photo: a link (or a bundled drawable name for placeholders). */
    val portrait: String = "",
    val photos: List<String> = emptyList(),
    val sports: List<SportEntry> = emptyList(),
    val voiceIntro: String? = null,
    /** Seconds. */
    val voiceDuration: Double = 0.0,
    val icebreaker: Icebreaker = Icebreaker.Kind.JOKE.blank,
    val favoriteSpot: String = "",
    val bio: String = "",
    val goal: String = "",
    /** They super liked you: their card comes first in your deck, marked in red, with their note. */
    val superLikedMe: Boolean = false,
    val superLikeNote: String? = null,
    /** From the server's card, or set when the user edits their own profile. */
    val vitalsOverride: Vitals? = null,
    val promptsOverride: List<ProfilePrompt>? = null,
) {
    val firstName: String get() = name
    val allPhotos: List<String> get() = listOf(portrait) + photos
    val vitals: Vitals? get() = vitalsOverride
    val prompts: List<ProfilePrompt> get() = promptsOverride ?: emptyList()
}

/** A current match (`my_matches`): the match's id on the server, who, and since when. */
data class Match(
    val id: String,
    val profile: Profile,
    val matchedAt: Instant,
)

// Sessions

data class SessionProposal(
    /** The server's `sessions.id` (a new proposal's own until the server saves it, see `SessionStore`). */
    val id: UUID = UUID.randomUUID(),
    val sport: Sport,
    /** Proposed day-and-time options (1 to 3). The other person picks one or suggests others. */
    val options: List<Instant>,
    /** The option both agreed on, once accepted. */
    val chosen: Instant? = null,
    /** Optional headline for the invite, e.g. "Cool morning run in Parc de la Tête d'Or?". */
    val title: String = "",
    val note: String = "",
    /** Details picked as toggles ("Easy pace", "Coffee after"...), separate from the free note. */
    val tags: List<String> = emptyList(),
    /** Set when one of you introduces the other to a sport they don't do yet. */
    val discovery: Discovery? = null,
    val status: Status = Status.PENDING,
) {
    /**
     * pending: waiting for a pick; countered: replaced by a newer proposal with other times;
     * cancelled: called off by either person, or with the match or an account.
     */
    enum class Status { PENDING, ACCEPTED, DECLINED, COUNTERED, CANCELLED }

    enum class Discovery { I_TEACH, THEY_TEACH }

    /** The agreed time, or the first option while it's still being decided. */
    val date: Instant get() = chosen ?: options.firstOrNull() ?: Instant.now()

    val displayTitle: String get() = title.ifEmpty { L("%s session", sport.displayName) }

    /** "Tuesday 30 Sep at 07:00" */
    val whenText: String get() = L("%s at %s", dayText, timeText)
    val dayText: String get() = DateText.weekdayDayMonth(date)
    val timeText: String get() = DateText.time(date)

    companion object {
        /** Title ideas per sport, shown as tappable examples. */
        fun titleIdeas(sport: Sport): List<String> = when (sport) {
            Sport.RUNNING -> listOf(L("Cool morning run in Parc de la Tête d'Or?"), L("Easy 8k along the canal, then croissants?"), L("Intervals, loser buys coffee?"))
            Sport.TRAIL -> listOf(L("Muddy loop in Fontainebleau?"), L("Hill repeats and a view?"), L("Long slow trail, big brunch after?"))
            Sport.CYCLING -> listOf(L("Longchamp laps at sunrise?"), L("Gravel ride with a picnic stop?"), L("Easy spin to a café?"))
            Sport.SWIMMING -> listOf(L("Early lengths at the outdoor pool?"), L("1k swim, then breakfast?"), L("Open-water dip if you dare?"))
            Sport.CLIMBING -> listOf(L("Bouldering, then a beer?"), L("Project night, you spot me?"), L("Easy circuits for a first session?"))
            Sport.PADEL -> listOf(L("Doubles, loser buys drinks?"), L("Friendly match after work?"), L("Padel and a terrace?"))
            Sport.TENNIS -> listOf(L("A few sets before work?"), L("Rally, no score, just fun?"), L("Best of three, winner picks dinner?"))
            Sport.STRENGTH -> listOf(L("Leg day, I'll spot you?"), L("Push day then smoothies?"), L("Deadlift PR attempt, come cheer?"))
            Sport.YOGA -> listOf(L("Sunset flow in the park?"), L("Morning stretch, slow start?"), L("Yoga then a long lunch?"))
            Sport.HIKING -> listOf(L("Forest walk with a picnic?"), L("Day hike, sandwiches on me?"), L("Sunrise hike, worth the alarm?"))
            Sport.CROSSFIT -> listOf(L("WOD together, no mercy?"), L("Partner workout, then tacos?"), L("Saturday class, you in?"))
            Sport.TRIATHLON -> listOf(L("Brick session: bike then run?"), L("Swim-run by the lake?"), L("Easy aerobic day together?"))
            else -> listOf(
                L("%s together, then coffee?", sport.displayName),
                L("Easy %s session to start?", sport.inSentence),
                L("Come try my usual %s spot?", sport.inSentence),
            )
        }
    }
}

// Chat

enum class DeliveryState {
    /** Couldn't be sent (offline too long, refused): kept in the thread, sent again on a tap. */
    FAILED,
    SENDING, SENT, DELIVERED, READ,
}

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent

    /** [asset] is a link (or a local file URI while sending); [imageData] the picked bytes while uploading. */
    data class Photo(val asset: String?, val imageData: ByteArray? = null) : MessageContent {
        override fun equals(other: Any?) = other is Photo && other.asset == asset &&
            (other.imageData === imageData || (other.imageData != null && imageData != null && other.imageData.contentEquals(imageData)))
        override fun hashCode() = asset.hashCode() * 31 + (imageData?.contentHashCode() ?: 0)
    }

    data class Video(val url: String, val thumbnail: ByteArray? = null, val duration: Double) : MessageContent {
        override fun equals(other: Any?) = other is Video && other.url == url && other.duration == duration &&
            (other.thumbnail === thumbnail || (other.thumbnail != null && thumbnail != null && other.thumbnail.contentEquals(thumbnail)))
        override fun hashCode() = url.hashCode() * 31 + duration.hashCode()
    }

    data class Voice(val url: String, val duration: Double, val levels: List<Float>) : MessageContent
    data class File(val name: String, val size: Long, val url: String?) : MessageContent
    data class Session(val proposal: SessionProposal) : MessageContent
    data class IcebreakerReply(val quote: String, val reply: String) : MessageContent

    /** A like on one of their photos, carried into the match as the first message. */
    data class PhotoReply(val asset: String, val reply: String) : MessageContent
}

data class Message(
    /**
     * The chat service's message id (lowercased UUIDs for the app's own messages, deterministic ids for
     * the server's: openers, sessions).
     */
    val id: String = UUID.randomUUID().toString().lowercase(),
    val content: MessageContent,
    val fromMe: Boolean,
    val date: Instant = Instant.now(),
    val state: DeliveryState = DeliveryState.READ,
    val reaction: String? = null,
    /** The message this one answers (swipe to reply, or Reply in the long-press menu). */
    val replyTo: String? = null,
    /** What the answered message said, for a quote whose message isn't loaded in the thread. */
    val replyQuote: Quote? = null,
    /** Pixel size of a photo or video: the bubble keeps the media's proportions before it's loaded. */
    val mediaSize: PixelSize? = null,
    /** A video's poster (a link). */
    val poster: String? = null,
) {
    data class Quote(val fromMe: Boolean, val text: String)
    data class PixelSize(val width: Double, val height: Double)

    val previewText: String
        get() = when (val c = content) {
            is MessageContent.Text -> c.text
            is MessageContent.Photo -> L("Photo")
            is MessageContent.Video -> L("Video")
            is MessageContent.Voice -> L("Voice message (%s)", c.duration.clock)
            is MessageContent.File -> c.name
            is MessageContent.Session -> L("Session: %s, %s", c.proposal.sport.displayName, DateText.weekdayShortDay(c.proposal.date))
            is MessageContent.IcebreakerReply -> c.reply.ifEmpty { L("Liked your profile") }
            is MessageContent.PhotoReply -> c.reply.ifEmpty { L("Liked a photo") }
        }
}

data class Conversation(
    val id: String,
    val profile: Profile,
    val messages: List<Message>,
    val isTyping: Boolean = false,
    val unread: Int = 0,
    /** Marked unread by hand ("Mark as unread"): a dot, no count. Cleared when the chat is opened. */
    val markedUnread: Boolean = false,
    val matchedAt: Instant,
    /** Muted chats stay in the list but leave the tab badge and use a quiet unread badge. */
    val muted: Boolean = false,
    /** The other person has the app open right now. */
    val online: Boolean = false,
) {
    /** Shown as unread in the list: messages not read yet, or marked by hand. */
    val isUnread: Boolean get() = unread > 0 || markedUnread
    val lastMessage: Message? get() = messages.lastOrNull()
}

/** "1:05" for a duration in seconds. */
val Double.clock: String
    get() {
        val s = Math.round(this).toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

/**
 * Dates as the app writes them, in the app's language and the phone's time zone. Formats are ICU
 * skeletons, like the iPhone's `Date.FormatStyle` fields: each language orders and punctuates them its
 * own way. On Android, [formatter] is set to ICU (`android.icu.text.DateFormat.getInstanceForSkeleton`)
 * at launch; the default below serves JVM tests.
 */
object DateText {
    val zone: ZoneId get() = ZoneId.systemDefault()

    /** Formats [date] with an ICU skeleton in [locale]. */
    @Volatile
    var formatter: (skeleton: String, date: Instant, locale: java.util.Locale) -> String = ::jvmFormat

    private fun jvmFormat(skeleton: String, date: Instant, locale: java.util.Locale): String {
        if (skeleton == "jmm") {
            return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(date)
        }
        val pattern = skeleton.replace(Regex("(E+)(d+)(M*)"), "$1 $2 $3").trim()
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(date)
    }

    fun format(skeleton: String, date: Instant): String = formatter(skeleton, date, appLocale)

    /** "Tuesday 30 Sep" (weekday wide, day, month abbreviated). */
    fun weekdayDayMonth(date: Instant): String = format("EEEEdMMM", date)

    /** "Tue 30" */
    fun weekdayShortDay(date: Instant): String = format("EEEd", date)

    /** "Tue 30 Sep" */
    fun weekdayShortDayMonth(date: Instant): String = format("EEEdMMM", date)

    /** "30 Sep" */
    fun dayMonth(date: Instant): String = format("dMMM", date)

    /** "18:30" (or "6:30 PM" where the language writes it so). */
    fun time(date: Instant): String = format("jmm", date)

    /**
     * How long ago (or ahead) [date] is, in words, like `.relative(presentation: .named)`: "yesterday",
     * "2 weeks ago". On Android, [relativeFormatter] is set to ICU's `RelativeDateTimeFormatter` at
     * launch; the default below (English) serves JVM tests.
     */
    @Volatile
    var relativeFormatter: (date: Instant, now: Instant, locale: java.util.Locale) -> String = ::jvmRelative

    fun relative(date: Instant, now: Instant = Instant.now()): String = relativeFormatter(date, now, appLocale)

    private fun jvmRelative(date: Instant, now: Instant, locale: java.util.Locale): String {
        val seconds = java.time.Duration.between(date, now).seconds
        val (value, unit) = relativeUnit(seconds)
        if (value == 0L) return "now"
        val plural = if (kotlin.math.abs(value) == 1L) unit else unit + "s"
        return if (value > 0) "$value $plural ago" else "in ${-value} $plural"
    }

    /** The largest whole unit in [seconds] (positive: in the past), as ICU's unit names. */
    fun relativeUnit(seconds: Long): Pair<Long, String> {
        val a = kotlin.math.abs(seconds)
        return when {
            a < 60 -> seconds to "second"
            a < 3_600 -> seconds / 60 to "minute"
            a < 86_400 -> seconds / 3_600 to "hour"
            a < 7 * 86_400 -> seconds / 86_400 to "day"
            a < 30 * 86_400 -> seconds / (7 * 86_400) to "week"
            a < 365 * 86_400 -> seconds / (30 * 86_400) to "month"
            else -> seconds / (365 * 86_400) to "year"
        }
    }
}

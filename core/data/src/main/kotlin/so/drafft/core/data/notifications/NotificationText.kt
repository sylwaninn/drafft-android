package so.drafft.core.data.notifications

import java.text.BreakIterator
import so.drafft.core.model.AppLanguage

// Ports Drafft/Services/NotificationText.swift.

/**
 * Every notification's words, in the app's languages: the same phrases as the server's pushes
 * (drafft-backend `supabase/functions/_shared/texts.ts`), so one event reads the same whichever sends it.
 * WORDING.md, Push: the title is the person's name (or the event, for an anonymous like), never "drafft"
 * (the system already shows the app's name); the body is one sentence with the useful fact, no emoji of
 * ours, no "!". Casual register: "tu" in French, "du" in German, European Portuguese. French puts a
 * non-breaking space before ":" and inside « ». A body that ends with a session's name has no full stop:
 * that name can be the person's own words, a question included.
 */
object NotificationText {
    sealed interface Kind {
        data object Message : Kind
        data object Like : Kind
        data object SuperLike : Kind
        data object Match : Kind

        /** An emoji on one of your messages, with that message's text (null: previews off, or not text). */
        data class Reaction(val emoji: String, val text: String?) : Kind
        data class SessionProposed(val title: String) : Kind
        data class SessionAccepted(val title: String) : Kind
        data class SessionDeclined(val title: String) : Kind
        data class SessionCancelled(val title: String) : Kind
    }

    private const val NBSP = " "

    /** The person's name, or the event for a like: a like stays anonymous, the Likes tab says who it was. */
    fun title(kind: Kind, name: String, language: AppLanguage): String = when (kind) {
        Kind.Like -> pick(language, en = "New like", fr = "Nouveau like", es = "Nuevo like", de = "Neues Like",
            it = "Nuovo like", pt = "Novo like", nl = "Nieuwe like")
        Kind.SuperLike -> pick(language, en = "New super like", fr = "Nouveau super like", es = "Nuevo superlike",
            de = "Neuer Super Like", it = "Nuovo super like", pt = "Novo super like", nl = "Nieuwe superlike")
        else -> who(name, language)
    }

    @Suppress("UNUSED_PARAMETER")
    fun body(kind: Kind, name: String, language: AppLanguage): String = when (kind) {
        is Kind.Reaction -> reaction(kind.emoji, kind.text, language)
        Kind.Message -> pick(language,
            en = "New message.", fr = "Nouveau message.", es = "Nuevo mensaje.", de = "Neue Nachricht.",
            it = "Nuovo messaggio.", pt = "Nova mensagem.", nl = "Nieuw bericht.")
        Kind.Like -> pick(language,
            en = "Someone liked your profile.", fr = "Quelqu'un a liké ton profil.",
            es = "A alguien le ha gustado tu perfil.", de = "Jemandem gefällt dein Profil.",
            it = "Qualcuno ha messo like al tuo profilo.", pt = "Alguém gostou do teu perfil.",
            nl = "Iemand vindt je profiel leuk.")
        Kind.SuperLike -> pick(language,
            en = "Someone sent you a super like.", fr = "Quelqu'un t'a envoyé un super like.",
            es = "Alguien te ha enviado un superlike.", de = "Jemand hat dir einen Super Like geschickt.",
            it = "Qualcuno ti ha mandato un super like.", pt = "Alguém enviou-te um super like.",
            nl = "Iemand heeft je een superlike gestuurd.")
        Kind.Match -> pick(language,
            en = "It's mutual: propose a first session.",
            fr = "C'est réciproque$NBSP: propose une première séance.",
            es = "Es mutuo: proponle una primera sesión.",
            de = "Ihr mögt euch beide: Schlag eine erste Session vor.",
            it = "È reciproco: proponi una prima sessione.", pt = "É recíproco: propõe uma primeira sessão.",
            nl = "Het is wederzijds: stel een eerste sessie voor.")
        is Kind.SessionProposed -> kind.title.let { t ->
            pick(language,
                en = "Proposed a session: $t", fr = "Te propose une séance$NBSP: $t",
                es = "Te propone una sesión: $t", de = "Schlägt dir eine Session vor: $t",
                it = "Ti propone una sessione: $t", pt = "Propõe-te uma sessão: $t",
                nl = "Stelt een sessie voor: $t")
        }
        is Kind.SessionAccepted -> kind.title.let { t ->
            pick(language,
                en = "Confirmed the session: $t", fr = "A confirmé la séance$NBSP: $t",
                es = "Ha confirmado la sesión: $t", de = "Hat die Session bestätigt: $t",
                it = "Ha confermato la sessione: $t", pt = "Confirmou a sessão: $t",
                nl = "Heeft de sessie bevestigd: $t")
        }
        is Kind.SessionDeclined -> kind.title.let { t ->
            pick(language,
                en = "Can't make it this time: $t", fr = "Ne peut pas cette fois-ci$NBSP: $t",
                es = "Esta vez no puede: $t", de = "Kann diesmal nicht: $t", it = "Stavolta non può: $t",
                pt = "Desta vez não pode: $t", nl = "Kan deze keer niet: $t")
        }
        is Kind.SessionCancelled -> kind.title.let { t ->
            pick(language,
                en = "Cancelled the session: $t", fr = "A annulé la séance$NBSP: $t",
                es = "Ha cancelado la sesión: $t", de = "Hat die Session abgesagt: $t",
                it = "Ha annullato la sessione: $t", pt = "Cancelou a sessão: $t",
                nl = "Heeft de sessie afgezegd: $t")
        }
    }

    /**
     * "Reacted ❤️ to “See you at 7?”", or without the message when previews are off. The emoji is the
     * person's reaction, not ours.
     */
    private fun reaction(e: String, text: String?, language: AppLanguage): String {
        if (text == null) {
            return pick(language,
                en = "Reacted $e to your message.", fr = "A réagi $e à ton message.",
                es = "Ha reaccionado con $e a tu mensaje.", de = "Hat mit $e auf deine Nachricht reagiert.",
                it = "Ha reagito con $e al tuo messaggio.", pt = "Reagiu com $e à tua mensagem.",
                nl = "Reageerde met $e op je bericht.")
        }
        val t = short(text)
        return pick(language,
            en = "Reacted $e to “$t”",
            fr = "A réagi $e à «$NBSP$t$NBSP»",
            es = "Ha reaccionado con $e a «$t»",
            de = "Hat mit $e auf „$t“ reagiert.",
            it = "Ha reagito con $e a «$t»",
            pt = "Reagiu com $e a «$t»",
            nl = "Reageerde met $e op ‘$t’")
    }

    /** The quoted message in a reaction notification: one line's worth (counted in visible characters, like Swift). */
    private fun short(text: String): String {
        val t = text.replace("\n", " ")
        val breaks = BreakIterator.getCharacterInstance().apply { setText(t) }
        var count = 0
        var end59 = 0
        while (breaks.next() != BreakIterator.DONE) {
            count += 1
            if (count == 59) end59 = breaks.current()
        }
        // design-lint: allow truncation - quoted message in a notification, not UI copy
        return if (count > 60) t.substring(0, end59).trim(' ', '\t') + "…" else t
    }

    /** A message with previews on: its text is the body, under the sender's name as the title. */
    @Suppress("UNUSED_PARAMETER")
    fun preview(text: String, name: String, language: AppLanguage): String = text

    /** The name as the title, or "Someone" when the profile has none (never an empty title). */
    private fun who(name: String, language: AppLanguage): String {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return pick(language, en = "Someone", fr = "Quelqu'un", es = "Alguien", de = "Jemand", it = "Qualcuno",
            pt = "Alguém", nl = "Iemand")
    }

    private fun pick(
        language: AppLanguage, en: String, fr: String, es: String, de: String, it: String, pt: String, nl: String,
    ): String = when (language) {
        AppLanguage.EN -> en
        AppLanguage.FR -> fr
        AppLanguage.ES -> es
        AppLanguage.DE -> de
        AppLanguage.IT -> it
        AppLanguage.PT -> pt
        AppLanguage.NL -> nl
    }
}

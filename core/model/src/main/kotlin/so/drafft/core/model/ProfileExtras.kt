package so.drafft.core.model

/** Hinge-style profile content: written prompts that can be liked one by one. */
data class ProfilePrompt(
    /** The English library text: the prompt's identity (saved, compared, used as id). */
    val question: String,
    val answer: String,
) {
    val id: String get() = question

    /** The question in the app's language. */
    val questionText: String get() = text(question)

    companion object {
        val library: List<PromptCategory> = listOf(
            PromptCategory(id = "move", icon = "running", questions = listOf(
                "My ideal Sunday session",
                "After a workout you'll find me",
                "A stat I'm weirdly proud of",
                "My competitive streak, rated",
                "My pre-race ritual involves",
                "The worst run of my life happened when",
                "I'd drop everything to watch",
                "My playlist for the last kilometre",
                "Rest day? Here's what that really means",
                "The piece of gear I'd save in a fire",
                "I've never been as sore as the day I",
            )),
            PromptCategory(id = "dating", icon = "heart", questions = listOf(
                "The way to win me over",
                "I'll know it's a match if",
                "Green flag in a training partner",
                "A first date that isn't dinner",
                "I'm looking for someone who",
                "You should not go out with me if",
                "My love language is basically",
                "Together we could finally",
            )),
            PromptCategory(id = "fun", icon = "smile-circle", questions = listOf(
                "My most irrational fear",
                "My most controversial opinion",
                "I will never shut up about",
                "The snack that fuels my entire personality",
                "Two truths and a lie about my fitness",
                "My hidden talent nobody believes",
                "If I were an energy gel flavour I'd be",
                "The dumbest injury I've ever had",
                "Unpopular opinion about stretching",
                "Karaoke song, no hesitation",
            )),
            PromptCategory(id = "deep", icon = "stars", questions = listOf(
                "Something I'm training for outside of sport",
                "The best advice a coach ever gave me",
                "What keeps me going on hard days",
                "A goal that scares me a little",
                "I feel most like myself when",
                "The person who got me into sport",
            )),
        )

        /** Every question in the library, flat. */
        val questions: List<String> get() = library.flatMap { it.questions }

        /** Taken out of the library; people who answered them still see them translated. */
        private val retired = listOf("The sport I'm secretly bad at", "We'll get along if", "Red flag: you skip")

        private val known: Set<String> by lazy { (questions + retired).toSet() }

        /** A library question in the app's language (questions not in the library are shown as is). */
        /** Saved questions whose catalog key moved off a forbidden word; the saved text stays (WORDING.md 5.3). */
        private val renamed = mapOf("I'll know it's a match if" to "We're on the same wavelength if")

        fun text(question: String): String = if (question in known) L(renamed[question] ?: question) else question
    }
}

/** Quick facts: lifestyle answers, saved as their English option ("Early bird", "Never"...). */
data class Vitals(
    val drinks: String,
    val smokes: String,
    val diet: String,
    val chronotype: String,
) {
    /** The drinking answer as a short phrase for summaries ("Drinks socially"); empty if unanswered. */
    val drinksSummary: String
        get() = when (drinks) {
            "" -> ""
            "Never" -> L("No alcohol")
            "Rarely" -> L("Drinks rarely")
            "Socially" -> L("Drinks socially")
            "Post-race only" -> L("Drinks post-race only")
            "Apéro is sacred" -> L("Drinks: apéro is sacred")
            else -> drinks
        }

    companion object {
        /** Nothing answered: what a new account starts with. */
        val blank = Vitals(drinks = "", smokes = "", diet = "", chronotype = "")

        private val options = setOf(
            "Very early bird",
            "Early bird",
            "Night owl",
            "Omnivore",
            "Flexitarian",
            "Vegetarian",
            "Vegan",
            "Pescatarian",
            "Never",
            "Rarely",
            "Socially",
            "Post-race only",
            "Apéro is sacred",
            "Sometimes",
            "Yes",
        )

        /**
         * Lifestyle answers are saved as their English option, which stays their identity; this is the
         * text to show. Anything else (free text) is shown as is.
         */
        fun label(value: String, gender: Audience? = null): String = when {
            value == "Meat lover" -> when (gender) {
                Audience.WOMEN -> L("Meat lover (woman)")
                Audience.MEN -> L("Meat lover (man)")
                else -> L("Meat lover")
            }
            value in options -> L(value)
            else -> value
        }
    }
}

/** A themed set of prompt questions. Add categories or questions here; the picker scales with them. */
data class PromptCategory(
    val id: String,
    /** SF Symbol name (see `DrafftIcon`). */
    val icon: String,
    /** English library text: the stable identity of each question. Show [ProfilePrompt.text]. */
    val questions: List<String>,
) {
    val name: String
        get() = when (id) {
            "move" -> L("On the move")
            "dating" -> L("Dating me")
            "fun" -> L("Just for fun")
            "deep" -> L("A bit deeper")
            else -> id
        }
}

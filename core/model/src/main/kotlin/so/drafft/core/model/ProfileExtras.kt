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
            PromptCategory(id = "move", icon = "figure.run", questions = listOf(
                "My ideal Sunday session",
                "After a workout you'll find me",
                "A stat I'm weirdly proud of",
                "My competitive streak, rated",
                "The sport I'm secretly bad at",
                "My pre-race ritual involves",
                "The worst run of my life happened when",
                "I'd drop everything to watch",
                "My playlist for the last kilometre",
                "Rest day? Here's what that really means",
                "The piece of gear I'd save in a fire",
                "I've never been as sore as the day I",
            )),
            PromptCategory(id = "dating", icon = "heart.fill", questions = listOf(
                "We'll get along if",
                "The way to win me over",
                "I'll know it's a match if",
                "Green flag in a training partner",
                "Red flag: you skip",
                "A first date that isn't dinner",
                "I'm looking for someone who",
                "You should not go out with me if",
                "My love language is basically",
                "Together we could finally",
            )),
            PromptCategory(id = "fun", icon = "face.smiling.inverse", questions = listOf(
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
            PromptCategory(id = "deep", icon = "sparkles", questions = listOf(
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

        private val known: Set<String> by lazy { questions.toSet() }

        /** A library question in the app's language (questions not in the library are shown as is). */
        fun text(question: String): String = if (question in known) L(question) else question
    }
}

/** Quick facts: lifestyle answers, saved as their English option ("Early bird", "Never"...). */
data class Vitals(
    val drinks: String,
    val smokes: String,
    val diet: String,
    val chronotype: String,
) {
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
            "Sometimes",
            "Yes",
        )

        /**
         * Lifestyle answers are saved as their English option, which stays their identity; this is the
         * text to show. Anything else (free text) is shown as is.
         */
        fun label(value: String): String = if (value in options) L(value) else value
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

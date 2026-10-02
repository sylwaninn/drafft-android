package so.drafft.core.ui.image

import androidx.annotation.DrawableRes
import so.drafft.core.ui.R

/**
 * The photos shipped in the app (welcome slideshow, sport posters, the pack photos, the launch wordmark), by name.
 * Models and the server refer to them by name ("hero_1", `Sport.posterImage`, `PackPhotos`); this turns a name into
 * the drawable.
 */
object BundledImages {
    private val table: Map<String, Int> = mapOf(
        "hero_1" to R.drawable.hero_1,
        "hero_2" to R.drawable.hero_2,
        "hero_3" to R.drawable.hero_3,
        "hero_4" to R.drawable.hero_4,
        "hero_5" to R.drawable.hero_5,
        "hero_6" to R.drawable.hero_6,
        "launch_wordmark" to R.drawable.launch_wordmark,
        "pack_man_1" to R.drawable.pack_man_1,
        "pack_man_2" to R.drawable.pack_man_2,
        "pack_man_3" to R.drawable.pack_man_3,
        "pack_woman_1" to R.drawable.pack_woman_1,
        "pack_woman_2" to R.drawable.pack_woman_2,
        "pack_woman_3" to R.drawable.pack_woman_3,
        "sport_abs" to R.drawable.sport_abs,
        "sport_barbell" to R.drawable.sport_barbell,
        "sport_bike" to R.drawable.sport_bike,
        "sport_boulder" to R.drawable.sport_boulder,
        "sport_clay" to R.drawable.sport_clay,
        "sport_groupnight" to R.drawable.sport_groupnight,
        "sport_hike" to R.drawable.sport_hike,
        "sport_peloton" to R.drawable.sport_peloton,
        "sport_pullup" to R.drawable.sport_pullup,
        "sport_ropes" to R.drawable.sport_ropes,
        "sport_stairs" to R.drawable.sport_stairs,
        "sport_sunsetrun" to R.drawable.sport_sunsetrun,
        "sport_swim" to R.drawable.sport_swim,
        "sport_tennis" to R.drawable.sport_tennis,
        "sport_trail" to R.drawable.sport_trail,
        "sport_yoga" to R.drawable.sport_yoga,
        "sport_yogasunset" to R.drawable.sport_yogasunset,
    )

    /** The drawable for a bundled image name, or null when the name is a link or unknown. */
    @DrawableRes
    fun resource(name: String): Int? = table[name]

    fun isBundled(name: String): Boolean = name in table
}

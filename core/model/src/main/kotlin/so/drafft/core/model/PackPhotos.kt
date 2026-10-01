package so.drafft.core.model

import kotlin.random.Random

/**
 * Photos made ahead of time of athletes mid-effort (HYROX, running, climbing...), diverse in origin,
 * skin and build. Never real users. Two uses:
 * - the pile you stand out from in the boost, super like and likes sheets: people like you
 *   ([DiscoverFilters.audienceOf] me);
 * - Discover's empty stack: the people you're looking for (`filters.audience`).
 */
object PackPhotos {
    /** Where the person looks or turns in the frame. */
    enum class Facing { LEFT, FRONT, RIGHT }

    /** What the body is doing: a pile reads natural when poses differ. */
    enum class Pose { RESTING, MOVING, CLOSE_UP }

    /** Where and how they train: a pile never shows the same kind of sport twice when it can. */
    enum class Setting { GYM, CLIMBING, CYCLING, SKIING, RUNNING }

    data class Shot(
        /** A drawable name in core:ui. */
        val name: String,
        val isWoman: Boolean,
        /** An abstract appearance group, only compared for equality: two shots in the same group look alike. */
        val look: Int,
        val facing: Facing,
        val pose: Pose,
        val setting: Setting,
        /** Coarse lightness of skin, only used so two people of the same gender in one pile never read alike. */
        val light: Boolean = false,
        /** Only used when the pool has nothing better: a look-alike of a preferred shot. */
        val spare: Boolean = false,
        /** In the pile for non-binary and everyone: a woman skiing, a man lifting, a woman running. */
        val mixed: Boolean = false,
    )

    val shots: List<Shot> = listOf(
        Shot("pack_man_1", isWoman = false, look = 2, facing = Facing.LEFT, pose = Pose.MOVING, setting = Setting.GYM, light = true, mixed = true),
        Shot("pack_man_2", isWoman = false, look = 6, facing = Facing.LEFT, pose = Pose.RESTING, setting = Setting.CYCLING),
        Shot("pack_man_3", isWoman = false, look = 7, facing = Facing.FRONT, pose = Pose.MOVING, setting = Setting.RUNNING, light = true),
        Shot("pack_woman_1", isWoman = true, look = 3, facing = Facing.LEFT, pose = Pose.MOVING, setting = Setting.CLIMBING, light = true),
        Shot("pack_woman_2", isWoman = true, look = 5, facing = Facing.FRONT, pose = Pose.CLOSE_UP, setting = Setting.SKIING, light = true, mixed = true),
        Shot("pack_woman_3", isWoman = true, look = 4, facing = Facing.FRONT, pose = Pose.MOVING, setting = Setting.RUNNING, mixed = true),
    )

    /**
     * A fresh pick for an audience, never two of the same kind of sport (fewer than [count] if the pool
     * can't), otherwise as varied as it allows (different looks first, then different facings and poses),
     * in an order where neighbours differ. Non-binary and everyone mix women and men.
     */
    fun pick(audience: Audience, count: Int = 3, random: Random = Random.Default): List<String> {
        val pool = shots.filter { s ->
            when (audience) {
                Audience.WOMEN -> s.isWoman
                Audience.MEN -> !s.isWoman
                Audience.NON_BINARY, Audience.EVERYONE -> s.mixed
            }
        }
        val mixes = audience == Audience.NON_BINARY || audience == Audience.EVERYONE
        val picked = mutableListOf<Shot>()
        val rest = pool.shuffled(random).toMutableList()
        // Never the same kind of sport twice: a smaller pile rather than a repeat.
        while (picked.size < count) {
            val best = rest.indices
                .filter { i -> picked.none { it.setting == rest[i].setting } }
                .maxByOrNull { score(rest[it], picked, mixes) } ?: break
            picked += rest.removeAt(best)
        }
        return picked.map { it.name }
    }

    /**
     * Higher is better: (mixed piles) the other gender first, then for a second person of the same gender
     * a different skin lightness, then a new look, a new facing, a new pose, and a preferred shot over its spare.
     */
    private fun score(s: Shot, picked: List<Shot>, mixes: Boolean): Int {
        var v = if (s.spare) 0 else 1
        val last = picked.lastOrNull()
        if (mixes && last != null && last.isWoman != s.isWoman) v += 32
        val twin = picked.firstOrNull { it.isWoman == s.isWoman }
        if (twin != null && twin.light != s.light) v += 24
        if (picked.none { it.look == s.look }) v += 16
        if (last?.facing != s.facing) v += 8
        if (picked.none { it.pose == s.pose }) v += 4
        return v
    }
}

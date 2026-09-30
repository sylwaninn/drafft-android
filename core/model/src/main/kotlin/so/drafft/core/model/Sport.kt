package so.drafft.core.model

/**
 * Every sport in drafft, in one place. Pickers (sign-up, Edit profile, Filters) all read this list,
 * and each sport has its own symbol (never shared with another sport). `id` is the server's value
 * (the iPhone app's raw value). `symbol` is the SF Symbol name, drawn by `DrafftIcon` in core:ui.
 */
enum class Sport(
    val id: String,
    private val nameKey: String,
    val symbol: String,
    private val verbingKey: String,
    /** Stock photo used as the header of a sport block (a drawable name in core:ui). */
    val posterImage: String,
) {
    RUNNING("running", "Running", "steps-outline", "running", "sport_sunsetrun"),
    TRAIL("trail", "Trail", "landscape-2-outline", "trail running", "sport_trail"),
    WALKING("walking", "Walking", "walking", "walking", "sport_hike"),
    HIKING("hiking", "Hiking", "hiking", "hiking", "sport_hike"),
    CYCLING("cycling", "Cycling", "bicycling", "cycling", "sport_peloton"),
    SPINNING("spinning", "Indoor cycling", "directions-bike", "spinning", "sport_peloton"),
    MOUNTAIN_BIKING("mountainBiking", "Mountain biking", "pedal-bike-outline", "mountain biking", "sport_bike"),
    RUN_CLUB("runClub", "Run club", "users-group-rounded", "at run club", "sport_groupnight"),
    ULTRA("ultra", "Ultra running", "infinite", "running long", "sport_trail"),
    GRAVEL("gravel", "Gravel", "routing", "riding gravel", "sport_bike"),
    OBSTACLE_RACE("obstacleRace", "Obstacle racing", "fence", "racing", "sport_ropes"),
    STAIR_CLIMBING("stairClimbing", "Stepper", "stairs-2", "on the stepper", "sport_stairs"),
    SWIMMING("swimming", "Swimming", "swimming", "swimming", "sport_swim"),
    OPEN_WATER("openWater", "Open water", "water-sun", "open-water swimming", "sport_swim"),
    TRIATHLON("triathlon", "Triathlon", "medal-ribbons-star", "training", "sport_swim"),
    ROWING("rowing", "Rowing", "rowing", "rowing", "sport_ropes"),
    SURFING("surfing", "Surfing", "surfing", "surfing", "sport_swim"),
    SAILING("sailing", "Sailing", "sailing-outline", "sailing", "sport_swim"),
    SKATEBOARDING("skateboarding", "Skateboarding", "skateboarding", "skating", "sport_groupnight"),
    KITESURF("kitesurf", "Kitesurf", "kitesurfing-outline", "kiting", "sport_swim"),
    WING_FOIL("wingFoil", "Wing foil", "wind", "foiling", "sport_swim"),
    PADDLE_BOARD("paddleBoard", "Paddleboard", "water", "paddling", "sport_swim"),
    KAYAK("kayak", "Kayak", "kayaking", "kayaking", "sport_swim"),
    SKIING("skiing", "Skiing", "downhill-skiing-outline", "skiing", "sport_hike"),
    CROSS_COUNTRY_SKI("crossCountrySki", "Cross-country skiing", "nordic-walking", "cross-country skiing", "sport_hike"),
    SNOWBOARDING("snowboarding", "Snowboarding", "snowboarding", "snowboarding", "sport_hike"),
    SKI_TOURING("skiTouring", "Ski touring", "snowflake", "ski touring", "sport_hike"),
    ICE_SKATING("iceSkating", "Ice skating", "ice-skating-outline", "skating", "sport_groupnight"),
    HYROX("hyrox", "Hyrox", "stopwatch", "training for Hyrox", "sport_ropes"),
    STRENGTH("strength", "Strength", "dumbbell-large", "lifting", "sport_barbell"),
    FUNCTIONAL("functional", "Functional training", "weight-outline", "training", "sport_ropes"),
    CROSSFIT("crossfit", "CrossFit", "fitness-center", "at CrossFit", "sport_ropes"),
    HIIT("hiit", "HIIT", "heart-pulse", "doing HIIT", "sport_abs"),
    CALISTHENICS("calisthenics", "Calisthenics", "accessibility-new", "doing calisthenics", "sport_pullup"),
    JUMP_ROPE("jumpRope", "Jump rope", "jump-rope", "skipping", "sport_abs"),
    PARKOUR("parkour", "Parkour", "sprint", "doing parkour", "sport_stairs"),
    YOGA("yoga", "Yoga", "meditation", "doing yoga", "sport_yogasunset"),
    HOT_YOGA("hotYoga", "Hot yoga", "temperature", "at hot yoga", "sport_yoga"),
    PILATES("pilates", "Pilates", "stretching", "doing Pilates", "sport_yoga"),
    REFORMER("reformer", "Reformer Pilates", "physical-therapy", "on the reformer", "sport_yoga"),
    BARRE("barre", "Barre", "body-shape", "at barre", "sport_yoga"),
    MOBILITY("mobility", "Mobility", "accessibility", "stretching", "sport_yoga"),
    TAICHI("taichi", "Tai chi", "taichi", "doing tai chi", "sport_yogasunset"),
    DANCE("dance", "Dance", "music-notes", "dancing", "sport_groupnight"),
    GYMNASTICS("gymnastics", "Gymnastics", "sports-gymnastics", "doing gymnastics", "sport_abs"),
    BOXING("boxing", "Boxing", "sports-mma-outline", "boxing", "sport_barbell"),
    KICKBOXING("kickboxing", "Kickboxing", "sports-martial-arts", "kickboxing", "sport_barbell"),
    MARTIAL_ARTS("martialArts", "Martial arts", "martial-arts", "training", "sport_barbell"),
    BJJ("bjj", "Jiu-jitsu", "sports-kabaddi", "rolling", "sport_barbell"),
    FENCING("fencing", "Fencing", "fencing", "fencing", "sport_groupnight"),
    PADEL("padel", "Padel", "padel", "playing padel", "sport_tennis"),
    TENNIS("tennis", "Tennis", "tennis", "playing tennis", "sport_clay"),
    BEACH_TENNIS("beachTennis", "Beach tennis", "sun", "playing beach tennis", "sport_tennis"),
    BADMINTON("badminton", "Badminton", "badminton", "playing badminton", "sport_tennis"),
    SQUASH("squash", "Squash", "sports-tennis-outline", "playing squash", "sport_tennis"),
    TABLE_TENNIS("tableTennis", "Table tennis", "table-tennis", "playing ping-pong", "sport_tennis"),
    PICKLEBALL("pickleball", "Pickleball", "pickleball", "playing pickleball", "sport_clay"),
    FOOTBALL("football", "Football", "football", "playing football", "sport_groupnight"),
    BASKETBALL("basketball", "Basketball", "basketball", "playing basketball", "sport_groupnight"),
    VOLLEYBALL("volleyball", "Volleyball", "volleyball", "playing volleyball", "sport_groupnight"),
    BEACH_VOLLEY("beachVolley", "Beach volley", "umbrella", "playing beach volley", "sport_groupnight"),
    RUGBY("rugby", "Rugby", "rugby", "playing rugby", "sport_groupnight"),
    HANDBALL("handball", "Handball", "sports-handball", "playing handball", "sport_groupnight"),
    HOCKEY("hockey", "Hockey", "sports-hockey", "playing hockey", "sport_groupnight"),
    ULTIMATE("ultimate", "Ultimate frisbee", "frisbee", "playing ultimate", "sport_groupnight"),
    SPIKEBALL("spikeball", "Spikeball", "spikeball", "playing spikeball", "sport_groupnight"),
    CLIMBING("climbing", "Climbing", "carabiner", "climbing", "sport_boulder"),
    BOULDERING("bouldering", "Bouldering", "boulder", "bouldering", "sport_boulder"),
    GOLF("golf", "Golf", "golf", "golfing", "sport_hike"),
    EQUESTRIAN("equestrian", "Horse riding", "horseshoe", "riding", "sport_hike"),
    ARCHERY("archery", "Archery", "target", "at the range", "sport_hike");

    val displayName: String get() = L(nameKey)

    /** "running", "on the bike"... for sentences like "you're usually out running". */
    val verbing: String get() = L(verbingKey)

    /**
     * The name inside a sentence ("easy padel session"): lowercased in the app's language, except in
     * German where nouns keep their capital.
     */
    val inSentence: String
        get() = if (Localization.language == AppLanguage.DE) displayName else displayName.lowercase(appLocale)

    /** Case- and accent-insensitive match for search fields. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || displayName.foldForSearch().contains(q.foldForSearch())
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String?): Sport? = id?.let(byId::get)
    }
}

/** Lowercased, accents removed: "Équitation" matches "equi". */
fun String.foldForSearch(): String =
    java.text.Normalizer.normalize(lowercase(appLocale), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")


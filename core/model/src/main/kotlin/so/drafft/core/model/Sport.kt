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
    RUNNING("running", "Running", "figure.run", "running", "sport_sunsetrun"),
    TRAIL("trail", "Trail", "figure.hiking", "trail running", "sport_trail"),
    WALKING("walking", "Walking", "figure.walk", "walking", "sport_hike"),
    HIKING("hiking", "Hiking", "mountain.2", "hiking", "sport_hike"),
    CYCLING("cycling", "Cycling", "figure.outdoor.cycle", "cycling", "sport_peloton"),
    SPINNING("spinning", "Indoor cycling", "figure.indoor.cycle", "spinning", "sport_peloton"),
    MOUNTAIN_BIKING("mountainBiking", "Mountain biking", "bicycle", "mountain biking", "sport_bike"),
    RUN_CLUB("runClub", "Run club", "person.3.fill", "at run club", "sport_groupnight"),
    ULTRA("ultra", "Ultra running", "infinity", "running long", "sport_trail"),
    GRAVEL("gravel", "Gravel", "road.lanes", "riding gravel", "sport_bike"),
    OBSTACLE_RACE("obstacleRace", "Obstacle racing", "flag.2.crossed.fill", "racing", "sport_ropes"),
    STAIR_CLIMBING("stairClimbing", "Stepper", "figure.stair.stepper", "on the stepper", "sport_stairs"),
    SWIMMING("swimming", "Swimming", "figure.pool.swim", "swimming", "sport_swim"),
    OPEN_WATER("openWater", "Open water", "figure.open.water.swim", "open-water swimming", "sport_swim"),
    TRIATHLON("triathlon", "Triathlon", "medal", "training", "sport_swim"),
    ROWING("rowing", "Rowing", "figure.rower", "rowing", "sport_ropes"),
    SURFING("surfing", "Surfing", "figure.surfing", "surfing", "sport_swim"),
    SAILING("sailing", "Sailing", "sailboat.fill", "sailing", "sport_swim"),
    SKATEBOARDING("skateboarding", "Skateboarding", "figure.skateboarding", "skating", "sport_groupnight"),
    KITESURF("kitesurf", "Kitesurf", "wind", "kiting", "sport_swim"),
    WING_FOIL("wingFoil", "Wing foil", "water.waves", "foiling", "sport_swim"),
    PADDLE_BOARD("paddleBoard", "Paddleboard", "figure.water.fitness", "paddling", "sport_swim"),
    KAYAK("kayak", "Kayak", "oar.2.crossed", "kayaking", "sport_swim"),
    SKIING("skiing", "Skiing", "figure.skiing.downhill", "skiing", "sport_hike"),
    CROSS_COUNTRY_SKI("crossCountrySki", "Cross-country skiing", "figure.skiing.crosscountry", "cross-country skiing", "sport_hike"),
    SNOWBOARDING("snowboarding", "Snowboarding", "figure.snowboarding", "snowboarding", "sport_hike"),
    SKI_TOURING("skiTouring", "Ski touring", "snowflake", "ski touring", "sport_hike"),
    ICE_SKATING("iceSkating", "Ice skating", "figure.skating", "skating", "sport_groupnight"),
    HYROX("hyrox", "Hyrox", "stopwatch.fill", "training for Hyrox", "sport_ropes"),
    STRENGTH("strength", "Strength", "figure.strengthtraining.traditional", "lifting", "sport_barbell"),
    FUNCTIONAL("functional", "Functional training", "figure.strengthtraining.functional", "training", "sport_ropes"),
    CROSSFIT("crossfit", "CrossFit", "figure.cross.training", "at CrossFit", "sport_ropes"),
    HIIT("hiit", "HIIT", "figure.highintensity.intervaltraining", "doing HIIT", "sport_abs"),
    CALISTHENICS("calisthenics", "Calisthenics", "figure.core.training", "doing calisthenics", "sport_pullup"),
    JUMP_ROPE("jumpRope", "Jump rope", "figure.jumprope", "skipping", "sport_abs"),
    PARKOUR("parkour", "Parkour", "figure.stairs", "doing parkour", "sport_stairs"),
    YOGA("yoga", "Yoga", "figure.yoga", "doing yoga", "sport_yogasunset"),
    HOT_YOGA("hotYoga", "Hot yoga", "thermometer.sun.fill", "at hot yoga", "sport_yoga"),
    PILATES("pilates", "Pilates", "figure.pilates", "doing Pilates", "sport_yoga"),
    REFORMER("reformer", "Reformer Pilates", "figure.flexibility", "on the reformer", "sport_yoga"),
    BARRE("barre", "Barre", "figure.barre", "at barre", "sport_yoga"),
    MOBILITY("mobility", "Mobility", "figure.mind.and.body", "stretching", "sport_yoga"),
    TAICHI("taichi", "Tai chi", "figure.taichi", "doing tai chi", "sport_yogasunset"),
    DANCE("dance", "Dance", "figure.dance", "dancing", "sport_groupnight"),
    GYMNASTICS("gymnastics", "Gymnastics", "figure.gymnastics", "doing gymnastics", "sport_abs"),
    BOXING("boxing", "Boxing", "figure.boxing", "boxing", "sport_barbell"),
    KICKBOXING("kickboxing", "Kickboxing", "figure.kickboxing", "kickboxing", "sport_barbell"),
    MARTIAL_ARTS("martialArts", "Martial arts", "figure.martial.arts", "training", "sport_barbell"),
    BJJ("bjj", "Jiu-jitsu", "figure.wrestling", "rolling", "sport_barbell"),
    FENCING("fencing", "Fencing", "figure.fencing", "fencing", "sport_groupnight"),
    PADEL("padel", "Padel", "figure.racquetball", "playing padel", "sport_tennis"),
    TENNIS("tennis", "Tennis", "figure.tennis", "playing tennis", "sport_clay"),
    BEACH_TENNIS("beachTennis", "Beach tennis", "sun.max.fill", "playing beach tennis", "sport_tennis"),
    BADMINTON("badminton", "Badminton", "figure.badminton", "playing badminton", "sport_tennis"),
    SQUASH("squash", "Squash", "figure.squash", "playing squash", "sport_tennis"),
    TABLE_TENNIS("tableTennis", "Table tennis", "figure.table.tennis", "playing ping-pong", "sport_tennis"),
    PICKLEBALL("pickleball", "Pickleball", "figure.pickleball", "playing pickleball", "sport_clay"),
    FOOTBALL("football", "Football", "figure.soccer", "playing football", "sport_groupnight"),
    BASKETBALL("basketball", "Basketball", "figure.basketball", "playing basketball", "sport_groupnight"),
    VOLLEYBALL("volleyball", "Volleyball", "figure.volleyball", "playing volleyball", "sport_groupnight"),
    BEACH_VOLLEY("beachVolley", "Beach volley", "beach.umbrella", "playing beach volley", "sport_groupnight"),
    RUGBY("rugby", "Rugby", "figure.rugby", "playing rugby", "sport_groupnight"),
    HANDBALL("handball", "Handball", "figure.handball", "playing handball", "sport_groupnight"),
    HOCKEY("hockey", "Hockey", "figure.hockey", "playing hockey", "sport_groupnight"),
    ULTIMATE("ultimate", "Ultimate frisbee", "figure.disc.sports", "playing ultimate", "sport_groupnight"),
    SPIKEBALL("spikeball", "Spikeball", "circle.grid.cross.fill", "playing spikeball", "sport_groupnight"),
    CLIMBING("climbing", "Climbing", "figure.climbing", "climbing", "sport_boulder"),
    BOULDERING("bouldering", "Bouldering", "mountain.2.fill", "bouldering", "sport_boulder"),
    GOLF("golf", "Golf", "figure.golf", "golfing", "sport_hike"),
    EQUESTRIAN("equestrian", "Horse riding", "figure.equestrian.sports", "riding", "sport_hike"),
    ARCHERY("archery", "Archery", "figure.archery", "at the range", "sport_hike");

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


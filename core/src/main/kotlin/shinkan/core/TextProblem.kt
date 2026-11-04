package shinkan.core

/**
 * What is wrong with a line of the policy or the block list as text (SPEC §4.4, §6.4).
 * A key and not a sentence: the words are the app's, one string per language, and
 * the app's `when` over this names every one.
 */
enum class TextProblem {
    AFTER_MINUTES,
    ALLOW,
    BUDGET_MINUTES,
    CATEGORY,
    CLOSE,
    CREATOR,
    HISTORY,
    HOURS,
    MIRROR,
    MODE,
    PASS,
    PERMISSION_KIND,
    PERMISSION_SHAPE,
    PLAN_DAYS,
    PLAN_WITHOUT_STAGES,
    PLAYER,
    REST,
    RESTRICT,
    SITE,
    STAGE_OUTSIDE_PLAN,
    STAGE_SHAPE,
    START,
    UNKNOWN_CATEGORY,
    UNKNOWN_PLATFORM,
    UNKNOWN_RESTRICTION,
    UNKNOWN_SEGMENT,
    UNKNOWN_STATEMENT,
    VOLUME_RANGE,
    VOLUME_SHAPE,
    WINDOW,
    ZERO_HOURS,
    UNKNOWN_DOOR,
    UNKNOWN_DAY,
    DAY_REPEATED,
    DOOR_REPEATED,
}

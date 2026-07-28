package com.nacon01.kunekune

import android.content.Context
import java.math.BigDecimal

object InterventionPreferences {
    private const val PREFERENCES_NAME = "intervention_preferences"
    private const val KEY_ARRIVAL_BEHAVIOR = "arrival_behavior"
    private const val KEY_FADE_TO_BLACK_SECONDS = "fade_to_black_seconds"
    private const val KEY_VIEWING_TARGET = "viewing_target"
    private const val KEY_VIEWING_THRESHOLD_MINUTES = "viewing_threshold_minutes"
    private const val KEY_VIEWING_THRESHOLD_SECONDS = "viewing_threshold_seconds"
    private const val KEY_PROGRESS_REWARD_CENTIMETERS = "progress_reward_centimeters"
    private const val KEY_PIP_SETUP_GUIDANCE_PREFIX = "pip_setup_guidance_"
    private const val KEY_ARRIVAL_FADE_MINUTES = "arrival_fade_minutes"
    private const val KEY_LEAVE_DESTINATION_FADE_MINUTES = "leave_destination_fade_minutes"

    fun arrivalBehavior(context: Context): ArrivalBehavior {
        val stored = preferences(context).getString(KEY_ARRIVAL_BEHAVIOR, null)
        return ArrivalBehavior.entries.firstOrNull { it.name == stored }
            ?: ArrivalBehavior.FADE_OUT
    }

    fun setArrivalBehavior(context: Context, behavior: ArrivalBehavior) {
        preferences(context).edit().putString(KEY_ARRIVAL_BEHAVIOR, behavior.name).apply()
    }

    fun fadeToBlackSeconds(context: Context): Int {
        val stored = preferences(context).getInt(KEY_FADE_TO_BLACK_SECONDS, DEFAULT_FADE_SECONDS)
        return stored.takeIf { it in FADE_MIN_SECONDS..FADE_MAX_SECONDS } ?: DEFAULT_FADE_SECONDS
    }

    fun setFadeToBlackSeconds(context: Context, seconds: Int): Int {
        val value = seconds.coerceIn(FADE_MIN_SECONDS, FADE_MAX_SECONDS)
        preferences(context).edit().putInt(KEY_FADE_TO_BLACK_SECONDS, value).apply()
        return value
    }

    fun adjustFadeToBlackSeconds(context: Context, delta: Int): Int =
        setFadeToBlackSeconds(context, fadeToBlackSeconds(context) + delta)

    fun viewingThresholdSeconds(context: Context): Int {
        val preferences = preferences(context)
        if (preferences.contains(KEY_VIEWING_THRESHOLD_SECONDS)) {
            val stored = preferences.getInt(
                KEY_VIEWING_THRESHOLD_SECONDS,
                DEFAULT_THRESHOLD_SECONDS
            )
            return stored.takeIf(::isValidViewingThresholdSeconds)
                ?: DEFAULT_THRESHOLD_SECONDS
        }
        val legacyMinutes = preferences.getInt(
            KEY_VIEWING_THRESHOLD_MINUTES,
            DEFAULT_THRESHOLD_MINUTES
        )
        val migrated = legacyMinutes
            .takeIf { it in LEGACY_THRESHOLD_MIN_MINUTES..THRESHOLD_MAX_MINUTES }
            ?.times(60)
            ?: DEFAULT_THRESHOLD_SECONDS
        preferences.edit().putInt(KEY_VIEWING_THRESHOLD_SECONDS, migrated).apply()
        return migrated
    }

    fun setViewingThresholdSeconds(context: Context, seconds: Int): Int {
        val value = seconds.takeIf(::isValidViewingThresholdSeconds)
            ?: nearestViewingThresholdSeconds(seconds)
        preferences(context).edit().putInt(KEY_VIEWING_THRESHOLD_SECONDS, value).apply()
        return value
    }

    fun adjustViewingThreshold(context: Context, direction: Int): Int {
        val current = viewingThresholdSeconds(context)
        return setViewingThresholdSeconds(
            context,
            nextViewingThresholdSeconds(current, direction)
        )
    }

    fun nextViewingThresholdSeconds(current: Int, direction: Int): Int =
        when {
            direction < 0 && current <= 60 -> current - 10
            direction < 0 -> current - 60
            direction > 0 && current < 60 -> current + 10
            direction > 0 -> current + 60
            else -> current
        }.coerceIn(THRESHOLD_MIN_SECONDS, THRESHOLD_MAX_SECONDS)

    fun formatViewingThreshold(seconds: Int): String =
        if (seconds < 60) "${seconds}秒" else "${seconds / 60}分"

    fun progressRewardCentimeters(context: Context): Float {
        val stored = preferences(context).getFloat(
            KEY_PROGRESS_REWARD_CENTIMETERS,
            DEFAULT_PROGRESS_REWARD_CENTIMETERS
        )
        return stored.takeIf(::isValidProgressRewardCentimeters)
            ?: DEFAULT_PROGRESS_REWARD_CENTIMETERS
    }

    fun setProgressRewardCentimeters(context: Context, centimeters: Float): Float {
        val value = centimeters.coerceIn(
            MIN_PROGRESS_REWARD_CENTIMETERS,
            MAX_PROGRESS_REWARD_CENTIMETERS
        )
        preferences(context).edit()
            .putFloat(KEY_PROGRESS_REWARD_CENTIMETERS, value)
            .apply()
        return value
    }

    fun formatProgressRewardCentimeters(centimeters: Float): String =
        BigDecimal(centimeters.toString()).stripTrailingZeros().toPlainString()

    fun arrivalFadeMinutes(context: Context): Int = readMinutes(
        context,
        KEY_ARRIVAL_FADE_MINUTES,
        DEFAULT_ARRIVAL_FADE_MINUTES
    )

    fun setArrivalFadeMinutes(context: Context, minutes: Int): Int = setMinutes(
        context,
        KEY_ARRIVAL_FADE_MINUTES,
        minutes
    )

    fun adjustArrivalFadeMinutes(context: Context, delta: Int): Int =
        setArrivalFadeMinutes(context, arrivalFadeMinutes(context) + delta)

    fun leaveDestinationFadeMinutes(context: Context): Int = readMinutes(
        context,
        KEY_LEAVE_DESTINATION_FADE_MINUTES,
        DEFAULT_LEAVE_DESTINATION_FADE_MINUTES
    )

    fun setLeaveDestinationFadeMinutes(context: Context, minutes: Int): Int = setMinutes(
        context,
        KEY_LEAVE_DESTINATION_FADE_MINUTES,
        minutes
    )

    fun adjustLeaveDestinationFadeMinutes(context: Context, delta: Int): Int =
        setLeaveDestinationFadeMinutes(context, leaveDestinationFadeMinutes(context) + delta)

    fun isValidFadeToBlackSeconds(value: Int): Boolean =
        value in FADE_MIN_SECONDS..FADE_MAX_SECONDS

    fun isValidViewingThresholdSeconds(value: Int): Boolean =
        (value in THRESHOLD_MIN_SECONDS..60 && value % 10 == 0) ||
            (value in 120..THRESHOLD_MAX_SECONDS && value % 60 == 0)

    fun isValidProgressRewardCentimeters(value: Float): Boolean =
        value.isFinite() &&
            value in MIN_PROGRESS_REWARD_CENTIMETERS..MAX_PROGRESS_REWARD_CENTIMETERS

    fun isValidArrivalFadeMinutes(value: Int): Boolean =
        value in ARRIVAL_FADE_MIN_MINUTES..ARRIVAL_FADE_MAX_MINUTES

    fun isValidLeaveDestinationFadeMinutes(value: Int): Boolean =
        value in LEAVE_DESTINATION_FADE_MIN_MINUTES..LEAVE_DESTINATION_FADE_MAX_MINUTES

    const val FADE_MIN_SECONDS = 1
    const val FADE_MAX_SECONDS = 60
    const val DEFAULT_FADE_SECONDS = 30
    const val THRESHOLD_MIN_SECONDS = 10
    const val THRESHOLD_MAX_SECONDS = 120 * 60
    const val DEFAULT_THRESHOLD_SECONDS = 30 * 60
    const val MIN_PROGRESS_REWARD_CENTIMETERS = 0.5f
    const val MAX_PROGRESS_REWARD_CENTIMETERS = 300f
    const val DEFAULT_PROGRESS_REWARD_CENTIMETERS = 8f
    private const val LEGACY_THRESHOLD_MIN_MINUTES = 1
    const val THRESHOLD_MAX_MINUTES = 120
    const val DEFAULT_THRESHOLD_MINUTES = 30
    const val ARRIVAL_FADE_MIN_MINUTES = 1
    const val ARRIVAL_FADE_MAX_MINUTES = 120
    const val DEFAULT_ARRIVAL_FADE_MINUTES = 30
    const val LEAVE_DESTINATION_FADE_MIN_MINUTES = 1
    const val LEAVE_DESTINATION_FADE_MAX_MINUTES = 120
    const val DEFAULT_LEAVE_DESTINATION_FADE_MINUTES = 30

    fun fadeToBlackAtMinimum(context: Context): Boolean = fadeToBlackSeconds(context) == FADE_MIN_SECONDS
    fun fadeToBlackAtMaximum(context: Context): Boolean = fadeToBlackSeconds(context) == FADE_MAX_SECONDS
    fun viewingThresholdAtMinimum(context: Context): Boolean =
        viewingThresholdSeconds(context) == THRESHOLD_MIN_SECONDS
    fun viewingThresholdAtMaximum(context: Context): Boolean =
        viewingThresholdSeconds(context) == THRESHOLD_MAX_SECONDS

    fun arrivalFadeAtMinimum(context: Context): Boolean =
        arrivalFadeMinutes(context) == ARRIVAL_FADE_MIN_MINUTES
    fun arrivalFadeAtMaximum(context: Context): Boolean =
        arrivalFadeMinutes(context) == ARRIVAL_FADE_MAX_MINUTES
    fun leaveDestinationFadeAtMinimum(context: Context): Boolean =
        leaveDestinationFadeMinutes(context) == LEAVE_DESTINATION_FADE_MIN_MINUTES
    fun leaveDestinationFadeAtMaximum(context: Context): Boolean =
        leaveDestinationFadeMinutes(context) == LEAVE_DESTINATION_FADE_MAX_MINUTES

    fun viewingTarget(context: Context): ViewingTarget {
        val stored = preferences(context).getString(KEY_VIEWING_TARGET, null)
        return ViewingTarget.entries.firstOrNull { it.name == stored } ?: ViewingTarget.BROWSER
    }

    fun cycleViewingTarget(context: Context): ViewingTarget {
        val next = if (viewingTarget(context) == ViewingTarget.YOUTUBE_APP) {
            ViewingTarget.BROWSER
        } else {
            ViewingTarget.YOUTUBE_APP
        }
        preferences(context).edit().putString(KEY_VIEWING_TARGET, next.name).apply()
        return next
    }

    fun isPictureInPictureSetupGuidanceShown(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return preferences(context).getBoolean(pictureInPictureSetupGuidanceKey(packageName), false)
    }

    fun markPictureInPictureSetupGuidanceShown(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        preferences(context).edit()
            .putBoolean(pictureInPictureSetupGuidanceKey(packageName), true)
            .apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun pictureInPictureSetupGuidanceKey(packageName: String): String =
        "$KEY_PIP_SETUP_GUIDANCE_PREFIX$packageName"

    private fun readMinutes(context: Context, key: String, default: Int): Int {
        val stored = preferences(context).getInt(key, default)
        return stored.takeIf { it in ARRIVAL_FADE_MIN_MINUTES..ARRIVAL_FADE_MAX_MINUTES } ?: default
    }

    private fun setMinutes(context: Context, key: String, minutes: Int): Int {
        val value = minutes.coerceIn(ARRIVAL_FADE_MIN_MINUTES, ARRIVAL_FADE_MAX_MINUTES)
        preferences(context).edit().putInt(key, value).apply()
        return value
    }

    private fun nearestViewingThresholdSeconds(seconds: Int): Int {
        val clamped = seconds.coerceIn(THRESHOLD_MIN_SECONDS, THRESHOLD_MAX_SECONDS)
        return if (clamped <= 60) {
            ((clamped + 5) / 10 * 10).coerceIn(THRESHOLD_MIN_SECONDS, 60)
        } else {
            ((clamped + 30) / 60 * 60).coerceIn(120, THRESHOLD_MAX_SECONDS)
        }
    }
}

package com.nacon01.kunekune

import android.content.Context

object InterventionPreferences {
    private const val PREFERENCES_NAME = "intervention_preferences"
    private const val KEY_ARRIVAL_BEHAVIOR = "arrival_behavior"
    private const val KEY_FADE_TO_BLACK_SECONDS = "fade_to_black_seconds"
    private const val KEY_VIEWING_TARGET = "viewing_target"
    private const val KEY_VIEWING_THRESHOLD_MINUTES = "viewing_threshold_minutes"
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

    fun viewingThresholdMinutes(context: Context): Int {
        val stored = preferences(context).getInt(KEY_VIEWING_THRESHOLD_MINUTES, DEFAULT_THRESHOLD_MINUTES)
        return stored.takeIf { it in THRESHOLD_MIN_MINUTES..THRESHOLD_MAX_MINUTES }
            ?: DEFAULT_THRESHOLD_MINUTES
    }

    fun setViewingThresholdMinutes(context: Context, minutes: Int): Int {
        val value = minutes.coerceIn(THRESHOLD_MIN_MINUTES, THRESHOLD_MAX_MINUTES)
        preferences(context).edit().putInt(KEY_VIEWING_THRESHOLD_MINUTES, value).apply()
        return value
    }

    fun adjustViewingThresholdMinutes(context: Context, delta: Int): Int =
        setViewingThresholdMinutes(context, viewingThresholdMinutes(context) + delta)

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

    fun isValidViewingThresholdMinutes(value: Int): Boolean =
        value in THRESHOLD_MIN_MINUTES..THRESHOLD_MAX_MINUTES

    fun isValidArrivalFadeMinutes(value: Int): Boolean =
        value in ARRIVAL_FADE_MIN_MINUTES..ARRIVAL_FADE_MAX_MINUTES

    fun isValidLeaveDestinationFadeMinutes(value: Int): Boolean =
        value in LEAVE_DESTINATION_FADE_MIN_MINUTES..LEAVE_DESTINATION_FADE_MAX_MINUTES

    const val FADE_MIN_SECONDS = 1
    const val FADE_MAX_SECONDS = 60
    const val DEFAULT_FADE_SECONDS = 30
    const val THRESHOLD_MIN_MINUTES = 1
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
        viewingThresholdMinutes(context) == THRESHOLD_MIN_MINUTES
    fun viewingThresholdAtMaximum(context: Context): Boolean =
        viewingThresholdMinutes(context) == THRESHOLD_MAX_MINUTES

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

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun readMinutes(context: Context, key: String, default: Int): Int {
        val stored = preferences(context).getInt(key, default)
        return stored.takeIf { it in ARRIVAL_FADE_MIN_MINUTES..ARRIVAL_FADE_MAX_MINUTES } ?: default
    }

    private fun setMinutes(context: Context, key: String, minutes: Int): Int {
        val value = minutes.coerceIn(ARRIVAL_FADE_MIN_MINUTES, ARRIVAL_FADE_MAX_MINUTES)
        preferences(context).edit().putInt(key, value).apply()
        return value
    }

}

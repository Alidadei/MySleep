package org.fossify.clock.models

import androidx.annotation.Keep

/**
 * One "wake session": opens when an alarm starts ringing (or re-rings after a snooze),
 * closes when the user finally dismisses it. Used by the sleep report to show the
 * user's real wake-up rhythm.
 */
@Keep
data class SleepRecord(
    var id: Int,
    var alarmId: Int,
    var label: String,
    var scheduledMinutes: Int,
    var ringAtMillis: Long,
    var stoppedAtMillis: Long,
    var snoozed: Boolean,
)

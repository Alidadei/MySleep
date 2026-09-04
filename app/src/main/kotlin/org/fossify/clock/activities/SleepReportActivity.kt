package org.fossify.clock.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import org.fossify.clock.R
import org.fossify.clock.databinding.ActivitySleepReportBinding
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.models.SleepRecord
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shows the user's real wake-up rhythm, built from actual alarm dismissals:
 * average wake time, on-time rate and average oversleeping per wake session.
 */
class SleepReportActivity : SimpleActivity() {

    companion object {
        private const val RECORD_LIMIT = 30
        private const val ON_TIME_TOLERANCE_MIN = 5
    }

    private val binding by viewBinding(ActivitySleepReportBinding::inflate)
    private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupTopAppBar(binding.sleepReportAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.root)

        ensureBackgroundThread {
            val records = dbHelper.getRecentSleepRecords(RECORD_LIMIT)
            runOnUiThread {
                showRecords(records)
            }
        }
    }

    private fun showRecords(records: List<SleepRecord>) {
        binding.sleepReportEmpty.beGoneIf(records.isNotEmpty())

        if (records.isNotEmpty()) {
            val oversleepMinutes = records.map {
                ((it.stoppedAtMillis - it.ringAtMillis) / 60000L).coerceAtLeast(0L)
            }
            val avgOversleep =
                (oversleepMinutes.sum().toFloat() / oversleepMinutes.size).roundToInt()
            val onTimeCount = oversleepMinutes.count { it <= ON_TIME_TOLERANCE_MIN }
            val onTimePercent = (onTimeCount * 100f / records.size).roundToInt()
            val avgWake = avgWakeTime(records)

            binding.sleepReportSummary.text = getString(
                R.string.sleep_report_summary_fmt,
                records.size,
                onTimePercent,
                avgOversleep
            )
            binding.sleepReportAvgWake.text =
                getString(R.string.sleep_report_avg_wake_fmt, avgWake)
        }

        val inflater = LayoutInflater.from(this)
        records.forEach { record ->
            val rowView = inflater.inflate(
                R.layout.item_sleep_record, binding.sleepReportRecordsHolder, false
            ) as LinearLayout

            rowView.findViewById<org.fossify.commons.views.MyTextView>(R.id.sleep_record_row)
                .text = formatRecord(record)

            binding.sleepReportRecordsHolder.addView(rowView)
        }
    }

    /** Mean of the actual "out of bed" clock times, robust against midnight wrap-around. */
    private fun avgWakeTime(records: List<SleepRecord>): String {
        val calendar = Calendar.getInstance()
        val secondsSum = records.sumOf {
            calendar.timeInMillis = it.stoppedAtMillis
            calendar.get(Calendar.HOUR_OF_DAY) * 3600 + calendar.get(Calendar.MINUTE) * 60
        }
        val avgSeconds = secondsSum / records.size
        return String.format(
            Locale.getDefault(), "%02d:%02d", avgSeconds / 3600, avgSeconds % 3600 / 60
        )
    }

    private fun formatRecord(record: SleepRecord): String {
        val scheduled = timeFormat.format(
            Date(recordHourMillis(record.scheduledMinutes) + record.scheduledMinutes * 60000L)
        )
        val upAt = timeFormat.format(Date(record.stoppedAtMillis))
        val oversleepMin = ((record.stoppedAtMillis - record.ringAtMillis) / 60000L)
            .coerceAtLeast(0L).toInt()
        val label = record.label.ifEmpty { getString(org.fossify.commons.R.string.alarm) }
        val day = dateFormat.format(Date(record.stoppedAtMillis))

        return getString(
            R.string.sleep_record_row_fmt,
            day,
            label,
            scheduled,
            upAt,
            oversleepMin,
            if (record.snoozed) getString(R.string.sleep_record_snoozed) else ""
        ).replace("  ", " ").trim()
    }

    private fun recordHourMillis(minutesOfDay: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = 0
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }
        return calendar.timeInMillis
    }
}

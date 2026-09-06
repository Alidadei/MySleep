package org.fossify.clock.dialogs

import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.RingtoneManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.databinding.DialogEditAlarmBinding
import org.fossify.clock.extensions.checkAlarmsWithDeletedSoundUri
import org.fossify.clock.extensions.colorCompoundDrawable
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.rotateWeekdays
import org.fossify.clock.helpers.PICK_AUDIO_FILE_INTENT_ID
import org.fossify.clock.helpers.TorchHelper
import org.fossify.clock.helpers.TODAY_BIT
import org.fossify.clock.helpers.getCurrentDayMinutes
import org.fossify.clock.helpers.getTodayBit
import org.fossify.clock.helpers.getTomorrowBit
import org.fossify.clock.helpers.updateNonRecurringAlarmDay
import org.fossify.clock.models.Alarm
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.SelectAlarmSoundDialog
import org.fossify.commons.extensions.addBit
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getDefaultAlarmSound
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTimePickerDialogTheme
import org.fossify.commons.extensions.isDynamicTheme
import org.fossify.commons.extensions.removeBit
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.models.AlarmSound

class EditAlarmDialog(
    val activity: SimpleActivity,
    val alarm: Alarm,
    val onDismiss: () -> Unit = {},
    val callback: (alarmId: Int) -> Unit,
) {
    private val binding = DialogEditAlarmBinding.inflate(activity.layoutInflater)
    private val textColor = activity.getProperTextColor()

    init {
        restoreLastAlarm()
        updateAlarmTime()

        binding.apply {
            editAlarmTime.setOnClickListener {
                if (activity.isDynamicTheme()) {
                    val timeFormat = if (activity.config.use24HourFormat) {
                        TimeFormat.CLOCK_24H
                    } else {
                        TimeFormat.CLOCK_12H
                    }

                    val timePicker = MaterialTimePicker.Builder()
                        .setTimeFormat(timeFormat)
                        .setHour(alarm.timeInMinutes / 60)
                        .setMinute(alarm.timeInMinutes % 60)
                        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                        .build()

                    timePicker.addOnPositiveButtonClickListener {
                        timePicked(timePicker.hour, timePicker.minute)
                    }

                    timePicker.show(activity.supportFragmentManager, "")
                } else {
                    TimePickerDialog(
                        root.context,
                        root.context.getTimePickerDialogTheme(),
                        timeSetListener,
                        alarm.timeInMinutes / 60,
                        alarm.timeInMinutes % 60,
                        activity.config.use24HourFormat
                    ).show()
                }
            }

            editAlarmSound.colorCompoundDrawable(textColor)
            editAlarmSound.text = alarm.soundTitle
            editAlarmSound.setOnClickListener {
                SelectAlarmSoundDialog(
                    activity = activity,
                    currentUri = alarm.soundUri,
                    audioStream = AudioManager.STREAM_ALARM,
                    pickAudioIntentId = PICK_AUDIO_FILE_INTENT_ID,
                    type = RingtoneManager.TYPE_ALARM,
                    loopAudio = true,
                    onAlarmPicked = {
                        if (it != null) {
                            updateSelectedAlarmSound(it)
                        }
                    },
                    onAlarmSoundDeleted = {
                        if (alarm.soundUri == it.uri) {
                            val defaultAlarm =
                                root.context.getDefaultAlarmSound(RingtoneManager.TYPE_ALARM)
                            updateSelectedAlarmSound(defaultAlarm)
                        }
                        activity.checkAlarmsWithDeletedSoundUri(it.uri)
                    })
            }

            editAlarmVibrateIcon.setColorFilter(textColor)
            editAlarmVibrate.isChecked = alarm.vibrate
            editAlarmVibrateHolder.setOnClickListener {
                editAlarmVibrate.toggle()
                alarm.vibrate = editAlarmVibrate.isChecked
            }

            setupTypeToggle()
            setupLightWakeOptions()

            editAlarmLabelImage.applyColorFilter(textColor)
            editAlarm.setText(alarm.label)

            val dayLetters = activity.resources
                .getStringArray(org.fossify.commons.R.array.week_day_letters)
                .toCollection(ArrayList())
            val dayIndexes = activity.rotateWeekdays(arrayListOf(0, 1, 2, 3, 4, 5, 6))

            dayIndexes.forEach {
                val bitmask = 1 shl it
                val day = activity.layoutInflater.inflate(
                    R.layout.alarm_day, editAlarmDaysHolder, false
                ) as TextView
                day.text = dayLetters[it]

                val isDayChecked = alarm.isRecurring() && alarm.days and bitmask != 0
                day.background = getProperDayDrawable(isDayChecked)

                day.setTextColor(if (isDayChecked) root.context.getProperBackgroundColor() else textColor)
                day.setOnClickListener {
                    if (!alarm.isRecurring()) {
                        alarm.days = 0
                    }

                    val selectDay = alarm.days and bitmask == 0
                    if (selectDay) {
                        alarm.days = alarm.days.addBit(bitmask)
                    } else {
                        alarm.days = alarm.days.removeBit(bitmask)
                    }
                    day.background = getProperDayDrawable(selectDay)
                    day.setTextColor(if (selectDay) root.context.getProperBackgroundColor() else textColor)
                    checkDaylessAlarm()
                }

                editAlarmDaysHolder.addView(day)
            }
        }

        activity.getAlertDialogBuilder()
            .setOnDismissListener { onDismiss() }
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (!activity.config.wasAlarmWarningShown) {
                            ConfirmationDialog(
                                activity = activity,
                                messageId = org.fossify.commons.R.string.alarm_warning,
                                positive = org.fossify.commons.R.string.ok,
                                negative = 0
                            ) {
                                activity.config.wasAlarmWarningShown = true
                                it.performClick()
                            }

                            return@setOnClickListener
                        }

                        updateNonRecurringAlarmDay(alarm)

                        alarm.label = binding.editAlarm.value
                        alarm.isEnabled = true
                        alarm.oneShot = false

                        var alarmId = alarm.id
                        activity.handleFullScreenNotificationsPermission { granted ->
                            if (granted) {
                                if (alarm.id == 0) {
                                    alarmId = activity.dbHelper.insertAlarm(alarm)
                                    if (alarmId == -1) {
                                        activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                                    }
                                } else {
                                    if (!activity.dbHelper.updateAlarm(alarm)) {
                                        activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                                    }
                                }

                                activity.config.alarmLastConfig = alarm
                                callback(alarmId)
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    /**
     * Explicit "once / repeat" type switch. A once alarm is the existing dayless
     * behaviour: it rings today or tomorrow, then gets disabled.
     */
    private fun setupTypeToggle() {
        updateTypeToggleUI()

        binding.editAlarmTypeOnce.setOnClickListener {
            if (alarm.isRecurring()) {
                alarm.days = 0
                updateTypeToggleUI()
                checkDaylessAlarm()
            }
        }

        binding.editAlarmTypeRepeat.setOnClickListener {
            if (!alarm.isRecurring()) {
                alarm.days = when (alarm.days) {
                    TODAY_BIT -> getTodayBit()
                    else -> getTomorrowBit()
                }
                updateTypeToggleUI()
            }
        }
    }

    private fun updateTypeToggleUI() {
        val recurring = alarm.isRecurring()
        val context = binding.root.context

        binding.editAlarmTypeOnce.apply {
            background = context.getDrawable(
                if (!recurring) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            setTextColor(if (!recurring) Color.WHITE else textColor)
        }
        binding.editAlarmTypeRepeat.apply {
            background = context.getDrawable(
                if (recurring) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
            )
            setTextColor(if (recurring) Color.WHITE else textColor)
        }
        binding.editAlarmDaysHolder.beVisibleIf(recurring)
    }

    private fun setupLightWakeOptions() {
        if (!TorchHelper.hasFlash(activity)) {
            // no flash unit on this device, don't tease the user with light options
            binding.editAlarmTorchHolder.beGone()
            binding.editAlarmLightOnlyHolder.beGone()
            binding.editAlarmSunriseHolder.beGone()
            alarm.enableTorch = false
            alarm.lightOnly = false
            alarm.sunriseMinutes = 0
            return
        }

        binding.editAlarmTorchIcon.setColorFilter(textColor)
        binding.editAlarmTorch.isChecked = alarm.enableTorch
        binding.editAlarmTorchHolder.setOnClickListener {
            binding.editAlarmTorch.toggle()
            alarm.enableTorch = binding.editAlarmTorch.isChecked
            if (!alarm.enableTorch) {
                alarm.lightOnly = false
                binding.editAlarmLightOnly.isChecked = false
            }
        }

        binding.editAlarmLightOnlyIcon.setColorFilter(textColor)
        binding.editAlarmLightOnly.isChecked = alarm.lightOnly
        binding.editAlarmLightOnlyHolder.setOnClickListener {
            binding.editAlarmLightOnly.toggle()
            alarm.lightOnly = binding.editAlarmLightOnly.isChecked
            if (alarm.lightOnly && !alarm.enableTorch) {
                // a silent light-only alarm obviously needs the light on
                alarm.enableTorch = true
                binding.editAlarmTorch.isChecked = true
            }
        }

        binding.editAlarmSunriseIcon.setColorFilter(textColor)
        updateSunriseLabel()
        binding.editAlarmSunriseHolder.setOnClickListener {
            showSunrisePicker()
        }
    }

    private fun showSunrisePicker() {
        val sunriseOptions = listOf(0, 1, 5, 10, 20, 30)
        val labels = sunriseOptions.map { minutes ->
            if (minutes == 0) {
                activity.getString(R.string.sunrise_fade_off)
            } else {
                activity.getString(R.string.sunrise_fade_minutes, minutes)
            }
        }

        activity.getAlertDialogBuilder()
            .setTitle(R.string.sunrise_fade_in)
            .setSingleChoiceItems(
                labels.toTypedArray(),
                sunriseOptions.indexOf(alarm.sunriseMinutes).coerceAtLeast(0)
            ) { dialog, which ->
                alarm.sunriseMinutes = sunriseOptions[which]
                updateSunriseLabel()
                dialog?.dismiss()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun updateSunriseLabel() {
        binding.editAlarmSunriseLabel.text = if (alarm.sunriseMinutes > 0) {
            activity.getString(R.string.sunrise_fade_minutes, alarm.sunriseMinutes)
        } else {
            activity.getString(R.string.sunrise_fade_off)
        }
    }

    private fun restoreLastAlarm() {
        if (alarm.id == 0) {
            activity.config.alarmLastConfig?.let { lastConfig ->
                alarm.label = lastConfig.label
                alarm.days = lastConfig.days
                alarm.soundTitle = lastConfig.soundTitle
                alarm.soundUri = lastConfig.soundUri
                alarm.timeInMinutes = lastConfig.timeInMinutes
                alarm.vibrate = lastConfig.vibrate
                alarm.enableTorch = lastConfig.enableTorch
                alarm.lightOnly = lastConfig.lightOnly
                alarm.sunriseMinutes = lastConfig.sunriseMinutes
            }
        }
    }

    private val timeSetListener = TimePickerDialog.OnTimeSetListener { _, hourOfDay, minute ->
        timePicked(hourOfDay, minute)
    }

    private fun timePicked(hours: Int, minutes: Int) {
        alarm.timeInMinutes = hours * 60 + minutes
        updateAlarmTime()
    }

    private fun updateAlarmTime() {
        binding.editAlarmTime.text = activity.getFormattedTime(
            passedSeconds = alarm.timeInMinutes * 60,
            showSeconds = false,
            makeAmPmSmaller = true
        )
        checkDaylessAlarm()
    }

    private fun checkDaylessAlarm() {
        if (!alarm.isRecurring()) {
            val textId = if (alarm.timeInMinutes > getCurrentDayMinutes()) {
                org.fossify.commons.R.string.today
            } else {
                org.fossify.commons.R.string.tomorrow
            }

            binding.editAlarmDaylessLabel.text = "(${activity.getString(textId)})"
        }
        binding.editAlarmDaylessLabel.beVisibleIf(!alarm.isRecurring())
    }

    private fun getProperDayDrawable(selected: Boolean): Drawable {
        val drawableId = if (selected) {
            R.drawable.circle_background_filled
        } else {
            R.drawable.circle_background_stroke
        }

        val drawable = activity.resources.getDrawable(drawableId)
        drawable.applyColorFilter(textColor)
        return drawable
    }

    fun updateSelectedAlarmSound(alarmSound: AlarmSound) {
        alarm.soundTitle = alarmSound.title
        alarm.soundUri = alarmSound.uri
        binding.editAlarmSound.text = alarmSound.title
    }
}

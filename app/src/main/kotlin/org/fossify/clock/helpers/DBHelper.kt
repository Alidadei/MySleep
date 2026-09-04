package org.fossify.clock.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.text.TextUtils
import org.fossify.clock.extensions.cancelAlarmClock
import org.fossify.clock.extensions.createNewAlarm
import org.fossify.clock.models.Alarm
import org.fossify.clock.models.SleepRecord
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.helpers.FRIDAY_BIT
import org.fossify.commons.helpers.MONDAY_BIT
import org.fossify.commons.helpers.SATURDAY_BIT
import org.fossify.commons.helpers.SUNDAY_BIT
import org.fossify.commons.helpers.THURSDAY_BIT
import org.fossify.commons.helpers.TUESDAY_BIT
import org.fossify.commons.helpers.WEDNESDAY_BIT

class DBHelper private constructor(
    val context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val ALARMS_TABLE_NAME = "contacts"  // wrong table name, ignore it
    private val COL_ID = "id"
    private val COL_TIME_IN_MINUTES = "time_in_minutes"
    private val COL_DAYS = "days"
    private val COL_IS_ENABLED = "is_enabled"
    private val COL_VIBRATE = "vibrate"
    private val COL_SOUND_TITLE = "sound_title"
    private val COL_SOUND_URI = "sound_uri"
    private val COL_LABEL = "label"
    private val COL_ONE_SHOT = "one_shot"
    private val COL_ENABLE_TORCH = "enable_torch"
    private val COL_LIGHT_ONLY = "light_only"
    private val COL_SUNRISE_MINUTES = "sunrise_minutes"

    private val SLEEP_RECORDS_TABLE_NAME = "sleep_records"
    private val COL_SR_ALARM_ID = "alarm_id"
    private val COL_SR_LABEL = "label"
    private val COL_SR_SCHEDULED_MINUTES = "scheduled_minutes"
    private val COL_SR_RING_AT = "ring_at_millis"
    private val COL_SR_STOPPED_AT = "stopped_at_millis"
    private val COL_SR_SNOOZED = "snoozed"

    private val mDb = writableDatabase

    companion object {
        private const val DB_VERSION = 3
        const val DB_NAME = "alarms.db"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var dbInstance: DBHelper? = null

        fun newInstance(context: Context): DBHelper {
            val appContext = context.applicationContext
            return dbInstance ?: synchronized(this) {
                dbInstance ?: DBHelper(appContext).also { dbInstance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $ALARMS_TABLE_NAME ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_TIME_IN_MINUTES INTEGER, $COL_DAYS INTEGER, " +
                "$COL_IS_ENABLED INTEGER, $COL_VIBRATE INTEGER, $COL_SOUND_TITLE TEXT, $COL_SOUND_URI TEXT, $COL_LABEL TEXT, $COL_ONE_SHOT INTEGER, " +
                "$COL_ENABLE_TORCH INTEGER NOT NULL DEFAULT 0, $COL_LIGHT_ONLY INTEGER NOT NULL DEFAULT 0, $COL_SUNRISE_MINUTES INTEGER NOT NULL DEFAULT 0)"
        )
        createSleepRecordsTable(db)
        insertInitialAlarms(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $ALARMS_TABLE_NAME ADD COLUMN $COL_ONE_SHOT INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $ALARMS_TABLE_NAME ADD COLUMN $COL_ENABLE_TORCH INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $ALARMS_TABLE_NAME ADD COLUMN $COL_LIGHT_ONLY INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $ALARMS_TABLE_NAME ADD COLUMN $COL_SUNRISE_MINUTES INTEGER NOT NULL DEFAULT 0")
            createSleepRecordsTable(db)
        }
    }

    private fun createSleepRecordsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $SLEEP_RECORDS_TABLE_NAME ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COL_SR_ALARM_ID INTEGER, $COL_SR_LABEL TEXT, $COL_SR_SCHEDULED_MINUTES INTEGER, " +
                "$COL_SR_RING_AT INTEGER, $COL_SR_STOPPED_AT INTEGER, $COL_SR_SNOOZED INTEGER)"
        )
    }

    private fun insertInitialAlarms(db: SQLiteDatabase) {
        val weekDays = MONDAY_BIT or TUESDAY_BIT or WEDNESDAY_BIT or THURSDAY_BIT or FRIDAY_BIT
        val weekDaysAlarm = context.createNewAlarm(420, weekDays)
        insertAlarm(weekDaysAlarm, db)

        val weekEnd = SATURDAY_BIT or SUNDAY_BIT
        val weekEndAlarm = context.createNewAlarm(540, weekEnd)
        insertAlarm(weekEndAlarm, db)
    }

    fun insertAlarm(alarm: Alarm, db: SQLiteDatabase = mDb): Int {
        val values = fillAlarmContentValues(alarm)
        return db.insert(ALARMS_TABLE_NAME, null, values).toInt()
    }

    fun updateAlarm(alarm: Alarm): Boolean {
        val selectionArgs = arrayOf(alarm.id.toString())
        val values = fillAlarmContentValues(alarm)
        val selection = "$COL_ID = ?"
        return mDb.update(ALARMS_TABLE_NAME, values, selection, selectionArgs) == 1
    }

    fun updateAlarmEnabledState(id: Int, isEnabled: Boolean): Boolean {
        val selectionArgs = arrayOf(id.toString())
        val values = ContentValues()
        values.put(COL_IS_ENABLED, isEnabled)
        val selection = "$COL_ID = ?"
        return mDb.update(ALARMS_TABLE_NAME, values, selection, selectionArgs) == 1
    }

    fun deleteAlarms(alarms: ArrayList<Alarm>) {
        alarms.filter { it.isEnabled }.forEach {
            context.cancelAlarmClock(it)
        }

        val args = TextUtils.join(", ", alarms.map { it.id.toString() })
        val selection = "$ALARMS_TABLE_NAME.$COL_ID IN ($args)"
        mDb.delete(ALARMS_TABLE_NAME, selection, null)
    }

    fun getAlarmWithId(id: Int) = getAlarms().firstOrNull { it.id == id }

    fun getAlarmsWithUri(uri: String) = getAlarms().filter { it.soundUri == uri }

    private fun fillAlarmContentValues(alarm: Alarm): ContentValues {
        return ContentValues().apply {
            put(COL_TIME_IN_MINUTES, alarm.timeInMinutes)
            put(COL_DAYS, alarm.days)
            put(COL_IS_ENABLED, alarm.isEnabled)
            put(COL_VIBRATE, alarm.vibrate)
            put(COL_SOUND_TITLE, alarm.soundTitle)
            put(COL_SOUND_URI, alarm.soundUri)
            put(COL_LABEL, alarm.label)
            put(COL_ONE_SHOT, alarm.oneShot)
            put(COL_ENABLE_TORCH, alarm.enableTorch)
            put(COL_LIGHT_ONLY, alarm.lightOnly)
            put(COL_SUNRISE_MINUTES, alarm.sunriseMinutes)
        }
    }

    fun getEnabledAlarms() = getAlarms().filter { it.isEnabled }

    fun getAlarms(): ArrayList<Alarm> {
        val alarms = ArrayList<Alarm>()
        val cols = arrayOf(
            COL_ID,
            COL_TIME_IN_MINUTES,
            COL_DAYS,
            COL_IS_ENABLED,
            COL_VIBRATE,
            COL_SOUND_TITLE,
            COL_SOUND_URI,
            COL_LABEL,
            COL_ONE_SHOT,
            COL_ENABLE_TORCH,
            COL_LIGHT_ONLY,
            COL_SUNRISE_MINUTES
        )
        var cursor: Cursor? = null
        try {
            cursor = mDb.query(ALARMS_TABLE_NAME, cols, null, null, null, null, null)
            if (cursor?.moveToFirst() == true) {
                do {
                    try {
                        val id = cursor.getIntValue(COL_ID)
                        val timeInMinutes = cursor.getIntValue(COL_TIME_IN_MINUTES)
                        val days = cursor.getIntValue(COL_DAYS)
                        val isEnabled = cursor.getIntValue(COL_IS_ENABLED) == 1
                        val vibrate = cursor.getIntValue(COL_VIBRATE) == 1
                        val soundTitle = cursor.getStringValue(COL_SOUND_TITLE)
                        val soundUri = cursor.getStringValue(COL_SOUND_URI)
                        val label = cursor.getStringValue(COL_LABEL)
                        val oneShot = cursor.getIntValue(COL_ONE_SHOT) == 1
                        val enableTorch = cursor.getIntValue(COL_ENABLE_TORCH) == 1
                        val lightOnly = cursor.getIntValue(COL_LIGHT_ONLY) == 1
                        val sunriseMinutes = cursor.getIntValue(COL_SUNRISE_MINUTES)

                        val alarm = Alarm(
                            id = id,
                            timeInMinutes = timeInMinutes,
                            days = days,
                            isEnabled = isEnabled,
                            vibrate = vibrate,
                            soundTitle = soundTitle,
                            soundUri = soundUri,
                            label = label,
                            oneShot = oneShot,
                            enableTorch = enableTorch,
                            lightOnly = lightOnly,
                            sunriseMinutes = sunriseMinutes
                        )
                        alarms.add(alarm)
                    } catch (e: Exception) {
                        continue
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }

        return alarms
    }

    fun openSleepRecord(alarm: Alarm) {
        val openRecord = getOpenSleepRecord(alarm.id)
        val values = ContentValues()
        if (openRecord != null) {
            // same wake session re-ringing (e.g. after snooze), just refresh the ring time
            values.put(COL_SR_RING_AT, System.currentTimeMillis())
            mDb.update(
                SLEEP_RECORDS_TABLE_NAME, values, "$COL_ID = ?",
                arrayOf(openRecord.id.toString())
            )
        } else {
            values.put(COL_SR_ALARM_ID, alarm.id)
            values.put(COL_SR_LABEL, alarm.label)
            values.put(COL_SR_SCHEDULED_MINUTES, alarm.timeInMinutes)
            values.put(COL_SR_RING_AT, System.currentTimeMillis())
            values.put(COL_SR_STOPPED_AT, 0L)
            values.put(COL_SR_SNOOZED, 0)
            mDb.insert(SLEEP_RECORDS_TABLE_NAME, null, values)
        }
    }

    fun closeSleepRecord(alarmId: Int, stoppedAtMillis: Long) {
        val values = ContentValues()
        values.put(COL_SR_STOPPED_AT, stoppedAtMillis)
        mDb.update(
            SLEEP_RECORDS_TABLE_NAME, values,
            "$COL_SR_ALARM_ID = ? AND $COL_SR_STOPPED_AT = 0",
            arrayOf(alarmId.toString())
        )
    }

    fun markSleepRecordSnoozed(alarmId: Int) {
        val values = ContentValues()
        values.put(COL_SR_SNOOZED, 1)
        mDb.update(
            SLEEP_RECORDS_TABLE_NAME, values,
            "$COL_SR_ALARM_ID = ? AND $COL_SR_STOPPED_AT = 0",
            arrayOf(alarmId.toString())
        )
    }

    private fun getOpenSleepRecord(alarmId: Int): SleepRecord? {
        return getSleepRecords(
            "$COL_SR_ALARM_ID = ? AND $COL_SR_STOPPED_AT = 0",
            arrayOf(alarmId.toString())
        ).firstOrNull()
    }

    fun getRecentSleepRecords(limit: Int): List<SleepRecord> {
        val records = getSleepRecords(null, null)
        return records.sortedByDescending { it.id }.take(limit)
    }

    private fun getSleepRecords(
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<SleepRecord> {
        val records = ArrayList<SleepRecord>()
        val cols = arrayOf(
            COL_ID,
            COL_SR_ALARM_ID,
            COL_SR_LABEL,
            COL_SR_SCHEDULED_MINUTES,
            COL_SR_RING_AT,
            COL_SR_STOPPED_AT,
            COL_SR_SNOOZED
        )
        var cursor: Cursor? = null
        try {
            cursor = mDb.query(
                SLEEP_RECORDS_TABLE_NAME, cols, selection, selectionArgs, null, null, null
            )
            if (cursor?.moveToFirst() == true) {
                do {
                    try {
                        records.add(
                            SleepRecord(
                                id = cursor.getIntValue(COL_ID),
                                alarmId = cursor.getIntValue(COL_SR_ALARM_ID),
                                label = cursor.getStringValue(COL_SR_LABEL),
                                scheduledMinutes = cursor.getIntValue(COL_SR_SCHEDULED_MINUTES),
                                ringAtMillis = cursor.getLong(
                                    cursor.getColumnIndexOrThrow(COL_SR_RING_AT)
                                ),
                                stoppedAtMillis = cursor.getLong(
                                    cursor.getColumnIndexOrThrow(COL_SR_STOPPED_AT)
                                ),
                                snoozed = cursor.getIntValue(COL_SR_SNOOZED) == 1
                            )
                        )
                    } catch (e: Exception) {
                        continue
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor?.close()
        }

        return records
    }
}

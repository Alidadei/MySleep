package org.fossify.clock

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getSelectedDaysStringSafe
import org.fossify.clock.fragments.AlarmFragment
import org.fossify.commons.views.MyRecyclerView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Launch smoke tests: reproduce runtime crashes on the real Android runtime (Robolectric)
 * without needing a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = App::class)
class LaunchSmokeTest {

    @Test
    fun dbHelper_createsSchema_andReadsAlarms() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val alarms = context.dbHelper.getAlarms()
        assert(alarms.isNotEmpty()) { "initial alarms should be seeded" }
    }

    @Test
    fun mainActivity_launches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assert(!activity.isFinishing)
            }
        }
    }

    @Test
    fun getSelectedDaysStringSafe_rendersWeekdayBits() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val weekdays = 1 or 2 or 4 or 8 or 16
        val text = context.getSelectedDaysStringSafe(weekdays)
        assert(text.isNotEmpty()) { "weekday letters should render, got '$text'" }
    }

    /**
     * Regression test for the real-device crash:
     * ClassCastException (Arrays$ArrayList -> ArrayList) thrown from commons
     * getSelectedDaysString while the alarm list bound its rows.
     * Forces a real layout pass on the alarm RecyclerView so its adapter
     * actually binds the seeded rows.
     */
    @Test
    fun alarmList_bindsSeededAlarms_withoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(Looper.getMainLooper()).idle()

                val fragment = activity.supportFragmentManager.fragments
                    .filterIsInstance<AlarmFragment>()
                    .firstOrNull()
                val recycler = fragment?.view
                    ?.findViewById<MyRecyclerView>(org.fossify.clock.R.id.alarms_list)
                    ?: return@onActivity

                val widthSpec = android.view.View.MeasureSpec
                    .makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
                val heightSpec = android.view.View.MeasureSpec
                    .makeMeasureSpec(2340, android.view.View.MeasureSpec.EXACTLY)

                var bound = false
                for (attempt in 0 until 20) {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (recycler.adapter != null && recycler.childCount > 0) {
                        bound = true
                        break
                    }
                    recycler.measure(widthSpec, heightSpec)
                    recycler.layout(0, 0, 1080, 2340)
                }

                assert(recycler.adapter != null) { "alarm adapter should be attached" }
                assert(bound) { "alarm rows should have been bound during layout" }
            }
        }
    }
}

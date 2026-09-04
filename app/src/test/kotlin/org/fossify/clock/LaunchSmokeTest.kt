package org.fossify.clock

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.clock.activities.MainActivity
import org.fossify.clock.extensions.dbHelper
import org.junit.Test
import org.junit.runner.RunWith
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
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<App>()
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
}

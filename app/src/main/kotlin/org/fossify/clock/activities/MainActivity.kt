package org.fossify.clock.activities

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import me.grantland.widget.AutofitHelper
import org.fossify.clock.BuildConfig
import org.fossify.clock.R
import org.fossify.clock.adapters.ViewPagerAdapter
import org.fossify.clock.databinding.ActivityMainBinding
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.alarmManager
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.getEnabledAlarms
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.updateWidgets
import org.fossify.clock.helpers.AutoStartHelper
import org.fossify.clock.helpers.INVALID_TIMER_ID
import org.fossify.clock.helpers.OPEN_TAB
import org.fossify.clock.helpers.PICK_AUDIO_FILE_INTENT_ID
import org.fossify.clock.helpers.TABS_COUNT
import org.fossify.clock.helpers.TAB_ALARM
import org.fossify.clock.helpers.TAB_ALARM_INDEX
import org.fossify.clock.helpers.TAB_RELAX
import org.fossify.clock.helpers.TAB_RELAX_INDEX
import org.fossify.clock.helpers.TAB_TIMER
import org.fossify.clock.helpers.TAB_TIMER_INDEX
import org.fossify.clock.helpers.TIMER_ID
import org.fossify.commons.databinding.BottomTablayoutItemBinding
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getBottomNavigationBackgroundColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.onPageChangeListener
import org.fossify.commons.extensions.onTabSelectionChanged
import org.fossify.commons.extensions.shortcutManager
import org.fossify.commons.extensions.storeNewYourAlarmSound
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateBottomTabItemColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import org.fossify.commons.helpers.LICENSE_NUMBER_PICKER
import org.fossify.commons.helpers.LICENSE_RTL
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FAQItem
import java.time.temporal.WeekFields
import java.util.Locale

class MainActivity : SimpleActivity() {
    private var storedTextColor = 0
    private var storedBackgroundColor = 0
    private var storedPrimaryColor = 0
    private val binding: ActivityMainBinding by viewBinding(ActivityMainBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainTabsHolder))

        storeStateVariables()
        initFragments()
        setupTabs()
        updateWidgets()
        migrateFirstDayOfWeek()
        ensureBackgroundThread {
            alarmController.rescheduleEnabledAlarms()
        }

        getEnabledAlarms { enabledAlarms ->
            if (!enabledAlarms.isNullOrEmpty()) {
                handleFullScreenNotificationsPermission {
                    if (!it) {
                        toast(org.fossify.commons.R.string.notifications_disabled)
                    }
                }
            }
        }

        if (!config.wasPermissionWizardCompleted) {
            config.wasPermissionWizardCompleted = true
            startPermissionWizard()
        } else {
            promptBatteryWhitelistIfMissing()
        }
    }

    /**
     * 首次启动自动运行完整权限向导；之后的冷启动只轻检电池白名单。
     * 完整向导随时可在 设置 → 闹钟可靠性 里重跑。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            runNextPermissionStep()
        }

    private val settingsIntentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            runNextPermissionStep()
        }

    private var permissionSteps = mutableListOf<() -> Unit>()

    private fun startPermissionWizard() {
        getAlertDialogBuilder()
            .setTitle(R.string.permissions_setup)
            .setMessage(R.string.permissions_setup_confirm)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                buildPermissionSteps()
                runNextPermissionStep()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun buildPermissionSteps() {
        permissionSteps = mutableListOf()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (org.fossify.commons.helpers.isTiramisuPlus() &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionSteps.add {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms().not()) {
            permissionSteps.add {
                settingsIntentLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            permissionSteps.add {
                settingsIntentLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        permissionSteps.add {
            AutoStartHelper.getIntent(this)?.let { settingsIntentLauncher.launch(it) }
                ?: runNextPermissionStep()
        }

        if (!config.backgroundPopupPromptShown) {
            config.backgroundPopupPromptShown = true
            permissionSteps.add {
                settingsIntentLauncher.launch(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun runNextPermissionStep() {
        if (permissionSteps.isEmpty()) {
            toast(R.string.permissions_setup_done)
            return
        }
        permissionSteps.removeAt(0).invoke()
    }

    /** 后续冷启动的轻检：电池白名单未通过就一直提示。 */
    private fun promptBatteryWhitelistIfMissing() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        getAlertDialogBuilder()
            .setTitle(R.string.battery_prompt_title)
            .setMessage(R.string.battery_prompt_message)
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (ignored: Exception) {
                    }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.mainAppbar, topBarColor = getProperBackgroundColor())
        val configTextColor = getProperTextColor()
        if (storedTextColor != configTextColor) {
            getInactiveTabIndexes(binding.viewPager.currentItem).forEach {
                binding.mainTabsHolder.getTabAt(it)?.icon?.applyColorFilter(configTextColor)
            }
        }

        val configBackgroundColor = getProperBackgroundColor()
        if (storedBackgroundColor != configBackgroundColor) {
            binding.mainTabsHolder.background = configBackgroundColor.toDrawable()
        }

        val configPrimaryColor = getProperPrimaryColor()
        if (storedPrimaryColor != configPrimaryColor) {
            binding.mainTabsHolder.setSelectedTabIndicatorColor(getProperPrimaryColor())
            binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.icon
                ?.applyColorFilter(getProperPrimaryColor())
        }

        if (config.preventPhoneFromSleeping) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setupTabColors()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        if (config.preventPhoneFromSleeping) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    private fun setupOptionsMenu() {
        binding.mainToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> when (binding.viewPager.currentItem) {
                    TAB_ALARM_INDEX -> getViewPagerAdapter()?.showAlarmSortDialog()
                    TAB_TIMER_INDEX -> getViewPagerAdapter()?.showTimerSortDialog()
                }

                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.mainToolbar.menu.apply {
            findItem(R.id.sort).isVisible = binding.viewPager.currentItem == getTabIndex(TAB_ALARM)
                    || binding.viewPager.currentItem == getTabIndex(TAB_TIMER)
            findItem(R.id.more_apps_from_us).isVisible =
                !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.extras?.containsKey(OPEN_TAB) == true) {
            val tabToOpen = intent.getIntExtra(OPEN_TAB, TAB_RELAX)
            binding.viewPager.setCurrentItem(getTabIndex(tabToOpen), false)
            if (tabToOpen == TAB_TIMER) {
                val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
                (binding.viewPager.adapter as ViewPagerAdapter).updateTimerPosition(timerId)
            }
        }
        super.onNewIntent(intent)
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedBackgroundColor = getProperBackgroundColor()
        storedPrimaryColor = getProperPrimaryColor()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        when {
            requestCode == PICK_AUDIO_FILE_INTENT_ID && resultCode == RESULT_OK && resultData != null -> {
                storeNewAlarmSound(resultData)
            }
        }
    }

    private fun storeNewAlarmSound(resultData: Intent) {
        val newAlarmSound = storeNewYourAlarmSound(resultData)

        when (binding.viewPager.currentItem) {
            TAB_ALARM_INDEX -> getViewPagerAdapter()?.updateAlarmTabAlarmSound(newAlarmSound)
            TAB_TIMER_INDEX -> getViewPagerAdapter()?.updateTimerTabAlarmSound(newAlarmSound)
        }
    }

    private fun getViewPagerAdapter() = binding.viewPager.adapter as? ViewPagerAdapter

    private fun initFragments() {
        val viewPagerAdapter = ViewPagerAdapter(supportFragmentManager)
        binding.viewPager.adapter = viewPagerAdapter
        binding.viewPager.onPageChangeListener {
            binding.mainTabsHolder.getTabAt(it)?.select()
            refreshMenuItems()
        }

        val tabToOpen = intent.getIntExtra(OPEN_TAB, config.defaultTab)
        intent.removeExtra(OPEN_TAB)
        if (tabToOpen == TAB_TIMER) {
            val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
            viewPagerAdapter.updateTimerPosition(timerId)
        }

        binding.viewPager.offscreenPageLimit = TABS_COUNT - 1
        binding.viewPager.currentItem = getTabIndex(tabToOpen)
    }

    private fun setupTabs() {
        binding.mainTabsHolder.removeAllTabs()
        val tabDrawables = arrayOf(
            R.drawable.ic_tab_relax,
            R.drawable.ic_alarm_vector,
            R.drawable.ic_hourglass_vector
        )
        val tabLabels = arrayOf(
            R.string.tab_relax,
            org.fossify.commons.R.string.alarm,
            R.string.timer
        )

        tabDrawables.forEachIndexed { i, drawableId ->
            binding.mainTabsHolder.newTab()
                .setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item)
                .apply tab@{
                    customView?.let { BottomTablayoutItemBinding.bind(it) }?.apply {
                        tabItemIcon.setImageDrawable(getDrawable(drawableId))
                        tabItemLabel.setText(tabLabels[i])
                        AutofitHelper.create(tabItemLabel)
                        binding.mainTabsHolder.addTab(this@tab)
                    }
                }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(
                    view = it.customView,
                    isActive = false,
                    drawableId = getDeselectedTabDrawableIds()[it.position]
                )
            },
            tabSelectedAction = {
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(
                    view = it.customView,
                    isActive = true,
                    drawableId = getSelectedTabDrawableIds()[it.position]
                )
            }
        )
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(
            view = activeView,
            isActive = true,
            drawableId = getSelectedTabDrawableIds()[binding.viewPager.currentItem]
        )

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.select()
        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int): List<Int> {
        return arrayListOf(0, 1, 2).filter { it != activeIndex }
    }

    private fun getSelectedTabDrawableIds() = arrayOf(
        R.drawable.ic_clock_filled_vector,
        R.drawable.ic_alarm_filled_vector,
        R.drawable.ic_hourglass_filled_vector
    )

    private fun getDeselectedTabDrawableIds() = arrayOf(
        org.fossify.commons.R.drawable.ic_clock_vector,
        R.drawable.ic_alarm_vector,
        R.drawable.ic_hourglass_vector
    )

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses =
            LICENSE_NUMBER_PICKER or LICENSE_RTL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(
                title = org.fossify.commons.R.string.faq_1_title_commons,
                text = org.fossify.commons.R.string.faq_1_text_commons
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_4_title_commons,
                text = org.fossify.commons.R.string.faq_4_text_commons
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.app_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    @Deprecated("Remove this method in future releases")
    private fun migrateFirstDayOfWeek() {
        if (config.migrateFirstDayOfWeek) {
            config.migrateFirstDayOfWeek = false
            config.firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        }
    }

    private fun getTabIndex(tabId: Int): Int {
        return when (tabId) {
            TAB_RELAX -> TAB_RELAX_INDEX
            TAB_ALARM -> TAB_ALARM_INDEX
            TAB_TIMER -> TAB_TIMER_INDEX
            else -> config.lastUsedViewPagerPage
        }
    }
}

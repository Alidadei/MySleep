package org.fossify.clock.fragments

import org.fossify.clock.helpers.TimeTheme

/** 时辰主题（网站同款插值）变化时由 MainActivity 通知各 tab 页面重染色 */
interface TimeThemeAware {
    fun applyTimeTheme(theme: TimeTheme)
}

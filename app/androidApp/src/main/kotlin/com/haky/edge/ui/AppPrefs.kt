package com.haky.edge.ui

import android.content.Context

// 간단한 앱 설정 영속화(SharedPreferences). 분석 모드(방어/공격)를 화면 간·재실행 간 유지.
object AppPrefs {
    private const val PREFS = "edge_prefs"
    private const val KEY_MODE = "analysis_mode"
    private const val KEY_THEME = "theme"
    private const val KEY_STATS_RECENT   = "stats_recent_expanded"
    private const val KEY_STATS_CODE     = "stats_code_expanded"
    private const val KEY_STATS_HOLD     = "stats_hold_expanded"
    private const val KEY_STATS_REASON   = "stats_reason_expanded"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): String =
        prefs(ctx).getString(KEY_MODE, "defensive") ?: "defensive"

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
    }

    fun getTheme(ctx: Context): String =
        prefs(ctx).getString(KEY_THEME, "system") ?: "system"

    fun setTheme(ctx: Context, theme: String) {
        prefs(ctx).edit().putString(KEY_THEME, theme).apply()
    }

    fun getStatsExpanded(ctx: Context, key: String, default: Boolean = false): Boolean =
        prefs(ctx).getBoolean(key, default)

    fun setStatsExpanded(ctx: Context, key: String, value: Boolean) {
        prefs(ctx).edit().putBoolean(key, value).apply()
    }

    const val STATS_RECENT = KEY_STATS_RECENT
    const val STATS_CODE   = KEY_STATS_CODE
    const val STATS_HOLD   = KEY_STATS_HOLD
    const val STATS_REASON = KEY_STATS_REASON
}

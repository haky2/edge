package com.haky.edge.ui

import android.content.Context

// 간단한 앱 설정 영속화(SharedPreferences). 분석 모드(방어/공격)를 화면 간·재실행 간 유지.
object AppPrefs {
    private const val PREFS = "edge_prefs"
    private const val KEY_MODE = "analysis_mode"

    fun getMode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "defensive") ?: "defensive"

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
    }
}

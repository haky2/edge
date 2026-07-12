package com.haky.edge.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(EdgeDb.Schema, context, "edge.db")
}

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun todayIso(): String = java.time.LocalDate.now().toString()

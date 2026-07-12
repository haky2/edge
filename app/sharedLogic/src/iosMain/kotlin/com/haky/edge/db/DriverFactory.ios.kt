package com.haky.edge.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(EdgeDb.Schema, "edge.db")
}

actual fun nowMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun todayIso(): String {
    val fmt = platform.Foundation.NSDateFormatter()
    fmt.dateFormat = "yyyy-MM-dd"
    return fmt.stringFromDate(NSDate())
}

package com.haky.edge.db

import app.cash.sqldelight.db.SqlDriver

/**
 * 플랫폼별 SQLite 드라이버 생성기.
 *  - iOS: 인자 없이 생성(Swift에서 DriverFactory()).
 *  - Android: Context 필요(MainActivity에서 DriverFactory(context)).
 * 그래서 생성자가 플랫폼마다 달라 expect class로만 선언하고 actual에서 각자 정의한다.
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** epoch millis. added_at/created_at 기록용 — 플랫폼 시계로 채운다. */
expect fun nowMillis(): Long

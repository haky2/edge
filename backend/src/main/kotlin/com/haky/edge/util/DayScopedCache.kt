package com.haky.edge.util

import java.util.concurrent.ConcurrentHashMap

/**
 * 날짜 문자열이 바뀌면 통째로 비우는 당일 인메모리 캐시.
 * 날짜가 키에 포함된 ConcurrentHashMap 패턴의 무한증식을 막는다 — 날짜 키 캐시는
 * 자정이 지나도 아무도 이전 엔트리를 지우지 않아 웜 인스턴스가 오래 살수록 누적됨.
 *
 * 사용법: get/put 모두 date 인자를 넘기면 날짜 불일치 시 자동 clear.
 */
class DayScopedCache<V> {
    @Volatile private var currentDate = ""
    private val map = ConcurrentHashMap<String, V>()

    fun get(date: String, key: String): V? {
        maybeRotate(date)
        return map[key]
    }

    fun put(date: String, key: String, value: V) {
        maybeRotate(date)
        map[key] = value
    }

    private fun maybeRotate(date: String) {
        if (date != currentDate) synchronized(this) {
            if (date != currentDate) {
                map.clear()
                currentDate = date
            }
        }
    }
}

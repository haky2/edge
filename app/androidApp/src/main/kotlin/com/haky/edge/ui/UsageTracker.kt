package com.haky.edge.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.haky.edge.api.EdgeApi
import com.haky.edge.model.UsageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M1 카드 사용량 트래커 — **단일 사용자 전제**.
 * 화면 진입(view)·카드 펼침(expand)을 SharedPreferences 큐에 모아 포그라운드 진입 시 배치 flush.
 * 개인 도구라 즉시 전송 불필요 → 앱 재실행에도 안 잃도록 영속. 서버가 (screen,card,action,at)로 디듀프(멱등).
 */
object Usage {
    private const val PREFS = "usage_tracker"
    private const val KEY = "queue_v1"
    private const val MAX = 1000
    // 필드 구분자(Unit Separator)·레코드 구분자(Record Separator) — 카드 제목엔 안 나오는 제어문자.
    private const val FS = ''
    private const val RS = ''

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val seoul = ZoneId.of("Asia/Seoul")

    private var api: EdgeApi? = null
    private var prefs: android.content.SharedPreferences? = null
    @Volatile private var flushing = false

    fun configure(api: EdgeApi, context: Context) {
        this.api = api
        this.prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 화면 진입(카드 노출). card는 빈 문자열. */
    fun view(screen: String) = enqueue(screen, "", "view")

    /** 접이식 카드 펼침. card = 카드 표시 제목. */
    fun expand(screen: String, card: String) = enqueue(screen, card, "expand")

    private fun now(): String =
        LocalDateTime.now(seoul).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    private fun enqueue(screen: String, card: String, action: String) {
        val p = prefs ?: return
        synchronized(lock) {
            val q = load(p).toMutableList()
            q.add(UsageEvent(screen = screen, card = card, action = action, at = now()))
            while (q.size > MAX) q.removeAt(0)
            save(p, q)
        }
    }

    /** 포그라운드 진입 시 호출. 큐 스냅샷을 전송하고 성공하면 그만큼만 앞에서 제거. */
    fun flush() {
        val api = api ?: return
        val p = prefs ?: return
        synchronized(lock) {
            if (flushing) return
            if (load(p).isEmpty()) return
            flushing = true
        }
        scope.launch {
            val batch = synchronized(lock) { load(p) }
            try {
                api.postUsageEvents(batch)
                synchronized(lock) {
                    val q = load(p).toMutableList()
                    // flush 사이 새로 들어온 이벤트는 큐 끝에 붙으므로, 보낸 개수만큼 앞에서 제거.
                    if (q.size >= batch.size) repeat(batch.size) { q.removeAt(0) } else q.clear()
                    save(p, q)
                }
            } catch (_: Exception) {
                // 실패 시 큐 유지 — 다음 포그라운드에서 재시도(서버 디듀프로 멱등).
            } finally {
                synchronized(lock) { flushing = false }
            }
        }
    }

    private fun load(p: android.content.SharedPreferences): List<UsageEvent> {
        val raw = p.getString(KEY, null)
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RS).mapNotNull { rec ->
            val f = rec.split(FS)
            if (f.size == 4) UsageEvent(screen = f[0], card = f[1], action = f[2], at = f[3]) else null
        }
    }

    private fun save(p: android.content.SharedPreferences, q: List<UsageEvent>) {
        val raw = q.joinToString(RS.toString()) { "${it.screen}$FS${it.card}$FS${it.action}$FS${it.at}" }
        p.edit().putString(KEY, raw).apply()
    }
}

/** CollapsibleCard가 자신이 속한 화면을 알도록 하는 컨텍스트. 기본 "detail"(대부분 상세 화면). */
val LocalUsageScreen = staticCompositionLocalOf { "detail" }

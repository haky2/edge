package com.haky.edge.slack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 백엔드 운영 오류를 #ops-오류 채널로 흘리는 알림기.
 *
 * - StatusPages 전역 예외 핸들러에서 호출. 사용자 응답을 지연시키지 않도록 **fire-and-forget**
 *   (scope.launch)으로 Slack 발송 → alert()는 즉시 반환(suspend 아님).
 * - **쿨다운 throttle**: 같은 시그니처(상태·라우트군·예외종류)의 알림은 cooldownMs 동안 1번만.
 *   KIS 세션 공백처럼 전 종목이 동시에 깨질 때 종목 수만큼 도배되는 걸 막는다(라우트 첫 세그먼트로 묶음).
 * - 토큰/채널 미설정이면 SlackClient가 no-op이라 로컬 개발에서도 무해.
 */
class OpsAlerter(
    private val slack: SlackClient,
    private val opsChannel: String,
    private val scope: CoroutineScope,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val lastSentAt = ConcurrentHashMap<String, Long>()

    /**
     * 요청 처리 중 발생한 예외를 알린다.
     * @param method  HTTP 메서드 (GET/POST…)
     * @param path    요청 경로 (예: /analysis/005930)
     * @param status  내려준 상태 코드 (500/502…)
     * @param errorClass 예외 클래스 단순명 (KisException 등)
     * @param message 예외 메시지
     */
    fun alert(method: String, path: String, status: Int, errorClass: String, message: String) {
        if (!slack.isConfigured || opsChannel.isBlank()) return

        // 라우트 첫 세그먼트로 묶어 시그니처 생성: /analysis/005930·/analysis/000660 → 같은 "analysis" 그룹.
        val routeFamily = "/" + (path.split("/").getOrNull(1)?.takeIf { it.isNotBlank() } ?: "")
        val signature = "$status|$routeFamily|$errorClass"

        val now = System.currentTimeMillis()
        val prev = lastSentAt[signature]
        if (prev != null && now - prev < cooldownMs) return  // 쿨다운 중 — 중복 알림 억제
        lastSentAt[signature] = now

        val trimmed = message.take(MAX_MESSAGE_LEN)
        val text = buildString {
            append("🔴 *$status* `$method $path`\n")
            append("```$errorClass: $trimmed```")
        }
        scope.launch { slack.postMessage(opsChannel, text) }
    }

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 5 * 60_000L  // 같은 오류 5분에 1번
        private const val MAX_MESSAGE_LEN = 500
    }
}

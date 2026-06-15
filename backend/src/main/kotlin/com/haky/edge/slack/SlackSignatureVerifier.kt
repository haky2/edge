package com.haky.edge.slack

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Slack 요청 서명 검증(https://api.slack.com/authentication/verifying-requests-from-slack).
 *
 * Slack은 모든 슬래시 명령·인터랙션 요청에 다음 두 헤더를 붙인다:
 *   X-Slack-Request-Timestamp: 요청 시각(epoch sec)
 *   X-Slack-Signature:         "v0=" + HMAC_SHA256(signingSecret, "v0:{ts}:{rawBody}") (hex)
 *
 * 이 게이트가 /slack/command 의 인증이다(앱↔백엔드 EDGE_API_TOKEN을 Slack은 못 보내므로,
 * Security.kt 의 토큰 게이트에서 /slack/command 는 제외하고 여기서 서명으로 대신 인증한다).
 *
 * signingSecret 이 비면(로컬 개발) 검증을 건너뛴다 — EDGE_API_TOKEN 비면 인증 생략하는 것과 동일한 정책.
 */
class SlackSignatureVerifier(private val signingSecret: String) {

    val isConfigured: Boolean get() = signingSecret.isNotBlank()

    /**
     * @param timestamp X-Slack-Request-Timestamp 헤더
     * @param signature X-Slack-Signature 헤더 ("v0=..." )
     * @param rawBody   요청 본문 원문(파싱 전 — HMAC은 바이트 단위라 원문 그대로여야 함)
     * @return 검증 통과 여부. signingSecret 미설정이면 항상 true(로컬).
     */
    fun verify(timestamp: String?, signature: String?, rawBody: String): Boolean {
        if (signingSecret.isBlank()) return true  // 로컬 개발: 검증 비활성
        if (timestamp.isNullOrBlank() || signature.isNullOrBlank()) return false

        // 리플레이 방지: 5분 이상 지난 요청은 거부.
        val ts = timestamp.toLongOrNull() ?: return false
        val nowSec = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(nowSec - ts) > 60 * 5) return false

        val expected = "v0=" + hmacSha256Hex("v0:$timestamp:$rawBody")
        return constantTimeEquals(expected, signature)
    }

    private fun hmacSha256Hex(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 타이밍 공격 방지 상수시간 비교. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}

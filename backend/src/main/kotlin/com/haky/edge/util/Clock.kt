package com.haky.edge.util

import java.time.ZoneId

/**
 * 거래일·시각 계산 기준 타임존(KST).
 *
 * ⚠️ 서버(Cloud Run)는 UTC로 돈다. `LocalDate.now()`/`LocalTime.now()`를 존 없이 쓰면 한국 00:00~09:00에
 * 전날로 밀려 캐시·날짜 로직이 어긋난다. 날짜/시각 기준이 필요한 모든 곳은 이 존으로 now()를 계산한다.
 */
val KST: ZoneId = ZoneId.of("Asia/Seoul")

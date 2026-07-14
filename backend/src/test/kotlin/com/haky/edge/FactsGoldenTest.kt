package com.haky.edge

import com.haky.edge.ai.Backtest
import com.haky.edge.ai.FlowCorrelation
import com.haky.edge.ai.FlowSensitivity
import com.haky.edge.ai.NewsCluster
import com.haky.edge.ai.PeerMetric
import com.haky.edge.ai.PeerValuation
import com.haky.edge.ai.Position
import com.haky.edge.ai.SignalResult
import com.haky.edge.ai.ThesisSnapshot
import com.haky.edge.ai.ValuationBand
import com.haky.edge.ai.buildFacts
import com.haky.edge.dart.FinancialSummary
import com.haky.edge.dart.QuarterlyIncome
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.Quote
import com.haky.edge.macro.ShortSellingSummary
import com.haky.edge.news.NewsItem
import com.haky.edge.news.TargetPriceEvents
import com.haky.edge.news.TargetPriceTrend
import com.haky.edge.toss.MarketCalendar
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * buildFacts 출력 바이트 고정(골든) — facts 다이어트 1a의 섹션 빌더 리팩터가
 * 출력을 1바이트도 바꾸지 않음을 보장한다. 입력은 전부 합성(결정적), 시간은 고정 주입.
 *
 * 골든 파일이 없으면 현재 출력으로 생성 후 실패시킨다(생성분을 사람이 검토하고 커밋).
 * 의도적으로 facts 내용을 바꾸는 변경은 골든 파일을 삭제 후 재생성해 diff를 리뷰한다.
 */
class FactsGoldenTest {

    private val fixedNow: ZonedDateTime =
        ZonedDateTime.of(2026, 7, 14, 10, 30, 0, 0, ZoneId.of("Asia/Seoul")) // 화요일 장 중

    /** 결정적 합성 일봉 60개(최신이 앞). 급등·급락·연속 하락이 섞이도록 조작된 수열. */
    private fun syntheticBars(): List<DailyBar> {
        val closes = buildList {
            // 최신 3일 연속 하락
            add(95_000L); add(97_000L); add(99_000L)
            // 급등일(+20%) 하나와 급락일(-16%) 하나를 포함한 40일
            var v = 99_000L
            repeat(17) { add(v); v += 500 }
            add((v * 1.2).toLong())  // 급등 흔적
            repeat(10) { add(v); v -= 300 }
            add((v * 0.84).toLong()) // 급락 흔적
            repeat(11) { add(v); v += 200 }
            // 남은 날들 완만 상승
            while (size < 60) { add(v); v -= 100 }
        }
        return closes.mapIndexed { i, c ->
            DailyBar(
                date = "202605%02d".format(60 - i),
                open = c - 500, high = c + 1000, low = c - 1000, close = c, volume = 1_000_000L + i,
            )
        }
    }

    private fun richFacts(): String {
        val bars = syntheticBars()
        return buildFacts(
            code = "005930",
            name = "삼성전자",
            q = Quote(
                code = "005930", price = 95_000, change = -2_000, changeRate = -2.06,
                volume = 12_345_678, open = 96_000, high = 97_000, low = 94_500,
                high52w = 120_000, low52w = 60_000, per = 12.34, pbr = 1.23, sectorName = "전기·전자",
            ),
            bars = bars,
            financials = FinancialSummary(
                fiscalYear = 2025, consolidated = true,
                revenue = 300_000_000_000_000, revenuePrev = 258_000_000_000_000,
                operatingProfit = 43_601_000_000_000, operatingProfitPrev = 32_726_000_000_000,
                netIncome = 45_207_000_000_000, netIncomePrev = 34_451_000_000_000,
            ),
            flows = listOf(
                InvestorFlow("20260710", 120_000, -30_000, -90_000),
                InvestorFlow("20260709", -50_000, 20_000, 30_000),
                InvestorFlow("20260708", 80_000, 10_000, -90_000),
            ),
            // 5건 — NEWS_DESC_TOP(4) 경계를 골든에 고정(5번째부터 요약 생략).
            news = listOf(
                NewsCluster(NewsItem("삼성전자, 대규모 수주 공시", "2조원 규모 반도체 공급 계약 체결", "한국경제", "https://ex.com/1", "not-a-date"), 3),
                NewsCluster(NewsItem("반도체 업황 회복 전망", "", "매일경제", "https://ex.com/2", "not-a-date"), 1),
                NewsCluster(NewsItem("HBM4 양산 일정 공개", "내년 상반기 양산 목표로 장비 발주 시작", "전자신문", "https://ex.com/3", "not-a-date"), 2),
                NewsCluster(NewsItem("파운드리 대형 고객 확보", "북미 빅테크와 3나노 계약", "조선비즈", "https://ex.com/4", "not-a-date"), 1),
                NewsCluster(NewsItem("주주환원 확대 검토 보도", "자사주 매입 규모 상향 검토", "연합뉴스", "https://ex.com/5", "not-a-date"), 1),
            ),
            consensusTarget = 130_000,
            targetTrend = TargetPriceTrend(
                current = 130_000, baseline = 120_000, baselineDate = "2026-06-14",
                changePct = 8.3, direction = "상향", snapshotCount = 25, daySpan = 30,
            ),
            targetEvents = TargetPriceEvents(
                raisesIn90d = 3, cutsIn90d = 0, breakthroughDays = 2, avgRaiseGapDays = 5, snapshotCount = 30,
            ),
            sectorChangeRate = -1.20,
            shortSelling = ShortSellingSummary(
                code = "005930", recentVolume = 1_234_567, recentVolumeDate = "2026/07/10",
                balance = 9_876_543, balanceDate = "2026/07/08", balanceChangePct = 2.5,
            ),
            valuationBand = ValuationBand(
                code = "005930",
                perCurrent = 12.3, perMin = 6.0, perMax = 20.0, perMedian = 11.0, perPercentile = 62, perLabel = "역사적 중간권",
                pbrCurrent = 1.23, pbrMin = 0.9, pbrMax = 2.1, pbrMedian = 1.4, pbrPercentile = 40, pbrLabel = "역사적 중간권",
                yearsUsed = 5,
            ),
            peerValuation = PeerValuation(
                code = "005930", clusterLabel = "메모리반도체", peerCount = 3,
                per = PeerMetric(current = 12.3, peerMedian = 15.0, peerMin = 8.0, peerMax = 22.0, diffPct = -18.0, label = "동종 대비 낮음"),
                pbr = PeerMetric(current = 1.23, peerMedian = 1.10, peerMin = 0.8, peerMax = 1.9, diffPct = 12.0, label = "동종과 비슷"),
            ),
            backtest = Backtest(
                code = "005930", tradingDays = 120, flowDays = 110, baselineWinRate = 52, baselineAvgReturn = 0.08,
                signals = listOf(
                    SignalResult("외인 순매수", n = 30, winRate = 60, avgReturn = 0.35, edge = 0.27, confident = true),
                    SignalResult("기관 순매수", n = 8, winRate = 55, avgReturn = 0.10, edge = 0.02, confident = false),
                ),
            ),
            flowSensitivity = FlowSensitivity(
                code = "005930",
                items = listOf(FlowCorrelation("외인", r = 0.42, label = "양의 중간 상관", n = 95, confident = true)),
            ),
            quarterlyIncome = QuarterlyIncome(
                label = "2026년 1분기", netIncome = 12_000_000_000_000, netIncomePrev = 2_100_000_000_000, yoyPct = 471.4,
            ),
            listedShares = 5_900_000_000,
            eventsText = "임박 거시 이벤트(향후 2주):\n  - 2026-07-15 미국 CPI 발표 (D-1)\n  - 2026-07-17 미시건 소비심리 (D-3)",
            warningsText = "투자유의(거래소 지정, 현재 발동 중): 단기과열",
            calendar = MarketCalendar(
                date = "2026-07-14", isHoliday = false, regularStart = "09:00", regularEnd = "15:30",
                previousBusinessDay = "2026-07-13", nextBusinessDay = "2026-07-15",
            ),
            position = Position(avgPrice = 70_000.0, qty = 50, targetPrice = 120_000.0, stopPrice = 65_000.0),
            thesis = "HBM 수요 확대로 메모리 슈퍼사이클 초입이라고 본다",
            thesisHistory = listOf(
                ThesisSnapshot("2026-05-30", "감산 효과로 가격 반등 기대"),
                ThesisSnapshot("2026-06-20", "HBM 수요 확대로 메모리 슈퍼사이클 초입이라고 본다"),
            ),
            marketContext = "시장 맥락(코스피, 확정 종가 기준): 직전 거래일 -8.95%, 최근 5거래일 누적 -13.52%",
            horizonLong = true,
            now = fixedNow,
        )
    }

    private fun minimalFacts(): String = buildFacts(
        code = "001440",
        name = "대한전선",
        q = Quote(
            code = "001440", price = 28_300, change = -1_950, changeRate = -6.45,
            volume = 3_456_789, open = 30_000, high = 30_100, low = 28_000,
            high52w = 0, low52w = 0, per = 0.0, pbr = 0.0,
        ),
        bars = emptyList(),
        financials = null,
        flows = emptyList(),
        news = emptyList(),
        consensusTarget = null,
        targetTrend = null,
        targetEvents = null,
        sectorChangeRate = null,
        shortSelling = null,
        valuationBand = null,
        peerValuation = null,
        backtest = null,
        flowSensitivity = null,
        quarterlyIncome = null,
        listedShares = null,
        eventsText = null,
        warningsText = null,
        calendar = null,
        now = fixedNow,
    )

    private fun assertGolden(name: String, actual: String) {
        val file = File("src/test/resources/golden/$name.txt")
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            fail("골든 파일 최초 생성: ${file.path} — 내용 검토 후 재실행하면 통과합니다")
        }
        assertEquals(file.readText(), actual, "buildFacts 출력이 골든과 다릅니다(바이트 동일 계약 위반)")
    }

    @Test fun `rich 케이스 — 전 블록 발동 출력이 골든과 바이트 동일`() {
        assertGolden("facts-rich", richFacts())
    }

    @Test fun `minimal 케이스 — 최소 블록 출력이 골든과 바이트 동일`() {
        assertGolden("facts-minimal", minimalFacts())
    }
}

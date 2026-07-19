package com.haky.edge.ai

import com.haky.edge.dart.DividendInfo
import com.haky.edge.dart.FinancialSummary
import com.haky.edge.dart.LeadingIndicators
import com.haky.edge.dart.LeadingQuarter
import com.haky.edge.dart.QuarterlyIncome
import com.haky.edge.kis.DailyBar
import com.haky.edge.kis.InvestorFlow
import com.haky.edge.kis.Quote
import com.haky.edge.macro.ShortSellingSummary
import com.haky.edge.news.NewsItem
import com.haky.edge.news.TargetPriceTrend
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

// AnalysisService.collectFacts()가 모은 사실을 Claude 입력 텍스트로 조립하는 순수 함수들.
// AnalysisService에서 분리(2026-07-14, facts 다이어트 1a) — 인스턴스 상태를 쓰지 않아
// 골든 테스트(출력 바이트 고정)와 /facts-audit 계측이 가능해진다.

/** facts 한 블록. text는 최종 문자열에 그대로 이어붙는 조각(개행 포함) — concat이 곧 buildFacts 출력. */
internal data class FactsSection(val label: String, val text: String)

/** 뉴스 요약(description)을 포함하는 최신 클러스터 수 — 이후는 제목+날짜만(1b 다이어트). */
internal const val NEWS_DESC_TOP = 4

internal fun buildFacts(
    code: String,
    name: String,
    q: Quote,
    bars: List<DailyBar>,
    financials: FinancialSummary?,
    flows: List<InvestorFlow>,
    news: List<NewsCluster>,
    consensusTarget: Long?,
    targetTrend: TargetPriceTrend?,
    targetEvents: com.haky.edge.news.TargetPriceEvents?,
    sectorChangeRate: Double?,
    shortSelling: ShortSellingSummary?,
    valuationBand: ValuationBand?,
    peerValuation: PeerValuation?,
    backtest: Backtest?,
    flowSensitivity: FlowSensitivity?,
    quarterlyIncome: QuarterlyIncome?,
    listedShares: Long?,
    eventsText: String?,
    warningsText: String?,
    calendar: com.haky.edge.toss.MarketCalendar?,
    position: Position? = null,
    thesis: String? = null,
    thesisHistory: List<ThesisSnapshot> = emptyList(),
    marketContext: String? = null,
    horizonLong: Boolean = false,
    leading: LeadingIndicators? = null,
    dividend: DividendInfo? = null,
    now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul")),
): String = buildFactsSections(
    code, name, q, bars, financials, flows, news, consensusTarget, targetTrend, targetEvents,
    sectorChangeRate, shortSelling, valuationBand, peerValuation, backtest, flowSensitivity,
    quarterlyIncome, listedShares, eventsText, warningsText, calendar, position, thesis,
    thesisHistory, marketContext, horizonLong, leading, dividend, now,
).joinToString("") { it.text }

/**
 * buildFacts의 블록 분해 버전 — /facts-audit가 블록별 크기를 계측한다.
 * 계약: 각 섹션 text를 순서대로 이어붙인 결과가 buildFacts 출력과 바이트 동일(FactsGoldenTest가 강제).
 */
internal fun buildFactsSections(
    code: String,
    name: String,
    q: Quote,
    bars: List<DailyBar>,
    financials: FinancialSummary?,
    flows: List<InvestorFlow>,
    news: List<NewsCluster>,
    consensusTarget: Long?,
    targetTrend: TargetPriceTrend?,
    targetEvents: com.haky.edge.news.TargetPriceEvents?,
    sectorChangeRate: Double?,
    shortSelling: ShortSellingSummary?,
    valuationBand: ValuationBand?,
    peerValuation: PeerValuation?,
    backtest: Backtest?,
    flowSensitivity: FlowSensitivity?,
    quarterlyIncome: QuarterlyIncome?,
    listedShares: Long?,
    eventsText: String?,
    warningsText: String?,
    calendar: com.haky.edge.toss.MarketCalendar?,
    position: Position? = null,
    thesis: String? = null,
    thesisHistory: List<ThesisSnapshot> = emptyList(),
    marketContext: String? = null,
    horizonLong: Boolean = false,
    leading: LeadingIndicators? = null,
    dividend: DividendInfo? = null,
    now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Seoul")),
): List<FactsSection> {
    val sections = mutableListOf<FactsSection>()
    fun add(label: String, text: String?) {
        if (!text.isNullOrEmpty()) sections.add(FactsSection(label, text))
    }

    add("header", buildString {
        appendLine("종목: $name ($code)")
        val kst = now
        val totalMin = kst.hour * 60 + kst.minute
        val isWeekend = kst.dayOfWeek == DayOfWeek.SATURDAY || kst.dayOfWeek == DayOfWeek.SUNDAY
        // 캘린더가 있으면 공식 휴장 여부(주말+공휴일 모두 커버), 없으면 주말 휴리스틱 폴백.
        // 공휴일(평일)을 "장 중"으로 오표시하던 문제를 토스 개장 캘린더로 바로잡는다.
        val isHoliday = calendar?.isHoliday ?: isWeekend
        val nextDay = calendar?.nextBusinessDay?.takeIf { it.isNotBlank() }
        val marketStatus = when {
            isHoliday       -> "휴장 — 전일 종가 기준" + (nextDay?.let { " (다음 거래일 $it)" } ?: "")
            totalMin < 540  -> "장 전 (09:00 개장 전) — 전일 종가 기준"
            totalMin < 930  -> "장 중 (09:00~15:30)"
            else            -> "장 마감 후 (15:30 이후) — 당일 종가 확정"
        }
        appendLine("현재 시장 상태: $marketStatus")
        appendLine("현재가: ${q.price}원 (전일대비 ${q.change}, ${q.changeRate}%)")
    })
    // 투자유의는 리스크 신호라 상단에 배치(거래소 지정 시장경보·단기과열·정리매매·VI).
    add("warnings", warningsText?.let { it + "\n" })
    // 국면 판정(계산) — 리레이팅/디레이팅을 룰로 감지해 해석 프레임을 지정(C11).
    // 상단 배치: 아래 밸류·실적을 읽기 전에 프레임이 잡혀야 "과거 밴드 기준 고평가" 관성 판정을 막는다.
    val regime = RegimeDetector.detect(
        price = q.price,
        consensusTarget = consensusTarget,
        targetTrend = targetTrend,
        quarterlyYoyPct = quarterlyIncome?.yoyPct,
        perPercentile = valuationBand?.perPercentile,
    )
    // 판정 입력·결과 진단 로그 — 임계(MIN_SIGNALS 등) 조정 판단의 실측 근거(O1).
    println("[Regime] ${q.code} price=${q.price} target=$consensusTarget trend=${targetTrend?.direction ?: "-"}" +
        " yoy=${quarterlyIncome?.yoyPct?.let { "%.0f".format(it) } ?: "-"} perPct=${valuationBand?.perPercentile ?: "-"}" +
        " → ${regime?.label ?: "일반 국면"}(${regime?.signals?.size ?: 0}신호)")
    add("regime", regime?.let { "국면 판정(계산): ${it.label} — 근거: ${it.signals.joinToString("; ")}\n" })
    // 시장 맥락(C17) — 종목 등락이 시장 동반인지 고유 움직임인지 가릴 사실 근거.
    // 2026-07 주간 코스피 -7.6% 급락 때 종목 분석들이 시장 동반 조정을 종목 고유 서사로
    // 기술한 실사례가 계기(docs/regime-consistency-2026-07.md 갭 1).
    add("market_context", marketContext?.let { it + "\n" })
    add("sector_rs", sectorChangeRate?.let {
        val rs = q.changeRate - it
        val label = when {
            rs > 0.5  -> "섹터 대비 강세"
            rs < -0.5 -> "섹터 대비 약세"
            else      -> "섹터 수준"
        }
        "섹터 대비 상대강도(RS): ${if (rs >= 0) "+" else ""}${"%.1f".format(rs)}%p" +
            " (소속 섹터지수 ${if (it >= 0) "+" else ""}${"%.2f".format(it)}%, $label)\n"
    })
    add("short_selling", shortSelling?.let { ss ->
        buildString {
            appendLine("공매도(KRX 데이터):")
            appendLine("  최근 공매도 거래량: ${"%.0f".format(ss.recentVolume.toDouble())}주 (${ss.recentVolumeDate})")
            if (ss.balance != null && ss.balanceDate != null) {
                val balLine = StringBuilder("  공매도 잔고: ${"%.0f".format(ss.balance.toDouble())}주 (${ss.balanceDate} 확정)")
                if (ss.balanceChangePct != null) {
                    val dir = when {
                        ss.balanceChangePct > 1.0 -> "잔고 증가(하락 베팅 강화)"
                        ss.balanceChangePct < -1.0 -> "잔고 감소(숏커버링·하락 베팅 약화)"
                        else -> "잔고 보합"
                    }
                    balLine.append(", 전일 대비 ${if (ss.balanceChangePct >= 0) "+" else ""}${"%.1f".format(ss.balanceChangePct)}% ($dir)")
                }
                appendLine(balLine)
            } else {
                appendLine("  공매도 잔고: 집계 중(T+2일 지연)")
            }
        }
    })
    add("target_price", consensusTarget?.takeIf { it > 0 }?.let { target ->
        buildString {
            val upside = (target - q.price).toDouble() / q.price * 100
            appendLine(
                "애널리스트 컨센서스 목표주가: ${"%,d".format(target)}원" +
                    " (현재가 대비 ${if (upside >= 0) "+" else ""}${"%.1f".format(upside)}%)"
            )
            if (targetTrend != null) {
                // 우리가 누적한 스냅샷 기준. 목표가가 오르는 추세면 밸류 상단권을 시장이 더 높이 본다는 신호.
                val signed = "${if (targetTrend.changePct >= 0) "+" else ""}${"%.1f".format(targetTrend.changePct)}%"
                appendLine(
                    "  └ 컨센서스 목표가 추세: 최근 ${targetTrend.daySpan}일 ${targetTrend.direction} " +
                        "(${targetTrend.baselineDate} ${"%,d".format(targetTrend.baseline)}원 → 현재 ${"%,d".format(targetTrend.current)}원, " +
                        "$signed, 스냅샷 ${targetTrend.snapshotCount}개 기준)"
                )
            }
            // 목표가 이벤트 이력 — "매주 목표가가 올라간다"·"주가가 목표가를 뚫었다" 같은 리레이팅
            // 정황을 정량 사실로. 우리 스냅샷 누적 기준이라 초기엔 비어 있다가 시간이 지나며 차오른다.
            AnalysisService.targetEventsLine(targetEvents)?.let { appendLine(it) }
        }
    })
    add("week52", if (q.high52w > q.low52w && q.high52w > 0) {
        val pos = (q.price - q.low52w).toDouble() / (q.high52w - q.low52w) * 100
        val fromHigh = (q.price - q.high52w).toDouble() / q.high52w * 100
        "52주: 최고 ${q.high52w} / 최저 ${q.low52w} " +
            "(현재 위치 ${"%.0f".format(pos)}%, 고점 대비 ${"%.1f".format(fromHigh)}%)\n"
    } else null)
    // PER/PBR 는 두 소스가 공존한다(KIS 시세 vs 아래 밴드 자체계산 — 이익 연도·주식수 기준이 달라
    // 값이 다를 수 있음). 라벨 없이 병기하면 모델이 날마다 다른 값을 집어 코멘트 PER이 튀는 실사고가
    // 있었음(6/15 43.4배 → 6/17 52.7배, 주가는 +2.7%). 라벨로 구분하고 일관 사용은 프롬프트가 지시.
    add("per_kis", if (q.per > 0) "PER(KIS 시세 기준) ${q.per} / PBR(KIS 시세 기준) ${q.pbr}\n" else null)
    // 연환산(포워드) PER — 트레일링 PER은 작년 이익 기준이라 이익 급증 종목을 구조적으로
    // "고평가"로 보이게 한다. 최근 분기 누적을 연환산한 추정 PER을 병기해 그 편향을 사실로 보정.
    add("forward_per", AnalysisService.forwardPerLine(q.price, quarterlyIncome, listedShares)?.let { it + "\n" })
    // 산식 설명("현재가÷최근 연간 실적...")과 소스 혼용 주의(※)는 C8로 이관(1b) —
    // 종목 무관 고정 문구를 매 호출 정가인 facts에서 캐시되는 system으로.
    add("valuation_band", valuationBand?.takeIf { it.yearsUsed > 0 }?.let { vb ->
        buildString {
            // 적자 연도는 PER 히스토리에서 제외되므로(턴어라운드 종목) 표본이 적을 수 있다. 적으면 신뢰도 경고.
            val sampleNote = if (vb.yearsUsed < 3)
                " ※ 표본 ${vb.yearsUsed}년으로 적어(적자 연도 제외 등) 밴드 신뢰도 낮음 — 결론은 약하게, 참고만." else ""
            appendLine("밸류에이션 히스토리 밴드(자체 계산, 과거 ${vb.yearsUsed}년):$sampleNote")
            if (vb.perCurrent > 0 && vb.perMax > 0) {
                appendLine(
                    "  PER(자체 계산) 현재 ${"%.1f".format(vb.perCurrent)}배 " +
                        "→ ${vb.yearsUsed}년 밴드 " +
                        "[${"%.1f".format(vb.perMin)}~${"%.1f".format(vb.perMax)}배], " +
                        "중앙 ${"%.1f".format(vb.perMedian)}배 " +
                        "(${vb.perLabel})"
                )
            }
            if (vb.pbrCurrent > 0 && vb.pbrMax > 0) {
                appendLine(
                    "  PBR(자체 계산) 현재 ${"%.2f".format(vb.pbrCurrent)}배 " +
                        "→ ${vb.yearsUsed}년 밴드 " +
                        "[${"%.2f".format(vb.pbrMin)}~${"%.2f".format(vb.pbrMax)}배], " +
                        "중앙 ${"%.2f".format(vb.pbrMedian)}배 " +
                        "(${vb.pbrLabel})"
                )
            }
        }
    })
    add("peer_valuation", peerValuation?.takeIf { it.per != null || it.pbr != null }?.let { pv ->
        buildString {
            // 동종(같은 사업) 대비 상대 위치. 역사 밴드(자기 과거)와 다른 축 — 리레이팅 국면에서 특히 유효.
            appendLine("동종(${pv.clusterLabel}) 상대 밸류 — peer ${pv.peerCount}개 중앙값 대비:")
            pv.per?.let { m ->
                appendLine(
                    "  PER 현재 ${"%.1f".format(m.current)}배 vs 동종 중앙값 ${"%.1f".format(m.peerMedian)}배 " +
                        "(${if (m.diffPct >= 0) "+" else ""}${"%.0f".format(m.diffPct)}%, ${m.label})"
                )
            }
            pv.pbr?.let { m ->
                appendLine(
                    "  PBR 현재 ${"%.2f".format(m.current)}배 vs 동종 중앙값 ${"%.2f".format(m.peerMedian)}배 " +
                        "(${if (m.diffPct >= 0) "+" else ""}${"%.0f".format(m.diffPct)}%, ${m.label})"
                )
            }
        }
    })
    add("volume", "거래량: ${q.volume}\n")

    // 최근 가격 흐름 서사(일봉 계산) — "상한가 두 번 치고 며칠째 급락" 같은 흐름을 사실로 제공.
    // 서사는 최근 20일로 한정(60일 전체는 서사가 늘어짐), 앵커 계산은 아래에서 60일 전체 사용.
    add("price_action", priceActionSummary(bars.take(20))?.let { "\n" + it })

    // 기술적 앵커 — 공격 모드가 진입·손절 레벨을 "지어내지 않고" 여기 있는 값에 묶도록 사실로 제공.
    // (실사고: facts에 레벨이 없어 "310,000~320,000원 분할 진입" 같은 창작 레벨이 나갔음)
    add("technical_anchors", technicalAnchorsText(bars)?.let { "\n" + it })

    // 회사 재무(DART 연간) — 급등락이 펀더멘털 성장에 근거하는지 판단할 근거.
    add("financials", financialSummaryText(financials)?.let { "\n" + it })
    add("quarterly_income", quarterlyIncomeText(quarterlyIncome)?.let { "\n" + it })
    // 수주·재고 선행지표(분기 재무상태표 잔액 추이) — 수주산업의 계약부채는 수주잔고 근사 지표(C19).
    add("leading_indicators", leadingIndicatorsText(leading)?.let { "\n" + it })
    // 배당(DART 배당사항) — 주당배당금 추이 + 현재가 기준 예상 수익률(C20).
    add("dividend", dividendText(dividend, q.price)?.let { "\n" + it })

    add("flows", if (flows.isNotEmpty()) {
        buildString {
            appendLine("수급(일별 순매수 수량, +매수/-매도):")
            flows.forEach {
                appendLine("  ${it.date} 외국인 ${it.foreign} / 기관 ${it.institution} / 개인 ${it.individual}")
            }
        }
    } else null)
    add("backtest", backtestText(backtest)?.let { "\n" + it })
    add("flow_sensitivity", flowSensitivityText(flowSensitivity)?.let { "\n" + it })
    // 요약(description)은 최신 NEWS_DESC_TOP개 클러스터만 — 뉴스가 facts의 40%를 차지하던 실측(1a)의
    // 최대 절감 지점. 오래된 기사는 제목+날짜만으로 충분(C6가 낡은 재료 사용을 이미 제한).
    // 안내문("유사 기사는 묶음..." 66자)은 C6로 이관 — 종목 무관 고정 문구는 system(캐시)에 산다.
    add("news", if (news.isNotEmpty()) {
        buildString {
            appendLine("최근 뉴스:")
            news.forEachIndexed { i, c ->
                val more = if (c.count > 1) " (유사 외 ${c.count - 1}건)" else ""
                val dateLabel = newsDateLabel(c.item.publishedAt)?.let { ", $it" } ?: ""
                appendLine("  - [${c.item.source}$dateLabel] ${c.item.title}$more")
                if (i < NEWS_DESC_TOP && c.item.description.isNotBlank()) {
                    appendLine("    요약: ${c.item.description}")
                }
            }
        }
    } else null)
    // 임박 거시 이벤트(향후 2주) — 이 종목·업종 변동성에 영향 줄 예정 일정.
    add("events", eventsText?.let { "\n" + it })

    // 계좌 성격(장기 계좌 컨텍스트) — C18이 이 라벨("계좌 성격: 장기")에 걸려 단기 매매 지시를
    // 장기 관점으로 전환한다. 자유 계좌·구버전 앱은 이 줄이 없어 기존 코멘트 그대로.
    add("horizon", if (horizonLong)
        "\n계좌 성격: 장기 — 이 보유는 ISA·IRP·퇴직연금 등 장기 투자 계좌의 포지션이다(사용자가 장기 관점으로 관리).\n"
    else null)

    add("position", position?.let { p ->
        buildString {
            val currentPrice = q.price.toDouble()
            val pnlRate = if (p.avgPrice > 0)
                (currentPrice - p.avgPrice) / p.avgPrice * 100 else 0.0
            val pnlAmt = (currentPrice - p.avgPrice) * p.qty
            appendLine()
            appendLine("내 포지션 (실제 보유 데이터):")
            appendLine(
                "  평단가: ${p.avgPrice.toLong()}원, 보유수량: ${p.qty}주"
            )
            appendLine(
                "  평가손익: ${if (pnlAmt >= 0) "+" else ""}${"%.0f".format(pnlAmt)}원" +
                    " (${"%.1f".format(pnlRate)}%)"
            )
            if (p.targetPrice > 0) {
                val toTarget = (p.targetPrice - currentPrice) / currentPrice * 100
                appendLine(
                    "  목표가: ${p.targetPrice.toLong()}원" +
                        " (현재가 대비 ${if (toTarget >= 0) "+" else ""}${"%.1f".format(toTarget)}%)"
                )
            }
            if (p.stopPrice > 0) {
                val toStop = (p.stopPrice - currentPrice) / currentPrice * 100
                appendLine(
                    "  손절가: ${p.stopPrice.toLong()}원" +
                        " (현재가 대비 ${if (toStop >= 0) "+" else ""}${"%.1f".format(toStop)}%)"
                )
            }
        }
    })
    // 사용자가 기록한 투자 논지 — "사실 데이터"가 아니라 점검 대상 가설임을 라벨로 명시.
    // C12가 이 라벨("가설")에 걸려 확증편향·논지 인용을 막는다.
    add("thesis", thesis?.takeIf { it.isNotBlank() }?.let {
        "\n내 투자 논지 (사용자가 직접 기록한 보유/관심 이유 — 검증할 가설이며, 사실 데이터가 아님):\n  \"${it.trim()}\"\n"
    })
    // 논지 변천(C16 드리프트 점검) — 각 변경 시점의 주가를 일봉에서 조인해 "하락 후 논지 교체 =
    // 사후 합리화 가능성"을 계산 사실로 뒷받침한다. 이력 2건 미만이면 변천이 없으므로 생략.
    add("thesis_history", AnalysisService.thesisHistoryText(thesisHistory, bars)?.let { "\n" + it })
    return sections
}

/**
 * 일봉(최신일이 앞)에서 최근 가격 흐름을 사람이 읽는 서사로 요약.
 * 고점 대비 낙폭·급등(상한가 수준 포함)·연속 등락을 사실로만 적는다(해석은 Claude 몫).
 */
private fun priceActionSummary(bars: List<DailyBar>): String? {
    if (bars.size < 2) return null
    val closes = bars.map { it.close }
    val cur = closes.first()
    // 일별 등락률 = (당일종가 - 전일종가)/전일종가. rates[0] 이 가장 최근일.
    val rates = closes.zipWithNext { day, prev ->
        if (prev > 0) (day - prev).toDouble() / prev * 100 else 0.0
    }

    // 최근 고점 대비 낙폭 + 저점 대비 반등폭 — 둘 다 항상 병기한다. 고점 프레임만 주면
    // 모든 종목이 "고점에서 -x%"라는 하락 앵커로만 서술되는 비대칭이 생긴다(편향 리뷰 P2).
    val highIdx = closes.indices.minByOrNull { -closes[it] } ?: 0
    val high = closes[highIdx]
    val drawdown = if (high > 0) (cur - high).toDouble() / high * 100 else 0.0
    val lowIdx = closes.indices.minByOrNull { closes[it] } ?: 0
    val low = closes[lowIdx]
    val rebound = if (low > 0) (cur - low).toDouble() / low * 100 else 0.0

    // 가장 최근일 부호 기준 연속 등락 일수
    val firstSign = rates.firstOrNull()?.let { if (it > 0) 1 else if (it < 0) -1 else 0 } ?: 0
    var streak = 0
    if (firstSign != 0) {
        for (r in rates) {
            val s = if (r > 0) 1 else if (r < 0) -1 else 0
            if (s == firstSign) streak++ else break
        }
    }
    // 연속 구간 누적 등락률
    val streakSum = rates.take(streak).sum()

    // 급변 이벤트는 상승·하락 대칭으로 센다 — 급등만 세면 "하루 -20% 폭락 후 반등"의
    // 폭락이 서사에서 사라지는 상승 편향이 생긴다(편향 리뷰 P2).
    val limitUps = rates.count { it >= 29.0 }  // 상한가 수준(+30% 제한 근처)
    val surges = rates.count { it in 15.0..29.0 } // 상한가는 아니지만 급등
    val limitDowns = rates.count { it <= -29.0 } // 하한가 수준
    val plunges = rates.count { it in -29.0..-15.0 } // 하한가는 아니지만 급락

    val sb = StringBuilder()
    sb.appendLine("최근 ${bars.size}거래일 가격 흐름:")
    sb.appendLine(
        "  최근 고점 ${high}원(약 ${highIdx}거래일 전) 대비 현재 ${"%.1f".format(drawdown)}%" +
            " / 최근 저점 ${low}원(약 ${lowIdx}거래일 전) 대비 ${if (rebound >= 0) "+" else ""}${"%.1f".format(rebound)}%"
    )
    val moves = buildList {
        if (limitUps > 0) add("상한가 수준(+29% 이상) 급등 ${limitUps}회")
        if (surges > 0) add("+15~29% 급등 ${surges}회")
        if (limitDowns > 0) add("하한가 수준(-29% 이하) 급락 ${limitDowns}회")
        if (plunges > 0) add("-15~29% 급락 ${plunges}회")
        if (streak >= 2) add("최근 ${streak}거래일 연속 ${if (firstSign > 0) "상승" else "하락"}(누적 ${"%.1f".format(streakSum)}%)")
    }
    if (moves.isNotEmpty()) sb.appendLine("  " + moves.joinToString(", "))
    return sb.toString()
}

/**
 * 기술적 앵커(일봉 계산, 최신일이 앞) — 매매 레벨 제시의 사실 근거.
 * 최근 20일 저점/고점 + MA20 + MA60(표본 60개 있을 때만). 판단 없이 값만.
 */
private fun technicalAnchorsText(bars: List<DailyBar>): String? {
    if (bars.size < 20) return null
    val closes = bars.map { it.close }
    val recent20 = closes.take(20)
    val low20 = recent20.min()
    val high20 = recent20.max()
    val ma20 = recent20.average()
    val ma60 = if (closes.size >= 60) closes.take(60).average() else null
    val sb = StringBuilder()
    sb.appendLine("기술적 앵커(레벨 제시용 사실 값, 종가 기준):")
    sb.appendLine("  최근 20거래일 저점 ${"%,d".format(low20)}원 / 고점 ${"%,d".format(high20)}원")
    sb.append("  20일 이동평균 ${"%,.0f".format(ma20)}원")
    if (ma60 != null) sb.append(", 60일 이동평균 ${"%,.0f".format(ma60)}원")
    sb.appendLine()
    return sb.toString()
}

/** 네이버 pubDate(RFC-1123, 예 "Mon, 15 Jun 2026 14:30:00 +0900") → "6/15". 파싱 실패 시 null(라벨 생략). */
private fun newsDateLabel(publishedAt: String): String? = runCatching {
    val dt = ZonedDateTime.parse(publishedAt.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
    "${dt.monthValue}/${dt.dayOfMonth}"
}.getOrNull()

/**
 * 백테스트(신호별 익일 적중률)를 Claude 입력용 텍스트로. 신뢰 가능한(confident) 신호만 적는다.
 * 표본이 작고 특정 기간 한정이라는 한계를 명시해 과신을 막는다.
 */
private fun backtestText(b: Backtest?): String? {
    if (b == null) return null
    val confident = b.signals.filter { it.confident && it.n > 0 }
    if (confident.isEmpty()) return null
    val sb = StringBuilder()
    sb.appendLine(
        "검증된 신호(이 종목 최근 ${b.tradingDays}거래일 실측, " +
            "평소 익일 상승확률 ${b.baselineWinRate}%·평균 ${"%.2f".format(b.baselineAvgReturn)}%):"
    )
    confident.forEach { s ->
        val edgeSign = if (s.edge >= 0) "+" else ""
        sb.appendLine(
            "  ${s.signal}일(n=${s.n}): 익일 상승확률 ${s.winRate}% / 평균 ${"%.2f".format(s.avgReturn)}%" +
                " (평소 대비 $edgeSign${"%.2f".format(s.edge)}%p)"
        )
    }
    // "과거 통계일 뿐·소표본 오차 ±20%p" 주의문은 C9로 이관(1b) — 고정 문구는 system(캐시)에.
    return sb.toString()
}

/** 수급-가격 민감도(Pearson 상관)를 Claude 입력용 텍스트로. confident 항목만. */
private fun flowSensitivityText(fs: FlowSensitivity?): String? {
    if (fs == null) return null
    val confident = fs.items.filter { it.confident }
    if (confident.isEmpty()) return null
    val sb = StringBuilder()
    sb.appendLine("수급-가격 민감도(이 종목 수급 규모와 당일 등락률의 순위 상관, 과거 표본):")
    confident.forEach { c ->
        val rSign = if (c.r >= 0) "+" else ""
        sb.appendLine("  ${c.investor}(n=${c.n}): r=$rSign${c.r}, ${c.label}")
    }
    // 상관 해설·"과거 한정 미래 보장 아님" 주의문은 C9로 이관(1b) — n은 각 행에 이미 병기.
    return sb.toString()
}

/**
 * 연간 재무 요약을 사람이 읽는 텍스트로(단위 억원, 전년比 YoY 포함).
 * 매출·영업이익·순이익 중 있는 것만 적는다. 전부 없으면 null.
 */
private fun financialSummaryText(f: FinancialSummary?): String? {
    if (f == null) return null
    val lines = buildList {
        financeLine("매출액", f.revenue, f.revenuePrev)?.let { add(it) }
        financeLine("영업이익", f.operatingProfit, f.operatingProfitPrev)?.let { add(it) }
        financeLine("당기순이익", f.netIncome, f.netIncomePrev)?.let { add(it) }
    }
    if (lines.isEmpty()) return null
    val basis = if (f.consolidated) "연결" else "별도"
    val sb = StringBuilder()
    sb.appendLine("회사 재무(DART $basis 사업보고서 ${f.fiscalYear}년, 단위 억원):")
    lines.forEach { sb.appendLine("  $it") }
    return sb.toString()
}

/** 분기 실적 방향 — "2026년 1분기 누적 순이익 X억 (전년 동기 대비 +Y%, 개선)" 한 줄. */
private fun quarterlyIncomeText(q: QuarterlyIncome?): String? {
    if (q == null) return null
    val ni = q.netIncome ?: return null
    val niEok = ni / 100_000_000
    val yoy = q.yoyPct
    val direction = when {
        yoy == null -> ""
        yoy > 10    -> " (실적 개선)"
        yoy < -10   -> " (실적 악화)"
        else         -> " (전년 동기와 유사)"
    }
    val yoyText = if (yoy != null) ", 전년 동기 대비 ${if (yoy >= 0) "+" else ""}${"%.1f".format(yoy)}%$direction" else ""
    return "${q.label} 누적 순이익: ${"%,d".format(niEok)}억$yoyText\n"
}

/**
 * 수주·재고 선행지표 — 분기말 잔액 시리즈(오래된 순)를 "2025.1Q 1,234 → … → 2026.1Q 1,700" 형태로.
 * 시리즈 첫·끝이 정확히 1년 차이(같은 분기)면 전년 동기 대비 %를 병기(분기 결측 시 생략 —
 * 다른 분기끼리의 비율은 계절성 때문에 오독 유발). 지표별 포인트 2개 미만이면 그 줄 생략,
 * 전부 없으면 null(섹션 자체 생략). 해석 지침은 C19.
 */
internal fun leadingIndicatorsText(li: LeadingIndicators?): String? {
    val qs = li?.quarters ?: return null
    fun eok(v: Long) = "%,d".format(Math.round(v / 1e8))
    fun signed(p: Double) = "${if (p >= 0) "+" else ""}${"%.1f".format(p)}%"

    fun seriesLine(name: String, metric: (LeadingQuarter) -> Long?): String? {
        val pts = qs.mapNotNull { q -> metric(q)?.let { q to it } }
        if (pts.size < 2) return null
        val body = pts.joinToString(" → ") { (q, v) -> "${q.label} ${eok(v)}" }
        val (fq, fv) = pts.first()
        val (lq, lv) = pts.last()
        val yoy = if (lq.year == fq.year + 1 && lq.quarter == fq.quarter && fv != 0L)
            " (전년 동기 대비 ${signed((lv - fv).toDouble() / kotlin.math.abs(fv) * 100)})" else ""
        return "  $name: $body$yoy"
    }

    // 매출은 연초 누적이라 시리즈로 이으면 연말→연초에 뚝 떨어져 보인다 → 전년 동기 1점 비교만.
    fun revenueLine(): String? {
        val last = qs.lastOrNull { it.revenueCum != null } ?: return null
        val prev = qs.firstOrNull { it.year == last.year - 1 && it.quarter == last.quarter && it.revenueCum != null }
            ?: return null
        val lv = last.revenueCum!!
        val pv = prev.revenueCum!!
        if (pv == 0L) return null
        val pct = (lv - pv).toDouble() / kotlin.math.abs(pv) * 100
        return "  매출액(연초 누적): ${last.label} ${eok(lv)} vs 전년 동기 ${eok(pv)} (${signed(pct)})"
    }

    val contractName = if (qs.any { it.contractLiabIsAdvance }) "선수금(계약부채 미표기 회사, 같은 성격)" else "계약부채"
    val lines = listOfNotNull(
        seriesLine(contractName) { it.contractLiabilities },
        seriesLine("재고자산") { it.inventories },
        seriesLine("매출채권") { it.tradeReceivables },
        revenueLine(),
    )
    if (lines.isEmpty()) return null
    return buildString {
        appendLine("수주·재고 선행지표(DART 분기 재무제표 자체 추출, 분기말 잔액, 단위 억원):")
        lines.forEach { appendLine(it) }
    }
}

/**
 * 배당 사실 블록. 주당배당금 3년 추이 + 현재가 기준 예상 수익률(사실 계산) + 당시 시가배당률·배당성향 참고.
 * 배당락 확정일은 다루지 않는다(상법 개정 후 불규칙) — 결산월만 사실로 제시하고 해석은 C20이 규율한다.
 */
internal fun dividendText(div: DividendInfo?, currentPrice: Long): String? {
    val d = div ?: return null
    fun won(v: Long) = "%,d원".format(v)
    fun signed(p: Double) = "${if (p >= 0) "+" else ""}${"%.1f".format(p)}%"

    // 주당배당금 추이 — 오래된 순으로 있는 값만. (연도, 값) 구성.
    val series = listOfNotNull(
        d.dpsPrev2?.let { (d.fiscalYear - 2) to it },
        d.dpsPrev?.let { (d.fiscalYear - 1) to it },
        (d.fiscalYear to d.dpsThis),
    )
    val trendBody = series.joinToString(" → ") { (y, v) -> "$y ${won(v)}" }
    val yoy = d.dpsYoyPct?.let { " (전년 대비 ${signed(it)})" } ?: ""

    val expectedYield = if (currentPrice > 0L) d.dpsThis.toDouble() / currentPrice * 100 else null

    val refParts = listOfNotNull(
        d.yieldPctAtRecord?.let { "배당 시점 시가배당률 ${"%.1f".format(it)}%" },
        d.payoutPct?.let { "현금배당성향 ${"%.1f".format(it)}%" },
        d.settleDate?.substring(5, 7)?.toIntOrNull()?.let { "결산월 ${it}월" },
    )

    return buildString {
        appendLine("배당(DART 배당사항, 최신 확정 ${d.fiscalYear} 사업연도 기준):")
        appendLine("  주당 현금배당금: $trendBody$yoy")
        if (expectedYield != null)
            appendLine("  현재가(${won(currentPrice)}) 기준 예상 배당수익률: ${"%.2f".format(expectedYield)}% (최신 주당배당금 ÷ 현재가, 차기 배당 미확정)")
        if (refParts.isNotEmpty())
            appendLine("  참고: ${refParts.joinToString(", ")}")
    }
}

/** "매출액 1,234억 (전년 1,000억, YoY +23.4%)" 형태. 당기 없으면 null. */
private fun financeLine(label: String, cur: Long?, prev: Long?): String? {
    if (cur == null) return null
    val curEok = cur / 100_000_000
    val sb = StringBuilder("$label ${"%,d".format(curEok)}억")
    if (prev != null && prev != 0L) {
        val prevEok = prev / 100_000_000
        val yoy = (cur - prev).toDouble() / kotlin.math.abs(prev) * 100
        sb.append(" (전년 ${"%,d".format(prevEok)}억, YoY ${if (yoy >= 0) "+" else ""}${"%.1f".format(yoy)}%)")
    }
    return sb.toString()
}

// ── 유사 뉴스 클러스터링 ───────────────────────────────────────────────
// 같은 이슈로 도배된 기사를 한 건으로 묶되, 제목이 비슷해도 요약(description)에 유의미한
// 추가 정보가 있으면 별건으로 둔다(사용자 요구). 최신순 입력 → 대표 limit 건 반환.

internal data class NewsCluster(val item: NewsItem, val count: Int)

private class MutableCluster(
    val item: NewsItem,
    val titleTokens: Set<String>,
    val descTokens: Set<String>,
    var count: Int = 1,
)

internal fun dedupeNews(items: List<NewsItem>, limit: Int): List<NewsCluster> {
    val reps = mutableListOf<MutableCluster>()
    for (it in items) {
        val tTok = tokens(it.title)
        val dTok = tokens(it.description)
        // 제목이 비슷하고(>=0.5) 요약도 비슷하면(>=0.6) 같은 기사로 보고 묶는다.
        // 제목만 비슷하고 요약이 다르면 → 추가 정보가 있다고 보고 별건 유지.
        val match = reps.firstOrNull { r ->
            jaccard(tTok, r.titleTokens) >= 0.5 && jaccard(dTok, r.descTokens) >= 0.6
        }
        if (match != null) match.count++
        else reps.add(MutableCluster(it, tTok, dTok))
    }
    return reps.take(limit).map { NewsCluster(it.item, it.count) }
}

/** 한국어/영문/숫자 토큰 집합(2자 이상). 대소문자 무시. */
private fun tokens(s: String): Set<String> =
    s.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .toSet()

/** 자카드 유사도. 둘 다 비면 1.0, 한쪽만 비면 0.0. */
private fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val inter = a.count { it in b }
    return inter.toDouble() / (a.size + b.size - inter)
}

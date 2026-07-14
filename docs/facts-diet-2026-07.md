# facts 컨텍스트 다이어트 — 1a 계측 리포트 (2026-07-14)

Fable 트랙 2차 ①(정본 스펙: fable-tracks-2-spec.md). 측정 없이 교정 없음(O1/D1 원칙) — 이 문서가 1b 교정의 근거.

## 방법

- `buildFacts`를 `FactsText.kt`의 라벨된 섹션 빌더(`buildFactsSections`)로 리팩터 — **출력 바이트 동일을 FactsGoldenTest(합성 rich/minimal 골든 파일)가 강제**. 이후 facts 구조 변경은 골든 diff 리뷰가 게이트.
- `GET /facts-audit`(1회성 관리 라우트): 관심 11종목 `collectFacts()` 실행 → 블록별 char 실측 + 합성 최대 콤보(포지션+논지 200자+변천 5건+장기 계좌) + 프롬프트 상수 크기. LLM 호출 없음.
- 실토큰 표본: 삼성에스디에스(018260) 방어 모드 실호출 1건의 usage diff(ClaudeUsageTracker 일 파일 전후 비교).

## 실측 결과 (2026-07-14, 관심 11종목)

### char→token 환산 (실호출 1건 표본)

| 대상 | chars | tokens | 환산 |
|---|---|---|---|
| system (analysis_defensive) | 7,007 | 7,050 (cache_creation) | **1.006 tok/char** |
| user facts (018260) | 4,159 | 3,789 (input) | **0.911 tok/char** |

한국어 혼합 텍스트 실측 ≈ **0.9~1.0 tok/char** — 통념(1.3~1.6)보다 낮다. 이하 char ≈ token으로 근사.

### 요청당 입력 토큰 구조 (analysis 1회, 방어 모드)

- **system 프롬프트 ~7.0k tok** — cache_control(ephemeral) 대상: 첫 호출 cache_creation(정가 1.25×), 5분 내 재호출은 cache_read(0.1×). 프리웜 11종목 연속 생성에선 사실상 1회 비용.
- **user facts ~3.7k tok(평균)** — **매 호출 정가.** 다이어트의 대상은 여기.
- 메타 최대 콤보(개인화 요청 상한): 5,822 chars ≈ +42% (thesis_history 1,200 + thesis 261 + position 135 + horizon 68).

### 블록별 크기 (11종목, 평균 총량 4,105 chars)

| 블록 | 존재 | 평균 chars | 비중 |
|---|---|---|---|
| **news** | 11 | **1,656** | **40.3%** |
| valuation_band | 11 | 286 | 7.0% |
| backtest | 11 | 276 | 6.7% |
| flows | 11 | 249 | 6.1% |
| events | 11 | 222 | 5.4% |
| target_price | 11 | 184 | 4.5% |
| flow_sensitivity | 11 | 181 | 4.4% |
| financials | 11 | 154 | 3.8% |
| price_action | 11 | 112 | 2.7% |
| short_selling | 11 | 109 | 2.7% |
| technical_anchors | 11 | 106 | 2.6% |
| forward_per | 10 | 109 | 2.4% |
| peer_valuation | 8 | 131 | 2.3% |
| header~per_kis 등 잔여 7종 | — | 각 12~81 | 합 ~8% |
| regime | 2 | 128 | 0.6% |

### 프롬프트(system) 상수 크기

| 트리거 | chars |
|---|---|
| analysis_defensive / aggressive | 7,007 / 7,488 |
| ask_defensive / aggressive | 2,414 / 2,585 |
| portfolio_defensive / aggressive | 2,246 / 2,523 |
| market_mood_defensive / aggressive | 1,441 / 1,844 |
| comparison_defensive / aggressive | 1,092 / 823 |

## 1b 교정 우선순위 (절감폭 × 품질 리스크)

1. **news 압축** — 유일한 지배 항목(40%). 후보: description 절단 길이 도입(제목은 유지 — 촉매 식별의 본체는 제목+날짜, description은 보조). description이 블록의 약 2/3로 추정 → 절단 시 전체 facts ~10~15% 절감. **리스크 中**(수주 금액 등 본문에만 있는 수치 손실 가능 — 절단 길이를 보수적으로, 스트레스 실호출로 품질 확인).
2. **facts 안 고정 설명문의 system 이관** — 종목 무관 문구(밸류밴드 ※주의 103c, 백테스트 ※ 105c, 민감도 ※ ~90c, 뉴스 안내문 66c 등 합 ~370c/종목 ≈ 9%). system은 캐시라 반복 호출에서 0.1×. **리스크 低**(값과 해석 지시의 분리 — 조건부 문구는 "해당 항목이 있으면" 형태로 일반화).
3. **메타 블록 통합** — 논지·변천·직전분석·계좌성격 라벨/주의문 1회화. 평시엔 미발동이라 절감 0, 콤보 시 ~100~200c. **우선순위 낮음** — 9월 스탠스 성적표 주입 때 함께.
4. flows·valuation_band 포맷 압축 — 각 ~250~290c로 작아 보류(②-1 교훈: 판단 기여 없는 비대만 제거, "짧게" 자체가 목표 아님).

**예상 절감(1+2 시행 시): user facts 기준 약 20%p 안팎** — 시행 후 /facts-audit 재실행으로 실측 보고(완료 기준).

## 한계·메모

- 환산 표본 n=1(방어 모드) — 1b 전후 비교는 같은 방법의 diff라 상대 비교엔 충분.
- regime 발동 2/11 — 국면 판정 블록은 비용 무시 가능.
- ClaudeUsageTracker는 일 합계만(트리거 구분 없음) — 트리거별 분해 계측(스펙 1a-4, 선택)은 미시행. 필요해지면 record()에 trigger 추가.
- 보류 2건(섹터 순환 종목 주입·브리핑 상호 참조) 재평가는 1b 완료 후.

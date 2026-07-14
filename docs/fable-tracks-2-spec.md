# Fable 트랙 2차 — 3종 실행 스펙 (2026-07-13 분석, 미착수)

1차 트랙(`fable-tracks-spec.md`)·캘리브레이션 트랙(메모리 edge-calibration-track) 완료 후 뽑은 다음 Fable급 3종.
**순서: ① facts 다이어트 → ② 판정 실증 4탄(Analog·Discovery) → ③ MoodLog 가중치 백테스트.**
①이 보류 항목 2개(섹터 순환 종목 주입·메타 블록 비대)를 풀고, ②·③은 인프라(2년 이력 실측)가 겹쳐 연속 진행이 싸다.
각 트랙 착수 전 슬라이스+모델 재확인(step-recommendation 워크플로). **이 문서가 3트랙의 정본.**
9월(②-2 regime 채점+①-(b) 스탠스 성적표)·10월(catalyst 재실측) 예정분과 독립 — 데이터 성숙 불필요한 것만 골랐다.

| 트랙 | 성격 | 슬라이스 | 모델 |
|---|---|---|---|
| ① facts 다이어트 | 비용·구조 감사 | 1a 계측 → 1b 교정 | Fable |
| ② 판정 실증 4탄 | 정확도 검증 | 2a Analog → 2b Discovery | Fable |
| ③ MoodLog 가중치 실측 | 정확도 검증 | 3a 실측 → 3b 교정 | Fable |

---

## ① facts 컨텍스트 다이어트 (계측 → 교정)

**왜**: 메타 블록 비대가 두 번 "관찰 지속"으로 기록됐고(프롬프트 감사 4탄 LOW·국면일관성 보류),
**섹터 순환 신호의 종목 facts 주입이 이것 때문에 보류** 중. 해석 트리거 전부 기본 Opus라 입력 토큰이 비용 본체.

### 현황 분석 (2026-07-13 코드 기준)

- `AnalysisService.collectFacts()`(294행~)가 17+ 소스 병렬 수집, `factsText`(392행~)가 한 문자열로 조립.
  블록 목록: 시장상태·현재가·경보칩·국면판정·**시장맥락(C17)**·공매도·목표가 이벤트·PER 이중소스·포워드PER·
  밸류밴드(+산식 주석 3줄)·peer 상대밸류·가격흐름 서사·기술앵커·연간재무·분기실적·수급 5일 로우·백테스트·
  수급민감도·뉴스 8클러스터(+description)·이벤트 캘린더·**내 포지션**·**논지**·**논지 변천(C16)**·**직전 분석 기록(C13)**.
- 메타(판단 이력) 블록 4종이 각자 다른 시점에 추가되며 라벨·주의문이 제각각 — 통합 여지.
- **캐시 구조가 핵심 레버**: `ClaudeClient`는 system 마지막 블록에 `cache_control(ephemeral)`(115행) —
  system은 프롬프트 캐시 90% 할인, **facts(user message)는 매 호출 정가**. 그런데 facts 안에
  종목 무관 고정 설명문이 다수 산다(예: 밸류밴드 산식·"KIS 기준/자체 계산" 주의(517행)·뉴스 안내문(558행)·
  수급 포맷 설명·논지/직전분석 괄호 주의문). 이 고정분을 system 쪽으로 이관하면 반복 호출에서 캐시로 떨어진다.
- 계측 인프라: `ClaudeUsageTracker`는 **일 합계만**(트리거·서비스 구분 없음) — 블록별/트리거별 분해 계측이 1a의 일.
- 같은 문제가 정도만 다르게 다른 서비스에도 있음: ComparisonService·PortfolioReviewService·MarketMoodService(성적표 주입)·ask.

### 1a — 계측 (측정 없이 교정 없음, O1/D1 원칙)

1. **블록별 크기 실측**: 관리 라우트 `GET /facts-audit`(1회성, 운영 기능 아님) — 관심 11종목 `collectFacts()` 실행 →
   블록별 char 수 + 종목별 총합 + 최대 콤보(포지션+논지+변천+직전스탠스) 크기. 블록 경계는
   factsText를 **라벨된 섹션 빌더로 리팩터**해 얻는다(측정 부산물로 구조도 정리 — 출력 문자열은 바이트 동일 보장, 골든 테스트).
2. **프롬프트 상수 크기**: DEFENSIVE/AGGRESSIVE_PROMPT(=CORE+COMMON_RULES(1100행~)+FINAL_GUARD+STANCE_TAG)·ASK·PORTFOLIO·COMPARISON 등 char 집계(정적).
3. **실토큰 표본**: 실호출 2~3건의 usage(input/cache_read/cache_creation)로 char→token 환산 검증.
4. (선택) `ClaudeUsageTracker.record()`에 trigger 파라미터 추가 — 트리거별 일 사용량 분해(운영 상시 계측, S6 비용모니터에 병기).

**산출**: `docs/facts-diet-2026-07.md`에 블록별 토큰 표 + 교정 우선순위(절감폭×품질 리스크).

### 1b — 교정 (측정 결과로 후보 확정, 아래는 가설)

- **고정 설명문의 system 이관**(캐시 적중화): facts엔 값만, 해석 지시·산식 설명은 system으로. 주의 — system은
  전 종목 공유이므로 "해당 데이터 없을 때"의 조건부 문구 정리 필요.
- **메타 블록 통합**: 논지·변천·직전분석·(9월 예정 스탠스 성적표)를 "판단 이력" 단일 섹션으로 — 중복 주의문 1회화.
  ⚠️ NumberGuard 화이트리스트 로직(198행: 가드 기준은 주입 전 facts — 직전 분석 블록의 낡은 수치 제외)이
  블록 위치에 의존하므로 이동 시 가드 기준 재확인 필수.
- **데이터 블록 압축**: 뉴스 description 절단 길이·수급 5일 로우 포맷·밸류밴드 연도별 상세의 요약화 등 — 측정 상위 항목만.
- **완료 후 재평가**: 보류 2건 — 섹터 순환 종목 주입(국면일관성 보류분), 브리핑 상호 참조.

**검증**: ⑴ 섹션 빌더 리팩터 골든 테스트(교정 전 바이트 동일) ⑵ 스트레스 콤보 실호출(감사 4탄 방식 — 메타 3종 동시,
005930)로 품질·`### 소제목` 파싱 계약·스탠스 태그 불변 ⑶ 교정 후 /facts-audit 재실행으로 절감 %p 수치 보고
⑷ NumberGuard·SummaryPriceGuard 회귀 테스트. **완료 기준: 트리거당 입력 토큰 절감률 수치 + 품질 불변 실호출 증빙.**

### 리스크
- 캐시 이관은 system 블록 수 증가 → cache_control 블록 위치(마지막 블록) 규약 유지 확인.
- "짧게" 자체가 목표 아님 — 실측에서 판단 기여 없는 비대만 제거. ②-1 교훈(반대 교정 금지) 준용.

---

## ② 판정 실증 4탄 — Analog·Discovery (②-1 catalyst·②-3 anchor 패턴)

6개 판정 시스템 중 미실증 2개. 둘 다 LLM 0 계산 시스템이라 실측이 곧 판정. `AnchorValidationService`
패턴 재사용: 생성자 `(history, master, codes)` + 1회성 관리 라우트 + 정본 리포트 문서 + 사전 지정 판정 기준.

### 2a — Analog 캘리브레이션 (`GET /analog-validation`)

**질문**: 유사 국면 카드가 보여주는 forward 분포("5일 승률 75%")가 사후에 맞는 분포였나 — 예측이 아니라 **캘리브레이션** 검증.

**방법** (walk-forward replay):
- `AnalogService.compute()`는 순수 함수 + `vectorAt`이 look-ahead 안전(idx까지만 사용) — **그대로 재사용**.
- 이력 확장: `history.getHistory(code, minBars = 750)` — DailyHistoryService MAX_PAGES=8이라 최대 800봉.
  replay 가능 t는 `[MIN_HISTORY+60, n-1-60]` — 750봉이면 종목당 ~377일, **5거래일 간격 샘플링**(클러스터 자기상관 완화,
  통계감사 교훈: 창 겹침 비독립) → 종목당 ~75표본 × 11종목 ≈ 800표본.
- 각 replay일 t: bars[0..t]로 compute() → 예측 winRate·median (5/20일) vs 실현 fwd 수익률.

**측정** (사전 지정 — 다중비교 방지):
1. **캘리브레이션**: 예측 winRate 3버킷(<45 / 45~60 / >60) → 버킷별 실현 양수율. 단조 증가 여부가 본선.
2. **판별력**: Spearman(예측 median, 실현 수익률) — heavy-tail이라 순위 상관(통계감사 규칙).
3. **베이스라인 대비**: "무조건 직전 추세 유지" 나이브 예측과 부호 일치율 비교.
4. n<15 버킷은 침묵(표본 카운트 병기).

**교정 범위** (Analog는 facts 미주입·카드 전용 — Application.kt 258행 확인):
- 캘리브레이션 실패 → 카드 caveat 강화("과거 분포일 뿐" → 실측 수치 병기) 또는 winRate 숨김(분포 범위만).
- 성공해도 facts 주입 확대는 **별도 판단**(①의 비대 해소와 상충 — 자동 확대 금지).
- 정본: `docs/discovery-analog-validation-2026-07.md`(2a+2b 한 문서).

**한계 (기록 의무)**: 관심 11종목 = 강세주 선택 편향(②-3과 동일). 750봉 확보 실패 종목은 500봉 폴백(표본 감소 병기).

### 2b — Discovery 신호 실측 (`GET /discovery-validation`)

**질문**: 후보 발굴 컷(신호 2개 교집합·상대모멘텀 +5%p·신고가 90%·저점반등 30%/+5%)이 전부 손짐작 값 — 발굴된 후보가
실제로 코스피 대비 초과수익을 냈나.

**제약 (설계를 좌우)**:
- **수급전환은 이력 재구성 불가** — KIS `getInvestorFlow`는 최근 N일만 반환(과거 임의 시점 수급 이력 API 없음).
  → 백테스트는 **가격 기반 3신호만**(상대모멘텀·신고가근접·저점반등). 수급전환은 F4 때 백테스트 근거가 이미 있고,
  운영 discovery FileCache 누적분(7/8~, CACHE_DIR=GCS)으로 실후보 소표본 추적만 보조로.
- 52주 위치: 라이브는 `quote.high52w/low52w`, 백테스트는 252일 창 high/low 재계산 — 근사 차이 caveat 병기.
- 코스피 벤치마크 2년: Yahoo `^KS11`(SensitivityValidationService.fetchYahooHistory 패턴 재사용)이 가장 간단.
  KIS index chart range는 페이지네이션 미검증이라 피한다.

**방법**:
- 유니버스: `PeerValuationService.peerUniverse()` 전 종목(관심 제외 없이 — 라이브와 다름을 caveat) × 750봉.
- 이벤트: 각 날짜 t에 3신호 평가(`DiscoveryService.evaluateSignals` 임계 상수 재사용) →
  버킷: 단일 신호별 / 2신호 교집합(라이브 컷) / 전체 일자 베이스라인.
- 채점: forward 5/20거래일 **코스피 대비 초과수익률**(②-1 방식), **날짜 distinct**(같은 날 복수 신호 중복 카운트 금지 —
  통계감사 규칙), 연속 발화는 첫 발화만(±5일 클러스터, anchor 방식).
- 컷 민감도는 그리드 튜닝 금지(F1 교훈) — 상대모멘텀 +5%p에 한해 {+3, +7} 2값만 사전 지정 비교.

**교정 후보**: 신호별 무근거 판정 시 해당 신호 제거 또는 MIN_SIGNALS 조정 / caveat에 실측 수치 병기 /
브리핑 카드 문구("관찰 후보"의 통계적 근거 or 부재 명시).

---

## ③ MoodLog 방향예측 가중치 백테스트 (`GET /moodweight-validation`)

**질문**: `MarketMoodLogService.LEADING_WEIGHTS`(nasdaq 3·sp500 3·dow 2·ewy 3·sox 1·rut 1·dxy −2·usdkrw −2·
nqfut 2·esfut 2·ymfut 1)와 composite 임계 ±0.5가 전부 손짐작. 라이브 성적 43%(3분류, 기준선 ~33%)를
21건 표본으로 판단하지 말고 **2년 이력으로 실측**. ①-(a)가 주입하는 성적표의 모수 자체를 개선.

**데이터** (SensitivityValidationService 인프라 재사용 — `fetchYahooHistory`를 private에서 공유 유틸로 추출 판단):
- Yahoo 2y: `^IXIC`(nasdaq)·`^GSPC`·`^DJI`·`EWY`·`^SOX`·`^RUT`·`DX-Y.NYB`(dxy)·`KRW=X`(usdkrw)·`^KS11`(코스피 정답).
- **선물 3종(nqfut·esfut·ymfut)은 백테스트 불가 명시**: 라이브는 08시 KST 야간 세션 중간 스냅샷을 쓰는데
  이력의 선물 일봉 종가는 지수 종가와 사실상 동일 정보 — 재구성 불가. → 백테스트는 지수·환율 8지표분만 검증,
  선물 기여분은 라이브 MoodLog 성숙 후(9월, ②-2와 함께) 재실측.
- 정렬: 미국 지표 = T-1 세션 종가 등락 → 한국 T일 예측. usdkrw·dxy도 T-1(08시엔 당일 미형성).
  Yahoo FX 봉 UTC 타이밍 함정은 SensitivityValidation의 Timing 처리 참조.

**측정** (사전 지정):
1. **지표별 단변량**: T-1 등락 부호 vs T일 코스피 부호 일치율 + Spearman(등락률, 코스피 등락률) — SUPPORTED/CONTRADICTED/INCONCLUSIVE 판정(D1 임계 재사용: 일치≥54% 등).
2. **현행 시스템 재현**: `inferDirection` 로직을 이력에 적용 → 3분류 정확도 vs 기준선(33%·다수 클래스 비율 둘 다 병기 — 감사 4탄 F5 교훈).
3. **클래스 구조 점검**: classifyActual ±0.3% 중립 밴드의 클래스 분포 — 중립 버킷 구조적 불리 여부(통계감사 ①-(b) 주입 시 명시 사항과 동일 관점).
4. **교정안 탐색은 보수적으로**: 부호 교정·무근거 지표 제거·정수 가중치(1~3) 소폭 조정만. 연속 최적화 금지(F1 교훈).
   **홀드아웃 필수**: 전반 1년 적합 → 후반 1년 검증. 홀드아웃에서 개선 없으면 현행 유지 + 결과만 기록.

**교정 반영 (3b)**:
- LEADING_WEIGHTS·임계 수정 + decisions.md 기록 + 방향 고정 회귀 테스트(D2 방식).
- **성적표 연속성 처리 설계 필요**: 가중치가 바뀌면 과거 MoodLog 성적과 단절 — 성적표 주입 문구에 교정일 이후 표본만
  쓸지, 전체+교정일 병기할지 결정(①-(a) 규칙 9와 정합 유지). MoodLogEntry에 가중치 버전 태그 추가 검토.
- MarketMoodService 프롬프트 규칙 7(선물=선행신호) 유지 — 백테스트가 선물을 못 봤을 뿐 기각 아님.

**한계**: 2년 = 특정 국면(2024H2~2026H1) 편중 / 코스피 방향의 예측 가능성 자체가 낮을 수 있음 —
"전부 무근거"도 정직한 결과(그 경우 성적표 주입 문구를 "예측력 낮음 인지"로 강화하는 게 교정).

---

## 공통 인프라 메모
- 관리 라우트 3종(facts-audit·analog-validation·discovery-validation·moodweight-validation)은 전부
  sensitivity/anchor/catalyst-validation 전례 — 상시 배포·재실측 가능하게 두되 운영 기능 아님.
- 통계 규칙(통계감사 정본 준수): heavy-tail=Spearman / 이벤트=날짜 distinct·클러스터 dedupe / 소표본 오차 병기(n=8이면 ±18%p) / 판정 기준 사전 지정.
- ②·③은 외부(Yahoo)·자체 이력만 써서 **로컬 실행 검증 가능**(catalyst와 달리 운영 GCS 불필요 — 단 2a·2b는 KIS 일봉 필요하니 평일 권장).
- 교정의 경계(캘리브레이션 트랙 원칙 유지): 실측은 확신 표현·컷·부호를 조정하지, 판단 방향을 뒤집는 근거로 쓰지 않는다. 표본 부족 시 교정 보류가 기본.

## 진행 기록
- [x] 1a facts 계측 (2026-07-14) — 섹션 빌더+골든, /facts-audit, 실토큰 환산 0.91~1.01 tok/char. news 40.3% 지배. 트리거별 usage(선택)는 미시행. 정본 docs/facts-diet-2026-07.md
- [x] 1b facts 교정 (2026-07-14) — 뉴스 요약 top4 + 고정 설명문 C6/C8/C9 이관. **-22.1%**(4,105→3,198c), 콤보 5,822→4,956. 스트레스 콤보 실호출·골든·NumberGuard 전부 통과. 메타 통합은 9월로. 보류 2건 = 실사례 관찰 시 재론으로 종결
- [ ] 2a Analog 캘리브레이션 실측 → 카드 caveat 교정
- [ ] 2b Discovery 3신호 실측 → 컷/caveat 교정
- [ ] 3a MoodLog 가중치 실측 (지표별 단변량 + 현행 재현 + 홀드아웃)
- [ ] 3b 가중치 교정 + 성적표 연속성 처리

# LOW 백로그 수정 스펙 (2026-07-10, Fable 작성)

2026-07 감사 시리즈(1~3탄)와 G4·O5 감사에서 LOW로 분류돼 남긴 항목들의 실행 스펙.
각 항목의 위치·원인·수정 방향을 Fable이 코드 재확인 후 기록했다 — Sonnet/Opus가 이 문서만 보고 착수할 수 있게.

- **일괄 처리 권장**: Sonnet 항목(S1~S9)은 한 슬라이스로 묶어 처리 가능. Opus 항목(O1~O3)은 판단이 필요해 개별 확인 권장.
- 완료 시 각 항목에 `[x]` + 커밋 해시를 남긴다.
- **제외**: SENSITIVITY 실증 검증(감사 2탄 백로그)은 백테스트 확장 성격 + 프롬프트 수정 시 Opus 고정 정책 대상이라 이 배치에서 제외 — 별도 트랙.
- H3(레이트리밋 XFF)은 이미 수정 확인됨(`Security.kt` clientKey가 맨 뒤 항목 사용) — 목록에 없음.

---

## Sonnet 급 (기계적 — 스펙대로)

### S1. 날짜-키 인메모리 캐시 무한증식
- **위치**: 날짜가 키에 들어가는 `ConcurrentHashMap` 전부. 확인된 곳 —
  `KisClient.investorCache`(kis/KisClient.kt:64, 키 "장구분|날짜|code"),
  `ComparisonService.cache`(:53, 키 "lo:hi:날짜:mode"), `MarketMoodService.cache`(:60),
  `MacroImpactService.cache`(:82), `SectorBriefingService.cache`(:45), `CatalystService.cache`(:102),
  `OverseasAnalysisService.cache`(:36), `DartClient`의 "date|code" 캐시들(earningsCache:112·financialsCache:203·quarterlyIncomeCache:326·cumulativeNetCache:394),
  `KrxShortSellingClient.dataCache`(:55).
  ※ `priceCache`·`overseasPriceCache`(TTL 타임스탬프)·`NaverTargetPriceClient.cache`(코드당 1엔트리 교체)는 크기가 종목 수로 유계 — 대상 아님.
- **증상**: 키에 날짜가 있어 논리적으론 하루 유효인데 어제 엔트리를 아무도 안 지움 → 웜 인스턴스가 오래 살수록 누적(메모리 누수). max-instances=1이라 실피해는 느리지만 확실.
- **수정**: `util/`에 소형 헬퍼 하나 —
  ```kotlin
  /** 날짜 문자열이 바뀌면 통째로 비우는 당일 캐시. */
  class DayScopedCache<V> { fun get(date: String, key: String): V?; fun put(date: String, key: String, v: V) }
  ```
  내부에 `@Volatile var currentDate` + `ConcurrentHashMap`, put/get에서 date 불일치 시 clear 후 갱신. 위 대상들을 이 헬퍼로 교체(키에서 날짜 제거). DartClient처럼 키 조립이 제각각인 곳은 기존 키에서 날짜만 분리하면 됨.
- **검증**: 기존 전체 테스트 + 헬퍼 유닛(날짜 전환 시 clear) 1개.

### S2. Comparison 역순 요청 시 a/b 순서 불일치
- **위치**: `ai/ComparisonService.kt:56~` `compare()`.
- **원인**: 캐시 키는 정렬(lo:hi)인데 생성은 요청 순서(codeA,codeB) 그대로 → A vs B 캐시 후 B vs A를 요청하면 A/B 자리가 뒤집힌 캐시본이 반환됨(코멘트 본문·stockA/stockB 필드 모두).
- **수정**: 생성도 항상 정렬 순서로 — `compare()` 진입부에서 `(codeA, codeB) = (lo, hi)`로 치환 후 진행. 캐시본과 신규 생성본이 요청 순서와 무관하게 항상 동일해짐(클라는 응답 객체의 이름 필드로 표시하므로 안전). 순서 민감 로직 없음 확인됨.
- **검증**: 유닛 — 역순 두 번 호출 시 동일 객체(캐시 적중) + stockA=lo.

### S3. signal_state 비원자적 쓰기
- **위치**: `slack/SignalService.kt:371` `stateFile.writeText(...)`.
- **증상**: 쓰는 도중 프로세스 종료 시 파일 반파손 → 다음 스캔에서 파싱 실패 → 디듀프 상태 전체 유실(이미 알린 신호 재발송 도배).
- **수정**: `util/`에 `File.writeTextAtomic(text)` 확장(같은 디렉터리 temp 파일에 쓰고 `renameTo`/`Files.move(ATOMIC_MOVE)`) → signal_state 쓰기에 적용. 같은 패턴의 다른 `.data` 쓰기(PremortemService:132, CatalystService:441, RebalanceService:220, EventSyncService:106, TargetPriceLogService:122, MarketMoodLogService:174, DailyHistoryService:78, ClaudeUsageTracker:50, FileCache:55)도 같은 헬퍼로 일괄 교체 — 전부 단순 치환.
- **검증**: 기존 전체 테스트(파일 IO 경로는 기존 테스트가 커버).

### S4. StockMaster 프로세스 수명 내 미갱신
- **위치**: `master/StockMaster.kt:41~45` — 첫 조회 때 1회 로드 후 영구 유지.
- **증상**: 웜 인스턴스가 오래 살면 신규 상장 종목이 검색에 안 나옴.
- **수정**: `loadedAt` 타임스탬프 + 24h TTL — 초과 시 재로드(뮤텍스 가드, 재로드 실패하면 기존 데이터 유지 + 로그만). OverseasMaster도 같은 구조면 동일 적용.
- **검증**: 기존 검색 테스트 + TTL 경계 유닛 1개.

### S5. 토큰 비교 non-constant-time
- **위치**: `Security.kt:65` `provided != expectedToken`.
- **수정**: `java.security.MessageDigest.isEqual(provided?.toByteArray(), expectedToken.toByteArray())` — null 처리 포함 한 줄 교체. (실위험 낮음 — 타이밍 공격은 이론상, 그래도 공짜 수정)
- **검증**: 기존 401/200 테스트 그대로.

### S6. getListedShares가 직전 시세 응답 재활용 안 함
- **위치**: `kis/KisClient.kt:327~339` — `requestPrice`를 새로 호출. 같은 응답에 이미 `lstn_stcn`(KisModels.kt:80)이 있는데 `getPrice`(priceCache) 직후에도 재조회.
- **수정**: `listedSharesCache: ConcurrentHashMap<String, Pair<Long, Long>>`(값+ts, TTL은 하루 — 상장주식수는 일중 불변) 추가. 부수 개선: `getPrice` 성공 시 같은 응답에서 listedShares도 이 캐시에 채워 넣으면 별도 호출 자체가 거의 사라짐.
- **검증**: ValuationBand 경로 기존 테스트.

### S7. 백테스트 일봉 120개 요청 → KIS 실반환 ~100개
- **위치**: `ai/BacktestService.kt:86` `getDailyChart(code, bars = 120)` — inquire-daily-itemchartprice는 1회 최대 ~100건.
- **증상**: 백테스트 표본 창이 의도(120영업일)보다 짧음. 오류는 아니고 통계 창 축소.
- **수정**: 이미 있는 기간 지정 페이지네이션(`KisClient.kt:388~` getDailyChartRange, F1에서 사용)으로 교체해 120개 확보. 간단히 가려면 상수를 100으로 낮추고 주석으로 실반환 한계 명시(통계 n이 함께 줄어드는 것만 인지).— 페이지네이션 교체 권장.
- **검증**: BacktestService 기존 유닛(룩어헤드 차단 등) + bars 수 assert 1개.

### S8. [G4 LOW] 계좌 필터 결과 0건일 때 빈 안내 없음 (iOS+Android)
- **위치**: iOS `PortfolioView.swift` — `emptyState`(:620)는 전체 rows 기준이라, 세그먼트로 빈 계좌 선택 시 0원 집계 카드만 덩그러니. Android `PortfolioScreen.kt` 동일 구조.
- **수정**: `filteredRows.isEmpty && selectedAccountId != nil`일 때 "이 계좌에 보유 종목이 없습니다" 안내(기존 emptyState 스타일 재사용, 아이콘+한 줄). [[feedback-android-sync]] — 양 플랫폼 같은 커밋.
- **검증**: 시뮬/에뮬에서 빈 계좌 선택 화면 확인.

### S9. [G4 LOW] PositionEditView가 항상 기본 계좌로 열림 (iOS+Android)
- **위치**: iOS `PositionEditView.swift:29` — 초기 선택 = `defaultAccountId()`. 내 자산 탭에서 특정 계좌 필터 중에 열어도 기본 계좌가 선택됨.
- **수정**: `initialAccountId: Int64?` 파라미터 추가(기본 nil=현행 유지). PortfolioView에서 열 때 `selectedAccountId` 전달, 관심종목 탭 등 계좌 컨텍스트 없는 진입은 nil. Android `PositionInputSheet`도 동일 파라미터.
- **검증**: 계좌 필터 상태에서 진입 시 피커 초기값 확인.

### S10. [O5 LOW] Android OverseasDetail 미사용 파라미터
- **위치**: `androidApp/ui/EdgeApp.kt:42` — `AppDestination.OverseasDetail(item, overseasQuote)`로 시세를 담아 넘기는데 :136 `OverseasDetailScreen(item, api, onBack)`은 안 받음(화면이 재조회).
- **수정**: 둘 중 하나 — ① `OverseasDetailScreen`에 `initialQuote` 파라미터를 추가해 첫 페인트에 사용(국내 `StockDetailScreen`의 initialQuote 패턴과 통일, 권장) ② 파라미터 제거. iOS는 해당 없음.
- **검증**: 에뮬 해외 종목 상세 진입.

### S11. [감사4탄 LOW] 논지 캐시 키 hashCode 충돌
- **위치**: `ai/AnalysisService.kt` buildKey `t${thesis.hashCode()}` / `ai/PortfolioReviewService.kt` buildKey `t${t.hashCode()}` / (감사5탄 추가) `ai/TradeReviewService.kt` buildKey `r$h` — 트레이드 필드 전체를 32비트 hashCode로 접음(같은 클래스).
- **증상**: 32비트 해시라 서로 다른 논지가 같은 키로 접힐 이론적 가능성 — 충돌 시 다른 논지 기준 캐시 코멘트 수신(같은 코드·날짜·모드·포지션 전제라 개인 앱에선 사실상 무시 가능).
- **수정**: SHA-256 hex 앞 16자로 교체(양쪽 buildKey 공용 헬퍼). CacheKeyTest 갱신.

### S12. [감사4탄 LOW] POST /portfolio-review 중복 code last-wins
- **위치**: `routes/PortfolioReviewRoutes.kt` post — `positions ... .toMap()`.
- **증상**: 같은 code 2건이 오면 마지막 것만 남음(클라는 mergedByCode로 병합해 보내므로 실경로 없음, 서버 방어만 부재).
- **수정**: 중복 감지 시 400 또는 수량가중 병합(G4 mergeMoveHoldings 원칙). 400이 단순.

### S14. [감사5탄 LOW] 매매 복기 재조회 경로 없음 — 화면 이탈 시 유실
- **위치**: iOS `StockDetailView.tradeReview` / Android `StockDetailScreen.tradeReview` — onSellWithReview 콜백으로만 설정, 재로드 없음.
- **증상**: Opus로 생성한 복기가 화면 이탈·앱 재시작이면 사라짐(서버 FileCache엔 있음). 다시 보려면 같은 매도를 재기록해야 함.
- **수정**: 클라가 마지막 복기 요청 파라미터를 로컬 저장해 상세 진입 시 재POST(당일 서버 캐시 적중 = 무료) 또는 GET 조회 라우트 신설. 내 패턴 탭 누적 리스트(B2 선택 항목)와 묶어 처리 권장.

### S15. [감사5탄 LOW] 복기 매수 쌍 = 최근 매수 1건 고정
- **위치**: iOS `ActionLogSheetView` 매도 저장부(`allLogs.first(where: buy)`) / Android `StockDetailScreen.onSellWithTradeReview` 동일.
- **증상**: 분할 매수 시 마지막 매수만 기준 — TradeReviewRoutes 주석("분할 매수는 클라가 평균가로 합쳐 보낸다")과 불일치. 또 getByCode(limit 10) 창 밖의 매수는 못 찾음(관심 로그가 많으면).
- **수정**: 매도 이전의 연속 매수 로그를 전부 모아 수량 미상이므로 단순 평균가로 합산 + limit 상향(또는 action='buy' 필터 쿼리).

### S13. [감사4탄 LOW] 논지 저장 직후 화면의 분석은 이전 논지 기준
- **위치**: iOS `StockDetailView` onSave / Android `StockDetailScreen` onSave — 논지 변경 후 loadAnalysis 자동 재조회 없음(다음 진입·새로고침에 반영).
- **수정**: onSave에서 논지가 바뀐 경우 분석 카드에 "논지가 바뀌었어요 — 새로고침" 힌트 또는 자동 재조회(비용: 캐시 키가 달라져 풀 LLM 생성이므로 힌트 쪽 권장).

---

## Opus 급 (판단 필요 — 방향은 제시, 세부는 판단)

### O1. RegimeDetector 부스터 신호로 판정 성립
- **위치**: `ai/RegimeDetector.kt:67` — ④ PER 밴드 상단 신호가 `up.isNotEmpty()`만 요구 → 실질 신호 1개 + 부스터 = 2개로 `MIN_SIGNALS=2` 충족. "보강용"이라던 ④가 사실상 판정을 성립시킴.
- **수정 방향**: 부스터는 카운트에서 제외 — `up.size >= MIN_SIGNALS`일 때만 ④를 추가(근거 표시용으로만). 단 이러면 기존 판정보다 리레이팅 레이블이 덜 나옴 → **판정 빈도 변화가 코멘트 톤에 미치는 영향을 보고 임계 재조정 여부 판단**(그래서 Opus). 기존 RegimeTest 7케이스 중 ④ 의존 케이스가 있으면 의도 재정의.
- **검증**: RegimeTest 갱신 + 관심종목 몇 개 실호출로 레이블 변화 확인.

### O2. EREV — review 실패한 공시도 seen 처리(영영 스킵)
- **위치**: `slack/SignalService.kt:147~148` — `seen += rceptNo`가 `runCatching { ep.review(...) }` **앞**. 리뷰 생성이 실패(DART 일시 오류 등)해도 접수번호가 seen으로 영속(:152) → 그 실적 리뷰는 재시도 없이 소실.
- **수정 방향**: 성공 시에만 seen 추가 — `review()`가 non-null을 반환한 경우에만 `seen += rceptNo`. 재시도 폭주 걱정은 없음: 공시 조회 창이 `days = 2`(:119)라 실패해도 최대 2일 재시도 후 자연 소멸. **판단 포인트**: review()가 null을 반환하는 게 "실패"인지 "해당 없음"인지 — 해당 없음(예: 비교 기준 부재)이라면 매 스캔 재시도가 낭비이므로 null 의미를 구분(예외=미기록, 정상 null=seen 기록)해야 함. EarningsPreviewService.review 내부를 읽고 결정.
- **검증**: 유닛 — 예외 시 seen 미기록, 정상 null 시 기록.

### O4. [감사4탄 LOW] GET /analysis 논지 쿼리 파라미터 — 액세스 로그 노출
- **위치**: `routes/AnalysisRoutes.kt` — thesis를 GET 쿼리로 수신 → Cloud Run 액세스 로그에 URL(논지 원문 %인코딩) 잔존. 기존 position 쿼리(평단·수량)와 동급의 노출이라 LOW.
- **수정 방향**: POST /analysis 신설 + GET 유지(구버전 호환, ask/포폴 POST 전례). 단 로그 정책(개인 앱·본인 로그)상 수용 가능 여부가 판단 포인트 — position 쿼리도 같은 결정에 묶임.

### O3. 네이버 목표가 — 실패 negative 캐시 + 구조 변경 무감지
- **위치**: `news/NaverTargetPriceClient.kt:27~29` — `runCatching { fetch }.getOrNull()`이 예외·파싱실패를 전부 null로 뭉개 당일 캐시. 페이지 구조가 바뀌면 전 종목이 조용히 null(RegimeDetector·비교·분석 facts에서 목표가만 사라짐).
- **수정 방향** 2단:
  1. **예외와 파싱 null 구분** — HTTP/네트워크 예외는 캐시하지 않음(다음 호출 재시도). 파싱 null(페이지는 왔는데 "목표주가" 없음)만 당일 negative 캐시(현행 유지 — 컨센서스 없는 종목은 실제로 null이 정답).
  2. **구조 변경 감지** — 파싱 null이 특정 임계(예: 당일 서로 다른 종목 5개 연속) 넘으면 `OpsAlerter`로 Slack 운영 알림 1회(당일 디듀프). "컨센서스 없는 종목" 오탐을 피하려면 **직전에 값이 있었던 종목이 null로 바뀐 경우만 카운트** — `TargetPriceLogService`에 종목별 이력이 이미 있으니 활용. 임계·판정 기준 설계가 판단 포인트(그래서 Opus).
- **검증**: 유닛(예외 미캐시·파싱null 캐시·알림 임계) + 실호출 1종목.

---

## 확인만 (수정 아님)

### V1. [O5 LOW] 해외-only 관심목록 엣지케이스
관심목록이 전부 `US:`일 때 국내 전제 화면들(브리핑 수급주목·공시·매크로 영향, 내 자산, 통계)이 빈 목록을 우아하게 처리하는지 점검. O5 감사에서 주요 경로(quotes 분리 fetch, compactMap 스킵)는 확인됐고 전수 확인은 안 함. 시뮬에서 해외 1종목만 남기고 전 탭 순회 — 문제 발견 시에만 수정 항목으로 승격.

### V2. [O5 LOW] 해외 코멘트 stale 감지 없음
`OverseasAnalysisService` 당일 캐시는 미국 장중 갱신을 안 함 — **설계상 허용**(경량 시세 트랙, 국내와 동일한 당일 캐시 원칙). 재검토 시점: 해외 종목을 실제로 늘려 쓰기 시작할 때. 지금은 손대지 않는다.

---

## 진행 메모
- 권장 순서: S1~S7(백엔드, 한 슬라이스) → S8~S10(클라, 한 슬라이스, iOS 빌드 필수) → O1~O3(개별) → V1.
- 클라 수정은 [[feedback-android-sync]](iOS+Compose 같은 커밋), iOS 빌드는 매번(C3 회귀 교훈).
- 착수 전 [[edge-step-recommendation]] 워크플로대로 슬라이스+모델 승인.

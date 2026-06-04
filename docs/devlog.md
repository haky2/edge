# 작업 로그 (devlog)

세션마다 **한 일 / 막힌 점·배운 것 / 다음 할 일**만 가볍게 남긴다.
"무엇이 끝났나/다음 단계"의 상세는 `CLAUDE.md`의 Phase 체크리스트가 정본. 여기엔 맥락·서사만.
최신이 위로 오게 적는다.

---

## 2026-06-04 — 매매일지 (행동 로그 기록 UI)

**한 일**
- `ActionLogEntry` 모델 + `ActionLogRepository`(sharedLogic): `insert(code, action, reason)` / `getByCode(code, limit=5)`. 기존 `action_log` 스키마(Phase 1.3b 작성) 그대로 사용.
- `Db.actionLog` 싱글톤 (`ContentView`).
- `StockDetailView`: "기록" 툴바 버튼(✎ 아이콘) → `ActionLogSheetView` 시트. 시트 닫히면 `loadLogs()` 재호출.
- `ActionLogSheetView`: 관심/매수/매도 세그먼트 피커 + 사유 텍스트필드 + 저장.
- `logCard()`: 최근 5건 배지(관심=주황/매수=빨강/매도=파랑) + 사유 + 시각. 기록이 있을 때만 표시.

**검증**: sharedLogic 테스트 통과, Xcode BUILD SUCCEEDED.

**다음**: iOS 시뮬에서 관심 기록 입력 후 로그 카드 표시 확인 (수동) / Phase 2 남은 항목: Claude 매수/매도 참고 범위 / Phase 3 탭바.

---

## 2026-06-04 — 2c 개인화: Claude 코멘트에 내 포지션 전달

**한 일**
- 백엔드 `Position` data class (avgPrice·qty·targetPrice·stopPrice) 추가.
- `AnalysisService.analyze(code, position?)`: position 있으면 `(code,date,avg,qty)` 별도 캐시 키로 개인화 버전 생성. `buildFacts()`에 "내 포지션" 섹션 추가(평가손익·수익률·목표가/손절가 거리).
- 시스템 프롬프트 규칙 7 추가: "내 포지션 섹션 있으면 평단 기준 해석을 코멘트에 자연스럽게 녹여라".
- `AnalysisRoutes`: avgPrice·qty·targetPrice·stopPrice 쿼리 파라미터 파싱 → Position 생성.
- `EdgeApi.getAnalysisPersonalized(code, avgPrice, qty, targetPrice, stopPrice)` 추가.
- `StockDetailView.loadAnalysis()`: 포지션(avgPrice+qty) 있으면 personalized 호출, 없으면 일반 호출.

**검증 (curl · 빌드)**
- 포지션 없는 일반 호출: 캐시 적중 0.009초.
- 포지션 있는 개인화 호출(avg=300,000·qty=50·target=420,000·stop=270,000): "평단 300,000원 대비 현재 +17.2%, 목표가까지 19.5%, 손절가 거리 23.2%" 자연스럽게 녹인 코멘트 생성(첫 호출 ~14초, 2차 캐시 적중).
- 백엔드 컴파일·sharedLogic iOS 프레임워크 링크·Xcode 빌드 모두 BUILD SUCCEEDED.

**캐시 설계**: 포지션 없음=(code,date) 전 유저 공유 캐시. 포지션 있음=(code,date,avg,qty) 별도 캐시(포지션별). targetPrice/stopPrice는 캐시 키 제외(같은 포지션이면 보통 동일).

**다음**: iOS 시뮬에서 포지션 입력 후 개인화 코멘트 렌더 확인 (수동) / KRX 업종별 PER 연동 / 매매일지 / Phase 3 탭바 검토.

---

## 2026-06-04 — 업종명 표시 + 기술적 지표 설명 추가

**한 일**
- 백엔드 `KisPriceOutput`에 `bstp_kor_isnm` 매핑 → `Quote.sectorName` 필드 추가 (백엔드·sharedLogic 모두).
- `StockDetailView` 지표 해석 카드: PER 위에 "업종: 전기·전자" 행 추가.
- 기술적 지표 카드: MA5/20/60·RSI14·거래량 비율 각각에 설명 한 줄 추가(`technicalRow` 함수 공통화).

**검증**: iOS 시뮬 — 업종 "전기·전자" 표시 확인, MA/RSI/거래량 설명 문구 렌더 확인. 빌드 에러 없음.

**확인된 사실 (섹터 평균 PER)**: 한투 API에 업종 평균 PER/PBR 직접 제공 엔드포인트 없음. 섹터 평균은 **KRX 통계 파일** 방식으로 별도 슬라이스에서 구현 예정.

**다음**: KRX 업종별 PER/PBR 파일 연동 / 또는 다른 Phase 2 항목.

---

## 2026-06-04 — Phase 2: 기술적 지표 (이평선·RSI·거래량) 슬라이스 완료

**한 일**
- `DailyBar.kt` (sharedLogic 모델, YYYYMMDD·OHLCV, 최신이 앞)
- `TechnicalIndicators.kt` (object): `sma(n)` / `rsi(wilder, n=14)` / `volumeRatio(n=20)` → `TechnicalResult` (null=데이터 부족)
- `TechnicalIndicatorsTest.kt` (commonTest): SMA·RSI·volumeRatio 경계값 + calculate 통합 8케이스, `iosSimulatorArm64Test` 경고 없이 통과.
- `EdgeApi.getDaily(code, bars=62)` 추가 (`GET /daily/{code}?bars=62`, 백엔드는 이미 완성).
- `StockDetailView` — **기술적 지표 카드** 추가: MA5/MA20/MA60(원 단위), RSI14(과매수권 빨강/과매도권 파랑/구간 라벨), 거래량 비율(20일 평균 대비 배수, 2배↑=주황). `load()` 안에서 `getDaily` → `TechnicalIndicators.calculate` 순으로 연결.

**검증 (iOS 시뮬 실데이터)**
- SK하이닉스: MA5 2,328,600원 / MA20 1,978,500원 / MA60 1,355,117원 / RSI14 75.9(과매수권·빨강) / 거래량 0.6배 — 정상 렌더.
- 백엔드 `/daily/005930?bars=62` 62개 정상 반환, 수동 MA·거래량 비율 계산과 일치.
- 빌드 경고·크래시 없음.

**막힌 점**
- 이전 세션이 1M 컨텍스트 에러로 중단됐지만 파일은 이미 기록됨 → /clear 후 재확인 후 이어서 진행.

**다음**: (2d) 섹터 수급/cross-sector(Phase 3에 묶음) / 또는 2c 개인화(포지션 전달) / 또는 CLAUDE.md Phase 2 기술적 지표 체크박스 정리.

---

## 2026-06-04 — Phase 2: Claude 종합 코멘트 (2c) ⭐ 핵심 차별화 첫 구현

**한 일**
- 백엔드 `ClaudeClient`(Anthropic Messages API 직접 REST — KisClient/NaverNewsClient와 동일 패턴, 공식 SDK 없이). 헤더 `x-api-key`+`anthropic-version: 2023-06-01`, 모델 `claude-sonnet-4-6`(CLAUDE_MODEL로 override), HttpTimeout 60s.
- `AnalysisService`: 사실 수집(quote+investorFlow+name+news) → `buildFacts`로 한국어 텍스트 → Claude. `(code,date)` 인메모리 캐시(전 유저 공유, CLAUDE.md 비용 정책). v1 포지션 무관.
- 시스템 프롬프트(캐시 대상, cache_control ephemeral): 사실만 근거·수치 날조 금지·매매 단정 금지·참고용 디스클레이머·한국어 문단. `GET /analysis/{code}` 라우트, ClaudeException→502.
- 앱: `Analysis` 모델 + `EdgeApi.getAnalysis`. 상세 'AI 종합 코멘트' 카드(✨, 별도 `.task`로 비동기 로딩, 참고용+기준일 디스클레이머 footer).

**검증 (실호출)**
- 충전 후 `/analysis/005930`: 사실 기반 코멘트(현재가·PER 53.55·외인 5일 순매도 구체수치·HBM 뉴스) + 목표가 등 **환각 없음** + 신중표현/디스클레이머. 생성 13.7초.
- 캐시: 2차 호출 **0.007초**, 동일 코멘트 → (code,date) 적중 ✅.
- iOS: 카드가 실제 코멘트로 렌더, 크래시 없음. (크레딧 막혔을 때도 502→폴백 우아 처리 확인.)

**막힌 점**: Anthropic 계정 크레딧 잔액 부족으로 1차 검증 막힘 → 충전 후 통과(코드 문제 아님). 키 인증·요청·에러처리는 그 전에 이미 검증됨.

**다음**: 2c 개인화("내 평단 기준" — 앱이 포지션 전달) / 또는 (2d) 섹터 수급 'cross-sector 썰'(Phase 3) / 또는 기술적 지표(RSI·이평).

---

## 2026-06-04 — Phase 2: 뉴스 헤드라인 (2b)

**한 일**
- 백엔드 `NaverNewsClient`(네이버 검색 API, sort=date, HTML 태그+엔티티 제거) + `GET /news?q=종목명&display=5` 라우트. `NewsException`→StatusPages 502 처리.
- sharedLogic `NewsItem` + `EdgeApi.getNews`. 상세 화면 '관련 뉴스' 카드(언론사·시각·탭→Safari).
- `.env.example`에 `NAVER_CLIENT_ID/SECRET` 추가.

**막힌 점**
- Application.kt 인라인 FQN(`io.ktor.client.plugins...json(...)`)으로 Unresolved reference → NaverNewsClient가 자체 HttpClient+ContentNegotiation 생성하는 방식으로 분리해 해결.
- HTML 엔티티(`&quot;` 등) stripHtml()에서 미처리 → 정규식+수동 replace 추가.

**데이터 한계**: 네이버 검색 특성상 검색어 포괄성이 넓어 관련 없는 기사가 섞일 수 있음(삼성전자 같은 큰 기업). 실사용 시 크리티컬하지 않고, 2c Claude 해석 때 뉴스를 참고 입력으로만 쓸 것.

**검증**: curl 실데이터(삼성전자 5건·HD현대중공업 3건) + 빌드 성공. 뉴스 카드는 스크롤 아래에 위치.

**다음**: (2c) Claude 종합 코멘트 — 지금 ①(수급·52주·PER)+뉴스가 모두 준비됨. **Opus 권장**.

---

## 2026-06-04 — Phase 2: PER/PBR 노출(2a) + 지표 해석에 개인 추가 + ② 비전 정리

**한 일**
- 지표 해석 수급 요약에 **개인** 추가(외인/기관과 함께, 거울상이라 신호↓지만 '개인 연속 순매수=과열' 맥락). 테스트 보강.
- **(2a) PER/PBR 노출**: 한투 inquire-price의 `per`/`pbr`를 Quote에 매핑 → '지표 해석' 카드에 **값 + 의미 설명**(PER=주가÷주당순이익, 낮을수록 이익 대비 저렴…). 판단은 안 함(① 계층).
- CLAUDE.md ② Claude 층 비전 확장 기록: **사실(우리 수집) vs 해석(Claude) 분리·환각가드** 원칙 + 데이터 매핑(PER/뉴스/목표가/섹터) + 단계 2a~2d.

**막힌 점/결정**
- **EPS/BPS는 inquire-price에서 0(빈값)**으로 옴 → 삼성/하이닉스 모두 0 확인. 오해 소지 있어 **제외**, 정확값은 추후 DART 재무에서. PER/PBR만 노출.

**검증**: curl(삼성 PER 54.5/PBR 5.6, 하이닉스 38.9/13.1 — 합리적) + 상세 자동진입 스크린샷(PER/PBR 값+설명 캡션 표시).

**다음(②로 가는 길)**: (2b) 뉴스 헤드라인(네이버) → (2c) Claude 종합 코멘트 v1. 또는 기술적 지표(RSI/이평, 일봉 history).

---

## 2026-06-04 — Phase 2: 종목상세 '지표 해석 ① 계산 기반'(수급 흐름 요약 포함)

**한 일**
- `sharedLogic/analysis/StockAnalysis`(object) — 이미 받은 Quote/수급으로 **즉시 계산**(외부호출·LLM 없음):
  - `priceContext`: 52주 범위 내 위치(%), 고점/저점 대비(%).
  - `flowStreaks`: 외국인·기관 최신일 기준 **연속 순매수/순매도 일수 + 누적량**(0이나 방향전환에서 끊음).
- 상세 '지표 해석' 카드: 52주 위치 %+구간라벨(저점권/중하단/중상단/고점권), 고저 대비 %, 수급 연속 추세(순매수 빨강·순매도 파랑). **판단은 안 함**(그건 ② Claude 층 경계 유지).
- **commonTest** `StockAnalysisTest` 4케이스(streak 연속·중립/방향전환 끊김, priceContext 계산·범위0 null) → `iosSimulatorArm64Test` 통과.

**검증**: 단위테스트 통과 + 상세 자동진입 스크린샷. 삼성전자: 52주 위치 96%·고점권, 고점대비 -3.7%, 외국인 5일연속 순매도 -1771만/기관 3일연속 순매수 +1224만 — 수급표 합과 일치.

**다음**: ② Claude 해석(수급→내 포지션 영향) = Claude API 연동 시작 / 또는 기술적 지표(RSI·이평, 일봉 history 필요) / 거래량 평소대비(history).

---

## 2026-06-04 — Phase 2 시작: 외인/기관 수급 ⭐

**한 일**
- 백엔드 `GET /investor/{code}?days=5` — 한투 `inquire-investor`(tr_id `FHKST01010900`, 주식현재가 투자자)로 종목별 일별 외인/기관/개인 순매수. 경계 격리 DTO(`InvestorFlow`) + getPrice와 동일한 동시성제한·rt_cd 백오프.
- 앱: `EdgeApi.getInvestorFlow` + 상세 '수급 · 순매수(주)' 카드(Grid). 외인/기관/개인 일별, 순매수=빨강/순매도=파랑, 만/억 축약, monospacedDigit 정렬.

**배운 것 / 데이터 함정**
- 한투 응답 **최신 행(당일)은 장 마감 전이면 전부 0(미확정)**. CLAUDE.md "장후 확정값" 원칙대로 **백엔드에서 전부-0 행 제외** 후 N일. (curl로 확인: 005930 당일 0,0,0 → 필터 후 확정 5일)
- 순매수 수량은 이미 부호 포함. 외인+기관+개인 합 ≈ 0(수급은 제로섬) — 데이터 정합성 확인됨.
- 필드명: `stck_bsop_date`/`frgn_ntby_qty`/`orgn_ntby_qty`/`prsn_ntby_qty`.

**검증**: curl 실데이터(삼성전자/하이닉스 합리적 값) → 상세 자동진입 스크린샷(외국인 5일 연속 순매도·기관 순매수 추이 표시). 임시 자동진입 코드 되돌림.

**다음**: Phase 2 — 기술적 지표(RSI/이평) 또는 종목상세 '지표 해석'(계산기반 먼저) 중 택1. (수급에 '외인 N일 연속 순매수' 같은 신호 요약은 추후)

---

## 2026-06-04 — fix: 백엔드 다운 시 앱 크래시(@Throws 누락) 🐛

**증상**: Xcode에서 빌드·실행하면 앱이 뜨자마자 크래시(역추적이 `DispatchedTask.kt`). 백엔드를 안 켠 상태였음.
**원인**: `EdgeApi`의 suspend 함수에 `@Throws`가 없어, Ktor 네트워크 예외(`DarwinHttpRequestException: Could not connect`)가 Swift `catch`로 **전달되지 않고** `Program will be terminated`로 크래시. ContentView의 "불러오기 실패" 처리까지 못 감. 그동안 항상 백엔드를 켜고 테스트해 잠복.
**수정**: `getQuote/getQuotes/search`에 `@Throws(Exception::class)` → NSError로 브리지돼 Swift에서 잡힘.
**검증**: 백엔드 down으로 실행 → 크래시 없이 "불러오기 실패…(cd backend && ./run.sh)" 메시지 표시, 앱 프로세스 생존 확인. 백엔드 up 재실행 → 라이브 시세 정상.

> 교훈: KMP에서 Swift가 잡아야 하는 suspend 예외는 반드시 `@Throws` 필요. `try await`만으로는 부족(없으면 크래시).

---

## 2026-06-04 — 1.3c 삭제 + 1.5 평단가/수익률 ⭐ Phase 1 핵심 루프 완성

**한 일**
- 1.3c: 리스트 스와이프 삭제(`.onDelete`→`repo.remove`). 검색추가(1.4b)+스와이프삭제로 하드코딩→DB 교체 완전 마무리.
- 1.5a: `watchlist`에 nullable `avg_price·qty·target_price·stop_price` 추가. **기존 DB는 `migrations/1.sqm`(v1→v2) ALTER로 보존 업그레이드**.
- 1.5b: 상세에 `PositionEditView`(시트, decimalPad) — 평단/수량/목표/손절 입력, 빈칸은 NULL → `repo.updatePosition`.
- 1.5c: 상세 '내 포지션' 카드 — 현재가로 평가손익·수익률(상승빨강/하락파랑), 목표·손절 거리%·도달(🎯/⚠️) 표시.
- 카드 공통 스타일 추출(`cardStyle()`), 상세를 ScrollView로(포지션 늘어나도 스크롤).

**검증 (iOS 시뮬 + SQLite 직접 확인)**
- 마이그레이션: 기존 v1 DB(11종목) 재설치 → 컬럼 4개 추가·`user_version=2`·**11행 보존**(데이터 손실 없음). 신규설치도 v2로 바로 생성.
- 수익률: 005930 포지션 시드(평단30만·10주) → 상세 자동진입 스크린샷: 평가손익 +555,000원·수익률 +18.50%·목표 +12.5%·손절 -21.2% **계산 정확**.
- Swift→DB 쓰기: `updatePosition`(nil→NULL 정상)·`remove` 임시 자동구동 후 sqlite로 확인. 끝나고 임시코드 되돌림 + 앱 재설치로 DB 클린 리시드.

**배운 것**
- Swift는 **Kotlin 기본값 인자를 못 받음** → `WatchItem(code:name:...)` 전 필드 명시 필요(빌드에러로 발견).
- nullable Double/Long ↔ `KotlinDouble(double:)`/`KotlinLong(longLong:)` 박싱, 읽기 `.doubleValue`/`.int64Value`.
- 헤드리스라 탭/키보드 못 줌 → 자동구동+sqlite 직접조회 패턴으로 검증(1.4b와 동일).

**다음 할 일**
- Phase 1 핵심 루프(검색→관심→평단→수익률) 완성. 남은 1.0c(Cloud Run 배포)는 선택.
- 다음: **Phase 2 진입** — 수급(외인/기관) 또는 종목상세 '지표 해석'(계산기반 먼저) 중 택1.

---

## 2026-06-04 — 1.4b: 종목 검색 화면 → 관심종목 추가 ✅

**한 일**
- sharedLogic: `StockInfo` 모델 + `EdgeApi.search(q)`(GET /search).
- iOS `SearchView`(시트): `.searchable` + return 시 검색, 결과 행에 이름/코드·시장 + "추가"(이미 있으면 초록 체크). 추가 시 `Db.watchlist.add`→SQLite. 시트 닫히면 ContentView가 DB 재로드해 반영(`+` 버튼 toolbar leading).
- 1.4b로 **1.3c의 추가 경로까지 완성**(검색→DB). 삭제(스와이프) UI만 남음.

**검증 (iOS 시뮬, 헤드리스 제약 우회)**
- 환경에 idb/cliclick 없고 System Events 접근성 미허용(-1719) → 탭/키보드를 헤드리스로 못 줌.
- 대신 SearchView를 임시 자동구동(query="삼성전기"+검색+첫 결과 추가)해 스크린샷으로 검색 UI 확인 → **앱 컨테이너의 edge.db를 sqlite3로 직접 읽어** 009150이 sort_order 11로 영속된 것 확인(api.search→repo.add→디스크 관통). 그 후 임시코드 되돌리고 테스트행 삭제, 리빌드로 정상 상태 복구.

**배운 것**
- 한글 쿼리는 curl에서 `--data-urlencode` 안 하면 빈 결과(앱의 Ktor `parameter()`는 자동 인코딩). `/search?q=0091`처럼 숫자면 코드 prefix.
- Xcode 프로젝트가 `PBXFileSystemSynchronizedRootGroup`(동기화 그룹)이라 새 .swift는 폴더에 두면 pbxproj 편집 없이 자동 포함.
- 헤드리스 UI 검증 한계 → 로직 경로는 임시 자동구동+DB 직접조회로 증명하는 패턴 유효.

**다음 할 일**
- 1.3c 마무리: ForEach `.onDelete`로 스와이프 삭제(repo.remove 연결).
- 1.5: 평단가/수익률 — watchlist에 avg_price·qty·target·stop 필드 추가 + 입력 UI.

---

## 2026-06-04 — 1.3b: SQLDelight 로컬 DB (관심종목 영속화) ✅

**한 일**
- SQLDelight 2.3.2 도입(Kotlin 2.3.21 호환 확인). `sharedLogic`에 `.sq` 스키마 2개: `watchlist`(code PK·name·sort_order·added_at), `action_log`(id·code·action·**reason**·created_at) — reason은 Phase 4 학습 입력값이라 Phase 1부터 포함.
- `expect/actual DriverFactory`(iOS=NativeSqliteDriver, Android=AndroidSqliteDriver) + `nowMillis()`. `WatchlistRepository`(seed/all/add/remove).
- `Watchlist`는 이제 **시드 소스**(`defaultItems`)일 뿐, 정본은 DB. iOS `ContentView`가 DB에서 읽도록 교체 — 앱 전역 단일 repo(`Db.watchlist`)로 드라이버 1회 오픈.
- **iOS 시뮬 풀 검증**: 빌드→설치→실행→스크린샷. 관심종목 11개가 우선순위 순서대로 라이브 시세와 함께 표시(DB 시드→읽기→/quotes→화면 관통).

**막힌 점 / 배운 것**
- iOS 링크 실패 `Undefined symbols: _sqlite3_*` → SQLiter가 시스템 libsqlite3를 쓰는데 정적 프레임워크라 자동 링크 안 됨. **`OTHER_LDFLAGS=-lsqlite3`**를 `Config.xcconfig`에 넣어 해결(Xcode GUI 빌드도 적용되게 xcconfig에).
- Android는 SDK 위치 미설정이라 이 환경에선 컴파일 불가 → Phase 5라 보류, 코드는 표준 패턴으로 작성만.
- expect/actual class는 아직 Beta 경고(무해).

**다음 할 일**
- 1.4b: 검색 화면(`GET /search`) → 결과 → 관심종목 추가.
- 1.3c: 추가/삭제를 검색과 연동(repo.add/remove는 이미 있음, UI만).

---

## 2026-06-03 (밤) — 1.2 상세화면 + 다종목/한투 유량·토큰

**한 일**
- 1.2: iOS 시세 상세 카드(거래량·시고저·52주).
- 1.3a-1: 백엔드 `GET /quotes` 다종목 시세 — `Semaphore(KIS_MAX_CONCURRENCY,기본3)` + 백오프 재시도. 9/9 검증.
- 한투 토큰 **파일 영속화**(`.kis-token.json`) — 재시작 시 재발급 방지(검증: 재시작 전후 토큰 동일).

**배운 것 (한투 유량 — kis-api-notes.md에 기록)**
- 9개 병렬 실패 원인 = **신규 고객 3일간 초당 3건** 제한(3일 후 자동 상향). 코드/토큰 문제 아니었음.
- 기본 유량: 실전 18/s, 토큰발급 1/s(+1일1회 원칙), WS 41건. 한투 권장: 거부 시 즉시 재호출 + 동시호출 100~150ms 텀.

**계획 변경**
- 1.3을 더 쪼갬: **리스트 화면 먼저(하드코딩 9종목+라이브 시세)** → SQLDelight 영속화는 다음 세션(풀 예산).

**이어서 완료**
- 1.3a-2: iOS 관심종목 11개 리스트(라이브 시세) + **행 탭 → 종목 상세**(거래량·시고저·52주).
- 관심종목 11종목으로 교체(/search로 코드 검증).
- IntelliJ에서 백엔드 실행 셋업: **EnvFile 플러그인으로 `backend/.env` 주입**(ApplicationKt main 컨피그, Executable 체크 안 함, working dir=backend/). 좀비 Gradle :run 서버가 8080 점유 → 포트 충돌 함정 겪음.
- 한투 공식 repo(github.com/koreainvestment/open-trading-api)를 수급 등 레퍼런스로 기록.

**다음 할 일 (다음 세션)**
- 1.3b: SQLDelight 로컬 DB(watchlist + action_log.reason) — 하드코딩 → DB 영속화.
- 1.3c: 검색(1.4b) 연동해 관심종목 추가/삭제.

---

## 2026-06-03 (저녁) — 1.1c 완료: iOS에 실시간 시세 ⭐ end-to-end 관통

**한 일**
- sharedLogic에 `EdgeApi`(Ktor) + `Quote` 모델, iosApp ContentView가 백엔드 `/quote/009150` 호출 → **iOS 시뮬에 삼성전기 현재가 표시 성공.**
- 한투 API → 백엔드 → KMP → SwiftUI 첫 관통. 이후는 패턴 반복.

**막힌 점 / 배운 것**
- Xcode `PhaseScriptExecution failed` → 원인은 **Xcode가 SDKMAN Java를 못 찾음**(셸 프로필 미로드). 빌드 스크립트(Compile Kotlin Framework)에 `JAVA_HOME=~/.sdkman/.../current` 주입으로 해결. (project.pbxproj에 주석 포함)
- iOS는 ATS 때문에 평문 localhost 차단 → Info.plist `NSAllowsLocalNetworking` 예외(개발용).
- Kotlin suspend 함수가 Swift에 `async throws`로 노출돼 `try await`로 호출됨.

**다음 할 일**
- 1.2(거래량·52주 등 더 표시) / 1.3(관심종목+SQLDelight) / 1.4b(검색 화면) 중 선택.

---

## 2026-06-03 (오후) — KMP 스캐폴드 + 폴더 정리

**한 일**
- KMP 앱 생성(마법사, com.haky.edge): `app/{androidApp(Compose), iosApp(SwiftUI), sharedLogic, sharedUI}` (1.0a).
- 모노레포 폴더 정리: 루트 `stock→edge`, 앱 `Edge→app`. 아키텍처 컨벤션 문서화(package-by-feature, Phase 2 적용).
- iOS는 SwiftUI 확인(ContentView.swift). sharedUI(Compose)는 안드로이드용.

**다음 할 일**
- 1.1c: `sharedLogic`에 Ktor 클라이언트+`Quote` 모델 → 백엔드 `/quote/009150` 호출 → 화면에 현재가 표시.
- 어느 플랫폼부터 띄울지(안드로이드 에뮬 vs iOS 시뮬) 정하고 빌드·실행 검증.

---

## 2026-06-03 (오전) — 백엔드 첫 슬라이스 + 검색 + 문서화

**한 일**
- 백엔드 스캐폴드(`backend/`, Ktor + Cloud Run 타깃). `git init` + .gitignore.
- `GET /health`, `GET /quote/{code}`(한투 현재가), `GET /search?q=`(종목 마스터) 구현·**실데이터 검증 완료**.
- 코드 주석 보강(왜·함정 중심), `docs/` 신설.
- **프로젝트명 "Edge"(태그라인 "Have an edge") 확정.** 패키지 `com.stockapp` → `com.haky.edge`, 프로젝트명 `edge-backend`로 리네임.

**막힌 점 / 배운 것**
- 토큰 발급에서 `EGW00115(grant_type 필수)` → 원인은 kotlinx.serialization이 **기본값 필드를 누락**. `encodeDefaults=true`로 해결.
- `/quote` 등락이 양수로 나옴 → 한투 `prdy_vrss`가 **이미 부호 포함**인데 sign을 또 곱해 음수×음수=양수. 부호 재적용 제거.
- inquire-price엔 **종목명이 없음** → 이름은 검색(StockMaster)에서 확보, Quote에서 제거.
- (참고) 종목 마스터 `.mst`는 cp949 고정폭. KOSPI tail=228 / KOSDAQ=222.

**다음 할 일**
- 도구: Xcode(iOS 컴포넌트만) 설치 + `sudo xcode-select`, Android Studio 준비.
- KMP 프로젝트 생성(kmp.jetbrains.com, UI공유 끔) → **1.1c: 앱에서 `/quote` 호출해 현재가 표시**.

---

## 2026-06-02 — 기획 정리 & 구조 결정

**한 일**
- 앱 기획서 리뷰, `STOCK_APP_CONTEXT.md` → 루트 `CLAUDE.md`로 정리.
- 핵심 구조 결정: **백엔드 도입(키 보관·캐시·푸시)**, Cloud Run + Ktor, 수급은 장후 확정값, 공시 알림·매매일지(reason) 추가.
- Phase 1을 vertical slice(1.0~1.5)로 잘게 쪼갬.

**다음 할 일**
- 한투/DART/네이버 키 발급 → 백엔드부터 시작.

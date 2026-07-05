# Edge — 주식 분석 앱 프로젝트 컨텍스트

> **Have an edge.** 투자에서 "an edge"는 남보다 나은 정보력·판단력을 뜻한다.
> MTS엔 없는, 나만의 투자 판단 우위를 만드는 보조 도구.

## 개요
개인용 주식 분석 보조 앱 **"Edge"**. MTS는 실행 도구, 이 앱은 판단 보조 도구. iOS + Android 네이티브 앱 + Ktor 백엔드.

**백엔드 서버를 앞단에 둔다.** 앱에는 API 키를 두지 않고, 모든 외부 호출(한투/DART/Claude)을 백엔드가 대행한다. 이유:
- 한투 앱키 1개를 백엔드가 보관 → 사용자는 한투 계좌 없이 앱만 설치하면 됨
- Claude/한투 키가 클라이언트에 노출되지 않음
- 시세·분석 결과를 **공유 캐시**로 묶어 호출량/Rate Limit 제어 (N명 → ≈1×)
- 정기 작업(아침 브리핑·신호 스캔 등)을 백엔드 스케줄러로 처리

---

## 기술 스택

| 항목 | 선택 | 비고 |
|---|---|---|
| 언어 | Kotlin (KMP) | Kotlin Multiplatform |
| iOS UI | SwiftUI | |
| Android UI | Jetpack Compose | iOS와 풀 패리티 완료 |
| 로컬 DB | SQLDelight | KMP 호환, 무료 오픈소스 |
| AI 분석 | Claude API (Sonnet 4.6) | 필요시 Opus 선택 가능 |
| 빌드/iOS | Xcode | Mac 필수 |
| 백엔드 | **Cloud Run + Ktor (Kotlin)** | 키 보관·프록시·캐시·정기 스케줄러. KMP와 언어 통일 |
| 백엔드 캐시 | 인메모리 우선 (나중에 필요시 Redis/KV) | 시세 수초, 분석 결과 당일 캐시 — 캐시는 필요해질 때 추가 |

---

## 데이터 소스

| 데이터 | 소스 | 비고 |
|---|---|---|
| 현재가 / 등락률 / 호가 | 한투 Open API | 백엔드의 내 앱키 1개로 전원 공유 |
| 수급 (외인/기관) | 한투 Open API | **장후 확정 일별값** 사용 (장중은 추정치라 학습 오염) |
| 공시 알림 | DART Open API | 보유/관심 종목 신규 공시 필터링 푸시 (무료, 차별화 강점) |
| 토스 잔고 조회 | 토스 Open API | Phase 5, 현재 사전신청 단계 |
| 뉴스 헤드라인 | 네이버 검색 OpenAPI | 공식·무료(일 25,000건), 스크래핑 대체 |
| 재무지표 | DART Open API | 무료, 분기 공시 기반 |
| 매크로 지표 | 별도 수집 | 환율(USD/원), 유가, 구리, 미국지수, 미 국채금리, 공포탐욕지수 |
| 목표주가 | 직접 입력 | 공식 무료 API 없음 (컨센서스만 스크래핑 검토) |
| AI 분석 코멘트 | Claude API | 백엔드 경유 + 프롬프트/결과 캐싱 |

> 모든 소스는 백엔드가 수집·캐시하고 앱은 백엔드 API만 호출한다. 스크래핑은 공식 API 없는 데이터(컨센서스 목표주가 등)에만 최소 사용.

---

## 아키텍처

```
[iOS / Android 앱]  (KMP 공유 로직, API 키 없음)
        │  HTTPS, 백엔드 API만 호출
        ▼
[얇은 백엔드]  ← 한투키 1개 · Claude키 1개 · DART키 보관
   ├ 시세 프록시 + 캐시(수초)         : 1키로 전원 공유
   ├ 수급/공시 배치 (장후)           : 확정 일별값, DART 공시 폴링
   ├ Claude 호출 + 결과 공유 캐시      : 같은 종목·시점 분석 1회 생성
   └ 푸시 스케줄러 (Phase 5)          : 장 시작 전 브리핑 등
        │
   [한투 API · DART · 네이버 검색 · Claude API]
```

**실시간성 정책**
- 현재가/등락률/호가: REST 폴링 수초 간격 = 준실시간 (Phase 1~2 충분)
- 외인/기관 수급: **장후 확정 일별값** (장중 추정치는 패턴 승률 통계 오염)
- WebSocket 실시간 시세: 나중 옵션 (한투 앱키당 동시 41종목 제한 → 백엔드 캐시·공유로 풀기)

---

## 핵심 차별화 포인트

1. **내 포지션 기준 해석** — 뉴스/매크로를 범용으로 보여주는 게 아니라, 내가 들고 있는 종목 기준으로 필터링된 영향 분석
2. **데일리 브리핑** — 장 시작 전 체크리스트. "오늘 뭐 봐야 해?"를 한 화면에
3. **투자 행동 학습** — 관심 표시/매수/매도 로그를 쌓아서 내 패턴 통계 분석
4. **공시 즉시 알림** — 보유/관심 종목의 신규 DART 공시(수주·유증·자사주 등)를 watchlist 기준 필터링 푸시. MTS가 가장 약한 영역, 방산/조선은 수주 공시가 주가 트리거

> 행동 학습(#3)은 **입력 데이터 품질이 전부**다. 매수/매도 시 "왜 샀나" 사유 태그(reason)를 마찰 없이 남기게 만드는 게 핵심 → 로그 스키마에 `reason` 필드를 Phase 1부터 포함.

---

## 기능 목록 (Phase별)

### Phase 1 — MVP
- **백엔드 세팅** (한투키 보관, 시세 프록시+캐시, 앱은 백엔드 API 호출)
- 종목 검색
- 현재가 / 등락률 / 거래량 / 52주 고저
- 관심종목 등록 / 삭제
- 관심종목 한눈에 보기
- 평단가 수동 입력 → 내 수익률 / 손절·목표가 표시
- 한투 Open API 연동 (백엔드 경유)
- 로컬 DB 세팅 (SQLDelight) — 행동 로그 스키마에 `reason` 필드 미리 포함

### Phase 2 — 분석
- [x] 외인 / 기관 / 개인 수급 (장후 확정 일별, 최근 5일 추이) ✅ 백엔드 `GET /investor/{code}`(inquire-investor, FHKST01010900) → 상세 '수급' 카드. **iOS 시뮬 검증**. 미확정 당일(전부 0) 백엔드에서 제외.
  - 실시간 수급 한계: 거래소가 외인/기관 구분 체결을 틱 실시간으로 공개하지 않음 → 어떤 API든 장중은 **추정(가집계)**, 확정은 장후. WebSocket도 투자자 구분 없음. (선택) 장중 추정은 한투 `foreign-institution-total`(국내주식-037, FHPTJ04400000)로 받아 **"장중 추정(참고)" 라벨 단 보조 표시**만 가능 — 학습/통계는 확정값만 사용(오염 방지).
- [x] 기술적 지표 (RSI, 5/20/60일 이평선) ✅ `TechnicalIndicators`(sharedLogic, SMA·Wilder RSI·volumeRatio) + `TechnicalIndicatorsTest`(8케이스) + 상세 '기술적 지표' 카드(MA5/20/60·RSI14 색상·거래량비율). **iOS 시뮬 검증**.
- **종목 상세 "지표 해석" (2층 구조)** — 상세 화면(StockDetailView)에 붙인다:
  - [x] ① **계산 기반 (LLM 없이, 즉시)** ✅ `StockAnalysis`(sharedLogic, 단위테스트 포함) → 상세 '지표 해석' 카드. 52주 위치(%)+구간라벨(저점권~고점권), 52주 고점/저점 대비(%), **수급 흐름 요약**(외국인/기관 연속 순매수·순매도 일수+누적량). 판단 없이 위치/흐름 사실만. **iOS 시뮬 검증**.
    - (남은 것) 거래량 평소 대비 수준: 일봉 history 엔드포인트 필요 → 별도 슬라이스(inquire-daily-itemchartprice).
  - ② **Claude 종합 코멘트 (① 위에 얹음)**: "지금 이 종목 어떻게 봐야 하나"를 **내 포지션 기준**으로 풀어줌. 원칙대로 *계산값 먼저 → Claude는 해석만*.
    - **핵심 원칙(환각 방지)**: Claude는 학습 컷오프가 있어 **현재 목표가·뉴스를 모른다 → 안 주면 지어낸다.** 그래서 구조는 항상 "**우리가 사실(데이터) 수집 → Claude는 해석만**". 화면에서도 **사실(우리 데이터) vs 해석(Claude)을 분리 표시**, 프롬프트에 "제공된 데이터 외 수치 생성 금지" 가드. 특정 숫자(목표가 등)는 반드시 출처 데이터에서.
    - **노리는 해석의 깊이**(예: 삼성전자 "52주 96% 고점권인데 PER은 글로벌 동종 대비 낮고 수주 모멘텀이라 목표가 상향", "반도체/로봇/AI는 전통 밸류 문법이 안 통하는 국면", "방산/조선은 실적·수주 양호하나 자금이 반도체로 쏠려 소외된 *가설*"). 이런 *근거 있는 썰*을 주려면 아래 입력 피드가 필요 → 단계로 쌓는다:
      - [x] **(2a) PER/PBR 노출** ✅ 한투 inquire-price의 per/pbr → Quote → '지표 해석' 카드에 **값+의미 설명**(낮을수록 저렴 등, 판단 X). **iOS 시뮬 검증**. EPS/BPS는 inquire-price에선 0(빈값)으로 와 제외 → 정확값은 추후 DART 재무.
      - [x] **(2b) 뉴스 헤드라인** ✅ 백엔드 `GET /news?q=종목명` (네이버 검색 API, sort=date 최신순, HTML태그+엔티티 제거) → 상세 '관련 뉴스' 카드(출처·시각·탭→Safari). **iOS 시뮬 검증**. 네이버 NAVER_CLIENT_ID/SECRET 필요(.env).
      - [x] **(2c) Claude 종합 코멘트 v1** ✅ 백엔드 `GET /analysis/{code}` — 사실(시세·52주·PER·수급·뉴스) 수집 → Anthropic Messages API(직접 REST, `claude-sonnet-4-6`) → 한국어 종합 코멘트. 시스템 프롬프트=사실/해석 분리·환각가드·디스클레이머(+프롬프트 캐싱 cache_control). `(code,date)` 인메모리 캐시(전 유저 공유). 상세 'AI 종합 코멘트' 카드. **iOS 시뮬+실호출 검증**(13.7초 생성, 2차 0.007초 캐시 적중). `ANTHROPIC_API_KEY` 필요. **v1은 포지션 무관** — "내 평단 기준" 개인화는 후속.
      - **(2d) 섹터 수급/모멘텀 + cross-sector 썰** — 섹터 분류+섹터 수급 데이터 필요 → Phase 3 섹터 대시보드와 함께. (그전엔 섹터 '썰'은 일반론이라 신뢰↓)
    - 목표가(컨센서스)는 공식 무료 API 없음 → **직접 입력 먼저**, 스크래핑은 나중(데이터 소스 표 참고).
- 당일 뉴스 → 내 종목 기준 영향 해석 (네이버 검색 API, 단순 전달 아님)
- Claude 종합 분석 코멘트 (백엔드 경유, 결과 당일 공유 캐시)
- Claude 매수 / 매도 참고 범위 — **계산값(매물대/이평선) 먼저 표시 후 Claude는 해석만**, 참고용 디스클레이머 강하게
- [x] 매매일지: 매수/매도 시 사유 태그(`reason`) 한 줄 기록 (Phase 4 학습의 입력값) ✅ `ActionLogRepository`(sharedLogic) + 상세 화면 기록 시트(관심/매수/매도 + 사유) + 로그 카드(최근 5건). **Xcode 빌드·테스트 통과**.

### Phase 3 — 나만의 브리핑
- [x] **UX: 하단 탭바 도입 (관심종목 / 내 자산)** ✅ TabView(관심종목·내 자산). `WatchlistView`(기존 ContentView 리네임) + `PortfolioView`(평단 입력 종목만, 총 평가금액·총 손익·수익률 집계 카드 + 보유 종목 리스트). `Db.api` 공유 싱글톤. **iOS 시뮬 검증**. 브리핑 탭은 데일리 브리핑 슬라이스에서 추가.
- [x] **브리핑 탭 v1** ✅ `BriefingView`(3섹션: 오늘 하이라이트·보유현황·수급주목). 기존 `/quotes`+`/investor` 재사용. 하이라이트=등락률 상위2·하위2, 보유현황=총 손익 집계+종목별 PnL, 수급주목=외인/기관 3일 연속 순매수(`withTaskGroup` 병렬). **BUILD SUCCEEDED**.
- [x] **DART 공시 v1** ✅ 백엔드 `DartClient`(stock_code→corp_code 캐시, `/company.json`+`/list.json`) + `GET /dart/{code}?days=7`. SharedLogic `DartDisclosure` 모델 + `getDartDisclosures()`. 브리핑 탭 "최근 공시(7일)" 섹션(관심종목 전체 병렬, 최신순 10건, Link→Safari). supply·dart `async let` 병렬. **BUILD SUCCEEDED**. 백엔드 재시작 + iOS 시뮬 검증 필요.
- 데일리 브리핑 (장 시작 전 체크리스트)
- DART 공시 알림 (보유/관심 종목 신규 공시 필터링) — 수주·유증·자사주·정정 등
- 실적/이벤트 캘린더 (실적 발표일·배당락일 D-day, 브리핑에 노출)
- [x] **매크로 지표 v1 (한투로 되는 것만)** ✅ 백엔드 `GET /macro` — 코스피·코스닥(국내 업종지수 inquire-index-price, tr_id FHPUP02100000, MRKT=U, ISCD 0001/1001) + 원/달러·다우·나스닥·S&P500(해외 기간별시세 inquire-daily-chartprice, tr_id FHKST03030100, MRKT N=지수/X=환율, ISCD FX@KRW/.DJI/COMP/SPX). `KisClient.getMacroIndicators()` 6개 병렬(`async`+`awaitAll`), 개별 실패는 제외(섹션 안 죽음). 전일대비·등락률은 `prdy_vrss_sign`(4·5=하락→−)으로 부호 적용. SharedLogic `MacroIndicator` + `EdgeApi.getMacro()`. 브리핑 탭 최상단 "시장 지표" 섹션(quotes와 독립 병렬, 보합=회색/상승 빨강/하락 파랑). **BUILD SUCCEEDED + curl 6개 검증**(장전 08시라 코스피·코스닥 전일대비 0=정상, 09시 개장 후 채워짐). iOS 시뮬 수동 확인 필요.
  - [x] (v2) **WTI유가 + 공포탐욕지수** ✅ 한투 MRKT="N",ISCD="CL" + CNN FearGreedClient(봇 헤더 필요·30분 캐시). `/macro` 8개. `MacroIndicator`에 tag 필드 추가. macro-impact crude 신호 추가. iOS BUILD SUCCEEDED.
  - [x] (v3) **구리 + 국고채3년** ✅ `CopperClient`(Yahoo Finance HG=F, KIS는 HG 미지원) + `EcosClient`(ECOS Open API 817Y002, ECOS_API_KEY 필요·없으면 skip). 전력기기·전자 구리 민감도(-1), 반도체·조선·전력기기·IT서비스 금리 민감도(-1) 추가. iOS BUILD SUCCEEDED + curl 검증(구리 6.41 -1.76%, macro-impact 전력기기 구리신호 정상). 시뮬 확인 필요.
- [x] **매크로 → 내 종목 영향 해석 v1** ✅ 백엔드 `GET /macro-impact?holdings=&watchlist=` — `MacroImpactService`: 섹터(우리 분류)×지표 민감도 하드코딩(`SENSITIVITY`) + 종목→섹터 오버라이드(`SECTOR_OVERRIDE`, 관심종목 11개). 종목별 영향 방향 = [민감도 부호 × 지표 등락 부호] **계산**(사실) → Claude는 보유/관심 구분 종합 해석만(환각가드, 참고용). v1 영향 지표=원/달러·나스닥(유가·금리는 v2). `(날짜+종목집합+등락 0.5%반올림)` 캐시(13.4초→0.17초 적중). SharedLogic `MacroImpact`/`StockImpact`/`MacroSignal` + `EdgeApi.getMacroImpact()`. 브리핑 탭 "내 종목 영향" 섹션(Claude 코멘트 마크다운 렌더 + 보유/관심별 종목 행: 섹터·net 배지·지표 신호). **BUILD SUCCEEDED + curl 검증**(캐시·빈보유·매핑없는종목 방어 포함). iOS 시뮬 수동 확인 필요.
  - 매핑 없는 종목(검색 추가 등)은 net "-"·신호 없음(폴백 키워드 추론은 오분류 위험이라 안 함). 새 종목은 `SECTOR_OVERRIDE`에 추가.
- [x] **실적 캘린더 v1** ✅ 백엔드 `GET /earnings?codes=` — DART pblntf_ty=A(정기공시) 최근 18개월 → 다음 예정일 법정마감 계산. 당일 캐시. SharedLogic `EarningsEntry` + `getEarnings()`. 브리핑 "실적 일정(D-90 이내)" 섹션(D-14빨강/D-30주황 배지). iOS BUILD SUCCEEDED + curl 검증(5종목 반기보고서 D-70). 시뮬 확인 필요.
- [x] **섹터 대시보드 v1** ✅ 백엔드 `GET /sectors` — KOSPI 업종지수 6개(전기전자·기계·운수장비·전기가스업·서비스업·철강금속, inquire-index-price FHPUP02100000, MRKT=U). `SectorRoutes.kt` + `KisClient.getSectorIndices()`. SharedLogic `SectorIndex` + `EdgeApi.getSectors()`. 브리핑 시장 탭 "섹터 동향" 섹션(시장 지표 아래·실적 일정 위). iOS BUILD SUCCEEDED + curl 검증(6개 정상). 시뮬 확인 필요.
- [x] **종목 분석 facts 강화 A+B** ✅ `AnalysisService`에 (A) 일봉 기반 가격흐름 서사(고점 대비 낙폭·상한가 수준 급등·연속 등락) + 뉴스 description 주입 & 유사기사 자카드 클러스터링(대표 8건·"외 N건"), (B) DART 연간 재무(매출·영업익·순익 YoY, `DartClient.getFinancials`, fnlttSinglAcnt 11011, 연결 우선). SYSTEM_PROMPT를 촉매→펀더멘털 대조→현재 위치 흐름으로 개편. **`Analysis` 모델 불변 → 앱 변경 없음**, 코멘트만 풍부해짐. LG CNS 검증 OK. **facts 강화로 Sonnet 충분**(Opus 전환 시 분석 1회 ~5배 비용).
- [x] **Claude 섹터 분석 + 주목 종목 추천** ✅ 백엔드 `SectorBriefingService` + `GET /sector-briefing?codes=` — 섹터지수 6개 + MacroImpactService 섹터 캐시 재사용. 강세 섹터(+0.5%↑)∩관심종목 = spotlight 알고리즘 계산(LLM 환각 없음). Claude는 2~3문단 해석만. 당일 캐시. SharedLogic `SectorBriefing`/`SpotlightStock` + `EdgeApi.getSectorBriefing()`. BriefingView 시장 탭 "섹터 분석" + "오늘 주목 종목" 섹션. iOS BUILD SUCCEEDED.
- [x] **UI/UX 전반 개선 v1** ✅ 슬라이스 1A~4 완료 (커밋 7e3d0bb·cf70c10·6d96e31). 상세화면 재정렬·Swift Charts·공시·브리핑 다이어트·수급배지·도넛·섹터 2층·가독성 개선·데이터 신선도 표시.
- [ ] **UI/UX 개선 v2** — 1차 이후 누적된 개선 요구사항 정리 후 진행. 별도 슬라이스 계획 필요.
- (검토 중) 분석 C: Claude 웹검색 도구로 실시간 촉매(예: 인물 방한 일정) 보강 — 검색 건당 별도 과금·지연 증가라 비용 검토 후 결정
- [x] **거시 이벤트 캘린더 슬라이스 1 — ClaudeClient 웹검색 지원** ✅ `completeWithWebSearch()`: `tools:[web_search_20250305]` + JsonElement 기반 multi-turn 루프(MAX_SEARCH_TURNS=5) + 출처 URL 추출. `WebSearchResult`/`WebSearchSource` 모델. `GET /websearch-test` 검증 라우트. **curl 실호출 검증**: CPI·FOMC·MSCI 일정 텍스트 + 소스 15개↑ 정상 반환. 커밋 add8777.
- [x] **거시 이벤트 캘린더 슬라이스 2 — 이벤트 동기화+저장** ✅ `EventSyncService` + `POST /events/sync`(웹검색 2단계: 자유텍스트→JSON upsert) + `GET /events?days=30`. 동시만기일(분기 둘째 목요일) 룰 고정. 커밋 153aa9f.
- [x] **거시 이벤트 캘린더 슬라이스 3 — 브리핑 노출** ✅ `MarketEvent` SharedLogic 모델 + `EdgeApi.getEvents()/syncEvents()` + BriefingView "이벤트 캘린더(30일)" 섹션. 빈 캐시 시 앱이 /events/sync 자동 호출. 카테고리 호재/주의/중립. 커밋 37a39d4(배포·시뮬 검증).
- [x] **거시 이벤트 캘린더 슬라이스 4 — 코멘트에 녹이기(영향 해석, Opus)** ✅ `EventSyncService.upcomingFactsText(14일)` 헬퍼(날짜·이름=사실/카테고리만 힌트, 우리 impact 문구는 미주입→Claude 재해석·복붙방지, D-day 포함). 세 곳에 facts 주입 + 프롬프트 규칙: `AnalysisService`(종목 상세), `MarketMoodService`(브리핑 시장 분위기), `MacroImpactService`(브리핑 내 종목 영향 — 보유·관심 섹터 관련 일정만 마무리 문단에). 공통 원칙: 관련 일정만 조건부 해석, 무관하면 건너뜀, 별도 소제목 금지. 캐시키 불변(이벤트는 일 단위 안정). **curl 검증**: 시장 분위기 문단③ PPI(D-day)·FOMC(D-5) / SK하이닉스 종합 단락 FOMC(6/17)→반도체 변동성 / 내 종목 영향 마무리 FOMC(D-5)→금리민감 반도체 보유분 변동성. 무관 일정(한국PPI·Juneteenth·중국LPR) 자동 제외. iOS 코드 변경 없음(코멘트 텍스트만 풍부).

- [x] **AI 코멘트 품질 개선 v1 (2026-07-03)** ✅ 리뷰에서 실사고 2건 발견(요약에 학습 프라이어 주가 누출·공격 모드 창작 매매 레벨) → ① 요약 한정 가격류 환각 가드+1회 재생성(`suspiciousSummaryPrices`+`SummaryPriceGuardTest`) ② 프롬프트 말미 FINAL_GUARD ③ facts "기술적 앵커"(20일 저점/고점·MA20/60)+공격 모드 레벨 앵커 규칙(A3) ④ PER "KIS 기준/자체 계산" 라벨 병기 ⑤ 뉴스 날짜 주입+신선도 규칙 ⑥ n<15 신호 과신 금지(C9) ⑦ 카탈리스트 판정에 연매출 앵커+"금액 없으면 강도 최대 中" ⑧ 방어/공격 프롬프트 COMMON_RULES 공통화(**소제목 형식=iOS 파싱 계약**) ⑨ Opus 기본={briefing, analysis_initial}로 축소+force 5분 쿨다운(decisions.md #10·#11). **검증 완료**: 유닛(가드 6/6·라우터 7/7) + 로컬 실호출(삼성·SK하이닉스 공격모드 — A3 레벨 앵커 괄호표기 작동, NumberGuard 발동 0=환각·오탐 모두 없음, ForceCooldown 캐시 반환 확인).

- [x] **리레이팅 인지 v1 (2026-07-03)** ✅ "이익 급변 종목이 과거 수치 기준으로 항상 고평가 판정되는" 구조 편향 해소, 3슬라이스 — ① **연환산(포워드) PER**: 분기 누적 순이익 연환산(1Q×4·반기×2·3Q×4/3)÷상장주식수 → facts에 트레일링과 병기(`forwardPerLine`, 삼성전자 근사 트레일링 43배→연환산 ~11배). ② **레짐 감지**: `RegimeDetector`(룰 계산, LLM 없음) — 주가≥목표가×0.95·목표가 상향 추세·분기 YoY±급변 중 2개 이상 → "리레이팅 국면/디레이팅 경계" 레이블+근거를 facts 상단 주입, C11 프레임 규칙(리레이팅 시 과거 밴드 고평가 단정 금지, 단 "이익이 따라와야 유지되는 가격" 조건 명시 — 양방향 대칭). ③ **목표가 이벤트 이력**: `TargetSnapshot`에 주가 병기(하위호환 nullable) → 90일 상향/하향 횟수·주가≥목표가 관측일·돌파→상향 평균 간격(`computeEvents`). 유닛테스트 3종(Regime 7·Events 6·ForwardPer 7) + 파이썬 교차검증 통과. **컴파일·실호출 검증은 로컬 필요.** 후속 후보: 웹검색 포워드 촉매(레짐 종목 한정 비용 캡), AI 스탠스 적중률 배치.

- [x] **종목 Q&A 슬라이스 A1 — 백엔드 (2026-07-04)** ✅ `POST /ask/{code}` — 자유 질문을 analyze()와 **같은 사실 데이터**로 답변. ① facts 수집을 `collectFacts()`로 추출(analyze·ask 공유, 병렬 구조 그대로) ② Q&A 전용 프롬프트(Q1~Q7: 정면 답변·짧게·근거 없으면 "알 수 없다"·통계 한정) + 방어/공격 스탠스 분기(공격은 A3식 레벨 앵커 강제) + ASK_FINAL_GUARD ③ 후속질문 `history`(앱이 이전 문답 되보냄, 서버 무상태 — 최근 3턴·답변 600자 절단) ④ 비용 가드: 질문 300자 제한(400) + 일일 상한 `ASK_DAILY_LIMIT`(기본 200, 초과 429) + 캐시 없음(자유 텍스트) ⑤ ModelRouter.ASK(기본 Sonnet — 대화형 지연 민감, env OPUS_TRIGGERS로 전환). **검증**: 유닛(AskMessageTest 4) + 전체 테스트 + curl 실호출(방어·공격+포지션+history — 레벨 앵커 괄호표기 작동, 400·429, /analysis 회귀). **남음: A2(iOS+Android 상세화면 질문 시트)**.

- [x] **포트폴리오 종합 진단 B — 백엔드 (2026-07-04)** ✅ `GET /portfolio-review?positions=code:avg:qty,...` — 보유 전체를 하나의 포트폴리오로 보는 구조 진단(종목별 분석과 역할 분리: 날씨가 아니라 "집의 구조"). ① **전부 계산**: 총 평가/손익, 종목·섹터 평가 비중, 매크로 공통 노출(SENSITIVITY×비중 가중 — "지표가 움직이면 몇 %가 같은 방향으로 흔들리나"), 밸류 밴드 위치 분포 ② Claude는 구조 해석만 — P1~P7 규칙(분산 설교 금지·상단권 비중=고평가 단정 금지·오늘 방향 예측 금지) + 방어(조정 지시 금지)/공격(비중 조정 명령 허용, facts 수치 앵커) + 말미 가드, `### 핵심 요약` 계약 재사용 ③ 캐시 (날짜+포지션집합+모드) 개인별 + force 5분 쿨다운 ④ ModelRouter.PORTFOLIO(기본 Sonnet). **검증**: PortfolioMathTest 7(비중·노출·분포·키) + 전체 테스트 + 합성 facts 실호출(방어·공격 — 규칙 전부 준수, KIS 주말 점검으로 실데이터 e2e는 평일 확인). **남음: 클라 카드(내 자산 탭, A2와 묶음)**.

### Phase 4 — 학습 / 통계
- [x] **행동 로그 통계 v1** — 관심종목 행동 로그(매수·매도·관심 등록) 집계 → 신호별 승률·내 패턴 통계 화면. Phase 1부터 `action_log`에 데이터 쌓임. ✅ StatsView(신호별 승률·손절익절규율·관심후미매수·사유태그·보유기간·AI적중률)
- [x] 관심만 보이고 안 산 종목 이후 추이 ✅
- [x] 내 투자 패턴 분석 ("외인 순매수 신호 매수 승률 70%" 등) ✅
- [x] 종목 비교 (두 종목 나란히) ✅
- 포트폴리오 섹터 비중 시각화

### Phase 5 — 확장
- 토스 Open API 연동 (잔고 자동 조회)
- 푸시 알림 (백엔드 스케줄러: 장 시작 전 브리핑, 외인 N일 연속 순매수, 목표가 진입, 공시)
- WebSocket 실시간 시세 (백엔드 공유, 한투 41종목 제한 캐시로 풀기)
- 클라우드 DB 마이그레이션 (Firebase 또는 iCloud)
- Android 포팅 (Jetpack Compose) — KMP라 공유 로직 재사용

---

## Phase 1 세부 작업 순서 (vertical slice 단위)

각 단계는 독립적으로 빌드·실행되고, 끝나면 "눈에 보이는 결과"가 있다. 위에서 아래로 하나씩.

**1.0 스켈레톤 (뼈대만, 데이터 없음)**
- [x] 1.0a KMP 프로젝트 생성 → `app/`(androidApp·iosApp·sharedLogic·sharedUI), 패키지 com.haky.edge ✅ *(빌드·실행 검증은 1.1c)*
- [x] 1.0b Ktor 백엔드 프로젝트 생성 → `GET /health` 200 반환, 로컬 실행 확인 ✅ `backend/`
- [x] 1.0c-a 배포 보안 게이트 ✅ 공유 토큰 헤더(`X-Edge-Token` vs `EDGE_API_TOKEN`, 비면 로컬은 인증 생략) + IP별 RateLimit(`X-Forwarded-For` 기준 120/분, `/health` 제외) — `Security.kt`/`configureSecurity()`. `backend/.data/` gitignore. `Dockerfile`+`.dockerignore`+`deploy.sh`+`docs/backend/deploy.md`(Secret Manager·`max-instances=1`). iOS `EdgeApi(apiToken)` → `X-Edge-Token` 자동 헤더, `Db.api`가 Info.plist(`EDGE_BASE_URL`/`EDGE_API_TOKEN`, 비면 localhost 폴백)에서 읽음, `Secrets.xcconfig`(gitignore)로 배포값 주입. **백엔드 compileKotlin + iOS BUILD SUCCEEDED. 런타임 검증: 토큰없음→401·정상토큰→200·130req→120후 429.**
- [ ] 1.0c-b 실제 Cloud Run 배포·검증 → 공개 URL에서 `/health`·토큰 호출 확인

**1.1 첫 수직 슬라이스 — 시세 1종목 end-to-end** ⭐ 첫 "보이는 성공"
- [x] 1.1a 백엔드: 한투 OAuth 접근토큰 발급 + 만료 관리(메모리 캐시) ✅ `KisClient`
- [x] 1.1b 백엔드: `GET /quote/{code}` → 한투 현재가 조회 → 정규화 JSON 반환 (캐시 없음) ✅ **실데이터 검증 완료**
- [x] 1.1c 앱: Ktor client로 백엔드 호출 → 삼성전기(009150) 현재가 표시 ✅ **iOS 시뮬에서 실데이터 확인 — end-to-end 관통!**

> 한투 inquire-price 참고: `prdy_vrss`·`prdy_ctrt`는 **이미 부호 포함**(재적용 금지). 종목명(`hts_kor_isnm`)은 이 엔드포인트에 없음 → 검색(1.4)/관심종목에서 확보. 토큰 요청은 `encodeDefaults=true` 필수.
> 도구: Xcode 설치 완료(단 `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer` 1회 필요), Android Studio = `~/Applications/Android Studio.app`.

> 백엔드 실행: `cd backend && cp .env.example .env` 후 키 입력 → `./run.sh` → `curl localhost:8080/quote/009150`
> ⚠️ KMP 함정: Swift가 잡아야 하는 **suspend 함수엔 `@Throws(Exception::class)` 필수**. 없으면 네트워크 예외(백엔드 다운 등)가 Swift `catch`로 안 가고 앱이 크래시(`DispatchedTask.kt` 역추적). `EdgeApi`의 get/search에 적용함.

**1.2 시세 필드 확장**
- [x] 1.2a 백엔드 응답에 등락률·거래량·52주 고저 추가 ✅ (Quote에 처음부터 포함)
- [x] 1.2b 앱 화면에 표시 ✅ iOS 카드형 상세(현재가·등락·거래량·시고저·52주)

**1.3 관심종목** — 더 잘게 쪼갬: 리스트 화면 먼저(하드코딩), DB 영속화는 나중에 얹는다.
- [x] 1.3a-1 백엔드: `GET /quotes?codes=...` 다종목 시세(병렬+동시성제한+재시도) ✅ 9/9 검증
- [x] 1.3a-2 앱: 관심종목 **리스트 화면**(하드코딩 11종목 + `/quotes` 라이브 시세) ✅ + 행 탭 → 상세(거래량·시고저·52주)
- [x] 1.3b SQLDelight 세팅 + `watchlist` + `action_log`(`reason`) — 영속화 ✅ **iOS 시뮬 검증: DB 시드→읽기→/quotes→표시 관통**
- [x] 1.3c 관심종목 추가/삭제 (검색 1.4b와 연동) ✅ 추가=1.4b, 삭제=리스트 스와이프(`.onDelete`→repo.remove). 하드코딩→DB 교체 완료

> SQLDelight 함정: 네이티브 드라이버(SQLiter)가 시스템 `libsqlite3`를 쓰는데 정적 프레임워크라 자동 링크가 안 됨 → iOS 앱 타깃 `OTHER_LDFLAGS`에 `-lsqlite3` 필수(`iosApp/Configuration/Config.xcconfig`에 넣음). 안 하면 `Undefined symbols: _sqlite3_*` 링크 에러. 드라이버 생성자는 플랫폼마다 달라(iOS=무인자, Android=Context) `expect class DriverFactory`로 선언. DB 정본은 `watchlist` 테이블, `Watchlist.defaultItems`는 첫 실행 시드값일 뿐.
> Android 빌드는 SDK 위치(`local.properties`/`ANDROID_HOME`) 미설정이라 이 환경에선 컴파일 불가 — Android는 Phase 5라 보류(코드는 표준 `AndroidSqliteDriver` 패턴으로 작성해 둠).

**1.4 종목 검색**
- [x] 1.4a 백엔드: `GET /search?q=` ✅ 한투 공개 종목마스터(.mst) 파싱, 코드/이름 검색 (`StockMaster`)
- [x] 1.4b 앱: 검색 화면 → 결과 → 관심종목 추가 연결 ✅ **iOS 시뮬 검증: /search 라이브 결과→추가→DB 영속(sqlite로 확인)**

**1.5 평단가 / 수익률**
- [x] 1.5a `watchlist`에 `avg_price`·`qty`·`target_price`·`stop_price` 필드 추가 ✅ nullable, 기존 DB는 `migrations/1.sqm`(v1→v2)로 보존 업그레이드
- [x] 1.5b 입력 UI ✅ 상세 화면 `PositionEditView`(시트, decimalPad) → repo.updatePosition
- [x] 1.5c 수익률 / 손절·목표가 도달 여부 표시 ✅ 상세 '내 포지션' 카드: 평가손익·수익률(색), 목표/손절 거리%·도달 표시

> Phase 1 완료 기준: 검색→관심등록→평단입력→관심리스트에서 내 수익률까지 한 번에 도는 것. 캐시·공유·DART·Claude는 전부 Phase 2+.
> **→ Phase 1 핵심 루프 완성**(검증: iOS 시뮬, SQLite 직접 확인). 남은 건 1.0c(선택 Cloud Run 배포)뿐.

> SQLDelight 스키마 변경 함정: 기존 DB가 있으면 CREATE만 바꾸면 안 되고 `migrations/N.sqm`(N=from버전) 필요. 신규설치는 .sq에서 최신 스키마로 생성(user_version=마이그레이션수+1), 기존설치는 .sqm 적용. KMP 인터롭: Swift는 **Kotlin 기본값 인자를 못 받음** → `WatchItem(...)` 생성 시 전 필드 명시. nullable Double/Long은 `KotlinDouble(double:)`/`KotlinLong(longLong:)`로 박싱, 읽기는 `.doubleValue`/`.int64Value`.

---

## 내 포트폴리오 컨텍스트

### 보유 계좌
- 미래에셋 (일반, IRP) — 수동 입력
- 카카오페이증권 (ISA) — 수동 입력
- 토스증권 — 자동 연동 검토(미적용)
- 한투 — API 연동용 (개설·연동 완료)

### 관심종목 (우선순위순) — 2026-06-03 갱신, 코드는 /search로 검증
1. 삼성에스디에스 (018260)
2. HD현대중공업 (329180)
3. LG전자 (066570)
4. 현대오토에버 (307950)
5. SK하이닉스 (000660)
6. 삼성전자 (005930)
7. HD현대일렉트릭 (267260)
8. 대한전선 (001440)
9. 산일전기 (062040)
10. 한국항공우주 (047810)
11. 한화에어로스페이스 (012450)

---

## 운영

- **Claude API 호출량 최소화**: ① 프롬프트 캐싱(시스템·종목정의 90% 할인) ② 같은 종목·시점 분석은 1회 생성 후 전 유저 공유 캐시 ③ 자동 생성 대신 탭 시 생성 + 당일 캐시.
- **백엔드**: Cloud Run + Cloud Scheduler(정기 잡) + Cloud Tasks(비동기) + Secret Manager(키) + GCS(캐시·토큰 영속).
- **배포**: Android는 서명된 APK 직접 배포. iOS는 개발자 설치(Personal Team), TestFlight는 Apple Developer 계정 등록 시점에.

---

## 현재 상태 (마무리 단계)

Phase 1~5 핵심 기능 완성. iOS·Android 풀 패리티, 백엔드 Cloud Run 배포 운영 중.
- ✅ 한투/DART/네이버/Claude 키 발급 + 백엔드 연동
- ✅ KMP 앱(iOS SwiftUI · Android Compose) + Ktor 백엔드, vertical slice로 단계별 구현
- ✅ Cloud Run 배포 + Cloud Scheduler/Tasks 자동화 + Slack 연동
- (선택) iOS TestFlight 배포는 Apple Developer 계정 등록 시점에

---

## 개발 작업 방식
- **한 번에 vertical slice 하나**: 백엔드 1 엔드포인트 ↔ 앱 1 화면을 end-to-end로 돌려보고 다음으로. "Phase 전체"를 한 번에 만들지 않는다.
- **앱에는 API 키를 절대 두지 않는다.** 모든 외부 호출은 백엔드 경유. (가장 비싼 되돌리기라 처음부터 지킴)
- **캐시·공유·배치는 필요해질 때 추가.** 처음엔 깡통 프록시로 시작, 미리 설계하지 않는다.
- 각 단계는 *빌드·실행 확인*까지가 완료. 안 되면 다음으로 안 넘어간다.
- 모델(개발 작업): **디폴트 Opus.** 가장 복잡하고 정확한 추론이 필요한 설계·판단(프롬프트 설계, 어려운 아키텍처 결정, 미묘한 정합성 검증 등)은 **Fable.** 아주 단순·기계적인 작업(검증된 패턴 복제, 단순 UI 배선 등)만 **Sonnet.** 슬라이스 시작 전 스텝+모델을 추천하고 승인을 받는다.
- 플러그인은 반복 패턴이 보일 때 그 한 가지만 추가 (풀세트 선설치 금지).

## 문서 (`docs/`)
- 계획·체크리스트의 정본은 이 `CLAUDE.md`. 보조 문서는 `docs/`에 둔다([docs/README.md](docs/README.md)).
- `docs/decisions.md` 결정 이유 · `docs/devlog.md` 세션 작업 로그 · `docs/backend/` API·개발·한투 함정.
- `docs/prediction-features-spec.md` **예측 보조 기능 6종 작업분석서(구현 대기)** — 유사 국면 통계·수주 공시 임팩트·실적 프리뷰·수급 전환 알림·프리모템·스탠스 적중률. 구현 시작 시 이 문서가 정본, 순서·의존성 표 포함. F2의 이벤트 로그(2-0)는 데이터 누적이 필요하므로 최우선.
- 세션 끝에 의미 있는 진척이 있으면 `docs/devlog.md`에 한 일/막힌 점/다음을 짧게 남긴다.
- changelog는 git 커밋으로 갈음(버전 낼 때 도입).

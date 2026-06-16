# 작업 로그 (devlog)

세션마다 **한 일 / 막힌 점·배운 것 / 다음 할 일**만 가볍게 남긴다.
"무엇이 끝났나/다음 단계"의 상세는 `CLAUDE.md`의 Phase 체크리스트가 정본. 여기엔 맥락·서사만.
최신이 위로 오게 적는다.

---

## 2026-06-16 — 밸류에이션 코멘트 약세편향 개선(밸류-A): 라벨 중립화 + 프롬프트 균형 + 표본가드

**한 일**
- 문제: AI 코멘트가 반도체·AI(멀티플 리레이팅)·조선·방산(이익 점프·턴어라운드)처럼 역사 밸류밴드가 깨지는 국면에서 "역사적 고평가라 사지 마라"로 약세 편향. 원인 진단(코드):
  - `ValuationBandService.label()`이 백분위 65%↑를 무조건 **"역사적 고평가"**로 못박아 facts에 주입 → Claude가 판정을 그대로 전달.
  - 동종 상대 밸류 코드 부재, 목표가 추세(상향/하향) 부재, 프롬프트 균형 지침 부재. 조선·방산은 적자연도가 PER 히스토리에서 제외(`netIncome>0`)돼 표본이 더 쪼그라들어 밴드가 더 망가짐.
- 수정(밸류-A):
  - 라벨 판정→위치 중립화: **"저평가/보통/고평가" → "역사적 하단권/중간권/상단권"**. 라벨은 facts·iOS칩·Android칩·Slack신호·Comparison **6접점 공유**라 전부 동기화(색상 매칭·`LOW_VALUE_LABEL`·주석).
  - 표본 부족 가드: `buildFacts` 밸류 블록에 `yearsUsed<3`이면 "신뢰도 낮음, 참고만" 주입(턴어라운드 오판 방지).
  - 프롬프트 균형 지침: DEFENSIVE #14 / AGGRESSIVE #12 — 상단권·높은 백분위를 그 자체로 "비싸다" 단정 금지, 실적 방향·목표가 상승여력·표본신뢰도와 저울질, 업종 미리 단정 금지(반도체·조선·방산 불문), 양방향 경계.
- **실 코멘트 검증**(`?refresh=true`, 백엔드 재기동 후): 한화에어로(012450)·삼성전자(005930) 모두 밸류 단락이 "역사적 상단권"이나 "이 수치만으로 비싸다 단정 어렵다 → 이익 점프/리레이팅으로 과거 밴드 의미 약화 + 목표가 상승여력(+54.8%/+29.4%) → 시장은 위를 본다 + 양방향 경계"로 의도대로 전환 확인. 백엔드 compileKotlin 통과.

**막힌 점 / 배운 것**
- 밴드 라벨 문자열이 백엔드·iOS·Android·Slack에 흩어져 **문자열 매칭**으로 색·신호를 결정 → 한 곳만 바꾸면 색/신호가 조용히 깨짐. 6접점 일괄 변경 필수([[feedback-android-sync]]).
- 반도체="P 리레이팅", 조선·방산="E 점프"로 원인은 반대지만 역사 밴드가 깨지는 결과는 동일 → 수정은 섹터 화이트리스트가 아니라 **사실 기반 해석 위임**이 정답.

**다음 할 일**
- 밸류-B(목표가 상향/하향 추세 스냅샷), 밸류-C(동종 상대 밸류). 둘 다 Opus. iOS/Android 빌드+시뮬 칩 색 확인은 다음 UI 검증 묶음에서.

---

## 2026-06-15 — /edge 슬래시 명령 오류 수정: Cloud Run CPU 스로틀링 → Cloud Tasks 워커

**한 일**
- `/edge 삼성전자`가 "분석 중 오류" 반환. 원인: `/slack/command`가 3초 ack 후 분석을 `application.launch{}` 인프로세스 백그라운드로 돌리는데, **Cloud Run은 HTTP 응답을 보내고 나면 CPU 할당을 끊어**(기본 스로틀링) 외부 API 읽기가 도중에 잘림 → Ktor CIO `Not enough data available`. (`/analysis`는 요청이 열려 있어 CPU 유지 → 정상이라 대비됨.)
- 수정: 분석을 **Cloud Tasks 워커**(`POST /slack/analyze-task`)로 별도 인바운드 요청으로 띄움 → 그 요청 처리 동안 CPU 할당 유지. `/slack/command`는 enqueue 후 즉시 ack만.
  - `tasks/CloudTasksClient.kt`(SDK 없이 REST, 메타데이터 SA 토큰 Bearer), `SlackCommandRoutes`에 워커 라우트 추가 + enqueue 분기(큐 미설정 로컬은 인프로세스 폴백), `Application.kt` 배선.
  - `deploy.sh`: cloudtasks API enable + 큐 `edge-slack` 생성 + 런타임 SA에 `roles/cloudtasks.enqueuer` + env(`GCP_PROJECT_ID`/`TASKS_LOCATION`/`TASKS_QUEUE`).
- **배포·검증**: 서명한 `/slack/command` → enqueue 성공(실패 로그 없음) → Cloud Tasks 디스패치 → 워커 200. 워커는 인바운드 요청이라 `/analysis`와 동일하게 풀 분석 완주.

**막힌 점 / 배운 것**
- Cloud Run 기본 스로틀링에선 **응답 후 백그라운드 작업이 CPU를 못 받는다.** fire-and-forget으로 외부 호출하면 조용히 잘림. always-on CPU(`--no-cpu-throttling`)는 idle 과금이라, 무료 티어 유지하려면 작업을 별도 인바운드 요청(Cloud Tasks)으로 돌리는 게 정석.
- min-instances=0(scale-to-zero) 유지 → 비용은 활성 시간에만. enqueue는 ack 처리 중(CPU 있음)에 끝나므로 3초 제약 안전.

**다음 할 일**
- 사용자: Slack에서 실제 `/edge 삼성전자` 한 번 확인(콜드 스타트 시 첫 분석 ~13초).
- (선택) MorningBrief/MoodLog 등 다른 백그라운드 발송도 같은 함정 점검 — 현재는 스케줄러가 요청을 열어 두고 호출해 안전.

---

## 2026-06-15 — Slack S7: 양방향 조회 /edge 종목명 (Opus)

**한 일**
- Slack 슬래시 명령 `/edge 종목명` → 종목 AI 코멘트 요약을 Slack으로. S2(아침브리핑)·S7 둘 다 커밋했고 **배포는 모아서 한 번에**(사용자 요청).
  - `slack/SlackSignatureVerifier.kt`: HMAC-SHA256 서명검증(`v0:{ts}:{body}`) + 5분 리플레이 거부 + 상수시간 비교. signingSecret 비면 로컬 검증 생략(토큰 게이트와 동일 정책).
  - `slack/SlackCommandService.kt`: 텍스트→코드 해석(6자리=코드/아니면 master.search 첫 결과) → `analysis.analyze(code)`(position=null=공개분석, 개인화 없음) → mrkdwn 포맷(`**`→`*`, 1600자 컷, footer).
  - `slack/SlackClient.kt`: `postToResponseUrl()` 추가(봇토큰 불필요, URL 자체가 비밀, ephemeral/in_channel·replace_original).
  - `routes/SlackCommandRoutes.kt`: POST /slack/command — 원문 본문으로 서명검증 → **즉시 200 ack**(3초 제약) → `application.launch`로 분석 비동기 → response_url 발송.
  - `Security.kt`: 토큰 게이트에서 `/slack/command` 제외(Slack은 EDGE_API_TOKEN 못 보냄 → 서명검증으로 대신 인증).
  - deploy.sh: SLACK_SIGNING_SECRET 시크릿 추가. .env.example 키 추가.
- 검증(로컬, signingSecret 켠 상태): ①잘못된 서명→401 ②리플레이→401 ③올바른 서명→200+ack. nc로 response_url 수신 → 삼성전자 실분석 22초 후 포맷 메시지 전달 확인(ephemeral·replace_original·굵게변환·컷).

**배운 것**
- 서명검증은 **파싱 전 원문 본문**으로 해야 함(HMAC은 바이트 단위) → Ktor `receiveText()` 먼저, 그 다음 `parseQueryString`.
- Slack 3초 제약 → 즉시 ack + response_url 비동기가 정석(분석은 Claude라 십수 초).
- `postToResponseUrl`은 봇토큰 불필요(response_url 자체가 일회성 비밀).

**다음 할 일 (S7 라이브 전 사용자 셋업)**
- Slack 앱에 `commands` scope 추가 → 재설치 / `/edge` 슬래시 명령 생성(Request URL=prod /slack/command) / Signing Secret → Secret Manager(SLACK_SIGNING_SECRET) 등록. 그 후 S2+S7 묶어서 배포.
- 이후: S8(라운지 명령어+인터랙션) 또는 S3(신호 푸시).

---

## 2026-06-15 — Slack 연동 S1: 운영 오류 알림 (#ops-오류) (Opus)

**한 일**
- 개인 Slack(무료) 연동 백로그 7슬라이스 계획 수립([[edge-slack-slices]] 메모리). 무료 푸시 대체 + 운영 모니터링. 봇토큰 1개로 채널/DM 라우팅(웹훅 채널별 분리 안 함).
- **S1 구현**: 백엔드 오류를 `#ops-오류` 채널로 자동 알림.
  - `slack/SlackClient.kt`: 봇토큰 `chat.postMessage` 얇은 클라이언트. **절대 예외 안 던짐**(에러 핸들러에서 호출되므로 실패는 로그만), 토큰/채널 비면 no-op.
  - `slack/OpsAlerter.kt`: StatusPages 예외 → Slack 발송. **fire-and-forget**(scope.launch, 응답 지연 0) + **5분 쿨다운 throttle**(시그니처=상태|라우트첫세그먼트|예외종류 → KIS 세션공백류 전종목 도배 방지).
  - `Application.kt`: StatusPages 핸들러에 `opsAlerter.alert(...)` 한 줄 추가(모든 500/502 자동 알림). 클라이언트는 install 전 생성(핸들러에서 참조), Application을 CoroutineScope로 주입.
  - `routes/SlackTestRoutes.kt`: 검증용 `/slack-ping`(직접 발송)·`/slack-test-error`(의도적 예외).
  - `deploy.sh`: `SLACK_BOT_TOKEN` 시크릿 + `SLACK_OPS_CHANNEL` env 추가.
- 검증: 로컬 실호출 — ping `sent=true`(채널 C0BA29NTQUF), 에러 경로 500+Slack 알림, 쿨다운으로 2번째 억제 확인. 사용자 채널 시각 확인(메시지 2개).

**배운 것**
- 에러 알림 경로에서 호출되는 클라이언트는 절대 예외를 던지면 안 됨(원 요청 처리 깨짐). runCatching + 로그만.
- 사용자별 비공개 채널은 친구 N명=채널 N개라 안 굴러감 → 개인 알림(S3·S4)은 봇 DM(Slack userID 대상)으로 갈 예정. 채널은 시장공통(공유)·운영(혼자)만.

**다음 할 일**
- S3(신호 알림 푸시, 푸시 대체 핵심) 또는 S2(아침 브리핑). 순서: S1→S3→S2→S5→S4→S6→S7→S8.

---

## 2026-06-15 — iOS 내비게이션 타이틀·바디 상단 여백 제거 (Sonnet)

**한 일**
- 5개 탭 전체(관심종목·내 자산·브리핑·내 패턴·설정)에 inline 타이틀 적용 + 설정 "문의" 행 iOS·Android 제거 (커밋 9f05d86).
- inline 전환 후 타이틀 바와 리스트 사이 회색 여백이 남는 문제 수정 (커밋 fd88bd5).
  - 원인: iOS 16+에서 SwiftUI `List`는 `UICollectionView` 기반 → `UITableView.appearance().sectionHeaderTopPadding` 완전 무효.
  - 수정: 5개 뷰의 `List`/`Form`에 `.contentMargins(.top, 0, for: .scrollContent)` (iOS 17+) 추가. iOSApp.swift UITableView hack 제거.

**배운 것**
- iOS 16+에서 SwiftUI List = UICollectionView. UITableView appearance proxy는 효과 없음.
- 내비 바-리스트 상단 여백 제거는 `.contentMargins(.top, 0, for: .scrollContent)` (iOS 17+)가 정석.

**다음 할 일**
- 백로그 슬라이스 C(완전 자동 스케줄러) 또는 슬라이스 7(푸시 알림)

---

## 2026-06-15 — 코스피 선행신호 주말 버그 3종 수정 (Sonnet)

**한 일**
- 문제 1: 토/일에 KOSPI 지표가 금요일 종가(+4.6%)를 반환해 보합 예측이 실패로 오기록됨.
  → `addOrUpdateEntry`에 주말 체크(`DayOfWeek.SATURDAY/SUNDAY`) 추가. `init` 블록에서 기존 주말 항목 자동 정리.
- 문제 2: Cloud Run 서버가 UTC 기준 → 한국 오전 7~9시(=UTC 일요일 밤)에 `LocalDate.now()`가 "일요일"로 인식, `addOrUpdateEntry`가 skip되어 오늘 예측 미기록.
  → `MarketMoodService`에서 `LocalDate.now()` → `LocalDate.now(ZoneId.of("Asia/Seoul"))`로 변경.
- 문제 3: `/market-mood`(느림·Claude 호출)와 `/market-mood-log`(빠름·파일 읽기) 동시 호출 레이스 컨디션 → 오늘 예측 카드 미표시.
  → `MarketMoodService.ensureTodayEntry()` 추가 + `/market-mood-log` 핸들러 선두에 호출.

**배운 것**
- Cloud Run은 UTC 고정. 한국 서비스의 "오늘" 날짜 계산은 `ZoneId.of("Asia/Seoul")` 명시 필수.
- 빠른 엔드포인트가 느린 엔드포인트의 부작용(로그 업데이트)에 의존할 때 레이스가 생김 → 빠른 쪽에서도 직접 보장해야.

**다음 할 일**
- 백로그 슬라이스 C(완전 자동 스케줄러) 또는 슬라이스 7(푸시 알림)

---

## 2026-06-12 — 거시 이벤트 캘린더 슬라이스 4: 코멘트에 이벤트 녹이기 (Opus)

**한 일**
- `EventSyncService.upcomingFactsText(days=14)`: 저장된 이벤트 중 향후 2주치를 Claude facts용 텍스트로.
  - 날짜·이벤트명은 **사실**(우리가 수집), 카테고리(호재/주의/중립)는 방향 힌트로만. D-day 계산 포함.
  - **우리 impact 문구는 일부러 넣지 않음** — Claude가 종목·시장 맥락에 맞춰 재해석하게(복붙·환각 방지).
- `AnalysisService`(종목 상세): `eventSync` 주입 → buildFacts에 임박 이벤트 주입(뉴스 뒤·포지션 앞).
  방어/공격 프롬프트 각각 규칙 추가 — 이 종목·업종과 관련된 일정만 종합/액션 단락에서 조건부로 짚고,
  무관하면 통째로 건너뜀, 별도 소제목 금지, 일정 자체로 주가 단정 금지.
- `MarketMoodService`(브리핑 시장 분위기): `eventSync?` 주입 → facts에 임박 이벤트 + 프롬프트 규칙
  (코스피 영향 큰 1~2개만 문단 ③에서 날짜와 함께).
- `MacroImpactService`(브리핑 "내 종목 영향"): `eventSync?` 주입 → facts에 임박 이벤트 + 프롬프트 규칙
  (내 보유·관심 섹터에 영향 큰 일정만 마무리 문단 ④에서 날짜와 함께 — 핵심 차별화 "내 포지션 기준" 강화).
- Application.kt 와이어업: eventSync를 macroImpact보다 앞에 생성해 3개 서비스에 공유 주입.
- 캐시 키 불변 — 이벤트는 일 단위로 안정적이라 날짜 기반 키 그대로(churn 없음).

**검증 (로컬 ./run.sh + curl, refresh=true)**
- 시장 분위기 문단 ③: "오늘 밤 **미국 5월 PPI(D-day)**가 높게 나오면 이번 주 목요일(**D-5**) **FOMC** 앞두고
  변동성 커질 수 있어..." — 날짜=사실, 영향=조건부, 핵심 1~2개만 ✓
- SK하이닉스(반도체) 종합 단락: "오는 **6월 17일** FOMC 결정 결과에 따라 반도체·기술주 변동성 커질 수 있어..."
  무관 일정(한국 PPI·Juneteenth·중국 LPR)은 자동 제외 ✓
- 내 종목 영향(보유 SK하이닉스·HD현대중공업 / 관심 한화에어로) 마무리 문단:
  "D-5인 6/17 FOMC 앞두고 금리 민감 반도체주 SK하이닉스는 변동성 확대될 수 있다" — 보유 종목에 직접 연결 ✓
- iOS 코드 변경 없음(코멘트 텍스트만 풍부해짐 → 기존 렌더 그대로).

**막힌 점**
- 저장된 events.json 카테고리가 옛 "고비/온기"(슬라이스3 이전 sync 잔재)였으나 내 코드와 무관하게 동작.
  실배포는 스케줄러 재sync로 호재/주의/중립 확보됨.

**다음 할 일**
- 슬라이스 5: Cloud Scheduler로 /events/sync 자동화(주 1회) — 배포 슬라이스와 묶음.

---

## 2026-06-12 — 거시 이벤트 캘린더 슬라이스 1: ClaudeClient 웹검색 지원

**한 일**
- `ClaudeClient.completeWithWebSearch()`: Anthropic web_search_20250305 서버 도구 지원 추가
  - 기존 `complete()`는 String 기반 content라 tools 필드 자체가 없었음 → JsonElement 기반 별도 경로로 구현
  - multi-turn 루프: stop_reason=tool_use 시 assistant 턴 + server_tool_use 별 빈 tool_result 추가 후 재호출
  - text 블록 수집 + web_search_tool_result 에서 출처 URL 자동 추출
- `WebSearchResult(text, sources)` / `WebSearchSource(title, url)` 모델 추가
- `GET /websearch-test` 검증 라우트 → curl 실호출 검증 완료 (CPI·FOMC·MSCI 일정 + 소스 15개)

**배운 점**
- Anthropic web_search_20250305는 서버 도구(Anthropic이 검색 실행)이나 multi-turn 루프 구조는 클라이언트 도구와 동일. stop_reason=tool_use가 오면 assistant 턴 추가 후 재호출 필요.
- Kotlin `buildJsonArray { forEach { add() } }` 스코프 함정: forEach 람다 안에선 JsonArrayBuilder.add() 스코프가 사라짐 → for 루프로 대체

**다음 할 일**
- 슬라이스 2: EventSyncService + POST /events/sync(웹검색→구조화 JSON upsert) + GET /events?days=30

---

## 2026-06-11 — 앱 아이콘 적용 (summit peak)

**한 일**
- 아이콘 디자인(어두운 배경 `#15110A` + 금색 정상 peak + 점)은 `docs/assets/`에 SVG/PNG 소스로 준비돼 있었음. 실제 앱엔 미적용(설치 시 아이콘 빈칸)이던 걸 적용.
- iOS: `docs/assets/AppIcon.appiconset`(edge-icon-1024.png + Contents.json, 1024 1장 universal)을 `app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`로 복사. 다크/틴티드는 iOS 자동 생성.
- Android(미리 준비, 이 환경 SDK 없어 빌드 검증은 다음에): SVG 좌표 그대로 어댑티브 아이콘 재작성 — `ic_launcher_background.xml` 단색 `#15110A`, `ic_launcher_foreground.xml` peak 벡터를 세이프존 맞춰 `scale 0.62`. 레거시 `mipmap-*/ic_launcher(_round).png` 5밀도 `sips` 리사이즈.

**검증**
- iOS Debug 빌드 → `simctl install booted` → 홈 화면에서 Edge 아이콘 표시 확인. Assets.car에 `edge-icon-1024.png` 렌디션 + 번들에 `AppIcon60x60@2x.png` 생성 확인.

---

## 2026-06-10 — 1.0c-a: 배포 보안 게이트 (토큰 인증 + IP 레이트리밋)

**한 일**
- 백엔드 `Security.kt`/`configureSecurity()`:
  - 공유 토큰 인증 — `intercept(Plugins)`에서 `X-Edge-Token` 헤더를 `EDGE_API_TOKEN` 환경변수와 비교. 불일치 401. `EDGE_API_TOKEN` 비면 검사 생략(로컬 개발 무변). `/health`는 예외.
  - IP별 레이트리밋 — `install(RateLimit)` `RateLimitName("api")`, `X-Forwarded-For` 맨 앞(없으면 remoteHost) 기준 120/분. 라우팅에서 `/health`만 밖에 두고 나머지는 `rateLimit(ApiRateLimit){}`로 감쌈.
  - `build.gradle.kts`에 `ktor-server-rate-limit` 추가.
- `.gitignore`에 `.data/`(MarketMoodLog 영속, 삭제되면 안 되지만 커밋 대상 아님) + iOS `Secrets.xcconfig` 추가. `.env.example`에 `EDGE_API_TOKEN`.
- `backend/Dockerfile`(멀티스테이지 JDK빌드→JRE실행, installDist, PORT 주입) + `.dockerignore` + `deploy.sh` + `docs/backend/deploy.md`(Secret Manager 등록·`max-instances=1`·`--allow-unauthenticated`+토큰층·검증 curl).
- iOS: `EdgeApi(apiToken="")` → 비어있지 않으면 `defaultRequest`로 `X-Edge-Token` 자동 첨부. `Db.api`가 Info.plist `EDGE_BASE_URL`/`EDGE_API_TOKEN`에서 읽고 비면 localhost·토큰없음 폴백. `Config.xcconfig`에 빈 기본값 + `#include? Secrets.xcconfig`(배포값, gitignore) + `Secrets.xcconfig.example`. Info.plist에 `$(EDGE_*)` 치환 키.

**검증**
- 백엔드 `compileKotlin` 통과. 별도 포트(8099)에 `EDGE_API_TOKEN=testtoken123`로 띄워: `/health` 토큰없이 200 · `/quote` 토큰없음/오답 401 · 정답 토큰 200(삼성전자 시세) · `/search` 130연타 → 120 통과 후 11×429. 사용자 8080 dev 서버는 건드리지 않음.
- iOS BUILD SUCCEEDED(arm64 시뮬). 빌드된 Info.plist에 `EDGE_BASE_URL`/`EDGE_API_TOKEN` 빈 값 확인 → 로컬 동작 그대로.

**배운 것**
- `xcodebuild -destination generic/platform=iOS Simulator`는 x86_64로 잡혀 Kotlin arm64 프레임워크와 링크 실패 → 구체적 시뮬 id(arm64) 지정 필요.
- xcconfig는 `//`를 주석 처리 → URL의 `//`는 빈 변수 `$()`로 끊어 적어야(`http:/$()/...`).
- Ktor `rateLimit{}` 블록 밖에 `/health`를 두면 프로브가 인증·제한 없이 통과.

**다음**: 1.0c-b — 실제 Cloud Run 배포(gcloud 로그인·시크릿 등록·deploy.sh) + 친구 디바이스 관통 검증. Apple Developer 계정 시점에 푸시(백로그 7)와 묶음.

---

## 2026-06-10 — 근거 데이터 칩 흐름 레이아웃 (커밋 0e94e7e)

**한 일**
- HStack+Spacer 구조에서 칩이 압축돼 "공매도→공매\n도" 식으로 텍스트가 칩 안에서 줄바꿈되던 문제 수정
- `ChipFlowLayout`(iOS 16 `Layout` 프로토콜) 추가 + 각 칩에 `.fixedSize()` 적용
- 칩은 고정 크기 유지, 한 줄 넘치면 자연스럽게 다음 줄로 흘러내림. 칩 추가 시 자동 대응.

**검증**: iOS 시뮬 확인 완료 (공매도·백테스트 등 한 단어 칩 줄바꿈 없이 표시)

---

## 2026-06-10 — AI 코멘트 사실 수집 병렬화 + 타이밍 로그

**한 일**
- `AnalysisService.analyze()` 사실 수집 13개 순차 → `coroutineScope { async }` 병렬.
  - 1단계: quote·name·flows·bars·financials·consensusTarget·shortSelling·valuationBand·backtest·flowSensitivity·quarterly 동시 발사
  - 2단계: quote·name 확보 후 뉴스(naver.search)·sectorChangeRate(quote.sectorName) 병렬 합류
- `[Timing]` 로그 2줄 삽입: `facts=Xms`, `claude=Xms  total=Xms` → 사실 수집 vs Claude 생성 분리 측정 가능
- 품질 변화 0 — buildFacts 입력/프롬프트/maxTokens 전부 동일

**배운 것**
- `coroutineScope { async {} }` 패턴으로 return 값까지 한 블록 안에 자연스럽게 처리 가능
- KIS Semaphore(3) + 백오프가 이미 있어 병렬화해도 한투 rate limit 안전

**다음**: 백엔드 재시작 → `curl /analysis/{code}` 로 facts=Xms 측정. 스트리밍·분석 재투자는 별도 논의 후 결정.

---

## 2026-06-10 — AI 코멘트 수동 재생성 + 새로고침 UX (커밋 71abc4d)

**한 일**
- 브리핑 3개 AI 섹션(시장 분위기·내 종목 영향·섹터 분석)에도 ?refresh=true 적용 + ↻ 재생성 버튼 추가
- 툴바 새로고침 버튼 역할 표시 개선: "9:32 ↻" 형태로 갱신 시각을 버튼 바로 왼쪽에 표시
  - .labelStyle(.titleAndIcon)은 iOS 툴바에서 무시됨 → HStack(Image+Text)도 동작 확인 후 → 최종 시각+아이콘으로
  - 로딩 중에도 시각 유지, 스피너로 교체. 관심종목·내 자산·브리핑 동일 패턴
- buildMarketMood/Impact/SectorBriefing에 force 파라미터 + 내부에서 loading=true 세팅으로 onChange 핸들러 중복 정리

**배운 것**
- iOS 툴바 아이템에서 Label의 labelStyle은 시스템이 강제 override → Icon-only가 기본. 텍스트 보이려면 HStack으로 직접 구성 필요
- 갱신 시각을 버튼 옆에 두면 "무엇을·언제 갱신했는지" 스크롤 없이 바로 확인 가능 (Apple 날씨·주식 앱 패턴)

**검증**: iOS BUILD SUCCEEDED. 시뮬 스크린샷으로 "9:32 ↻" 노출 확인.

**다음**: 슬라이스 B — 조회 시 자동 stale 감지(생성가 저장 → 가격 ±N% 갭 시 재생성, 30분 쿨다운)

---

## 2026-06-09 — AI 코멘트 수동 재생성 (슬라이스 A)

**한 일**
- 백엔드 `AnalysisRoutes`: `?refresh=true` 파라미터 수신 → `analyze(..., force=true)` 전달.
- 백엔드 `AnalysisService.analyze()`: `force=true`면 인메모리+파일 캐시 읽기 건너뜀, 재생성 후 캐시 덮어씀.
- 캐시 키 보정: 포지션 키에 `targetPrice·stopPrice`(Long 정수 단위) 추가 — 목표가·손절가만 바꿔도 캐시가 다른 키로 잡혀 새 코멘트 생성됨(기존엔 캐시 적중으로 옛 코멘트 나오는 불일치 있었음).
- SharedLogic `getAnalysis()` / `getAnalysisPersonalized()`: `refresh: Boolean = false` 파라미터 추가.
- iOS `loadAnalysis(force: Bool = false)`: force 분기로 API에 `refresh=true` 전달. 기존 `.task`/`.onChange` 호출은 `force=false` 유지.
- iOS 툴바 버튼 역할 명시: `pencil.and.list.clipboard` → `.help("매매 기록")`, `square.and.pencil` → `.help("평단·목표가 수정")`, `arrow.clockwise` → `.help("시세·수급 새로고침")` — 툴바 ↺은 시세만, AI는 카드 버튼으로 분리.
- iOS AI 코멘트 카드 하단: 생성 시각 라벨 오른쪽에 `↺ 재생성` 버튼(보라, caption2) 추가 — 재생성 중엔 ProgressView로 교체. 불러오기 실패 시 "다시 시도" 버튼도 추가.

**배운 것**
- 툴바 ↺에서 `loadAnalysis()` 분리: 시장 데이터(빠름)와 AI 재생성(13초)은 다른 버튼이어야 UX가 자연스러움.
- `force=true`도 캐시에 결과를 덮어씀 → 같은 유저가 다시 열면 새 캐시 적중(재생성 남발 방지).

**검증**: 백엔드 `compileKotlin` BUILD SUCCESSFUL. iOS BUILD SUCCEEDED.

**다음**: 슬라이스 B — 조회 시 자동 stale 감지(생성가 저장 → 가격 ±N% 벌어지면 재생성, 쿨다운).

---

## 2026-06-05 — Phase 3: Claude 섹터 분석 + 주목 종목 추천

**한 일**
- 백엔드 `SectorBriefingService`: 섹터지수 6개 + MacroImpactService 섹터 캐시 재사용. 강세 섹터(+0.5%↑)∩관심종목 교집합을 알고리즘으로 spotlight 결정(LLM 환각 없음). Claude는 2~3문단 해석만 담당. (날짜+codes+섹터등락 0.5% 반올림) 당일 캐시.
- 백엔드 `GET /sector-briefing?codes=...` + `Application.kt` 등록.
- `MacroImpactService.resolveStockSectors()` public 노출 (SectorBriefingService에서 섹터 캐시 재사용).
- SharedLogic `SectorBriefing`/`SpotlightStock` 모델 + `EdgeApi.getSectorBriefing()`.
- `BriefingView` 시장 탭: sectorSection 아래 "섹터 분석" (Claude 코멘트) + "오늘 주목 종목" (강세 섹터 종목, NavigationLink). `allItemsLoaded`/`quoteMapLoaded` state 추가(spotlight 네비게이션용). `sectorBriefingTask` 기존 병렬 로드 패턴에 합류.
- CLAUDE.md: UI/UX 전반 개선 스텝 추가.

**막힌 점·배운 것**
- spotlight 알고리즘: sector_0014(전기전자)는 반도체+전자 혼재 → SEMICONDUCTOR+ELECTRONICS 양쪽 매핑. sector_0028(서비스업)은 IT서비스+로봇 매핑. 완벽하진 않지만 `macroImpact.resolveStockSectors()`가 종목별로 더 정밀하게 분류하므로 교집합 정확도 충분.
- Claude에게 JSON 구조 반환 요구 대신 comment는 plain text + spotlight는 알고리즘 계산으로 분리 → JSON 파싱 오류·환각 위험 제거.

**검증**: 백엔드 BUILD SUCCESSFUL. iOS BUILD SUCCEEDED.

**다음**: UI/UX 전반 개선 — 브리핑·상세화면·관심종목 리스트 디자인 점검. 또는 다른 슬라이스.

---

## 2026-06-05 — 브리핑 UX + 매크로 영향 섹터 자동 추론

**한 일**
- BriefingView: 기본 탭을 "내 종목"으로 변경(enum 순서도 내 종목→시장).
- MacroImpactService.autoSector: SECTOR_OVERRIDE 미매핑 종목 폴백. KIS getPrice() 추가 호출 → sectorName(업종명)으로 섹터 추론(서비스업→IT_SERVICE, 전기가스업→POWER_EQUIP, 기계/조선→SHIPBUILDING, 운수장비→DEFENSE, 전기·전자→ELECTRONICS). LG CNS(064400) "영향 매핑 준비 중" 해소.

**막힌 점·배운 것**
- "전기·전자" 업종명에 반도체·가전이 혼재 → 보수적으로 ELECTRONICS 매핑. 정확도 필요 시 SECTOR_OVERRIDE에 직접 추가.

**검증**: 백엔드 빌드 OK. LG CNS `/macro-impact` → IT서비스 자동 추론, 원/달러·국고채3년 신호 정상. iOS BUILD SUCCESSFUL.

**다음**: 컨센서스 목표주가 — 네이버 금융 스크래핑(NaverTargetPriceClient, 당일 캐시), AnalysisService facts 주입. Sonnet.

---

## 2026-06-05 — Phase 3: 종목 분석 facts 강화 (A: 가격흐름·뉴스 / B: DART 재무)

**한 일 (슬라이스 A)**
- `AnalysisService.priceActionSummary`: 일봉 20개로 최근 고점 대비 낙폭·상한가 수준(+29%↑) 급등 횟수·연속 등락(누적 등락률)을 서사로 계산해 facts에 주입.
- 뉴스: 기존엔 제목만 넣고 description을 버렸음 → description(요약)까지 주입. 30건 받아 자카드 유사도로 클러스터링(제목·요약 둘 다 ≥임계면 묶음, 제목만 비슷하고 요약 다르면 별건 유지) → 대표 8건 + "외 N건"(관심도 신호). 사용자 요구: 젠슨황 도배 뉴스는 한 건으로, 요약에 추가정보 있으면 개별건.
- SYSTEM_PROMPT: 촉매→일시적/펀더멘털→현재 위치 흐름 + 기회비용 가이드 추가.

**한 일 (슬라이스 B)**
- `DartClient.getFinancials`: fnlttSinglAcnt.json(사업보고서 11011)으로 매출·영업이익·당기순이익 당기/전기 추출. 연결(CFS) 우선·별도(OFS) 폴백, 최근 연도부터 역순 시도, 날짜 캐시. `FinancialSummary` DTO.
- `AnalysisService`: DartClient 주입(Application에서 dart 생성을 analysis 앞으로 이동). `financialSummaryText`로 억원 단위·전년比 YoY를 facts에 추가. PER/PBR·기대감이 실적 성장으로 뒷받침되는지 대조 가이드.

**막힌 점·배운 것**
- session limit으로 한 번 끊겼다 이어서 진행.
- DART 금액은 콤마 포함 문자열 → `replace(",","")` 후 Long 파싱. 계정명은 회사마다 "매출액/수익(매출액)/영업수익" 등 변형 → contains 매칭.
- **facts만 풍부해지면 Sonnet으로 충분한 품질**이 나옴(Opus 불필요). Opus 전환 시 분석 1회 $0.02→$0.10(약 5배), 월 +$25~35(15~20종목/일 기준) 추산.

**검증**: 백엔드 빌드 OK. LG CNS(064400) 분석에서 ①젠슨황 방한 촉매+상한가 후 3일 연속하락(-19.5%) ②매출+2.5%/영업익+7.6%/순익+20.3% 반영해 'AI 프리미엄 vs 실적 간극' 짚음. 삼성전자 2024 매출 300.9조 스케일 검산 일치. `Analysis` 모델 불변이라 iOS 변경 없음(코멘트만 풍부해짐).

**다음**: 슬라이스 C(Claude 웹검색으로 실시간 촉매) 검토 중 — 사용자 결정 대기. (분석은 Sonnet 유지 권고)

---

## 2026-06-05 — Phase 3: 매크로 v3 (구리 + 국고채3년)

**한 일**
- **구리(CopperClient)**: KIS Open API는 HG(구리선물)를 지원하지 않음(rt_cd=0이지만 price=0으로 반환). Yahoo Finance 비공개 API(`query1.finance.yahoo.com/v8/finance/chart/HG=F`)로 COMEX 구리선물 조회. 30분 캐시. 실패 시 null(섹션 유지).
- **국고채3년(EcosClient)**: ECOS Open API `817Y002/D/010300000`로 국고채 3년 금리 조회. ECOS_API_KEY 없으면 skip(graceful). 당일 자정까지 캐시. `.env`에 `ECOS_API_KEY=` 추가(ecos.bok.or.kr 무료 발급 필요).
- **MacroImpactService**: copper·rate3y를 IMPACT_INDICATORS에 추가. 전력기기·전자에 구리 민감도(-1, 원재료 부담), 반도체·조선·전력기기·IT서비스에 금리 민감도(-1, 성장주/부채 부담) 추가.
- **MacroRoutes**: CopperClient·EcosClient 주입해 `/macro` 응답에 병합.

**막힌 점·배운 것**
- KIS HG코드: 어떤 ISCD 변형(HG/COPPER/4HGc1)이든 rt_cd=0에 price=0 반환 → KIS가 구리 선물을 미지원.
- Yahoo Finance: `HG=F` 5일 range로 `regularMarketPrice`·`chartPreviousClose` 바로 읽어서 changeRate 계산 가능. User-Agent 헤더 필요.
- session limit이 걸려 작업이 중간에 끊겼으나 이어서 완료.

**검증**: 백엔드 빌드 OK. `/macro` 구리 6.41 -1.76% 정상. `/macro-impact` 전력기기에 구리 신호 정확. iOS BUILD SUCCESSFUL. → iOS 시뮬 수동 확인 필요.

**다음**: 다음 슬라이스 논의.

---

## 2026-06-05 — Phase 3: 섹터 대시보드 v1

**한 일**
- 백엔드 `GET /sectors`: `KisClient.getSectorIndices()` — KOSPI 업종지수 6개(전기전자·기계·운수장비·전기가스업·서비스업·철강금속) 병렬 조회(`inquire-index-price`, FHPUP02100000, MRKT=U). `SectorRoutes.kt` 신규. `Application.kt` 라우트 등록.
- `KisModels.kt`에 `SectorIndex` DTO 추가.
- SharedLogic `SectorIndex` 모델 + `EdgeApi.getSectors()`.
- `BriefingView` 시장 탭 "섹터 동향" 섹션: 시장 지표 아래·실적 일정 위에 배치. 상승 빨강/하락 파랑. macroTask와 독립 병렬 로드.

**막힌 점·배운 것**
- 기존에 KOSPI(0001)/KOSDAQ(1001)에 쓰던 `requestIndex` + `MRKT=U` 코드가 업종지수에도 그대로 동작. ISCD 코드 체계 확인(0014=전기전자, 0013=기계, 0016=운수장비, 0018=전기가스업, 0028=서비스업, 0012=철강금속).

**검증**: 백엔드 빌드 OK. `curl /sectors` 6개 전부 정상(전기전자 1628.36 -4.11% 등). iOS BUILD SUCCEEDED. → iOS 시뮬 수동 확인 필요.

**다음**: 다음 슬라이스 논의.

---

## 2026-06-05 — Phase 3: 실적 캘린더 v1

**한 일**
- 백엔드 `GET /earnings?codes=`: DART pblntf_ty=A(정기공시)로 최근 18개월 조회 → 최근 보고서 종류 파악 → 법정 마감일 계산(분기/반기 45일·사업 90일 기준). 당일 캐시(date+code 키). D-90 이내만 반환, daysUntil 오름차순.
  - 다음 예정 계산 규칙: 분기(03) → 반기(06, 8/14), 반기(06) → 분기(09, 11/14), 분기(09) → 사업(12, 3/31), 사업(12) → 분기(03, 5/15).
- SharedLogic `EarningsEntry` 모델 + `EdgeApi.getEarnings()`.
- `BriefingView` "실적 일정 (D-90 이내)" 섹션: daysUntil 오름차순 표시, D-14 빨강/D-30 주황/이상 회색 배지. 시장 지표 아래, 매크로 영향 위에 배치.

**막힌 점·배운 것**
- `toCodeList()`가 MacroRoutes.kt에 `private`라 EarningsRoutes.kt에서 접근 불가 → 파일 내 재선언.
- Kotlin `Int` → Swift `Int32` 브리지 → `Int(e.daysUntil)` 변환 필요.

**검증**: 백엔드 빌드 OK. `/earnings?codes=018260,329180,...` 5개 전부 반기보고서(2026.06) D-70. iOS BUILD SUCCEEDED. → iOS 시뮬 수동 확인 필요.

**다음**: 다음 슬라이스 논의.

---

## 2026-06-05 — Phase 3: 매크로 v2 (WTI유가 + 공포탐욕지수)

**한 일**
- **WTI유가**: `MACRO_SPECS`에 `MacroSpec("crude", "WTI유가", OVERSEAS, "N", "CL")` 추가. `"C"`는 잘못된 MRKT코드 → `"N"`이 정답(상품선물도 해외지수 코드로 조회됨). `requestOverseas` output1 필드 그대로 재사용.
- **공포탐욕지수**: `FearGreedClient` 신규 작성 — CNN `https://production.dataviz.cnn.io/index/fearandgreed/graphdata`. 봇 차단(418 teapot) → User-Agent/Accept-Language/Referer 브라우저 헤더 필요. score/previous_close로 change·changeRate 계산. rating을 한국어(탐욕/공포/중립/극단적 탐욕/극단적 공포)로 변환해 `tag` 필드에 담아 반환. 30분 인메모리 캐시.
- `MacroIndicator`에 `tag: String = ""` 추가(백엔드·KMP 공유 모델 동시). F&G만 tag 사용, 나머지는 빈 문자열.
- `/macro` 라우트: KIS 7개(기존 6 + WTI) + FearGreedClient 1개 합산 반환(F&G 실패 시에도 KIS분만 내려감).
- `MacroImpactService`: `IMPACT_INDICATORS`에 `"crude"` 추가. 방향 계산 섹터별 WTI 민감도 추가(반도체 -1/조선 +1/전력기기 +1/전자 -1). F&G는 방향 계산 제외, Claude facts 맥락용으로만 포함. `FearGreedClient`를 생성자 인자로 받도록 변경.
- `BriefingView`: `macroRow`에 tag 배지 추가(공포=파랑/탐욕=빨강/중립=회색). F&G 값은 `"%.1f"` 포맷(0–100 점수, 천단위 불필요).

**막힌 점·배운 것**
- WTI 한투 코드: `FID_COND_MRKT_DIV_CODE="C"`가 아니라 `"N"`. curl 직접 테스트로 확인.
- CNN F&G API: 단순 curl은 "I'm a teapot. You're a bot." 반환 → User-Agent/Referer 필수.

**검증**: 백엔드 빌드 OK. `/macro` 8개 전부 정상(WTI 85.10 +0.27%, F&G 54.7 중립). `/macro-impact` crude 신호 정확(SK하이닉스 WTI→부담, 조선 WTI→우호). iOS BUILD SUCCEEDED. → iOS 시뮬 수동 확인 필요.

**다음**: 다음 슬라이스 논의.

---

## 2026-06-05 — Phase 3: 매크로 지표 v1 (시장 지표)

**한 일**
- 백엔드 `GET /macro`: 한투 키로 바로 되는 6개만 v1 — 코스피·코스닥(국내 업종지수) + 원/달러·다우·나스닥·S&P500(해외 지수/환율).
  - 국내: `inquire-index-price` (tr_id `FHPUP02100000`, MRKT=U, ISCD 0001/1001).
  - 해외: `inquire-daily-chartprice` (tr_id `FHKST03030100`, MRKT N=지수/X=환율, ISCD `FX@KRW`/`.DJI`/`COMP`/`SPX`). 현재값은 output1(요약)에서.
  - `KisClient.getMacroIndicators()`: 6개 `async`+`awaitAll` 병렬, 개별 실패는 `runCatching`으로 제외(섹션 통째로 안 죽음). 전일대비·등락률은 `prdy_vrss_sign`(4·5=하락→−1)으로 부호 적용 → 원본에 부호가 있든 없든 `abs×sign`이라 안전.
  - `MacroSpec`/`MacroKind`/`MacroRaw` 내부 표현으로 국내·해외 응답을 부호 적용 전 공통 형태로 통일.
- SharedLogic: `MacroIndicator` 모델 + `EdgeApi.getMacro()`.
- `BriefingView`: 최상단 "시장 지표" 섹션. quotes와 무관해 `async let macroTask`로 독립 병렬, 섹션 내부 스피너. 보합=회색/상승 빨강/하락 파랑, 값은 `%,.2f`.

**막힌 점·배운 것**
- 코스피·코스닥 전일대비가 0으로 와서 필드명 의심 → raw 응답 임시 로그로 확인: 필드명(`bstp_nmix_prdy_vrss`/`bstp_nmix_prdy_ctrt`/`prdy_vrss_sign`) 다 정상, **08시 장 시작 전이라 한투가 vrss="0.00", sign="3"(보합)을 주는 게 맞음**. 09시 개장 후 채워짐. (코스피 값 8639도 실값 — 응답의 연중고점 8933.62/20260602 필드로 교차확인.) 디버그 로그는 제거.
- 해외(원/달러·미국지수)는 등락률·부호까지 한 번에 정상.

**검증**: 백엔드 컴파일 + `curl /macro` 6개 전부 정상. iOS `BUILD SUCCEEDED`. → iOS 시뮬 수동 확인 + 09시 개장 후 국내지수 등락 표시 확인 남음.

**다음**: 매크로 v2(유가·구리·국채금리 ECOS·공포탐욕지수) 또는 매크로→포트폴리오 영향 해석 / 실적 캘린더 / 섹터 대시보드.

---

## 2026-06-05 — Phase 3: 매크로 → 내 종목 영향 해석 v1

**한 일**
- 포맷 버그 수정: 시장 지표 값이 화면에 `,.2f`로 깨져 나옴 → Swift `String(format:)`은 천단위(`%,`) 플래그 미지원이 원인. `NumberFormatter(.decimal)`로 교체(파일 레벨 1회 생성).
- 백엔드 `GET /macro-impact?holdings=&watchlist=` + `MacroImpactService`:
  - 도메인 매핑(한 곳에 모음): 우리 분류 `Sector` enum + 종목→섹터 `SECTOR_OVERRIDE`(관심종목 11개) + 섹터×지표 `SENSITIVITY`(direction +1/0/-1 + 근거 한 줄).
  - 종목별 영향 방향 = **민감도 부호 × 지표 등락 부호** 계산(사실). net = 신호 합의 부호로 우호/부담/중립. → Claude는 보유/관심 구분 종합 해석만(사실/해석 분리, 환각가드).
  - v1 영향 지표 = 원/달러·나스닥(유가·구리·금리는 v2에서 추가되면 SENSITIVITY만 확장).
  - 캐시: `(날짜+종목집합+영향지표 등락 0.5%반올림)` → 등락 의미변화 시 재생성, 그 외 당일 공유.
- SharedLogic `MacroImpact`/`StockImpact`/`MacroSignal` + `EdgeApi.getMacroImpact()`.
- `BriefingView` "내 종목 영향" 섹션: Claude 코멘트(마크다운 렌더로 **굵게**·줄바꿈 살림) + 디스클레이머 + 보유/관심 종목 행(섹터·net 배지·지표별 신호). impact는 코드만 필요해 quotes와 독립 병렬(`async let`).

**막힌 점·배운 것**
- Kotlin 리스트의 `.indices`를 Swift `ForEach`에 쓰면 `indices(where:)` 오버로드로 잘못 해석됨 → `ForEach(list, id: \.someField)`로 회피.
- Kotlin `Int`는 Swift에 `Int32`로 브리지 → `Int(sig.direction)`로 변환 후 헬퍼에 전달.

**검증**: `curl /macro-impact` 정상(첫 13.4초 → 캐시 0.17초). 빈 보유·매핑없는 종목(net "-") 방어 OK. 신호 계산 정확(SK하이닉스 환율+/나스닥− → 중립, 한국항공우주 → 우호적). iOS `BUILD SUCCEEDED`. → iOS 시뮬 수동 확인 남음.

**다음**: 매크로 v2(ECOS 금리·유가·공포탐욕) / 실적 캘린더 / 섹터 대시보드.

---

## 2026-06-04 — Phase 3: DART 공시 v1

**한 일**
- 백엔드 `DartClient`: DART `corpCode.xml` ZIP 최초 1회 다운로드 → SAX 파싱 → `stock_code→corp_code` 전체 맵 인메모리 캐시(Mutex) → `/list.json` 공시 목록.
  - 함정: DART `CORPCODE.xml`의 개별 기업 항목 태그가 `<corp>` 가 아닌 `<list>`. SAX endElement 조건 수정으로 해결.
  - `/company.json` 은 `corp_code` 가 필수라 `stock_code` 조회 불가 → corpCode.xml 전체 맵 방식으로 대체.
- `GET /dart/{code}?days=7` 라우트. `DartException` → 기존 StatusPages 502.
- `.env.example`에 `DART_API_KEY` 항목 추가.
- SharedLogic: `DartDisclosure` 모델 + `EdgeApi.getDartDisclosures()`.
- `BriefingView`: "최근 공시(7일)" 섹션 추가. 관심종목 전체 `/dart` 병렬(`withTaskGroup`) → 최신순 10건. supply·dart `async let` 동시 진행. 날짜 YY.MM.DD 포맷. Link → Safari.

**검증**: curl(삼성전자 10건, 삼성에스디에스 6건) + iOS 시뮬 확인 완료.

**다음**: 매크로 지표 / 실적 캘린더 / Phase 3 기타 항목.

---

## 2026-06-04 — Phase 3: 브리핑 탭 UX 개선

**한 일**
- `loading` / `supplyLoading` 분리: quotes 완료 시 하이라이트·보유현황 즉시 표시, 수급 섹션만 내부 스피너("확인 중…") 유지.
- 수급 섹션 로딩 중 "해당 종목 없음" 오표시 수정 → `supplyLoading` 체크로 구분.
- 하이라이트 섹션 항상 표시 + 빈 상태(보합) "변동 종목 없음" 메시지 추가.
- 툴바: 로딩 중 새로고침 버튼 → 작은 `ProgressView`로 교체.

**검증**: `BUILD SUCCEEDED`.

**다음**: iOS 시뮬 수동 확인 / Phase 3 남은 항목.

---

## 2026-06-04 — Phase 3: 브리핑 탭 v1

**한 일**
- `BriefingView.swift` 신규 생성. 3섹션 구성:
  - **오늘 하이라이트**: 관심종목 `/quotes` 전체 로드 → 등락률 상위 2개(상승) + 하위 2개(하락) 표시.
  - **보유현황**: 평단·수량 입력 종목 PnL 집계 카드 + 종목별 손익 리스트. PortfolioView와 공통 데이터 구조.
  - **수급주목**: `/investor` 를 `withTaskGroup` 병렬 호출 → 외인 or 기관 3일 연속 순매수 종목 badge 표시.
- `ContentView` TabView에 `BriefingView` 탭 추가 (`newspaper` 아이콘).

**검증**: `BUILD SUCCEEDED` (iPhone 17 Pro Simulator).

**다음**: iOS 시뮬 수동 확인 / Phase 3 남은 항목: 매크로 지표·DART 공시·실적 캘린더 / 데일리 브리핑 고도화.

---

## 2026-06-04 — Phase 3: 하단 탭바 + 내 자산 탭

**한 일**
- `ContentView` → `WatchlistView` 리네임. `Db.api` 공유 싱글톤 추가(EdgeApi 인스턴스 중복 생성 제거).
- `ContentView` = TabView 래퍼 (관심종목·내 자산 탭). 브리핑 탭은 Phase 3 데일리 브리핑 때 추가.
- `PortfolioView`: 평단·수량 입력된 종목만 필터 → `/quotes` 현재가 → 총 투자금·총 평가금액·총 손익·수익률 집계 카드 + 보유 종목 리스트(행 탭→StockDetailView). 빈 상태 안내 문구.

**검증**: iOS 시뮬 — 탭바 표시, 관심종목 탭 정상 동작. Xcode BUILD SUCCEEDED.

**다음**: 평단 입력 후 내 자산 탭 집계 확인(수동) / Phase 3 데일리 브리핑 / DART 공시 / 매크로.

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

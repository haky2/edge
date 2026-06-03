# 작업 로그 (devlog)

세션마다 **한 일 / 막힌 점·배운 것 / 다음 할 일**만 가볍게 남긴다.
"무엇이 끝났나/다음 단계"의 상세는 `CLAUDE.md`의 Phase 체크리스트가 정본. 여기엔 맥락·서사만.
최신이 위로 오게 적는다.

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

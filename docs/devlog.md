# 작업 로그 (devlog)

세션마다 **한 일 / 막힌 점·배운 것 / 다음 할 일**만 가볍게 남긴다.
"무엇이 끝났나/다음 단계"의 상세는 `CLAUDE.md`의 Phase 체크리스트가 정본. 여기엔 맥락·서사만.
최신이 위로 오게 적는다.

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

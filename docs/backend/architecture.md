# 백엔드 아키텍처 컨벤션

> 원칙: **규모에 맞는 아키텍처.** 지금은 가벼운 레이어드, 도메인이 복잡해지는 곳만 DDD를 깊게 적용한다.
> 기능 몇 개에 풀 DDD를 까는 오버엔지니어링은 피한다.

## 현재 상태 진단 (의도 안 했어도 이미 잡혀 있는 결)

- `routes/` = **표현 계층**(얇은 컨트롤러)
- `kis/`, `master/` = **인프라 계층**(외부 API 클라이언트)
- `Application.kt` = **컴포지션 루트**(의존성 조립 + 플러그인 설치)
- `Kis*`(한투 원본 DTO) ↔ `Quote`(정규화 모델) 분리 = DDD의 **안티커럽션 레이어**
  - 외부(한투) 응답 포맷이 우리 도메인/앱 계약을 오염시키지 않게 막는 경계. **이 프로젝트의 핵심 사상.**

## 채택 컨벤션: package-by-feature

레이어를 최상위로 가르지(`routes/`, `service/`, `infra/`) 않고 **기능별로 묶고, 그 안에서 역할을 나눈다.**
이 규모에선 응집도가 높고(한 기능이 한 폴더), 기능 추가·삭제가 쉽다.

```
com.haky.edge
├─ Application.kt          # 컴포지션 루트 (라우팅·플러그인·DI 조립)
├─ common/                 # 공통(에러 응답, 직렬화 설정 등)
├─ kis/                    # 한투 클라이언트 — 여러 기능이 공유하는 infra (예외적으로 횡단)
├─ quote/                  # 시세:   QuoteRoutes (+ 필요시 QuoteService)
├─ search/                 # 검색:   SearchRoutes + StockMaster
├─ supply/    (Phase 2)    # 수급(외인/기관)
├─ news/      (Phase 2)    # 뉴스
├─ briefing/  (Phase 3)    # 데일리 브리핑
└─ analysis/  (Phase 2~3)  # Claude 분석 ← 도메인 복잡해지면 여기만 DDD 깊게
```

### 기능(feature) 내부 역할
- **`XxxRoutes.kt`** — 표현 계층. HTTP 입출력만. 얇게 유지(검증·응답 변환 정도), 비즈니스 로직 금지.
- **`XxxService.kt`** — 애플리케이션/도메인 로직. *로직이 생길 때만* 만든다(지금 quote/search는 단순해 없음).
- **`XxxClient.kt` / `XxxRepository.kt`** — 인프라. 외부 API·DB 접근.

### 횡단(공유) 요소
- **`kis/`** — 한투 클라이언트는 quote·supply 등 여러 기능이 쓰므로 기능 폴더 밖 공유 infra로 둔다. (DART도 나중에 `dart/`)
- **`common/`** — 에러 모델 등 전 기능 공통.

## 불변 규칙 (지금부터 지킨다)
1. **외부 원본 모델을 라우트 응답으로 그대로 내보내지 않는다.** 항상 우리 모델로 정규화(예: `Kis*` → `Quote`). = 안티커럽션 유지.
2. **라우트는 얇게.** 로직은 Service/Client로.
3. **키·시크릿은 코드에 두지 않는다**(환경변수만).
4. 새 외부 연동은 공유 infra 패키지(`kis/`, `dart/`…)에, 기능별 화면/로직은 feature 패키지에.

## 적용 시점
- **지금(Phase 1):** 현재 구조(`kis/`, `master/`, `routes/`) 유지. 리팩터링 안 함.
- **Phase 2 시작 시:** 수급/뉴스가 들어오면서 위 package-by-feature로 재구성.
  - 이때 `routes/QuoteRoutes` → `quote/QuoteRoutes`, `routes/SearchRoutes` + `master/StockMaster` → `search/` 로 이동.
- **Phase 4(학습/통계)·분석 도메인:** 로직이 진짜 복잡해지는 그 기능에 한해 Service/도메인 모델을 두껍게(필요하면 Repository 추상화).

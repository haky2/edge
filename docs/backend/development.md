# 백엔드 개발 가이드

Ktor(Kotlin) 서버. 한투/DART/Claude 등 외부 API를 대신 호출하고 키를 보관하는 계층.

## 빠른 시작

```bash
cd backend
cp .env.example .env        # 그리고 .env 에 한투 키 입력
./run.sh                    # .env 를 읽어 서버 실행 (localhost:8080)
```
다른 터미널에서:
```bash
curl localhost:8080/health
curl localhost:8080/quote/009150
```

## 환경변수 (.env)

| 변수 | 설명 |
|---|---|
| `KIS_APP_KEY` / `KIS_APP_SECRET` | 한투 Open API 앱키/시크릿. **절대 커밋 금지**(.env 는 .gitignore됨). |
| `KIS_BASE_URL` | 실전 `https://openapi.koreainvestment.com:9443`(기본) / 모의 `...:29443`. |
| `PORT` | 기본 8080. Cloud Run은 런타임이 주입하므로 코드에서 env로 읽는다. |

`run.sh` 가 `.env` 를 `source` 해서 환경변수로 넣어준다(Ktor가 `System.getenv`로 읽음).

## 프로젝트 구조

```
backend/
├─ build.gradle.kts            # 의존성(Ktor server/client, serialization, logback)
├─ run.sh                      # .env 읽고 ./gradlew run
├─ .env.example               # 키 템플릿(.env 는 gitignore)
└─ src/main/
   ├─ kotlin/com/haky/edge/
   │  ├─ Application.kt         # 서버 부팅, 플러그인(ContentNegotiation/StatusPages), 라우팅 등록
   │  ├─ kis/
   │  │  ├─ KisClient.kt        # 한투 호출(OAuth 토큰 캐시 + 현재가)
   │  │  └─ KisModels.kt        # 한투 원본 DTO + 정규화 Quote
   │  ├─ master/
   │  │  └─ StockMaster.kt      # 종목 마스터(.mst) 다운로드·파싱·검색
   │  └─ routes/
   │     ├─ QuoteRoutes.kt      # GET /quote/{code}
   │     └─ SearchRoutes.kt     # GET /search
   └─ resources/logback.xml     # 로깅
```

설계 원칙: **`kis/`·`master/` 가 외부 연동, `routes/` 가 HTTP 노출.** 라우트는 얇게 두고 로직은 클라이언트 클래스에.
한투 원본 DTO(`Kis*`)와 앱에 주는 `Quote` 를 분리해, 한투 포맷이 바뀌어도 앱 계약은 유지한다.

## 자주 쓰는 명령

```bash
./gradlew build           # 컴파일 + 검사
./gradlew run             # 실행(환경변수는 직접 export 하거나 run.sh 사용)
./gradlew compileKotlin   # 컴파일만 빠르게
```

## 빌드/툴 버전
- JDK 21 (SDKMAN), Gradle 9.5.1(wrapper), Kotlin 2.3.20, Ktor 3.2.0.

## 배포 (예정, 1.0c)
- Cloud Run 컨테이너로 배포. 키는 Cloud Run 환경변수/Secret으로 주입(.env 와 동일 변수명).
- 배포 후 베이스 URL을 앱 설정에 반영. 실기기·친구 테스트는 이 시점부터 가능.

## 트러블슈팅
- 한투 연동 관련 에러(EGW…, 부호, 종목명 없음 등)는 [kis-api-notes.md](kis-api-notes.md) 참고.
- 포트 점유로 안 뜨면: `lsof -ti tcp:8080 | xargs kill`.

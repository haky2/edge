# Edge 백엔드 컨테이너 이미지 (Cloud Run 배포용, 1.0c-a).
# 멀티스테이지: ① JDK로 빌드 → ② 가벼운 JRE로 실행.

# ── 빌드 스테이지 ───────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 레이어 캐싱: 빌드 스크립트·래퍼 먼저 복사하면 소스만 바뀔 때 의존성 재다운로드를 건너뛴다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 실행 가능한 배포본 생성(application 플러그인의 installDist).
COPY src ./src
RUN ./gradlew --no-daemon installDist

# ── 실행 스테이지 ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드 산출물(start 스크립트 + 라이브러리)만 가져온다. rootProject.name = edge-backend.
COPY --from=build /app/build/install/edge-backend ./

# Cloud Run은 PORT 환경변수를 주입하고 그 포트로 트래픽을 보낸다(Application.kt가 PORT를 읽음).
# 키(KIS·ANTHROPIC·EDGE_API_TOKEN 등)는 이미지에 넣지 않고 Secret Manager로 런타임 주입한다.
ENV PORT=8080
EXPOSE 8080

CMD ["./bin/edge-backend"]

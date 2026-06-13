#!/usr/bin/env bash
# Cloud Run 배포 헬퍼 (1.0c). 상세·사전준비는 docs/backend/deploy.md 참고.
# 시크릿은 미리 Secret Manager에 등록돼 있어야 한다(deploy.md의 "시크릿 등록" 절).
set -euo pipefail
cd "$(dirname "$0")"

PROJECT="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${GCP_REGION:-asia-northeast3}"
SERVICE="edge-backend"

if [ -z "$PROJECT" ]; then
  echo "GCP 프로젝트가 설정되지 않았습니다. 'gcloud config set project <ID>' 또는 GCP_PROJECT 환경변수." >&2
  exit 1
fi

echo "→ 배포: service=$SERVICE project=$PROJECT region=$REGION (max-instances=1)"

# GCS 볼륨 마운트:
#   edge-kis-token  → /mnt/token  : KIS OAuth 토큰 (재발급 방지)
#   edge-app-cache  → /mnt/cache  : FileCache 당일 Claude/KIS 결과 (콜드 스타트 재호출 방지)
#   edge-app-data   → /mnt/data   : MarketMoodLog 적중률 누적 히스토리 (영구 보존)
gcloud run deploy "$SERVICE" \
  --source . \
  --project "$PROJECT" \
  --region "$REGION" \
  --min-instances 0 \
  --max-instances 1 \
  --allow-unauthenticated \
  --add-volume "name=kis-token,type=cloud-storage,bucket=edge-kis-token" \
  --add-volume-mount "volume=kis-token,mount-path=/mnt/token" \
  --add-volume "name=app-cache,type=cloud-storage,bucket=edge-app-cache" \
  --add-volume-mount "volume=app-cache,mount-path=/mnt/cache" \
  --add-volume "name=app-data,type=cloud-storage,bucket=edge-app-data" \
  --add-volume-mount "volume=app-data,mount-path=/mnt/data" \
  --set-env-vars "KIS_TOKEN_CACHE=/mnt/token/.kis-token.json,CACHE_DIR=/mnt/cache,DATA_DIR=/mnt/data" \
  --set-secrets "KIS_APP_KEY=KIS_APP_KEY:latest,KIS_APP_SECRET=KIS_APP_SECRET:latest,NAVER_CLIENT_ID=NAVER_CLIENT_ID:latest,NAVER_CLIENT_SECRET=NAVER_CLIENT_SECRET:latest,ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,DART_API_KEY=DART_API_KEY:latest,ECOS_API_KEY=ECOS_API_KEY:latest,EDGE_API_TOKEN=EDGE_API_TOKEN:latest"

URL=$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" --format='value(status.url)')
echo "→ 배포 완료: $URL"
echo "→ 헬스체크:"
curl -s "$URL/health" && echo

# Cloud Scheduler 잡 동기화 (없으면 create, 있으면 update)
echo "→ Cloud Scheduler 잡 동기화..."
EDGE_TOKEN=$(gcloud secrets versions access latest --secret=EDGE_API_TOKEN --project="$PROJECT")

scheduler_upsert() {
  local JOB=$1; shift
  # delete 후 create로 통일한다. update http는 헤더 플래그가 --update-headers 로 달라(create는 --headers)
  # 같은 인자를 양쪽에 넘기면 update가 깨진다 → 항상 create 문법만 쓰도록 delete+create.
  # 잡은 스케줄대로만 발화하므로 재배포 중 잠깐 없어도 무방하다.
  gcloud scheduler jobs delete "$JOB" --project="$PROJECT" --location="$REGION" --quiet 2>/dev/null || true
  gcloud scheduler jobs create http "$JOB" "$@" --project="$PROJECT" --location="$REGION"
}

# mood-log-morning: 매주 월~금 오전 5:00 KST — 코스피 방향 예측 기록
scheduler_upsert mood-log-morning \
  --schedule="0 5 * * 1-5" --time-zone="Asia/Seoul" \
  --uri="$URL/market-mood?mode=defensive" --http-method=GET \
  --headers="X-Edge-Token=${EDGE_TOKEN}" \
  --attempt-deadline=120s \
  --description="매주 월~금 오전 5시 KST 코스피 방향 예측 기록 (미장 마감 직후)"

# mood-log-afternoon: 매주 월~금 오후 3:35 KST — 코스피 마감 후 자동 채점
scheduler_upsert mood-log-afternoon \
  --schedule="35 15 * * 1-5" --time-zone="Asia/Seoul" \
  --uri="$URL/market-mood?mode=defensive" --http-method=GET \
  --headers="X-Edge-Token=${EDGE_TOKEN}" \
  --attempt-deadline=120s \
  --description="매주 월~금 오후 3:35 KST 코스피 마감 후 자동 채점"

# events-sync: 매주 월요일 오전 6:00 KST — 거시 이벤트 캘린더 자동 동기화
scheduler_upsert events-sync \
  --schedule="0 6 * * 1" --time-zone="Asia/Seoul" \
  --uri="$URL/events/sync" --http-method=POST \
  --headers="X-Edge-Token=${EDGE_TOKEN},Content-Type=application/json" \
  --message-body="{}" \
  --attempt-deadline=300s \
  --description="매주 월요일 오전 6시 KST 거시 이벤트 캘린더 자동 동기화 (Claude 웹검색, 6주치)"

# prewarm: 매주 월~금 오전 8:45 KST — 관심종목 시세·수급·공시 캐시 예열(아침 첫 진입 가속)
# 코드 목록은 CLAUDE.md 관심종목 11개. 사용자가 종목을 바꿔도 미포함분은 온디맨드로 조회됨(예열은 best-effort).
PREWARM_CODES="018260,329180,066570,307950,000660,005930,267260,001440,062040,047810,012450"
scheduler_upsert prewarm \
  --schedule="45 8 * * 1-5" --time-zone="Asia/Seoul" \
  --uri="$URL/prewarm?codes=${PREWARM_CODES}" --http-method=GET \
  --headers="X-Edge-Token=${EDGE_TOKEN}" \
  --attempt-deadline=120s \
  --description="매주 월~금 오전 8:45 KST 관심종목 시세·수급·공시 캐시 예열"

echo "→ Scheduler 잡 동기화 완료"

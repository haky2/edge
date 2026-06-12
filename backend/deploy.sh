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

# KIS 토큰 파일을 GCS 버킷(edge-kis-token)에 저장해 컨테이너 재시작 시에도 재발급 없이 재사용.
# 볼륨 마운트: gs://edge-kis-token → /mnt/token, KIS_TOKEN_CACHE로 파일 경로 지정.
gcloud run deploy "$SERVICE" \
  --source . \
  --project "$PROJECT" \
  --region "$REGION" \
  --min-instances 0 \
  --max-instances 1 \
  --allow-unauthenticated \
  --add-volume "name=kis-token,type=cloud-storage,bucket=edge-kis-token" \
  --add-volume-mount "volume=kis-token,mount-path=/mnt/token" \
  --set-env-vars "KIS_TOKEN_CACHE=/mnt/token/.kis-token.json" \
  --set-secrets "KIS_APP_KEY=KIS_APP_KEY:latest,KIS_APP_SECRET=KIS_APP_SECRET:latest,NAVER_CLIENT_ID=NAVER_CLIENT_ID:latest,NAVER_CLIENT_SECRET=NAVER_CLIENT_SECRET:latest,ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,DART_API_KEY=DART_API_KEY:latest,ECOS_API_KEY=ECOS_API_KEY:latest,EDGE_API_TOKEN=EDGE_API_TOKEN:latest"

URL=$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" --format='value(status.url)')
echo "→ 배포 완료: $URL"
echo "→ 헬스체크:"
curl -s "$URL/health" && echo

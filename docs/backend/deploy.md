# 배포 가이드 — Cloud Run (1.0c)

친구 몇 명 규모의 개인 배포. 무료 티어 안에서 도는 게 목표.

## 보안 모델 (1.0c-a에서 구현)

공개 Cloud Run URL은 인증 없으면 누구나 `/analysis`를 때려 ANTHROPIC 토큰을 과금시킬 수 있다. 두 층으로 막는다:

1. **공유 토큰 헤더** — 앱이 모든 요청에 `X-Edge-Token` 헤더를 붙이고, 백엔드가 `EDGE_API_TOKEN` 환경변수와 비교. 불일치 시 401. `/health`만 예외(프로브용).
   - 한계: 토큰은 앱 바이너리에 박혀 추출 가능 → **"캐주얼 차단"** 수준. 진짜 안전판은 아래 레이트리밋 + `max-instances=1` + 비용 모니터링.
2. **IP별 레이트리밋** — `X-Forwarded-For` 맨 앞(실 클라이언트 IP) 기준 분당 120회. 토큰이 유출돼도 청구 폭탄을 막는다.
3. **`max-instances=1`** — `FileCache`(`.cache/`)·`.kis-token.json`(1일 1토큰)·`MarketMoodLog`(`.data/`)가 로컬 디스크 기반이라 다중 인스턴스에서 깨진다. 인스턴스 1개로 고정해 회피.

> 토큰이 유출돼 악용되면 `EDGE_API_TOKEN`만 새로 발급해 교체하면 된다(앱도 같이 갱신 배포).

## 사전 준비 (1회)

```bash
# gcloud CLI 로그인 + 프로젝트 지정
gcloud auth login
gcloud config set project <PROJECT_ID>
gcloud services enable run.googleapis.com secretmanager.googleapis.com artifactregistry.googleapis.com

# 공유 토큰 생성 (이 값을 백엔드 시크릿 + iOS 앱 양쪽에 넣는다)
openssl rand -hex 24
```

## 시크릿 등록 (Secret Manager)

키는 이미지·코드·깃에 절대 넣지 않고 런타임에 주입한다. `backend/.env`의 각 값을 시크릿으로 올린다:

```bash
for KEY in KIS_APP_KEY KIS_APP_SECRET NAVER_CLIENT_ID NAVER_CLIENT_SECRET \
           ANTHROPIC_API_KEY DART_API_KEY ECOS_API_KEY EDGE_API_TOKEN; do
  printf '%s' "$(grep "^$KEY=" backend/.env | cut -d= -f2-)" \
    | gcloud secrets create "$KEY" --data-file=- 2>/dev/null \
    || printf '%s' "$(grep "^$KEY=" backend/.env | cut -d= -f2-)" \
       | gcloud secrets versions add "$KEY" --data-file=-
done
```

> `EDGE_API_TOKEN`은 위 `openssl rand`로 만든 값을 `backend/.env`에 먼저 넣어두고 함께 올린다.

## 배포

`backend/deploy.sh` 참고. 핵심 명령:

```bash
gcloud run deploy edge-backend \
  --source backend \
  --region asia-northeast3 \
  --max-instances 1 \
  --allow-unauthenticated \  # 앱이 토큰만 들고 호출하므로 공개 접근 허용(인증은 우리 X-Edge-Token 층에서)
  --set-secrets "KIS_APP_KEY=KIS_APP_KEY:latest,KIS_APP_SECRET=KIS_APP_SECRET:latest,\
NAVER_CLIENT_ID=NAVER_CLIENT_ID:latest,NAVER_CLIENT_SECRET=NAVER_CLIENT_SECRET:latest,\
ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,DART_API_KEY=DART_API_KEY:latest,\
ECOS_API_KEY=ECOS_API_KEY:latest,EDGE_API_TOKEN=EDGE_API_TOKEN:latest"
```

- `--source backend`: 루트 Dockerfile 대신 `backend/Dockerfile`로 Cloud Build가 이미지를 만든다.
- `KIS_BASE_URL`·`CLAUDE_MODEL`은 시크릿이 아니라 평문 기본값이라 생략 가능(코드 기본값 사용). 바꾸려면 `--set-env-vars`로.

## 배포 후 검증 (1.0c-b)

```bash
URL=$(gcloud run services describe edge-backend --region asia-northeast3 --format='value(status.url)')

curl -s "$URL/health"                         # → OK (토큰 없이 통과)
curl -s -o /dev/null -w '%{http_code}' "$URL/quote/005930"   # → 401 (토큰 없음)
curl -s -H "X-Edge-Token: <TOKEN>" "$URL/quote/005930"       # → 정상 시세 JSON
```

그 다음 iOS 앱의 `EDGE_BASE_URL`·`EDGE_API_TOKEN`(Secrets.xcconfig)에 위 URL·토큰을 넣고 빌드 → 실기기에서 관통 확인.

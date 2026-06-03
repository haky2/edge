#!/usr/bin/env bash
# backend/.env 를 읽어서 Ktor 서버 실행. 키는 .env 에만 두고 커밋하지 않는다.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
exec ./gradlew run -q

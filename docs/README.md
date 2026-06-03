# docs — 프로젝트 문서

이 폴더는 "왜 이렇게 만들었는지"와 "어떻게 돌리는지"를 남기는 곳이다.
**살아있는 계획(Phase 체크리스트·기능 목록)의 단일 출처는 루트 `CLAUDE.md`** 이고,
여기 docs는 그걸 보조하는 참고 문서다(중복은 피하고 서로 링크).

## 문서 지도

| 문서 | 무엇 | 언제 보나 |
|---|---|---|
| [decisions.md](decisions.md) | 주요 의사결정과 그 이유(ADR-lite) | "왜 Cloud Run? 왜 키를 백엔드에?" 다시 헷갈릴 때 |
| [devlog.md](devlog.md) | 세션별 작업 로그(한 일·막힌 점·다음) | 오랜만에 다시 시작할 때 맥락 복구 |
| [backend/api.md](backend/api.md) | 백엔드 API 레퍼런스(엔드포인트·예시) | 앱에서 백엔드 붙일 때 |
| [backend/development.md](backend/development.md) | 백엔드 실행/빌드/구조/배포 가이드 | 서버를 돌리거나 고칠 때 |
| [backend/kis-api-notes.md](backend/kis-api-notes.md) | 한투(KIS) API 함정 모음 | 한투 연동에서 막힐 때 |

## 문서 운영 원칙

- **계획은 CLAUDE.md, 이유는 decisions.md, 사용법은 backend/, 기록은 devlog.md.** 역할을 안 섞는다.
- **git이 곧 changelog다.** 별도 CHANGELOG는 버전(TestFlight/v1)을 낼 때 도입한다.
- 문서는 짧게. 코드가 답인 건 코드에 주석으로 두고, 여기엔 코드만 봐선 모르는 "맥락"을 남긴다.
- 사실이 바뀌면 문서도 같은 커밋에서 고친다(드리프트 방지).

# Fable 트랙 4종 계획 (2026-07-10 승인)

LOW 백로그(`low-backlog-spec.md`, Sonnet/Opus 몫)와 별개로 Fable 급 작업 4개를 순서대로 진행한다.
**순서: A 감사 4탄 → B 매매 복기 → C 종목 딥리서치 → D SENSITIVITY 실증.**
각 트랙 착수 전 슬라이스+모델 재확인(step-recommendation 워크플로). 이 문서가 4트랙의 정본.

| 트랙 | 성격 | 슬라이스 | 모델 |
|---|---|---|---|
| A 감사 4탄 | 리스크 제거 | A(단일) | Fable |
| B 매매 복기 | 신규 기능 | B1 백엔드 → B2 클라 | B1 Fable, B2 Sonnet |
| C 딥리서치 | 신규 기능 | C1 백엔드 → C2 클라 | C1 Fable, C2 Sonnet |
| D SENSITIVITY 실증 | 정확도 검증 | D1 실측 → D2 교정 | Fable |

---

## A. 감사 4탄 — 투자 논지 신규분 (Fable, 단일 슬라이스)

**대상**: O5 감사(2eeb811, 7/9) 이후 미감사 커밋 — 논지 백엔드 63c6829(Fable), 논지 클라 6e01e07(Sonnet), 캡션 수정 69ecd6d, 재릴리스 cbdce48·7804702. 슬라이스 2가 Sonnet 작업이라 전례(G1 회귀·C3 빌드) 상 정독 필수.

**점검 체크리스트** (위험도순):
1. **공유 캐시 오염 = 개인정보**: 논지 포함 분석 결과가 논지 해시 키에만 저장되는지(다른 사용자의 무논지 키로 서빙될 경로 0인지). analyze·ask·portfolio-review 세 경로 + force/refresh 경로 전부. FileCache 파일명에 해시 문자 안전성.
2. **Slack 아카이브 제외**: 논지 포함 분석이 #ai코멘트 아카이브에서 실제 제외되는 게이트 위치·조건(포지션 제외와 동일 로직인지).
3. **5.sqm 마이그레이션**: 구버전 DB(v5)→v6 thesis 컬럼 추가 시 기존 행·G1 holding 데이터 보존. 프레시 설치(.sq 최신 스키마) 경로. 시뮬 구DB 실마이그레이션 관통(3탄 방식).
4. **GET→POST 전환 회귀**: 구버전 앱(1.1)의 GET /portfolio-review 호환 유지. POST 바디 검증·논지 개수/크기 상한.
5. **클라 배선 전수**: thesis 전달이 모든 진입 경로에 있는지(초기로드·refresh·ask·포폴 — 3탄 교훈 "재배선은 모든 진입 경로 추적"). 다계좌 mergedByCode와 thesisMap 조합 정합(키 충돌·유실). hydrate() copy 전파.
6. 200자 제한 클라·서버 이중 방어, 캡션 수정(69ecd6d) 회귀, 재릴리스 커밋 순수성(버전만).

**완료 기준**: 발견 전부 수정(HIGH/MED)+테스트, LOW는 low-backlog-spec.md에 추가. iOS+Android 빌드.

---

## B. 매매 복기(트레이드 포스트모템) — 신규 기능

**무엇**: 매도로 완결된 트레이드(매수 reason·논지·평단·기간 → 매도 reason·가격)를 AI가 복기 — "그 판단은 맞았나"를 과정/결과 2축으로. 매수 전 premortem과 대칭, 차별화 축 #3(행동 학습)의 정성 절반.

**설계 핵심** (B1에서 확정, 방향은 다음):
- **전부 계산 먼저**: 백엔드가 일봉 이력(DailyHistoryService 재사용)으로 구간 수익률·구간 중 최고/최저(놓친 폭)·매도 후 5/20일 추이(일렀나/늦었나) 산출 — Claude는 해석만.
- **프롬프트가 Fable 몫**: ① 결과편향 가드 — 수익 났어도 과정(reason)이 나빴으면 지적, 손실이어도 과정이 옳았으면 인정 ② hindsight bias — "당시 알 수 있었던 정보" 기준, 사후 정보로 단죄 금지 ③ 아부 금지(논지 C12 원칙 재사용) ④ reason 텍스트 인용 금지 ⑤ 산출물은 "다음 매매 지침"이 아니라 반복 패턴 1~2개.
- **API**: `POST /trade-review` (reason·논지 한글 → JSON 바디, 포폴 POST 전례). 트레이드 해시 캐시. ModelRouter 신규 트리거 TRADE_REVIEW — 해석 코멘트라 기본 Opus([[edge-model-policy]]).
- **데이터**: action_log는 로컬 DB — 클라가 트레이드 쌍(매수~매도)을 조합해 전달(서버 무상태, 스냅샷 전례). action_log 스키마에 매도가·수량이 없으면 B1에서 확장 여부 판단.
- **UI(B2)**: 매도 기록 시 "복기 만들기" 제안 + 내 기록 카드에 복기 표시. 내 패턴 탭 누적 복기 리스트(선택).

---

## C. 종목 딥리서치 모드 — 신규 기능

**무엇**: 요청 시(on-demand) 웹검색 결합 심층 리포트 — 기존 facts(시세·수급·재무·밴드)에 웹검색(경쟁 동향·수주 파이프라인·해외 peer)을 얹어 한 종목을 깊게. 이벤트 캘린더의 `completeWithWebSearch`(MAX_SEARCH_TURNS=5) 재사용.

**설계 핵심** (C1에서 확정, 방향):
- **출처 2계층 분리가 프롬프트 핵심**: 우리 facts(검증된 데이터) vs 웹검색(주장·날짜 명시 의무·출처 URL 병기). 상충 시 병기하고 단정 금지. 요약 가격류 환각 가드(NumberGuard) 적용 범위 재설계 — 웹검색 수치는 출처 표기 시 허용해야 하므로 기존 가드와 충돌 지점 정리 필요.
- **비용 캡(검색 과금)**: 일일 상한 env `DEEP_RESEARCH_DAILY_LIMIT`(기본 5 제안, ASK_DAILY_LIMIT 전례), (code, 날짜) 공유 캐시, force 불허(당일 1회면 충분). 상한 도달 429.
- **API**: `GET /deep-research/{code}`. 생성 수십 초 — 클라 로딩 UX(B2에서 premortem 생성 패턴 재사용). ModelRouter 트리거 DEEP_RESEARCH 기본 Opus.
- **범위 제외**: 해외 종목(facts 빈약), 자동 생성(온디맨드만).

---

## D. SENSITIVITY 실증 검증 — 정확도

**무엇**: `MacroImpactService.SENSITIVITY`(섹터×지표 민감도 부호 하드코딩)를 과거 데이터로 실측 검증·교정. 브리핑 "내 종목 영향"의 근본 정확도.

**설계 핵심** (D1에서 확정, 방향):
- **방법**: 지표 일별 등락 이력(Yahoo chart 이력 — 지수·환율·구리는 가능, ECOS 금리 이력) × 섹터 대표 바스켓(SECTOR_PEERS) 익일 수익률 — 부호 일치율·상관·표본수. 동일일 vs 익일(lag) 두 축 모두(민감도 테이블의 의미가 "다음 날 영향"이므로 익일이 본선).
- **형태**: 운영 기능 아님 — 1회성 검증 리포트(테스트 코드 또는 관리 라우트, BacktestService lookahead 차단 패턴 준수). 결과로 테이블 부호를 교정하고 근거를 decisions.md에 기록.
- **판정 기준 설계가 Fable 몫**: n 최소치, 상관 임계, "유의하지 않음 → 테이블에서 제거"까지 갈지(신호 감소 트레이드오프), 노이즈 심한 지표(공포탐욕 등) 처리.
- **D2**: 교정된 테이블 반영 + 기존 MacroImpact 코멘트 톤 변화 실호출 확인.

---

## 진행 기록
- [x] A 감사 4탄 — HIGH 0·MED 4 수정·LOW 4 백로그 이관 (2026-07-10, devlog 참고)
- [x] B1 백엔드 (2026-07-10) — POST /trade-review, TradeReviewService, TRADE_REVIEW 트리거(기본 Opus), 유닛 8+실호출 검증 / [ ] B2 클라(Sonnet) — 매도 기록 시 복기 제안 + 복기 카드, iOS+Android 동시
- [ ] C1 / [ ] C2
- [ ] D1 / [ ] D2

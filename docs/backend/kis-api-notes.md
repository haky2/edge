# 한투(KIS) Open API 함정 모음

연동하며 실제로 부딪힌 것들. 같은 데서 두 번 막히지 않으려고 남긴다. (새로 발견하면 계속 추가)

## 인증

- **토큰 발급(`POST /oauth2/tokenP`) 바디에 `grant_type` 필수.** 빠지면 `EGW00115 권한부여 타입(grant_type)은 필수입니다`.
  - 우리 함정: kotlinx.serialization이 **기본값을 가진 필드를 직렬화에서 누락**한다. `grant_type="client_credentials"`가 기본값이라 통째로 빠졌었다.
  - 해결: 클라이언트 `Json { encodeDefaults = true }`.
- **토큰 발급은 "1일 1회 발급 원칙"이고, 잦은 발급 시 이용이 제한될 수 있다.** 발급 자체도 초당 1건 제한.
  - 우리 함정: 토큰을 메모리에만 캐시하면 **백엔드 재시작마다 재발급**된다(개발 중 잦은 재시작 → 한투 경고/제한 위험).
  - 해결: 24시간 토큰을 **파일(`.kis-token.json`, gitignore)에도 저장**해 재시작 시 재사용. (`KisClient` 메모리+파일 2단 캐시, 만료 60초 전 폐기)
- 모든 시세 호출에 **3종 인증 헤더**가 같이 필요: `authorization: Bearer <token>`, `appkey`, `appsecret`.

## 유량(Rate Limit) — 중요

한투 공지 기준 정확한 수치:

- **신규 고객: 신청 후 3일간 "초당 3건"으로 제한** → 이후 기본 유량으로 자동 상향. (모의투자 제외)
  - 우리가 9개 관심종목을 병렬 조회했을 때 일부만 성공한 원인이 **이거**였다(코드/토큰 문제 아님).
- 기본 유량(2026.04.20): **REST 실전 1초당 18건**, 모의 1초당 1건. **계좌(앱키) 단위** 제한.
- **토큰 발급(`/oauth2/tokenP`)은 1초당 1건.**
- WebSocket: **1세션**, 실시간 데이터 합산 **41건**까지 등록(전 상품 합산).
- 한투 권장: **거부된 요청은 즉시 재호출**, 동시 호출은 **100~150ms 텀**을 둘 것.

우리 대응(`KisClient`):
- `Semaphore`로 동시 호출 수 제한(`KIS_MAX_CONCURRENCY`, 기본 3 — 신규 3일 제한에 맞춤). 3일 후 유량 풀리면 올려도 됨.
- `rt_cd != "0"` 거부 시 **점증 백오프 재시도**(한투 권장과 동일).

## 요청 규약

- 어떤 API인지는 **`tr_id` 헤더**로 정한다. 현재가 시세 = `FHKST01010100`.
- `custtype: P`(개인).
- 현재가 쿼리: `FID_COND_MRKT_DIV_CODE=J`(주식), `FID_INPUT_ISCD=<6자리코드>`.

## 응답 규약

- **HTTP 200이어도 본문 `rt_cd`로 성패를 판단**한다. `rt_cd == "0"` 이 성공, 아니면 `msg1`에 사유. (HTTP 상태만 보면 안 됨)
- **모든 값이 문자열로 온다.** 숫자도 `"1813000"` 식 → 파싱 필요(빈 문자열 방어).
- **`prdy_vrss`(전일대비)·`prdy_ctrt`(등락률)는 이미 부호 포함.** 예: `"-192000"`, `"-9.58"`.
  - 우리 함정: 별도 `prdy_vrss_sign`(5=하락)으로 부호를 **또** 곱해 음수×음수=양수 버그. → 그냥 값 그대로 쓴다.
- **현재가(inquire-price) 응답엔 종목명(`hts_kor_isnm`)이 없다.** 있는 건 업종명(`bstp_kor_isnm`)뿐.
  - 종목명은 종목 마스터(검색)에서 얻는다.
- output엔 PER/PBR/EPS/BPS/250일 고저 등 **수십 개 필드**가 더 온다(필요해지면 매핑 추가). 모르는 키는 `ignoreUnknownKeys`로 무시.

## 종목 마스터 파일(.mst)

이름→코드 검색용. 한투 공개 다운로드(인증 불필요):
- KOSPI: `https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip`
- KOSDAQ: `https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip`

포맷 주의:
- zip 안에 .mst 1개. **인코딩 cp949(MS949)** — UTF-8로 읽으면 한글 깨짐.
- **고정폭** 포맷. 줄 끝 고정 메타 길이가 **KOSPI=228 / KOSDAQ=222**로 다르다. 그만큼 잘라내면 앞부분만 남는다.
- 앞부분 오프셋: `[0:9]` 단축코드(패딩 포함), `[9:21]` 표준코드(ISIN), `[21:]` 한글명.
- 코드에 문자가 섞인 항목(ETF/ETN 등)이 있어, **6자리 숫자만** 채택해 일반 종목만 거른다.

## 실전 vs 모의
- 베이스 URL만 다르다: 실전 `:9443`, 모의 `:29443`.
- 키 발급 후 **실전투자 API 사용 신청**이 별도로 필요할 수 있다. 권한 에러가 뜨면 이걸 의심.

## 참고 자료
- **한투 공식 샘플 repo**: https://github.com/koreainvestment/open-trading-api
  - `examples_user/domestic_stock/domestic_stock_functions.py` — 국내주식 API 함수 전부(현재가·일별·**투자자별 매매동향=수급** 등) + tr_id·FID 파라미터.
  - `*_ws.py` — 웹소켓(실시간). Python 위주(+TS/C#).
  - **새 한투 엔드포인트 붙일 땐 이 repo의 해당 함수에서 tr_id·파라미터를 그대로 가져온다(추측 금지).**
- 한투 API 포털: https://apiportal.koreainvestment.com/

## 아직 안 정한 것 (TODO)
- 수급(투자자별 매매동향) 엔드포인트/tr_id 확정 — 위 repo `domestic_stock_functions.py` 참고, 장후 확정값 기준.
- ~~다종목 시세를 한 번에~~ → `/quotes` 구현 완료(병렬+동시성제한+재시도).

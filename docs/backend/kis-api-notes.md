# 한투(KIS) Open API 함정 모음

연동하며 실제로 부딪힌 것들. 같은 데서 두 번 막히지 않으려고 남긴다. (새로 발견하면 계속 추가)

## 인증

- **토큰 발급(`POST /oauth2/tokenP`) 바디에 `grant_type` 필수.** 빠지면 `EGW00115 권한부여 타입(grant_type)은 필수입니다`.
  - 우리 함정: kotlinx.serialization이 **기본값을 가진 필드를 직렬화에서 누락**한다. `grant_type="client_credentials"`가 기본값이라 통째로 빠졌었다.
  - 해결: 클라이언트 `Json { encodeDefaults = true }`.
- **토큰 발급은 분당 호출 제한이 있다.** 매 요청마다 새로 받으면 곧 막힌다 → 24시간 토큰을 **반드시 캐시**해 재사용(만료 60초 전 폐기).
- 모든 시세 호출에 **3종 인증 헤더**가 같이 필요: `authorization: Bearer <token>`, `appkey`, `appsecret`.

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

## 아직 안 정한 것 (TODO)
- 수급(투자자별 매매동향) 엔드포인트/tr_id 확정 — 장후 확정값 기준.
- 다종목 시세를 한 번에 받는 방법(개별 반복 호출 vs 별도 API).

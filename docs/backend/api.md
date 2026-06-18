# 백엔드 API 레퍼런스

베이스 URL: 로컬 `http://localhost:8080` (배포 후 Cloud Run URL로 교체).
모든 응답은 JSON. 에러는 항상 `{"error": "..."}` 형태.

> 에러 상태코드 규칙: **400** 입력 형식 오류(호출자 잘못) · **502** 한투 등 상류 호출 실패 · **500** 서버 버그.

---

## GET /health
헬스체크. 배포/모니터링용.

```
$ curl localhost:8080/health
OK
```

---

## GET /quote/{code}
6자리 종목코드의 현재 시세. 종목명은 포함하지 않는다(→ `/search`로 확보).

**파라미터:** `code` — 6자리 숫자 종목코드.

```
$ curl localhost:8080/quote/009150
{
  "code": "009150",
  "price": 1813000,        // 현재가
  "change": -192000,       // 전일 대비(부호 포함)
  "changeRate": -9.58,     // 등락률 %
  "volume": 1954960,       // 누적 거래량
  "open": 1839000,         // 시가
  "high": 1886000,         // 당일 고가
  "low": 1659000,          // 당일 저가
  "high52w": 2192000,      // 52주 최고
  "low52w": 120200         // 52주 최저
}
```

**에러 예시**
```
$ curl localhost:8080/quote/abc      # 400
{"error":"종목코드는 6자리 숫자여야 합니다: 'abc'"}

# 키 미설정/한투 권한 문제 등 → 502
{"error":"KIS_APP_KEY / KIS_APP_SECRET 가 설정되지 않았습니다 (.env 확인)"}
```

---

## GET /search?q=
종목 검색. 입력이 **전부 숫자면 코드 prefix**, 아니면 **이름 부분일치**(상위 20건, 짧은 이름 우선).

**파라미터:** `q` — 검색어. 비면 빈 배열 반환.

```
$ curl "localhost:8080/search?q=삼성전기"
[
  {"code":"009150","name":"삼성전기","market":"KOSPI"},
  {"code":"009155","name":"삼성전기우","market":"KOSPI"}
]

$ curl "localhost:8080/search?q=009150"
[ {"code":"009150","name":"삼성전기","market":"KOSPI"} ]
```

---

## 그 외 엔드포인트
위 예시는 일부이며, 다종목 시세·수급·공시·뉴스·분석·매크로·이벤트·밸류에이션·백테스트·Slack 등 전체 엔드포인트는 모두 구현돼 운영 중이다. 정본은 코드의 `backend/src/main/kotlin/com/haky/edge/routes/` 패키지(라우트 파일명 = 기능) 참고.

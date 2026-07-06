# PIKI-Extractor API 계약

이 문서가 계약의 single source 다. 구현(springdoc `/v3/api-docs`)과 어긋나면 이 문서를 기준으로 구현을 고친다.

소비자는 PIKI-Server 의 outbox 워커 하나뿐이다. 공개 API 가 아니며, 보안그룹으로 내부망에서만 접근한다(별도 인증 없음).

## 0. 설계 불변식

- Extractor 는 **무상태**다. DB 없음, 호출 간 상태 없음. 같은 요청이 중복 도착해도 상태 오염이 없다(중복의 대가는 LLM 비용 한 번뿐). Extractor 에 상태를 넣고 싶어지면 설계 경고 신호다.
- 재시도·내구성·상태 전이는 전부 호출자(PIKI-Server 의 item_snapshots outbox)의 책임이다. Extractor 는 "단건 시도 1회"에만 답한다.
- 에스컬레이션(plain fetch → headless 브라우저)은 Extractor 내부 관심사다. 계약에 드러나지 않는다 — 호출자는 어떤 fetch 전략이 쓰였는지 모른다.

## 1. 응답 3갈래 (전이 규약)

| Extractor 응답 | 의미 | PIKI-Server 전이 |
|---|---|---|
| 2xx + 추출 결과 | 성공 | `markReady` |
| 422 + `{code}` | 확정 실패 (재시도 무의미) | 즉시 `markFailed` |
| 그 외 전부 (5xx·타임아웃·연결 실패·미지의 상태) | 일시 실패 | PROCESSING 유지 → recover 재시도 (attempt 상한 2) |

- **전이 판정은 HTTP status 만 사용한다.** 422 body 의 `code` 는 관측·디버깅용이며, 호출자가 모르는 code 여도 422 면 확정 실패로 처리한다(tolerant reader).
- **fail-safe 원칙**: 분류할 수 없는 실패는 전부 "일시"로 떨어진다. 확정 실패 신호는 422 하나뿐이다. 호출자의 attempt 상한이 재시도 비용을 바운드한다.

## 2. 엔드포인트

### POST `/internal/extractions/link` — URL 상품 추출 (이관 1차)

요청:

```json
{ "url": "https://www.musinsa.com/products/12345" }
```

- `url` (필수): https 스킴의 상품 페이지 URL. 형식·스킴·미지원 플랫폼의 동기 검증은 호출자(PIKI-Server 등록 경계)가 이미 끝냈다는 전제이나, Extractor 도 자기 경계에서 방어 검증한다(다층 방어).
- 헤더 `X-Correlation-Id` (선택): 호출자의 item_snapshot id. 로그·trace 상관용이며 동작에 영향 없다.

성공 200:

```json
{ "name": "나이키 에어포스", "imageUrl": "https://...", "currentPrice": 99000, "currency": "KRW" }
```

- **`name`(non-blank)·`imageUrl`·`currentPrice` 의 non-null 을 Extractor 가 보장한다** — PIKI-Server 의 READY 불변식(`requireReadyInvariant`: name·price·imageUrl·extractedAt, extractedAt 은 호출자가 전이 시점에 채움)과 동일 조건이다. 보장할 수 없으면 성공이 아니라 422(`UNTRUSTWORTHY_VALUE`)다. `currency` 는 READY 필수가 아니라 **nullable** 이다. 호출자의 엔티티 불변식은 최후 보루로 유지된다.

확정 실패 422:

```json
{ "code": "NOT_PRODUCT_PAGE" }
```

| code | PIKI-Server 기준 동등물 |
|---|---|
| `NOT_PRODUCT_PAGE` | `ProductSnapshotException.notProductPage` — 상품 페이지가 아님 |
| `UNTRUSTWORTHY_VALUE` | `ProductSnapshotException.untrustworthyValue` — 추출값이 범위·상식 위반 |
| `FETCH_CLIENT_ERROR` | `PageFetchException.clientError` — 대상 4xx (403 차단·404·429 등) |
| `BLOCKED_HOST` | `PageFetchException.blockedHost` — SSRF 차단 (headless 에스컬레이션 절대 금지 대상) |
| `TOO_MANY_REDIRECTS` | `PageFetchException.tooManyRedirects` |
| `MALFORMED_REDIRECT` | `PageFetchException.malformedRedirect` |
| `PERMANENT_UPSTREAM` | `PageFetchException.permanentUpstreamError` — 대상 500/501 (봇 차단 추정) |
| `LLM_INVALID_RESPONSE` | `GeminiApiException` clientError/parseError/noTextPart — 재시도 무의미한 LLM 실패 |
| `INVALID_URL` | url 형식·스킴 위반. 정상 흐름에선 호출자가 동기 검증해 도달하지 않는다(방어) |

일시 실패 (Extractor 는 502 를 쓴다, 호출자는 status 구분 없이 "2xx/422 외 전부"로 처리):

- 대상 몰 502/503/504·연결 실패·빈 body (`UPSTREAM_ERROR`)
- Gemini 5xx/429/408/transport 오류 (`LLM_UPSTREAM`)
- body 에 code 를 실을 수 있으나 호출자는 읽지 않는다(관측용).

### POST `/internal/extractions/image` — S3 이미지 OCR 추출 + 크롭 (이관 6단계, 계약만 선확정)

요청:

```json
{ "bucket": "dev-piki-images-250758375457", "key": "items/raw/0f3a....png" }
```

- **`bucket` 을 요청이 준다** — Extractor 는 dev/staging/prod 세 환경 트래픽을 받고 각 환경의 이미지 버킷이 다르다(dev-piki-images-* · staging-piki-images-* · piki-images-*). 버킷을 고정 config 로 두지 않고 요청별로 받아 버킷 무관하게 동작한다. IAM 은 `*piki-images-250758375457/items/*` 와일드카드로 세 버킷을 덮는다.
- `key`: raw 원본 object key(등록 시 본 서버가 `items/raw/{uuid}.{ext}` 로 durable 적재한 것).

성공 200 (link 경로와 **동일한 필드 모양**):

```json
{ "name": "...", "imageUrl": "https://dev-piki-images-250758375457.s3.ap-northeast-2.amazonaws.com/items/0f3a....png", "currentPrice": 12000, "currency": "KRW" }
```

- Extractor 가 `download(bucket,key) → OCR 추출 → bbox 크롭(불가 시 원본) → upload(bucket, items/{uuid}.png)` 를 다 하고, 업로드한 결과 이미지의 public URL 을 `imageUrl` 로 돌려준다. imageUrl 은 항상 non-null(크롭 실패해도 원본을 올린다 — 본 서버 워커의 `bbox?.crop ?: 원본` 동작과 동일).
- non-null 규약은 link 와 같다: name(non-blank)·imageUrl·currentPrice 를 Extractor 가 보장, 못 채우면 422(`UNTRUSTWORTHY_VALUE`).

422 code 추가분: `IMAGE_UNSUPPORTED`(빈 이미지·미지원 MIME). 이미지에서 상품 식별 실패는 link 와 같은 `UNTRUSTWORTHY_VALUE` 를 재사용한다.
일시 실패 추가분: `STORAGE_ERROR` (S3 read/write 실패).

## 3. 타임아웃 예산

| 층 | 값 | 근거 |
|---|---|---|
| PIKI-Server stale 판정 | 60s | `ItemParsingScheduler.STALE_TIMEOUT` (기존 값) |
| PIKI-Server → Extractor HTTP read | 55s (connect 2s) | stale 미만 — recover 의 유령 중복 발주 방지. link·image 공용 |
| Extractor 내부 합계 (link) | 약 50s | 아래 합 + 여유 |
| 대상 몰 fetch (link) | connect 5s / read 15s | 현행 유지 |
| Gemini | read 30s | 현행 유지 (link LLM fallback·image OCR 동일) |
| Extractor 내부 합계 (image) | 약 40s | S3 download + Gemini OCR 30s + crop + 결과 upload. S3 는 동일 리전이라 수 초 |

**안쪽 예산은 항상 바깥보다 작아야 한다.** link 와 image 가 호출자 read 55s 를 공유하므로, 어느 경로든 Extractor 내부 값을 늘릴 땐 이 표를 갱신하고 PIKI-Server 쪽 read 타임아웃과 함께 재검증한다.

## 4. 진화 규칙

- **additive-only**: 응답 필드 추가·422 code 추가는 자유. 필드 제거·의미 변경·타입 변경은 금지 — 필요하면 새 경로로 분리한다.
- **배포 순서: Extractor 먼저, 소비자(PIKI-Server) 나중.** 본 서버 staging 이 extractor-prod 를 기본으로 바라보는 구성이 이 순서 위반을 릴리스 전에 잡는다.
- 호출자는 tolerant reader — 모르는 응답 필드·code 를 무시한다.

## 5. 관측

- W3C `traceparent` 헤더를 수용해 PIKI-Server 의 `item.parse` span 아래로 연결된다(micrometer tracing 기본 동작).
- 메트릭 `product.extract{via,reason}`·`product.extract.escalation{outcome,category}` 은 PIKI-Server 에서 이 서비스로 이동한다. `application=piki-extractor` 라벨로 본 서버 시계열과 구분된다.

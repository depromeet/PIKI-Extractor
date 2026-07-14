# extractor

PIKI 의 상품 추출 서비스. 상품 URL(또는 S3 이미지)을 받아 fetch → 구조화 파싱(JSON-LD/OpenGraph) → LLM(Gemini) fallback → 정규화를 거쳐 추출 결과를 반환한다. core 의 파싱 파이프라인을 분리한 것으로, 소비자는 core 의 outbox 워커 하나뿐인 내부 서비스다.

- **API 계약**: [docs/api-contract.md](docs/api-contract.md) — 응답 3갈래(2xx / 422 / 그 외=재시도), additive-only 진화
- **스택**: Java 25 · Spring Boot 4 · virtual threads · 무상태(DB 없음)
- **관련 repo**: [core](https://github.com/TeamPiKi/core) (호출자), renderer (차단 우회 렌더링, private)

## 실행

```bash
./gradlew test      # Docker 불필요 (DB 없음)
./gradlew bootRun   # 기본 포트 8090
```

## 운영 (prod 박스)

- 박스 자체: `infra/extractor/` (terraform, EC2·SG·IAM).
- 앱 컨테이너 기동: `infra/scripts/run-extractor.sh` — 컨테이너 (재)기동의 SSOT.
- 관측(Alloy) 배선: `infra/scripts/provision-observability.sh` — 공통 Alloy 블록을 이 박스 값으로 호출.
- 둘 다 박스 안에서 SSM(run-command 또는 세션)으로 실행하며, 시크릿은 SSM Parameter Store(`/piki-extractor/*`)에서 읽는다.
- 관측 계약(라벨 opt-in 등)은 TeamPiKi/infra 의 `contracts/observability.md` 가 SSOT.

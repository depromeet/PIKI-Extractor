#!/usr/bin/env bash
#
# extractor 앱 컨테이너 (재)기동 - 이 스크립트가 컨테이너 기동의 신설 SSOT다.
#
# 지금까지 앱 컨테이너(piki-extractor)는 임시(수동 SSM)로 기동돼 run 파라미터가
# repo 어디에도 없었다. 라벨·env 는 이 스크립트 한 곳에만 박는다 - 이 스크립트
# 밖에서 env 를 더하지 않는다.
#
# 실행 위치: extractor 박스 안 (SSM run-command 또는 세션 접속 후 직접 실행).
# 박스엔 aws cli 가 없어 SSM 조회는 dockerized aws cli 로 한다
# (인스턴스 IAM 롤 + IMDSv2 hop limit 2 라 컨테이너 안에서도 자격 획득 가능).
#
# 사용 예:
#   run-extractor.sh --image piki-extractor:latest
#
# 인자:
#   --image   (필수) 기동할 이미지:태그. default 없음
#
# 종료 코드: 성공(헬스체크 통과) 0, SSM 조회/헬스체크 실패 1, 인자 오류 2

set -euo pipefail

IMAGE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMAGE="${2:-}"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

[ -n "$IMAGE" ] || { echo "--image is required" >&2; exit 2; }

# --- 1. GEMINI_API_KEY 를 SSM Parameter Store 에서 조회 ---
GEMINI_API_KEY=$(
  docker run --rm --network host \
    -e AWS_DEFAULT_REGION=ap-northeast-2 \
    amazon/aws-cli ssm get-parameter \
    --name /piki-extractor/gemini-api-key \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text
) || { echo "필수 SSM 파라미터 조회 실패: /piki-extractor/gemini-api-key" >&2; exit 1; }

# --- 2. 기존 컨테이너 제거 후 재기동 (없으면 무시) ---
docker rm -f piki-extractor >/dev/null 2>&1 || true

docker run -d --name piki-extractor --restart unless-stopped \
  -p 8090:8090 \
  --add-host=host.docker.internal:host-gateway \
  --label piki.observe=true \
  --label piki.service=piki-extractor \
  --label piki.metrics.port=8090 \
  --label piki.metrics.path=/actuator/prometheus \
  -e AWS_REGION=ap-northeast-2 \
  -e GEMINI_API_KEY="$GEMINI_API_KEY" \
  -e TRACING_OTLP_ENABLED=true \
  -e OTLP_ENDPOINT=http://host.docker.internal:4318/v1/traces \
  -e LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
  "$IMAGE"

# 라벨 4종(piki.observe/piki.service/piki.metrics.port/piki.metrics.path)은 공통 Alloy 의
# label opt-in 계약(TeamPiKi/infra contracts/observability.md) - 이 라벨이 있어야 박스
# Alloy 가 이 컨테이너를 스크레이프 대상으로 인식한다. piki.metrics.port 는 컨테이너
# 내부 포트가 아니라 "호스트 127.0.0.1 에서 닿는 포트" 다.
#
# -p 8090:8090 전체 바인드: 이 박스는 SG 로 인바운드가 앱 SG 에서만 허용되니(SG 격리)
# 외부 노출이 없다. core 앱은 이 박스의 private IP 로 이 포트를 호출한다.
#
# --add-host=host.docker.internal:host-gateway + OTLP_ENDPOINT=...host.docker.internal:4318:
# 트레이스는 앱이 박스 로컬 Alloy(4318, OTLP HTTP)로 능동적으로 push 하는 유일한 신호다.
# 이 컨테이너는 bridge 네트워크라 host-gateway 를 거쳐야 호스트의 Alloy 에 닿는다.
#
# LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs: 공통 Loki 파이프라인이 ECS JSON 포맷을 전제로
# level 라벨링·trace 상관을 한다(Spring Boot 구조화 로깅, relaxed binding 으로 이 env 하나면
# 활성화된다). 형식이 다르면 그쪽 파이프라인이 파싱하지 못한다.
#
# 이 스크립트를 박스에 첫 적용할 때는 기존 임시 기동 컨테이너를
# `docker inspect piki-extractor` 로 대조해, 누락된 env 가 있으면 여기(이 스크립트)에
# 흡수시킨다 - 이 스크립트 밖에서 별도로 env 를 더하지 않는다.

# --- 3. 헬스 대기 - 공통 healthcheck 블록을 dogfooding ---
HEALTHCHECK_SH="/tmp/piki-run-extractor-healthcheck.sh"
curl -fsSL https://raw.githubusercontent.com/TeamPiKi/infra/main/blocks/healthcheck.sh -o "$HEALTHCHECK_SH" \
  || { echo "healthcheck.sh fetch 실패" >&2; exit 1; }

bash "$HEALTHCHECK_SH" \
  --url http://localhost:8090/actuator/health \
  --interval 5 \
  --attempts 24 \
  --expect-body '"status":"UP"'

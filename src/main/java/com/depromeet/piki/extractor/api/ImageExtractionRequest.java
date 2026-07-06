package com.depromeet.piki.extractor.api;

// POST /internal/extractions/image 요청 (docs/api-contract.md §2).
// bucket 을 요청이 준다 — extractor 는 dev/staging/prod 세 환경 트래픽을 받고 각 환경 이미지 버킷이 다르므로,
// 버킷을 고정 config 로 두지 않고 요청별로 받아 버킷 무관하게 동작한다. key 는 등록 시 본 서버가 적재한 raw object key.
public record ImageExtractionRequest(String bucket, String key) {
}

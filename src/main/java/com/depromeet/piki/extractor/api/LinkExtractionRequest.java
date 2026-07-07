package com.depromeet.piki.extractor.api;

// POST /internal/extractions/link 요청. url 의 형식 검증은 Bean Validation 이 아니라 ProductLink.parse 가 맡는다 —
// blank·형식·스킴 위반이 전부 계약 코드 INVALID_URL(422) 하나로 떨어져야 하기 때문(docs/api-contract.md).
//
// headlessFirst 는 additive 선택 필드 — 타입이 primitive boolean 이면 Jackson 3(FAIL_ON_NULL_FOR_PRIMITIVES 기본
// on)가 필드를 안 보내는 구버전 호출자의 요청을 400 으로 깨뜨린다(통합 테스트로 실측). Boolean 로 받아 생성자에서
// null→false 로 정규화한다. 의미는 ProductLinkExtractor.extract 의 headlessFirst 와 동일.
public record LinkExtractionRequest(String url, Boolean headlessFirst) {

    public LinkExtractionRequest {
        headlessFirst = Boolean.TRUE.equals(headlessFirst);
    }
}

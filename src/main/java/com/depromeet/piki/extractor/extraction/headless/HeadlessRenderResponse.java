package com.depromeet.piki.extractor.extraction.headless;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// POST /render 응답 wire 모델. 우리가 쓰는 필드만 선언한다 — 렌더 서비스가 주는 title/price 등 나머지는 무시
// (tolerant reader). verdict 는 렌더 서비스의 판정: PASS | PARTIAL | BLOCK | EMPTY | ERROR.
//
// 전 필드 nullable(박싱 타입) — 렌더 서비스의 예외 격리 경로는 platform·verdict·error 만 싣는다. primitive 를
// 쓰면 Jackson 3(FAIL_ON_NULL_FOR_PRIMITIVES 기본 on)가 필드 부재를 역직렬화 실패로 만들어, verdict 번역에
// 닿기도 전에 일시 실패로 오분류된다.
@JsonIgnoreProperties(ignoreUnknown = true)
record HeadlessRenderResponse(
    String verdict,
    // 페이지 파싱값의 출처(response-body | dom) — 관측용 로그에만 쓴다.
    String source,
    // 프록시 경유 여부 — 관측용.
    Boolean proxied,
    // 대상 페이지의 HTTP status — 관측용.
    Integer status,
    @JsonProperty("final_url") String finalUrl,
    String html,
    String error
) {
}

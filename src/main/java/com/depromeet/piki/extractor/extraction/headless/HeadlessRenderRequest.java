package com.depromeet.piki.extractor.extraction.headless;

import com.fasterxml.jackson.annotation.JsonProperty;

// POST /render 요청 wire 모델. 렌더 서비스(Python/FastAPI)의 snake_case 필드에 맞춘다.
// includeHtml 은 항상 true 로 보낸다 — 파싱(구조화/LLM)은 우리가 렌더된 HTML 로 직접 한다.
// 렌더 서비스가 덤으로 주는 title/price 는 imageUrl 이 없어 READY 불변식을 못 채우므로 쓰지 않는다.
record HeadlessRenderRequest(
    String url,
    @JsonProperty("include_html") boolean includeHtml
) {
}

package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.extraction.ProductLinkExtractor;
import com.depromeet.piki.extractor.image.ImageExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 내부 추출 API (docs/api-contract.md). 소비자는 PIKI-Server outbox 워커 하나뿐이고 보안그룹으로 격리되므로
// 인증·응답 래퍼 없이 계약 그대로 노출한다. 두 경로(link·image)가 같은 응답 모양(ExtractionResponse)을 공유한다.
@RestController
@RequestMapping("/internal/extractions")
public class ExtractionController {

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final ProductLinkExtractor productLinkExtractor;
    private final ImageExtractionService imageExtractionService;

    public ExtractionController(
        ProductLinkExtractor productLinkExtractor,
        ImageExtractionService imageExtractionService
    ) {
        this.productLinkExtractor = productLinkExtractor;
        this.imageExtractionService = imageExtractionService;
    }

    @PostMapping("/link")
    public ExtractionResponse extractLink(
        @RequestBody LinkExtractionRequest request,
        // 호출자의 item_snapshot id. 로그·trace 상관용이며 동작에 영향 없다 (계약 §2).
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        // 형식·스킴 위반은 ProductLink.parse 가 INVALID_URL(422) 로 떨어뜨린다 — 정상 흐름에선 호출자가
        // 등록 경계에서 이미 걸렀으므로 방어 검증이다(다층 방어).
        ProductLink link = ProductLink.parse(request.url());
        log.info(
            "extract request correlationId={} headlessFirst={} url={}",
            correlationId,
            request.headlessFirst(),
            link.safeLogString()
        );
        ProductSnapshot snapshot = productLinkExtractor.extract(link, request.headlessFirst());
        return ExtractionResponse.from(snapshot);
    }

    @PostMapping("/image")
    public ExtractionResponse extractImage(
        @RequestBody ImageExtractionRequest request,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId
    ) {
        // bucket·key 는 호출자가 준다(등록 시 raw 적재한 위치). extractor 가 download→OCR→crop→upload 를 다 하고
        // 업로드 결과 이미지의 public URL 을 imageUrl 로 돌려준다. bucket 은 로그에 남겨도 안전(내부 식별자).
        log.info("image extract request correlationId={} bucket={} key={}", correlationId, request.bucket(), request.key());
        ProductSnapshot snapshot = imageExtractionService.extract(request.bucket(), request.key());
        return ExtractionResponse.from(snapshot);
    }
}

package com.depromeet.piki.extractor.api;

import com.depromeet.piki.extractor.domain.ProductSnapshot;
import com.depromeet.piki.extractor.domain.ProductSnapshotException;

// 성공(200) 응답. link·image 두 엔드포인트가 같은 모양을 공유한다(둘 다 ProductSnapshot 을 내려보낸다).
// 계약(docs/api-contract.md): name(non-blank)·currentPrice·imageUrl 의 non-null 을 이 서비스가 보장한다 —
// 호출자(PIKI-Server)의 READY 불변식(name·price·imageUrl·extractedAt, extractedAt 은 호출자가 전이 시점에 채움)과
// 동일 조건이며, 보장 못 하면 성공이 아니라 422(UNTRUSTWORTHY_VALUE)다. currency 는 READY 필수가 아니라 nullable.
public record ExtractionResponse(
    String name,
    String imageUrl,
    Integer currentPrice,
    String currency
) {

    public static ExtractionResponse from(ProductSnapshot snapshot) {
        if (snapshot.name() == null || snapshot.name().isBlank()
            || snapshot.imageUrl() == null
            || snapshot.currentPrice() == null) {
            throw ProductSnapshotException.untrustworthyValue();
        }
        return new ExtractionResponse(
            snapshot.name(),
            snapshot.imageUrl(),
            snapshot.currentPrice(),
            snapshot.currency()
        );
    }
}

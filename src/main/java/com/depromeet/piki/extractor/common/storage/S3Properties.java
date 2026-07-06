package com.depromeet.piki.extractor.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// PIKI-Server: common/storage/S3Properties.kt 포팅 (download·upload 만 — region 만 남김).
// bucket 은 요청별 파라미터다(extractor 가 dev/staging/prod 세 환경 트래픽을 받고 환경마다 이미지 버킷이 다르다) — config 에서 뺀다.
// publicBaseUrl 도 제거 — 반환 URL 은 S3ImageStorage 가 bucket+region 으로 조합한다(https://{bucket}.s3.{region}.amazonaws.com/{key}).
// presign 만료 필드도 제외. region 은 미지정 시 ap-northeast-2.
@ConfigurationProperties(prefix = "s3")
public record S3Properties(
    @DefaultValue("ap-northeast-2") String region
) {
}

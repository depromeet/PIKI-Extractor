package com.depromeet.piki.extractor.common.storage;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.common.exception.ExtractionException;

// PIKI-Server: common/storage/ImageStorageException.kt 포팅 (download·upload 만).
// 원본은 BaseException + HttpMappable 로 502 BAD_GATEWAY + ErrorCategory.RETRYABLE 였다. 이 서비스의 계약으로 번역하면
// RETRYABLE → permanent=false(일시 실패, 502) — 호출자(PIKI-Server 워커)가 PROCESSING 유지 후 recover 재시도한다.
// message 는 로그·디버깅용 고정 문구이고 응답 body 에는 code 만 나간다(내부 정보 비노출) — 원본 사용자 문구를 그대로 유지한다.
//
// code: 원본엔 ExtractionErrorCode 개념이 없었다(ErrorCategory 로만 갈랐다). 스토리지 실패 전용 code 로 STORAGE_ERROR 를 쓴다.
//   STORAGE_ERROR 는 커널 ExtractionErrorCode 에 아직 없다 — 그 enum 은 커널이라 포팅 작업에서 수정하지 않으므로 여기서 추가하지 않았다.
//   통합 담당이 ExtractionErrorCode 에 STORAGE_ERROR 를 additive 로 더해야 이 파일이 컴파일된다(그 enum 문서: "추가는 자유").
//   docs/api-contract.md 의 code 표에도 STORAGE_ERROR 를 함께 더한다.
public final class ImageStorageException extends ExtractionException {

    private ImageStorageException(String message, ExtractionErrorCode code, Throwable cause) {
        // 원본 RETRYABLE/502 → permanent=false(일시 실패). 스토리지는 우리 밖 의존성이라 정상 요청도 닿을 수 있는 계약 응답이다.
        super(message, code, false, cause);
    }

    // 크롭 결과 업로드 실패. 원본 uploadFailed 문구 유지.
    public static ImageStorageException uploadFailed(Throwable cause) {
        return new ImageStorageException(
            "이미지를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.", ExtractionErrorCode.STORAGE_ERROR, cause);
    }

    // raw 원본 다운로드 실패(객체 없음·권한·네트워크). 원본 downloadFailed 문구 유지 — 워커가 일시 오류로 받아 재시도한다.
    public static ImageStorageException downloadFailed(Throwable cause) {
        return new ImageStorageException(
            "이미지를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.", ExtractionErrorCode.STORAGE_ERROR, cause);
    }
}

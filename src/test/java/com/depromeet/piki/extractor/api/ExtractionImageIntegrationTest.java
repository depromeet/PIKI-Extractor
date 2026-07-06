package com.depromeet.piki.extractor.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.depromeet.piki.extractor.common.storage.ImageStorageException;
import com.depromeet.piki.extractor.common.storage.StoredImage;
import com.depromeet.piki.extractor.image.gemini.GeminiImageResult;
import com.depromeet.piki.extractor.support.IntegrationTestSupport;
import com.depromeet.piki.extractor.support.StubGeminiClient;
import com.depromeet.piki.extractor.support.StubImageStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// POST /internal/extractions/image 의 HTTP 계약(docs/api-contract.md §2)을 실제 파이프라인
// (download→OCR→crop→upload, 외부 경계 S3(ImageStorage)·GeminiClient 만 stub)으로 검증한다.
// 응답 모양은 link 와 같고(name·imageUrl·currentPrice·currency), imageUrl 은 업로드 결과 URL 이다.
class ExtractionImageIntegrationTest extends IntegrationTestSupport {

    private static final String BUCKET = "dev-piki-images-250758375457";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private StubImageStorage stubImageStorage;

    @Autowired
    private StubGeminiClient stubGeminiClient;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String body(String key) {
        return "{\"bucket\": \"" + BUCKET + "\", \"key\": \"" + key + "\"}";
    }

    @Test
    @DisplayName("정상 이미지면 OCR 추출 후 크롭 결과를 업로드하고 200 과 업로드 URL 을 imageUrl 로 반환한다")
    void imageSuccess() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "image/png");
        // bbox 없이 반환 → 크롭 스킵하고 원본을 업로드(imageUrl 은 항상 채워진다).
        stubGeminiClient.build = request -> new GeminiImageResult("나이키 신발", 89000, "신발", "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "snapshot-9")
                .content(body("items/raw/abc.png")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("나이키 신발"))
            .andExpect(jsonPath("$.currentPrice").value(89000))
            .andExpect(jsonPath("$.currency").value("KRW"))
            .andExpect(jsonPath("$.imageUrl").value(StubImageStorage.UPLOADED_URL));
    }

    @Test
    @DisplayName("미지원 이미지 형식이면 422 IMAGE_UNSUPPORTED 를 반환한다")
    void unsupportedFormat() throws Exception {
        stubGeminiClient.reset();
        // key 확장자 txt·content-type 도 미지원 → ProductImage.of 가 확정 실패로 던진다.
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "text/plain");

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/bad.txt")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IMAGE_UNSUPPORTED"));
    }

    @Test
    @DisplayName("S3 download 가 실패하면 502 STORAGE_ERROR 를 반환한다 (호출자가 재시도)")
    void storageDownloadError() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> {
            throw ImageStorageException.downloadFailed(new RuntimeException("S3 timeout"));
        };

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/gone.png")))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("STORAGE_ERROR"));
    }

    @Test
    @DisplayName("추출이 name 을 못 채우면 422 UNTRUSTWORTHY_VALUE 를 반환한다 (link 와 같은 non-null 규약)")
    void incompleteExtraction() throws Exception {
        stubGeminiClient.reset();
        stubImageStorage.onDownload = (bucket, key) -> new StoredImage(new byte[] {1, 2, 3}, "image/png");
        stubGeminiClient.build = request -> new GeminiImageResult(null, 1000, null, "KRW", null);

        mockMvc().perform(post("/internal/extractions/image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("items/raw/noname.png")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("UNTRUSTWORTHY_VALUE"));
    }
}

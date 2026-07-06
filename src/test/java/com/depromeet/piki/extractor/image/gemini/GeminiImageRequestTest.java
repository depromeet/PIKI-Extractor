package com.depromeet.piki.extractor.image.gemini;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GeminiImageRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("forImageAnalysis 요청은 thinkingLevel 을 minimal 로 명시한다")
    void thinkingLevelIsMinimal() {
        // 명시하지 않으면 gemini-3.1-flash-lite 에 dynamic thinking 이 붙어 응답이 ~20s 까지 튄다.
        String json = objectMapper.writeValueAsString(GeminiImageRequest.forImageAnalysis("AAAA", "image/png"));

        assertTrue(
            json.contains("\"thinkingConfig\":{\"thinkingLevel\":\"minimal\"}"),
            "thinkingLevel minimal 이 직렬화되지 않았다: " + json);
    }

    @Test
    @DisplayName("요청에 null 값 필드는 직렬화되지 않는다")
    void nullFieldsAreNotSerialized() {
        // Gemini JSON Schema 파서는 STRING 타입에 붙은 "items":null 같은 잉여 null 을 스키마 위반으로 취급한다.
        String json = objectMapper.writeValueAsString(GeminiImageRequest.forImageAnalysis("AAAA", "image/png"));

        assertFalse(json.contains(":null"), "NON_NULL 정책인데 null 값이 직렬화됐다: " + json);
    }

    @Test
    @DisplayName("nullable 이 명시된 스키마 필드는 직렬화에 포함된다")
    void explicitNullableIsSerialized() {
        // null 값 생략(NON_NULL)과 별개로, 의도적으로 박은 nullable=true 는 보존되어야 한다.
        String json = objectMapper.writeValueAsString(GeminiImageRequest.forImageAnalysis("AAAA", "image/png"));

        assertTrue(json.contains("\"nullable\":true"), "스키마의 nullable=true 가 누락됐다: " + json);
    }

    @Test
    @DisplayName("이미지 part 는 inlineData 의 mimeType·data 를 그대로 싣는다")
    void imagePartCarriesInlineData() {
        String json = objectMapper.writeValueAsString(GeminiImageRequest.forImageAnalysis("BASE64DATA", "image/jpeg"));

        assertTrue(
            json.contains("\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"BASE64DATA\"}"),
            "inlineData 적재가 어긋났다: " + json);
    }
}

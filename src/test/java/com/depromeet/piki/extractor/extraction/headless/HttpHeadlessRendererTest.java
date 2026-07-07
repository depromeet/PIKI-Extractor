package com.depromeet.piki.extractor.extraction.headless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.depromeet.piki.extractor.common.exception.ExtractionErrorCode;
import com.depromeet.piki.extractor.domain.ProductLink;
import com.depromeet.piki.extractor.extraction.HeadlessExtractionProperties;
import com.depromeet.piki.extractor.extraction.PageContent;
import com.depromeet.piki.extractor.extraction.http.PageFetchException;
import com.depromeet.piki.extractor.extraction.http.RequestScopedDnsResolver;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// POST /render 의 wire 계약(요청 필드·verdict 번역·SSRF 가드·final_url 폴백·html 상한)을 네트워크 없이 검증한다.
// verdict → 계약 번역이 이 클래스의 단일 책임이라, 소비자(HeadlessProductLinkExtractor)는 여기서 못 가는
// PageContent 를 절대 받지 않는다. DNS 는 가짜 공인 IP 로 주입해 SSRF 가드를 통과시킨다(HttpPageFetcher 테스트와 같은 방식).
class HttpHeadlessRendererTest {

    private static final String BASE_URL = "http://headless.test:8000";

    private final ProductLink link = ProductLink.parse("https://kream.co.kr/products/6963");

    private final RequestScopedDnsResolver.HostResolver publicIp =
        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};

    private HttpHeadlessRenderer rendererWith(
        HeadlessExtractionProperties properties,
        RequestScopedDnsResolver.HostResolver hostResolver,
        Consumer<MockRestServiceServer> configure
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        configure.accept(server);
        return new HttpHeadlessRenderer(builder.build(), properties, new RequestScopedDnsResolver(hostResolver));
    }

    private HttpHeadlessRenderer rendererWith(Consumer<MockRestServiceServer> configure) {
        return rendererWith(HeadlessExtractionProperties.of(true), publicIp, configure);
    }

    @Test
    @DisplayName("PASS 렌더는 url 과 include_html=true 로 요청하고, html 과 final_url 기준 PageContent 를 돌려준다")
    void passRenderReturnsPageContent() {
        String html = "<html><body>rendered</body></html>";
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.url").value(link.value().toString()))
            // 파싱(구조화/LLM)은 우리가 하므로 렌더된 HTML 을 항상 요구한다.
            .andExpect(jsonPath("$.include_html").value(true))
            .andRespond(withSuccess(
                "{\"verdict\":\"PASS\",\"source\":\"response-body\",\"proxied\":true,\"status\":200,"
                    + "\"final_url\":\"https://kream.co.kr/products/6963?after-redirect\",\"html\":\"" + html.replace("\"", "\\\"") + "\"}",
                MediaType.APPLICATION_JSON
            )));

        PageContent page = renderer.render(link);

        assertEquals(html, page.html());
        // 정체성(원본 link)은 유지하고, baseUri 용 finalUrl 은 렌더가 따라간 최종 URL 을 쓴다.
        assertEquals(link, page.link());
        assertEquals("https://kream.co.kr/products/6963?after-redirect", page.finalUrl().value().toString());
    }

    @Test
    @DisplayName("PARTIAL·EMPTY·미지의 verdict 도 html 이 있으면 진행한다 — 렌더 서비스 파서의 실패가 우리 파이프라인의 실패는 아니다")
    void anyNonBlockVerdictWithHtmlProceeds() {
        for (String verdict : List.of("PARTIAL", "EMPTY", "SOMETHING_NEW")) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(
                    "{\"verdict\":\"" + verdict + "\",\"html\":\"<html>rendered dom</html>\"}",
                    MediaType.APPLICATION_JSON
                )));

            assertEquals("<html>rendered dom</html>", renderer.render(link).html(), "verdict=" + verdict);
        }
    }

    @Test
    @DisplayName("BLOCK 은 일시 실패(HEADLESS_BLOCKED)다 — 렌더 서비스 BLOCK 판정에 429·일시 챌린지가 섞여 fail-safe 로 일시")
    void blockIsTransient() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess("{\"verdict\":\"BLOCK\",\"status\":429}", MediaType.APPLICATION_JSON)));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link));

        assertEquals(ExtractionErrorCode.HEADLESS_BLOCKED, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("html 이 없으면(브라우저 오류 ERROR·빈 렌더) 일시 실패(HEADLESS_UPSTREAM)다")
    void missingHtmlIsTransient() {
        for (String body : List.of(
            "{\"verdict\":\"ERROR\",\"error\":\"TimeoutError: boom\"}",
            "{\"verdict\":\"PASS\"}",
            "{\"verdict\":\"EMPTY\",\"html\":\"\"}"
        )) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

            HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link));

            assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
            assertFalse(ex.permanent());
        }
    }

    @Test
    @DisplayName("내부망으로 resolve 되는 host 는 렌더 서비스 호출 전에 SSRF 로 차단된다 — 직행 경로의 방어선")
    void internalHostIsBlockedBeforeRender() {
        RequestScopedDnsResolver.HostResolver internalIp =
            host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        // 서버에 expect 를 하나도 걸지 않는다 — 가드가 먼저 던지므로 렌더 서비스로 요청이 나가면 안 된다.
        HttpHeadlessRenderer renderer =
            rendererWith(HeadlessExtractionProperties.of(true), internalIp, server -> { });

        PageFetchException ex = assertThrows(PageFetchException.class, () -> renderer.render(link));

        assertEquals(ExtractionErrorCode.BLOCKED_HOST, ex.code());
        assertTrue(ex.permanent());
    }

    @Test
    @DisplayName("final_url 이 없거나 우리 형식이 아니면 원본 link 로 폴백한다 — 렌더 성공을 실패로 오판하지 않는다")
    void invalidFinalUrlFallsBackToLink() {
        for (String finalUrlField : List.of("", ",\"final_url\":\"chrome-error://failed\"")) {
            HttpHeadlessRenderer renderer = rendererWith(server -> server
                .expect(requestTo(BASE_URL + "/render"))
                .andRespond(withSuccess(
                    "{\"verdict\":\"PASS\",\"html\":\"<html>ok</html>\"" + finalUrlField + "}",
                    MediaType.APPLICATION_JSON
                )));

            assertEquals(link, renderer.render(link).finalUrl());
        }
    }

    @Test
    @DisplayName("렌더 서비스의 5xx·비정상 응답은 일시 실패다")
    void renderServiceErrorIsTransient() {
        HttpHeadlessRenderer renderer = rendererWith(server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withServerError()));

        HeadlessRenderException ex = assertThrows(HeadlessRenderException.class, () -> renderer.render(link));

        assertEquals(ExtractionErrorCode.HEADLESS_UPSTREAM, ex.code());
        assertFalse(ex.permanent());
    }

    @Test
    @DisplayName("렌더된 HTML 은 maxHtmlChars 안전 상한으로 절단한다")
    void htmlIsCappedAtMaxChars() {
        HeadlessExtractionProperties small = new HeadlessExtractionProperties(
            true, BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(20), 10
        );
        HttpHeadlessRenderer renderer = rendererWith(small, publicIp, server -> server
            .expect(requestTo(BASE_URL + "/render"))
            .andRespond(withSuccess(
                "{\"verdict\":\"PASS\",\"html\":\"0123456789ABCDEF\"}",
                MediaType.APPLICATION_JSON
            )));

        assertEquals("0123456789", renderer.render(link).html());
    }

    @Test
    @DisplayName("렌더 error 원문의 URL(쿼리스트링 포함)은 마스킹된다 — playwright 예외가 대상 URL 을 통째로 싣는다")
    void errorUrlsAreMasked() {
        String masked = HttpHeadlessRenderer.maskUrls(
            "TimeoutError: Page.goto: navigating to \"https://shop.example.com/p?token=secret123\", waiting until commit"
        );

        assertFalse(masked.contains("token=secret123"));
        assertTrue(masked.contains("<url>"));
        assertEquals(null, HttpHeadlessRenderer.maskUrls(null));
    }
}

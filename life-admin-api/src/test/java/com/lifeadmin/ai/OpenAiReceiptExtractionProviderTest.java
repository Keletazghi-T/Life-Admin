package com.lifeadmin.ai;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiReceiptExtractionProviderTest {
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private RestClient client;
    private OpenAiReceiptExtractionProvider provider;
    private static final String DATA = """
        {"merchant":"  Oak Cafe  ","purchaseDate":"2025-09-20","totalAmount":"6.72","currency":"usd","category":"MEALS","description":"  Coffee and breakfast  "}
        """;
    @BeforeEach void setup() throws Exception {
        logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiReceiptExtractionProvider.class);
        logs = new ch.qos.logback.core.read.ListAppender<>(); logs.start(); logger.addAppender(logs);
        var builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build(); client = builder.build();
        provider = new OpenAiReceiptExtractionProvider(client, mapper, "fixture-not-a-real-key", "fixture-model");
    }
    private String envelope(String data) throws Exception {
        return mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", data))))));
    }
    private void respond(String data) throws Exception { server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(envelope(data), MediaType.APPLICATION_JSON)); }
    @AfterEach void verifyRequests() {
        try {
            server.verify();
            for (var event : logs.list) {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("fixture-not-a-real-key", "Authorization", "Bearer", "Oak Cafe", "AQIDBA==", "private diagnostic", "data:image", "sensitive-receipt", "Coffee and breakfast");
            }
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
    private String diagnostic(String stage) {
        assertThat(logs.list).hasSize(1);
        String message = logs.list.getFirst().getFormattedMessage();
        assertThat(message).contains("receipt_extraction_failure timestamp=", "stage=" + stage, "model=fixture-model");
        return message;
    }
    @Test void actualImageBytesAndStrictSchemaAreSentAndFieldsAreValidated() throws Exception {
        byte[] image = {1, 2, 3, 4};
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer fixture-not-a-real-key"))
            .andExpect(jsonPath("$.model").value("fixture-model")).andExpect(jsonPath("$.store").value(false))
            .andExpect(jsonPath("$.input[1].content[1].type").value("input_image"))
            .andExpect(jsonPath("$.input[1].content[1].image_url").value("data:image/png;base64,AQIDBA=="))
            .andExpect(jsonPath("$.text.format.type").value("json_schema"))
            .andExpect(jsonPath("$.text.format.strict").value(true))
            .andExpect(jsonPath("$.text.format.schema.required").value(org.hamcrest.Matchers.hasItem("description")))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("visible purchased line items")))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("not solely on the merchant name")))
            .andRespond(withSuccess(envelope(DATA), MediaType.APPLICATION_JSON));
        var result = provider.extract(image, "image/png");
        assertThat(result.description()).isEqualTo("Coffee and breakfast");
        assertThat(result.merchant()).isEqualTo("Oak Cafe"); assertThat(result.amount()).isEqualByComparingTo("6.72");
        assertThat(result.purchaseDate()).hasToString("2025-09-20"); assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.extractionStatus()).isEqualTo("EXTRACTED"); assertThat(result.extractionMethod()).isEqualTo("OPENAI_IMAGE");
        assertThat(result.extractionModel()).isEqualTo("fixture-model"); assertThat(result.taxRelevant()).isFalse(); assertThat(logs.list).isEmpty();
    }
    @Test void missingKeyDoesNotCallNetwork() throws Exception {
        var result = new OpenAiReceiptExtractionProvider(client, mapper, "", "fixture-model").extract(new byte[]{1}, "image/jpeg");
        assertThat(result.extractionStatus()).isEqualTo("OCR_REQUIRED"); assertThat(result.merchant()).isNull();
        assertThat(result.extractionError()).contains("not configured");
    }
    @ParameterizedTest @ValueSource(strings={"merchant", "purchaseDate", "totalAmount", "currency", "category", "description"})
    void nullFieldsStayUnknown(String field) throws Exception {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA); data.putNull(field); respond(data.toString());
        var result = provider.extract(new byte[]{1}, "image/jpeg");
        assertThat(result.extractionStatus()).isEqualTo("PARTIAL");
        assertThat(switch(field) { case "merchant" -> result.merchant(); case "purchaseDate" -> result.purchaseDate(); case "totalAmount" -> result.amount(); case "currency" -> result.currency(); case "description" -> result.description(); default -> result.category(); }).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"0", "-1", "1.234", "1,234.50", "$6.72", "NaN", "1e3", "1000000000000", "6.7O"})
    void invalidAmountsAreNull(String value) throws Exception {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA); data.put("totalAmount", value); respond(data.toString());
        var result = provider.extract(new byte[]{1}, "image/png"); assertThat(result.amount()).isNull(); assertThat(result.merchant()).isEqualTo("Oak Cafe");
    }
    @ParameterizedTest @ValueSource(strings={"2025-02-30", "03/04/2025", "2025-9-2", "2999-01-01", "1899-01-01", "2025-01-01T00:00:00"})
    void invalidDatesAreNull(String value) throws Exception {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA); data.put("purchaseDate", value); respond(data.toString());
        assertThat(provider.extract(new byte[]{1}, "image/png").purchaseDate()).isNull();
    }
    @Test void unsupportedCategoryUncertainCurrencyAndOversizedMerchantAreNull() throws Exception {
        var data = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA); data.put("category", "GROCERIES").put("currency", "$").put("merchant", "x".repeat(201)); respond(data.toString());
        var result = provider.extract(new byte[]{1}, "image/png"); assertThat(result.category()).isNull(); assertThat(result.currency()).isNull(); assertThat(result.merchant()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"not-json", "null", "{}", "{\"status\":\"incomplete\",\"output\":[]}", "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}"})
    void malformedEnvelopesSafelyFail(String body) {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertFailure(); diagnostic("RESPONSE_PARSE_ERROR");
    }
    @ParameterizedTest @ValueSource(strings={"null", "{}", "[]", "{\"merchant\":123}", "{\"merchant\":null,\"purchaseDate\":null,\"totalAmount\":6.72,\"currency\":null,\"category\":null}"})
    void malformedStructuredResultsSafelyFail(String data) throws Exception { respond(data); assertFailure(); diagnostic("INVALID_STRUCTURED_OUTPUT"); }
    @Test void oversizedResponseFails() { server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess("x".repeat(128_001), MediaType.APPLICATION_JSON)); assertFailure(); }
    @Test void timeoutHasSafeFailureAndDoesNotRetry() { server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(request -> {throw new ResourceAccessException("private diagnostic", new java.net.SocketTimeoutException());}); assertFailure(); assertThat(diagnostic("NETWORK_ERROR")).contains("httpStatus=null"); }
    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500,503})
    void httpErrorsHaveSafeFailureAndDoNotRetry(int status) {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withStatus(HttpStatus.valueOf(status))
            .header("x-request-id", "req_0123456789abcdef0123456789abcdef")
            .body("{\"error\":{\"code\":\"insufficient_quota\",\"type\":\"invalid_request_error\",\"message\":\"private diagnostic sensitive-receipt\"}}"));
        assertFailure();
        assertThat(diagnostic("HTTP_ERROR")).contains("httpStatus="+status, "errorCode=insufficient_quota", "errorType=invalid_request_error", "requestId=req_0123456789abcdef0123456789abcdef");
    }
    @Test void untrustedErrorFieldsAndHeadersCannotLeakIntoLogs() {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .header("x-request-id", "sensitive-receipt")
            .body("{\"error\":{\"code\":\"fixture-not-a-real-key\",\"type\":\"sensitive-receipt\",\"message\":\"private diagnostic\"}}"));
        assertFailure(); assertThat(diagnostic("HTTP_ERROR")).contains("errorCode=null", "errorType=null", "requestId=null");
    }
    @Test void malformedErrorBodyStillRetainsHttpStatus() {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private diagnostic"));
        assertFailure(); assertThat(diagnostic("HTTP_ERROR")).contains("httpStatus=403", "errorCode=null");
    }
    @Test void oversizedErrorBodyIsNotLoggedOrParsed() {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("sensitive-receipt".repeat(2000)));
        assertFailure(); assertThat(diagnostic("HTTP_ERROR")).contains("httpStatus=400", "errorCode=null");
    }
    @Test void connectionFailureRetainsOnlySafeStage() {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(request -> { throw new ResourceAccessException("private diagnostic", new java.net.ConnectException("sensitive-receipt")); });
        assertFailure(); diagnostic("NETWORK_ERROR");
    }
    @Test void successfulHttpWithMalformedJsonRetainsStatusAndRequestId() {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess("private diagnostic", MediaType.APPLICATION_JSON).header("x-request-id", "req_0123456789abcdef0123456789abcdef"));
        assertFailure(); assertThat(diagnostic("RESPONSE_PARSE_ERROR")).contains("httpStatus=200", "requestId=req_0123456789abcdef0123456789abcdef");
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> networkFailures() {
        var ssl = new javax.net.ssl.SSLHandshakeException("private diagnostic");
        ssl.initCause(new java.security.cert.CertificateException("sensitive-receipt"));
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(ssl, "TLS_CERTIFICATE_ERROR", "java.security.cert.CertificateException"),
            org.junit.jupiter.params.provider.Arguments.of(new javax.net.ssl.SSLException("private diagnostic"), "TLS_ERROR", "javax.net.ssl.SSLException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.UnknownHostException("sensitive-receipt"), "DNS_ERROR", "java.net.UnknownHostException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.ConnectException("Connection refused: Authorization Bearer fixture-not-a-real-key"), "CONNECTION_REFUSED", "java.net.ConnectException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.ConnectException(), "CONNECTION_FAILED", "java.net.ConnectException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.SocketException("Connection reset: data:image/png;base64,AQIDBA=="), "CONNECTION_RESET", "java.net.SocketException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.SocketException("Broken pipe sensitive-receipt"), "BROKEN_PIPE", "java.net.SocketException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.http.HttpConnectTimeoutException("private diagnostic"), "CONNECT_TIMEOUT", "java.net.http.HttpConnectTimeoutException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.SocketTimeoutException("private diagnostic"), "TIMEOUT", "java.net.SocketTimeoutException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.http.HttpTimeoutException("private diagnostic"), "TIMEOUT", "java.net.http.HttpTimeoutException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.net.NoRouteToHostException("private diagnostic"), "NO_ROUTE_TO_HOST", "java.net.NoRouteToHostException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.io.EOFException("private diagnostic"), "CONNECTION_CLOSED", "java.io.EOFException"),
            org.junit.jupiter.params.provider.Arguments.of(new java.io.IOException("private diagnostic sensitive-receipt"), "NETWORK_IO_ERROR", "java.io.IOException")
        );
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("networkFailures")
    void networkDiagnosticsExposeOnlyClassesAndFixedCategories(java.io.IOException cause, String category, String rootClass) {
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(request -> {
            throw new ResourceAccessException("Authorization Bearer fixture-not-a-real-key sensitive-receipt", cause);
        });
        assertFailure();
        assertThat(diagnostic("NETWORK_ERROR")).contains("httpStatus=null",
            "exceptionClass=org.springframework.web.client.ResourceAccessException", "rootCauseClass="+rootClass, "networkCategory="+category);
    }
    @Test void causeCyclesAreBoundedAndMessagesNeverReturned() {
        var first = new java.io.IOException("sensitive-receipt");
        var second = new java.io.IOException("Authorization Bearer fixture-not-a-real-key");
        first.initCause(second); second.initCause(first);
        var safe = SafeNetworkDiagnostic.from(first);
        assertThat(safe.category()).isEqualTo("NETWORK_IO_ERROR");
        assertThat(safe.toString()).doesNotContain("sensitive-receipt", "Authorization", "fixture-not-a-real-key");
    }
    @ParameterizedTest @ValueSource(strings={"", "   ", "Office\nprivate diagnostic", "Office\u0000supplies"})
    void blankOrControlContainingDescriptionsStayUnknown(String value) throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);data.put("description",value);respond(data.toString());
        var result=provider.extract(new byte[]{1},"image/png");assertThat(result.description()).isNull();assertThat(result.extractionStatus()).isEqualTo("PARTIAL");assertThat(result.taxRelevant()).isFalse();
    }
    @Test void descriptionLengthBoundaryIsValidatedWithoutInventingReplacement() throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);data.put("description","x".repeat(241));respond(data.toString());
        assertThat(provider.extract(new byte[]{1},"image/png").description()).isNull();server.reset();
        data.put("description","x".repeat(240));respond(data.toString());assertThat(provider.extract(new byte[]{1},"image/png").description()).hasSize(240);
    }
    @Test void everyFieldCanBeUncertainWithoutFillingInMerchantOrCategory() throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);for(String field:List.of("merchant","purchaseDate","totalAmount","currency","category","description"))data.putNull(field);respond(data.toString());
        var result=provider.extract(new byte[]{1},"image/png");assertThat(result.extractionStatus()).isEqualTo("NO_MATCH");assertThat(result.description()).isNull();assertThat(result.category()).isNull();assertThat(result.taxRelevant()).isFalse();
    }
    @Test void extraReasoningAndTaxFieldsAreRejectedRatherThanDisplayed() throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);data.put("reasoning","private diagnostic");data.put("taxRelevant",true);respond(data.toString());assertFailure();diagnostic("INVALID_STRUCTURED_OUTPUT");
    }
    private byte[] receiptDateImage(String printedDate) throws Exception {
        var image=new java.awt.image.BufferedImage(700,240,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,700,240);
        graphics.setColor(java.awt.Color.BLACK);graphics.setFont(new java.awt.Font(java.awt.Font.MONOSPACED,java.awt.Font.PLAIN,24));
        graphics.drawString("TEST RECEIPT - CONTEMPORARY SALE",20,40);
        graphics.drawString("Transaction: "+printedDate,20,90);
        graphics.drawString("Total USD 6.72",20,140);
        graphics.drawString("Coupon expires 12/31/27",20,190);graphics.dispose();
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    @ParameterizedTest @ValueSource(strings={"09/22/26","09/22/2026","2026-09-22","September 22, 2026","Sep 22 2026","22 September 2026"})
    void receiptDateFormatsRequestNormalizationAndAcceptMockedIsoDate(String printedDate) throws Exception {
        byte[] image=receiptDateImage(printedDate);
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);data.put("purchaseDate","2026-09-22");
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(jsonPath("$.input[1].content[1].image_url").value("data:image/png;base64,"+Base64.getEncoder().encodeToString(image)))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("09/22/26 normalizes to 2026-09-22")))
            .andExpect(jsonPath("$.input[0].content").value(org.hamcrest.Matchers.containsString("Ignore coupon expiration")))
            .andExpect(jsonPath("$.text.format.schema.properties.purchaseDate.description").value(org.hamcrest.Matchers.containsString("YYYY-MM-DD")))
            .andRespond(withSuccess(envelope(data.toString()),MediaType.APPLICATION_JSON));
        var result=provider.extract(image,"image/png");assertThat(result.purchaseDate()).hasToString("2026-09-22");assertThat(result.extractionStatus()).isEqualTo("EXTRACTED");assertThat(logs.list).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"03/04/26","09/22/76","09/22/??","02/30/26","September 22"})
    void ambiguousHistoricalUnreadableOrIncompleteDatesCanRemainNull(String printedDate) throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);data.putNull("purchaseDate");respond(data.toString());
        var result=provider.extract(receiptDateImage(printedDate),"image/png");assertThat(result.purchaseDate()).isNull();assertThat(result.extractionStatus()).isEqualTo("PARTIAL");
    }
    @Test void rawTwoDigitSlashDateIsNotSilentlyGuessedByBackend() throws Exception {
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(DATA);data.put("purchaseDate","09/22/26");respond(data.toString());
        assertThat(provider.extract(receiptDateImage("09/22/26"),"image/png").purchaseDate()).isNull();
    }
    private void assertFailure() {
        var result = provider.extract(new byte[]{1}, "image/png"); assertThat(result.extractionStatus()).isEqualTo("EXTRACTION_UNAVAILABLE");
        assertThat(result.merchant()).isNull(); assertThat(result.amount()).isNull(); assertThat(result.extractionError()).contains("Please enter the details manually").doesNotContain("private", "fixture-not-a-real-key");
    }
}

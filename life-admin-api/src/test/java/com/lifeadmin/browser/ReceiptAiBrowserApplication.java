package com.lifeadmin.browser;

import com.lifeadmin.LifeAdminApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Test-classpath-only harness: disposable PostgreSQL, mocked HTTP, no real API credentials. */
public class ReceiptAiBrowserApplication {
    public static void main(String[] args) throws Exception {
        var postgres = EmbeddedPostgres.builder().start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {try { postgres.close(); } catch(Exception ignored) {}}));
        System.setProperty("spring.datasource.url",postgres.getJdbcUrl("postgres","postgres"));
        System.setProperty("spring.datasource.username","postgres");System.setProperty("spring.datasource.password","postgres");
        System.setProperty("app.storage-directory",Files.createTempDirectory("life-admin-ai-browser-").toString());
        System.setProperty("debug","false");
        System.setProperty("server.port","8085");System.setProperty("app.frontend-url","http://localhost:3002");
        // Optional no-provider mode supports the full existing browser regression suite.
        boolean manual=Arrays.asList(args).contains("--manual-receipts");
        System.setProperty("app.ai.openai.api-key",manual?"":"fixture-not-a-real-key");
        if(manual) {
            System.setProperty("spring.profiles.active","dev");
            System.setProperty("app.development.mock-analysis-enabled","true");
        }
        System.setProperty("app.ai.openai.receipt-model","browser-mocked-model");
        System.setProperty("app.ai.openai.model","browser-mocked-model");
        System.setProperty("life-admin.test.documents",String.valueOf(Arrays.asList(args).contains("--document-analysis")));
        SpringApplication.run(new Class<?>[]{LifeAdminApplication.class, MockHttp.class},args);
    }
    @TestConfiguration
    public static class MockHttp {
        @Bean org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse> receiptAiTestRoutes(State state) {
            return org.springframework.web.servlet.function.RouterFunctions.route()
                .GET("/api/test/receipt-ai", request -> org.springframework.web.servlet.function.ServerResponse.ok().body(Map.of("mocked",true,"calls",state.calls.get())))
                .POST("/api/test/receipt-ai/fail", request -> {state.fail=true;return org.springframework.web.servlet.function.ServerResponse.noContent().build();})
                .build();
        }
        @Bean State receiptAiTestState() { return new State(); }
        @Bean @Primary @Qualifier("openAiRestClient") RestClient receiptTestClient(State state) {
            var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");
            var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(ExpectedCount.manyTimes(),requestTo("https://api.openai.com/v1/responses"))
                .andExpect(method(HttpMethod.POST)).andExpect(state.documents ? jsonPath("$.input[1].content").isString() : jsonPath("$.input[1].content[1].type").value("input_image"))
                .andExpect(jsonPath("$.text.format.strict").value(true)).andExpect(jsonPath("$.store").value(false))
                .andRespond(request -> {
                    state.calls.incrementAndGet();
                    if(state.fail) return withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private test-only diagnostic").createResponse(request);
                    if(state.documents) {
                        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
                        var result=new java.util.LinkedHashMap<String,Object>();
                        result.put("documentType","Information request");result.put("summary","Based on the document, identification is requested.");
                        result.put("actionRequired",true);result.put("deadline","2026-10-15");result.put("requiredDocuments",List.of("Identification"));
                        result.put("nextSteps",List.of("Check the original notice","Prepare identification"));result.put("warnings",List.of("Verify the deadline with the issuing organization."));
                        result.put("meaning","Based on the document, you should review the request and respond if it applies to you.");
                        result.put("evidence",List.of());result.put("importantDates",List.of());
                        String body=mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",mapper.writeValueAsString(result)))))));
                        return withSuccess(body,MediaType.APPLICATION_JSON).createResponse(request);
                    }
                    return withSuccess("""
                        {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\\"merchant\\":\\"Oak Cafe\\",\\"purchaseDate\\":\\"2025-09-20\\",\\"totalAmount\\":\\"6.72\\",\\"currency\\":\\"USD\\",\\"category\\":\\"MEALS\\",\\"description\\":\\"Coffee\\"}"}]}]}
                        """,MediaType.APPLICATION_JSON).createResponse(request);
                });
            return builder.build();
        }
    }
    // Registered only by MockHttp; never packaged in the application JAR or component-scanned.
    public static class State {
        final AtomicInteger calls=new AtomicInteger(); volatile boolean fail; final boolean documents=Boolean.getBoolean("life-admin.test.documents");
    }
}

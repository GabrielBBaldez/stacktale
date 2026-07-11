package io.github.gabrielbbaldez.stacktale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportRendererTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonReportRenderer renderer =
            new JsonReportRenderer(ZoneOffset.UTC, Redactor.withDefaults(List.of()));

    private JsonNode parse(String line) throws Exception {
        // NDJSON: each entry is exactly one physical line of valid JSON
        assertThat(line).endsWith("\n");
        assertThat(line.strip()).doesNotContain("\n");
        return mapper.readTree(line.strip());
    }

    @Test
    void richReportSerializesEverySectionAsAddressableJson() throws Exception {
        DistilledStack stack = new DistilledStack("IllegalStateException", "payment gateway refused",
                "PaymentService.charge(PaymentService.java:44)", true,
                List.of("CheckoutException(\"checkout failed\") at CheckoutService.confirm(CheckoutService.java:88)"),
                List.of("PaymentService.charge(PaymentService.java:44) ← culprit",
                        "… 30 collapsed (spring ×20, tomcat ×10)"),
                32, 1, List.of());
        Story story = new Story(List.of(
                new StoryEntry(1_000_050L, "INFO", "CheckoutService", "confirming order 889"),
                new StoryEntry(1_000_412L, "ERROR", "PaymentService", "charge failed for order 889")
        ), "traceId=7c2e", 2);
        Report r = new Report("a1b2c3d4", 1_000_412L, "http-nio-8080-exec-2", stack,
                "charge failed for order {}", new Object[]{889}, "com.acme.shop.PaymentService",
                Map.of("traceId", "7c2e"), Map.of("orderId", "889", "retryable", "false"),
                List.of("PaymentService.charge(orderId=889, amount=149.90)"),
                story, "app=shop-api 1.4.2 (git 7e3c1f) | java 21 | profile=prod | linux",
                3, 1_000_000L);

        JsonNode j = parse(renderer.render(r));

        assertThat(j.get("type").asText()).isEqualTo("report");
        assertThat(j.get("id").asText()).isEqualTo("a1b2c3d4");
        assertThat(j.get("ts").asText()).isEqualTo("1970-01-01T00:16:40.412Z");
        assertThat(j.at("/error/type").asText()).isEqualTo("IllegalStateException");
        assertThat(j.at("/error/message").asText()).isEqualTo("payment gateway refused");
        assertThat(j.at("/error/culprit/frame").asText()).isEqualTo("PaymentService.charge(PaymentService.java:44)");
        assertThat(j.at("/error/culprit/appCode").asBoolean()).isTrue();
        assertThat(j.at("/error/wrappedBy/0").asText()).contains("CheckoutException");
        assertThat(j.at("/log/pattern").asText()).isEqualTo("charge failed for order {}");
        assertThat(j.at("/log/args/0").asText()).isEqualTo("889");
        assertThat(j.at("/log/logger").asText()).isEqualTo("com.acme.shop.PaymentService"); // full, not abbreviated
        assertThat(j.at("/mdc/traceId").asText()).isEqualTo("7c2e");
        assertThat(j.at("/fields/orderId").asText()).isEqualTo("889");
        assertThat(j.at("/fields/retryable").asText()).isEqualTo("false");
        assertThat(j.at("/captured/0").asText()).contains("charge(orderId=889");
        assertThat(j.at("/recurrence/count").asInt()).isEqualTo(3);
        assertThat(j.at("/recurrence/firstSeen").asText()).isEqualTo("1970-01-01T00:16:40.000Z");
        assertThat(j.at("/story/label").asText()).isEqualTo("traceId=7c2e");
        assertThat(j.at("/story/omittedByAge").asInt()).isEqualTo(2);
        assertThat(j.at("/story/events/1/thisError").asBoolean()).isTrue();
        assertThat(j.at("/story/events/0/message").asText()).isEqualTo("confirming order 889");
        assertThat(j.at("/stack/shown").asInt()).isEqualTo(1);
        assertThat(j.at("/stack/total").asInt()).isEqualTo(32);
        assertThat(j.get("env").asText()).contains("shop-api");
    }

    @Test
    void redactionAppliesToJsonValuesToo() throws Exception {
        Report r = new Report("cafe", 1_000_000L, "main", null,
                "login failed", null, "com.acme.Auth",
                Map.of("password", "hunter2", "user", "bob"),
                Map.of("email", "gabriel@example.com"), List.of(),
                new Story(List.of(), "thread main"), "app=? | java 21 | linux", 1, 0L);

        String rendered = renderer.render(r);
        JsonNode j = parse(rendered);

        assertThat(j.at("/error/noException").asBoolean()).isTrue();
        assertThat(j.at("/mdc/password").asText()).isEqualTo("███"); // secret-named key masks its value
        assertThat(j.at("/mdc/user").asText()).isEqualTo("bob");
        assertThat(j.at("/fields/email").asText()).isEqualTo("███"); // secret-shaped value redacted
        assertThat(rendered).doesNotContain("hunter2").doesNotContain("gabriel@example.com");
    }

    @Test
    void secretPositionArgIsMaskedInJson() throws Exception {
        Report r = new Report("cafe", 1_000_000L, "main", null,
                "auth token={}", new Object[]{"sk-live-abcdef"}, "com.acme.Auth",
                Map.of(), Map.of(), List.of(),
                new Story(List.of(), "thread main"), "app=? | java 21 | linux", 1, 0L);

        String rendered = renderer.render(r);
        assertThat(parse(rendered).at("/log/args/0").asText()).isEqualTo("███");
        assertThat(rendered).doesNotContain("sk-live-abcdef");
    }

    @Test
    void multiLineValuesStayOnOneLineViaEscaping() throws Exception {
        Report r = new Report("cafe", 1_000_000L, "main", null,
                "boom", null, "com.acme.Svc", Map.of(),
                Map.of("note", "line1\nline2"), List.of(),
                new Story(List.of(), "thread main"), "app=? | java 21 | linux", 1, 0L);

        JsonNode j = parse(renderer.render(r)); // parse() asserts the physical line has no raw newline
        assertThat(j.at("/fields/note").asText()).isEqualTo("line1\nline2"); // newline preserved, escaped
    }

    @Test
    void nonReportEntriesAreTypedJson() throws Exception {
        assertThat(parse(renderer.fileHeader()).get("format").asText()).isEqualTo("st-json/1");
        assertThat(parse(renderer.sessionMarker(1_000_000L, 42L)).get("type").asText()).isEqualTo("session");
        assertThat(parse(renderer.sessionMarker(1_000_000L, 42L)).get("pid").asInt()).isEqualTo(42);
        JsonNode repeat = parse(renderer.renderSummary("cafe", 47, 1_000_000L));
        assertThat(repeat.get("type").asText()).isEqualTo("repeat");
        assertThat(repeat.get("count").asInt()).isEqualTo(47);
        JsonNode storm = parse(renderer.stormLine(5, 10));
        assertThat(storm.get("type").asText()).isEqualTo("storm");
        assertThat(storm.get("suppressed").asInt()).isEqualTo(5);
    }
}

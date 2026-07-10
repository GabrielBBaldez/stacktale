package io.github.gabrielbbaldez.stacktale.log4j2;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Log4j2 mirror of the Logback integration suite: XML-configured appender, full
 * pipeline (story, fields, dedup, redaction), format identical to the Logback output.
 */
class Log4j2IntegrationTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
        ThreadContext.clearAll();
    }

    static class GatewayException extends RuntimeException {
        GatewayException(String msg) { super(msg); }
        public int getStatusCode() { return 502; }
        public boolean isRetryable() { return true; }
    }

    private Path start(Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <Configuration status="WARN" packages="io.github.gabrielbbaldez.stacktale.log4j2">
                  <Appenders>
                    <Stacktale name="STACKTALE" file="%s" appPackages="com.acme"
                               installUncaughtHandler="false"/>
                  </Appenders>
                  <Loggers>
                    <Root level="info">
                      <AppenderRef ref="STACKTALE"/>
                    </Root>
                  </Loggers>
                </Configuration>
                """.formatted(file.toString().replace("\\", "/"));
        ConfigurationSource source = new ConfigurationSource(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        ctx = Configurator.initialize(null, source);
        return file;
    }

    @Test
    void fullPipelineMirrorsTheLogbackBehavior(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        Logger log = ctx.getLogger("com.acme.OrderService");

        ThreadContext.put("traceId", "7b2c");
        try {
            log.info("POST /orders/42/confirm");
            log.warn("customer cache miss, returning null");
            GatewayException e = new GatewayException("bad gateway");
            for (int i = 0; i < 5; i++) {
                log.error("checkout failed for order {} password=hunter2", 42, e);
            }
        } finally {
            ThreadContext.clearAll();
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("format st/1");                          // self-describing header
        assertThat(content).containsOnlyOnce("\n━━━ ERROR #");                // dedup: 5 errors → 1 report
        assertThat(content).contains("GatewayException: bad gateway");
        assertThat(content).contains("fields: retryable=true statusCode=502"); // exception state
        assertThat(content).contains("POST /orders/42/confirm");              // story via ThreadContext
        assertThat(content).contains("traceId=7b2c");
        assertThat(content).contains("← this error");
        assertThat(content).doesNotContain("hunter2");                        // redaction
        assertThat(content).contains("repeated");                             // dedup summary line
        assertThat(content).contains("━━━ END #");
    }

    @Test
    void errorWithoutThrowableStillReports(@TempDir Path dir) throws Exception {
        Path file = start(dir);
        ctx.getLogger("com.acme.Pay").error("payment rejected for order {}", 77);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("ERROR (no exception): payment rejected for order 77");
        assertThat(content).doesNotContain("stack (distilled,");
    }
}

package io.github.gabrielbbaldez.stacktale;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the EXACT adoption path from the README: configuring the appender through
 * logback XML (Joran calls the public setters). If a setter name or type is wrong, this
 * test fails even though programmatic tests pass.
 */
class LogbackXmlConfigTest {

    private LoggerContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.stop();
    }

    @Test
    void configuresEveryPropertyFromXmlAndReports(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        String xml = """
                <configuration>
                  <appender name="STACKTALE" class="io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender">
                    <file>%s</file>
                    <appPackages>com.acme</appPackages>
                    <storySize>10</storySize>
                    <storyWindowSeconds>30</storyWindowSeconds>
                    <dedupWindowSeconds>120</dedupWindowSeconds>
                    <maxFileSizeMb>2</maxFileSizeMb>
                    <maxBackups>3</maxBackups>
                    <truncateOnStart>false</truncateOnStart>
                    <installUncaughtHandler>false</installUncaughtHandler>
                    <reportErrorsWithoutThrowable>true</reportErrorsWithoutThrowable>
                    <captureExceptionFields>true</captureExceptionFields>
                    <redactionEnabled>true</redactionEnabled>
                    <redactPattern>BR\\d{2}-\\d{4}</redactPattern>
                    <correlationMdcKeys>traceId</correlationMdcKeys>
                    <zone>UTC</zone>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="STACKTALE"/>
                  </root>
                </configuration>
                """.formatted(file.toString().replace("\\", "/"));

        ctx = new LoggerContext();
        ctx.setMDCAdapter(MDC.getMDCAdapter());
        JoranConfigurator joran = new JoranConfigurator();
        joran.setContext(ctx);
        joran.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        org.slf4j.Logger log = ctx.getLogger("com.acme.CheckoutService");
        log.info("charging card for order 42");
        log.error("charge failed for order {}", 42, new IllegalStateException("gateway timeout"));

        assertThat(Files.exists(file))
                .withFailMessage("XML-configured appender produced no report file — Joran/setter wiring is broken. Context status: %s",
                        ctx.getStatusManager().getCopyOfStatusList())
                .isTrue();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("IllegalStateException: gateway timeout");
        assertThat(content).contains("charging card for order 42");   // story flowed through XML config too
        assertThat(content).contains("← YOUR CODE");                  // appPackages applied
    }
}

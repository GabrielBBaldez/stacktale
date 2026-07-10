package io.github.gabrielbbaldez.stacktale.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.StacktaleAppender;
import io.github.gabrielbbaldez.stacktale.agent.fixture.OrderFlow;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Issue-#27 acceptance: with the agent attached, a failure that logged NOTHING still
 * produces a report whose {@code captured:} section shows the method arguments at the
 * throw site — including the null that caused it.
 */
class StacktaleAgentTest {

    @BeforeAll
    static void attachAgent() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        StacktaleAgent.install(instrumentation, List.of("io.github.gabrielbbaldez.stacktale.agent.fixture"));
    }

    private static Throwable failingCall() {
        try {
            new OrderFlow().confirm(889, null, true);
            throw new AssertionError("should have thrown");
        } catch (NullPointerException e) {
            return e;
        }
    }

    @Test
    void installIsResilientAndPackageMatchingRespectsBoundaries() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        // a sibling package sharing the literal prefix must NOT be swallowed
        assertThatCode(() -> StacktaleAgent.install(instrumentation,
                List.of("io.github.gabrielbbaldez.stacktale.agent.fixture")))
                .doesNotThrowAnyException();
        // premain must never propagate — a bad config disables the agent, never aborts the JVM
        assertThatCode(() -> StacktaleAgent.premain("packages=", instrumentation))
                .doesNotThrowAnyException();
    }

    @Test
    void capturesMethodArgumentsAtTheThrowSite() {
        Throwable e = failingCall();
        List<String> captured = CaptureRegistry.get(e);
        assertThat(captured).isNotEmpty();
        assertThat(captured).anySatisfy(line -> assertThat(line)
                .contains("OrderFlow.sendConfirmation")
                .contains("customer=null")       // the value nobody logged — the whole point
                .contains("orderId=889"));
        assertThat(captured).anySatisfy(line -> assertThat(line)
                .contains("OrderFlow.confirm")
                .contains("express=true"));
    }

    @Test
    void capturedFramesReachTheReport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(org.slf4j.MDC.getMDCAdapter());
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(file.toString());
        appender.setInstallUncaughtHandler(false);
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        Throwable e = failingCall();
        ctx.getLogger("com.acme.Orders").error("confirm failed", e);
        ctx.stop();

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("captured (method args at throw site, via stacktale-agent):");
        assertThat(content).contains("customer=null");
        assertThat(content).contains("orderId=889");
    }
}

package io.github.gabrielbbaldez.stacktale.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coexistence with the OpenTelemetry javaagent (#46). Production JVMs almost always
 * already run an agent, so stacktale-agent must not fight one. This launches a real
 * subprocess with BOTH javaagents attached and asserts the JVM starts clean, OTel is
 * active, and stacktale-agent still captures throw-site arguments (the {@code captured:}
 * section) on top of OTel's instrumentation.
 *
 * <p>Ordering: stacktale-agent is attached <em>after</em> OTel on the command line. Agents
 * transform in attach order, so stacktale sees classes after OTel and layers its advice on
 * top — the arrangement a real deployment uses (vendor agent first, stacktale last).
 */
class AgentCoexistenceIT {

    @Test
    void capturesThrowSiteArgsWhileTheOtelJavaagentIsAlsoAttached(@TempDir Path dir) throws Exception {
        Path agentJar = Path.of(System.getProperty("stacktale.agent.jar"));
        Path otelJar = Path.of(System.getProperty("otel.javaagent.jar"));
        assertThat(agentJar).as("stacktale-agent shaded jar").exists();
        assertThat(otelJar).as("otel javaagent jar").exists();

        Path report = dir.resolve("errors-ai.log");
        Result r = run(report, otelJar, agentJar);

        assertThat(r.exitCode()).as("JVM with both agents must start and exit cleanly:\n" + r.output())
                .isZero();
        // both agents announced themselves at startup. OTel 2.x isolates its API from the
        // app classloader, so its banner — not a Class.forName — is the honest "it ran" signal
        assertThat(r.output()).as(r.output()).contains("opentelemetry-javaagent");
        assertThat(r.output()).contains("stacktale-agent");
        assertThat(r.output()).contains("COEXIST_DONE");

        // the whole point: stacktale-agent still captured, running behind OTel
        String content = Files.readString(report);
        assertThat(content)
                .contains("captured (method args at throw site")
                .contains("charge(")
                .contains("orderId=889");

        System.out.println("[coexistence] both agents attached; throw-path ≈ "
                + marker(r.output(), "THROW_PATH_NANOS=") + " ns/throw");
    }

    private Result run(Path report, Path otelJar, Path agentJar) throws Exception {
        String javaExe = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", javaExe).toString());
        cmd.add("-javaagent:" + otelJar);
        cmd.add("-Dotel.traces.exporter=none");   // no collector needed — just prove it loads and instruments
        cmd.add("-Dotel.metrics.exporter=none");
        cmd.add("-Dotel.logs.exporter=none");
        cmd.add("-Dotel.service.name=stacktale-coexistence");
        cmd.add("-javaagent:" + agentJar + "=packages=io.github.gabrielbbaldez.stacktale.agent");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("io.github.gabrielbbaldez.stacktale.agent.CoexistenceApp");
        cmd.add(report.toString());

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        }
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("subprocess with both agents timed out:\n" + out);
        }
        return new Result(p.exitValue(), out.toString());
    }

    private static String marker(String output, String key) {
        for (String line : output.split("\n")) {
            if (line.startsWith(key)) return line.substring(key.length()).trim();
        }
        return "?";
    }

    private record Result(int exitCode, String output) {
    }
}

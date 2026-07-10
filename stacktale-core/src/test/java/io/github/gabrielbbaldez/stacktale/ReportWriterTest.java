package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {

    private static ReportWriter writer(Path file, long maxBytes, String header) {
        return new ReportWriter(file, maxBytes, header, null, false, 1);
    }

    @Test
    void writesHeaderOnceAndAppends(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = writer(file, 1024 * 1024, "# header\n");
        w.append("block-1\n");
        w.append("block-2\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# header\nblock-1\nblock-2\n");
    }

    @Test
    void rotatesWhenMaxSizeExceeded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = writer(file, 40, "# h\n");
        w.append("x".repeat(30) + "\n");
        w.append("y".repeat(30) + "\n"); // would exceed 40 → rotate first
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("x");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("# h\n").contains("y");
    }

    @Test
    void createsParentDirectories(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/deep/errors-ai.log");
        writer(file, 1024, "# h\n").append("b\n");
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void marksNewSessionWhenFileAlreadyHasContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        new ReportWriter(file, 1024 * 1024, "# h\n", "─── app start A ───\n", false, 1).append("block-1\n");
        // a new writer instance = a new application run
        new ReportWriter(file, 1024 * 1024, "# h\n", "─── app start B ───\n", false, 1).append("block-2\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // fresh file gets no marker; the restart does
        assertThat(content).isEqualTo("# h\nblock-1\n─── app start B ───\nblock-2\n");
    }

    @Test
    void truncateOnStartDropsThePreviousSession(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        new ReportWriter(file, 1024 * 1024, "# h\n", null, false, 1).append("old-session\n");
        new ReportWriter(file, 1024 * 1024, "# h\n", "─── app start ───\n", true, 1).append("block-new\n");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).isEqualTo("# h\nblock-new\n");
    }

    @Test
    void keepsNRotatedBackups(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 3);
        w.append("a".repeat(30) + "\n");
        w.append("b".repeat(30) + "\n");
        w.append("c".repeat(30) + "\n");
        w.append("d".repeat(30) + "\n");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("d");
        assertThat(Files.readString(dir.resolve("errors-ai.log.1"), StandardCharsets.UTF_8)).contains("c");
        assertThat(Files.readString(dir.resolve("errors-ai.log.2"), StandardCharsets.UTF_8)).contains("b");
        assertThat(Files.readString(dir.resolve("errors-ai.log.3"), StandardCharsets.UTF_8)).contains("a");
        assertThat(Files.exists(dir.resolve("errors-ai.log.4"))).isFalse();
    }

    @Test
    void zeroBackupsMeansRotationJustStartsFresh(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("errors-ai.log");
        ReportWriter w = new ReportWriter(file, 40, "# h\n", null, false, 0);
        w.append("a".repeat(30) + "\n");
        w.append("b".repeat(30) + "\n");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith("# h\n").contains("b").doesNotContain("a");
        assertThat(Files.exists(dir.resolve("errors-ai.log.1"))).isFalse();
    }
}

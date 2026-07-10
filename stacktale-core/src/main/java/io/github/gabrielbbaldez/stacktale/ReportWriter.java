package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Appends report blocks to the AI error log. New/empty files start with the
 * self-describing header. A new writer instance (= a new application run) appends a
 * session marker before its first block when the file already has content — or truncates
 * the file when {@code truncateOnStart} is set. When the file would exceed
 * {@code maxBytes} it rotates through {@code .1 … .N} backups ({@code maxBackups=0}
 * simply starts fresh). Writes are UTF-8 and flushed per append — errors are rare and
 * readers (AI tools) may open the file immediately after.
 */
final class ReportWriter {

    private final Path file;
    private final long maxBytes;
    private final String header;
    private final String sessionMarker;
    private final boolean truncateOnStart;
    private final int maxBackups;
    private boolean sessionHandled;

    ReportWriter(Path file, long maxBytes, String header,
                 String sessionMarker, boolean truncateOnStart, int maxBackups) {
        this.file = file;
        this.maxBytes = maxBytes;
        this.header = header;
        this.sessionMarker = sessionMarker;
        this.truncateOnStart = truncateOnStart;
        this.maxBackups = Math.max(0, maxBackups);
    }

    synchronized void append(String block) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (!sessionHandled) {
                sessionHandled = true;
                if (truncateOnStart) {
                    Files.deleteIfExists(file);
                } else if (sessionMarker != null && !sessionMarker.isBlank()
                        && Files.exists(file) && Files.size(file) > 0) {
                    Files.write(file, sessionMarker.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }
            long size = Files.exists(file) ? Files.size(file) : 0;
            byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
            if (size > 0 && size + bytes.length > maxBytes) {
                rotate();
                size = 0;
            }
            if (size == 0) {
                Files.writeString(file, header, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // the appender catches this and degrades
        }
    }

    private void rotate() throws IOException {
        if (maxBackups == 0) {
            Files.deleteIfExists(file);
            return;
        }
        Files.deleteIfExists(backup(maxBackups));
        for (int i = maxBackups - 1; i >= 1; i--) {
            Path from = backup(i);
            if (Files.exists(from)) {
                Files.move(from, backup(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, backup(1), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path backup(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }
}

package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.BiConsumer;

/**
 * Appends report blocks to the AI error log. New/empty files start with the
 * self-describing header. A new writer instance (= a new application run) appends a
 * session marker before its first block when the file already has content — or truncates
 * the file when {@code truncateOnStart} is set. When the file would exceed
 * {@code maxBytes} it rotates through {@code .1 … .N} backups ({@code maxBackups=0}
 * simply starts fresh). Writes are UTF-8 and flushed per append — errors are rare and
 * readers (AI tools) may open the file immediately after.
 *
 * <p><b>Single writer.</b> stacktale assumes one process writes a given file. Every write
 * is append-only and never truncates, so a stray second writer at worst interleaves blocks
 * or repeats the header (both harmless to a parser) — it cannot wipe another process's
 * data. Two processes racing on <em>rotation</em> are still unsupported: give each instance
 * its own path (see {@code SECURITY.md}).
 *
 * <p><b>Blocked rotation.</b> On Windows a reader holding the live file open blocks the
 * rename that rotation needs. Rather than dropping the report — and going silent until the
 * handle frees — a blocked rotation degrades to appending past the size cap and retries on
 * the next report, warning once per episode.
 */
final class ReportWriter {

    private static final String ROTATING_SUFFIX = ".rotating";

    private final Path file;
    private final long maxBytes;
    private final String header;
    private final String sessionMarker;
    private final boolean truncateOnStart;
    private final int maxBackups;
    private final BiConsumer<String, Throwable> warn;
    private boolean sessionHandled;
    private boolean rotationBlockedWarned;

    ReportWriter(Path file, long maxBytes, String header,
                 String sessionMarker, boolean truncateOnStart, int maxBackups) {
        this(file, maxBytes, header, sessionMarker, truncateOnStart, maxBackups, null);
    }

    ReportWriter(Path file, long maxBytes, String header, String sessionMarker,
                 boolean truncateOnStart, int maxBackups, BiConsumer<String, Throwable> warn) {
        this.file = file;
        this.maxBytes = maxBytes;
        this.header = header;
        this.sessionMarker = sessionMarker;
        this.truncateOnStart = truncateOnStart;
        this.maxBackups = Math.max(0, maxBackups);
        this.warn = warn != null ? warn : (m, t) -> { };
    }

    synchronized void append(String block) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            handleSessionStart();
            long size = Files.exists(file) ? Files.size(file) : 0;
            byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
            if (size > 0 && size + bytes.length > maxBytes && tryRotate()) {
                size = 0;
            }
            if (size == 0) {
                // Non-destructive: append (never truncate) the header. A file rotated away
                // is recreated; an empty file receives the header at offset 0. This is what
                // keeps a stray second writer from wiping another process's data.
                appendBytes(header.getBytes(StandardCharsets.UTF_8));
            }
            appendBytes(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // the appender catches this and degrades
        }
    }

    private void handleSessionStart() throws IOException {
        if (sessionHandled) return;
        sessionHandled = true;
        if (truncateOnStart) {
            Files.deleteIfExists(file);
        } else if (sessionMarker != null && !sessionMarker.isBlank()
                && Files.exists(file) && Files.size(file) > 0) {
            appendBytes(sessionMarker.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void appendBytes(byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Attempts a full rotation. Returns {@code true} when the live file has been moved out
     * of the way (the caller then writes a fresh file), {@code false} when rotation is
     * blocked and the caller should append past the cap and retry later. Never leaves the
     * file half-rotated: the risky move (the live file) happens first, and a later backup
     * failure restores it.
     */
    private boolean tryRotate() {
        if (maxBackups == 0) {
            try {
                Files.deleteIfExists(file);
                rotationBlockedWarned = false;
                return true;
            } catch (IOException e) {
                return rotationBlocked(e);
            }
        }
        Path pending = file.resolveSibling(file.getFileName() + ROTATING_SUFFIX);
        try {
            // The only step a Windows reader holding the live file can block. On failure the
            // file is untouched, so returning false and appending past the cap is clean.
            Files.move(file, pending);
        } catch (IOException blocked) {
            return rotationBlocked(blocked);
        }
        try {
            Files.deleteIfExists(backup(maxBackups));
            for (int i = maxBackups - 1; i >= 1; i--) {
                Path from = backup(i);
                if (Files.exists(from)) Files.move(from, backup(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(pending, backup(1), StandardCopyOption.REPLACE_EXISTING);
            rotationBlockedWarned = false;
            return true;
        } catch (IOException e) {
            // A backup step failed after the live file was moved aside — rare, since readers
            // hold the live file, not backups. Put it back so we degrade to appending past
            // the cap rather than losing the report or leaving a half-rotated state.
            try {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // if even the restore fails the content is safe in the .rotating sibling
            }
            return rotationBlocked(e);
        }
    }

    private boolean rotationBlocked(IOException e) {
        if (!rotationBlockedWarned) {
            rotationBlockedWarned = true;
            warn.accept("stacktale could not rotate " + file.getFileName()
                    + " (a reader may be holding it open); appending past the size cap and "
                    + "retrying on the next report", e);
        }
        return false;
    }

    private Path backup(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }
}

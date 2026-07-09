package io.github.gabrielbbaldez.stacktale;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Best-effort environment line: app name/version, git sha, java version, profile, os.
 * Sources in priority order: {@code stacktale.app.*} system properties, Spring Boot's
 * {@code META-INF/build-info.properties}, then {@code git.properties} for the sha.
 * Collected once, cached, and never allowed to fail the pipeline.
 */
final class EnvCollector {

    private final ClassLoader classLoader;
    private volatile String cached;

    EnvCollector(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    String envLine() {
        String line = cached;
        if (line == null) {
            line = build();
            cached = line;
        }
        return line;
    }

    private String build() {
        Properties buildInfo = load("META-INF/build-info.properties");
        Properties git = load("git.properties");

        String name = firstNonBlank(System.getProperty("stacktale.app.name"), buildInfo.getProperty("build.name"), "?");
        String version = firstNonBlank(System.getProperty("stacktale.app.version"), buildInfo.getProperty("build.version"), "");
        String sha = firstNonBlank(git.getProperty("git.commit.id.abbrev"), "");
        String profile = firstNonBlank(System.getProperty("spring.profiles.active"),
                System.getenv("SPRING_PROFILES_ACTIVE"), System.getenv("APP_ENV"), "");
        String java = System.getProperty("java.version", "?");
        String os = System.getProperty("os.name", "?").split(" ")[0].toLowerCase();

        StringBuilder sb = new StringBuilder("app=").append(name);
        if (!version.isEmpty()) sb.append(' ').append(version);
        if (!sha.isEmpty()) sb.append(" (git ").append(sha).append(')');
        sb.append(" | java ").append(java);
        if (!profile.isEmpty()) sb.append(" | profile=").append(profile);
        sb.append(" | ").append(os);
        return sb.toString();
    }

    private Properties load(String resource) {
        Properties p = new Properties();
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // env info is best-effort; never fail the pipeline over it
        }
        return p;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}

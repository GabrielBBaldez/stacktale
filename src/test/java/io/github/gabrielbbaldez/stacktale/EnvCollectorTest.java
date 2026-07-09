package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EnvCollectorTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("stacktale.app.name");
        System.clearProperty("stacktale.app.version");
        System.clearProperty("spring.profiles.active");
    }

    @Test
    void readsBuildInfoAndGitPropertiesFromClasspath() {
        String line = new EnvCollector(getClass().getClassLoader()).envLine();
        assertThat(line).contains("app=shop-api 1.4.2").contains("(git 7e3c1f)").contains("java ");
    }

    @Test
    void syspropsOverrideBuildInfo() {
        System.setProperty("stacktale.app.name", "override");
        System.setProperty("stacktale.app.version", "9.9.9");
        System.setProperty("spring.profiles.active", "dev");
        String line = new EnvCollector(getClass().getClassLoader()).envLine();
        assertThat(line).contains("app=override 9.9.9").contains("profile=dev");
    }

    @Test
    void degradesGracefullyWithEmptyClasspath() {
        ClassLoader empty = new URLClassLoader(new URL[0], null);
        String line = new EnvCollector(empty).envLine();
        assertThat(line).startsWith("app=?").doesNotContain("git").contains("java ");
    }
}

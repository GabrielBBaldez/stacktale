package io.github.gabrielbbaldez.stacktale.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the packaging contract that a unit test attaching via ByteBuddyAgent can't see:
 * the shaded jar's manifest must declare the capabilities the runtime code requests, or a
 * real {@code -javaagent:} attach aborts the JVM. (The original bug: manifest said
 * Can-Retransform-Classes=false while the code asked for RETRANSFORMATION.)
 */
class AgentManifestIT {

    @Test
    void shadedJarManifestDeclaresRetransformCapability() throws Exception {
        Path jar = findShadedJar();
        assumeTrue(jar != null, "shaded jar not built yet (package phase) — skipping (run via failsafe post-package)");
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Attributes main = jarFile.getManifest().getMainAttributes();
            assertThat(main.getValue("Premain-Class"))
                    .isEqualTo("io.github.gabrielbbaldez.stacktale.agent.StacktaleAgent");
            // the code requests RETRANSFORMATION when supported — the manifest must allow it
            assertThat(main.getValue("Can-Retransform-Classes")).isEqualTo("true");
        }
    }

    private static Path findShadedJar() throws Exception {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) return null;
        try (Stream<Path> files = Files.list(target)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith("stacktale-agent-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().contains("-sources"))
                    .filter(p -> !p.getFileName().toString().contains("-javadoc"))
                    .filter(AgentManifestIT::hasPremain)
                    .findFirst().orElse(null);
        }
    }

    private static boolean hasPremain(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getManifest() != null
                    && jarFile.getManifest().getMainAttributes().getValue("Premain-Class") != null;
        } catch (Exception e) {
            return false;
        }
    }
}

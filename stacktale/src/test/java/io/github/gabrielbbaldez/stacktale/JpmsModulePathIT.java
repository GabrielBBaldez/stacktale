package io.github.gabrielbbaldez.stacktale;

import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ResolvedModule;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPMS smoke test (#44): the core and Logback-adapter jars must resolve together on the
 * module path. Before 0.5.0 both owned package {@code io.github.gabrielbbaldez.stacktale}
 * — a split package, which is a fatal error on the module path — so fully modular apps
 * could not use the library. The adapter now lives in {@code ...stacktale.logback}.
 *
 * <p>Runs as a failsafe IT because it needs the packaged jars (whose manifests carry the
 * {@code Automatic-Module-Name}), not the exploded {@code target/classes}. The two jar
 * paths are handed in by the build (core via maven-dependency-plugin, logback = this jar).
 */
class JpmsModulePathIT {

    @Test
    void coreAndLogbackAdapterResolveOnTheModulePathWithoutSplitPackage() {
        Path coreJar = Path.of(System.getProperty("stacktale.core.jar"));
        Path logbackJar = Path.of(System.getProperty("stacktale.logback.jar"));
        assertThat(coreJar).as("core jar (set by maven-dependency-plugin)").exists();
        assertThat(logbackJar).as("logback jar (this module)").exists();

        ModuleFinder finder = ModuleFinder.of(coreJar, logbackJar);
        String core = "io.github.gabrielbbaldez.stacktale";
        String logback = "io.github.gabrielbbaldez.stacktale.logback";

        // the manifests declare the stable automatic module names consumers `requires`
        assertThat(finder.find(core)).as(core).isPresent();
        assertThat(finder.find(logback)).as(logback).isPresent();

        // resolving both together must not throw — a split package fails resolution outright
        Configuration cfg = ModuleLayer.boot().configuration()
                .resolve(finder, ModuleFinder.of(), Set.of(core, logback));
        assertThat(cfg.modules()).extracting(ResolvedModule::name).contains(core, logback);

        // and the packages are provably disjoint: the split is gone at the descriptor level
        Set<String> corePkgs = cfg.findModule(core).orElseThrow().reference().descriptor().packages();
        Set<String> logbackPkgs = cfg.findModule(logback).orElseThrow().reference().descriptor().packages();
        assertThat(corePkgs).doesNotContainAnyElementsOf(logbackPkgs);
        assertThat(corePkgs).contains(core);
        assertThat(logbackPkgs).contains(logback);
    }
}

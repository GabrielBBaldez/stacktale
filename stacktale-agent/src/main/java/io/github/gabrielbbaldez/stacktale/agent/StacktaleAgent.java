package io.github.gabrielbbaldez.stacktale.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * The stacktale north star, v1: capture what the developer never logged. Attach with
 *
 * <pre>
 * java -javaagent:stacktale-agent.jar=packages=com.your.app -jar app.jar
 * </pre>
 *
 * and every method in those packages that exits with a throwable contributes its
 * argument values to the report's {@code captured:} section — so
 * {@code confirmOrder(orderId=123, customer=null)} shows up even when the code logged
 * nothing at all. Scope note: this captures method <em>arguments</em>, not full local
 * variables; that trade-off keeps the happy-path overhead at zero (the advice only runs
 * on the exceptional exit path).
 */
public final class StacktaleAgent {

    private StacktaleAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        List<String> packages = parsePackages(agentArgs);
        if (packages.isEmpty()) {
            System.err.println("[stacktale-agent] no packages configured — "
                    + "use -javaagent:stacktale-agent.jar=packages=com.your.app (';' separates multiple). Agent inactive.");
            return;
        }
        // An exception out of premain aborts the JVM per the java.lang.instrument spec:
        // a broken agent must disable itself, never take down a healthy application.
        try {
            install(instrumentation, packages);
            System.err.println("[stacktale-agent] capturing throw-site arguments in packages " + packages);
        } catch (Throwable t) {
            System.err.println("[stacktale-agent] failed to install, running without capture: " + t);
        }
    }

    /** Shared by premain and tests (which attach via ByteBuddyAgent). */
    public static void install(Instrumentation instrumentation, List<String> packages) {
        ElementMatcher.Junction<TypeDescription> types = ElementMatchers.none();
        for (String pkg : packages) {
            // require a package boundary: packages=com.acme.orders must NOT swallow
            // com.acme.ordersprocessing — match the package prefix OR the exact type name
            types = types.or(ElementMatchers.nameStartsWith(pkg + ".").or(ElementMatchers.named(pkg)));
        }
        AgentBuilder builder = new AgentBuilder.Default().disableClassFormatChanges();
        // Retransformation lets us instrument classes already loaded before the agent —
        // but only when the runtime and this jar's manifest both allow it. A premain
        // agent works fine without it (classes are transformed as they load), so degrade
        // gracefully instead of failing when retransform isn't available.
        if (instrumentation.isRetransformClassesSupported()) {
            builder = builder.with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        }
        builder.type(types)
                .transform((b, typeDescription, classLoader, module, protectionDomain) ->
                        b.visit(Advice.to(CaptureAdvice.class).on(isMethod().and(not(isAbstract())))))
                .installOn(instrumentation);
    }

    private static List<String> parsePackages(String agentArgs) {
        List<String> packages = new ArrayList<>();
        if (agentArgs == null) return packages;
        for (String part : agentArgs.split("[,;]")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("packages=")) trimmed = trimmed.substring("packages=".length());
            if (!trimmed.isEmpty() && !trimmed.contains("=")) packages.add(trimmed);
        }
        return packages;
    }
}

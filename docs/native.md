# stacktale on GraalVM native-image

stacktale's report pipeline is almost entirely reflection-free, so it works under
GraalVM native-image (including Spring Boot 3 AOT) with **no configuration**. The one
section that needs your help is `fields:`, because it reflects over *your* exception
types — which only you can enumerate for the image.

## What works with zero metadata

Everything that makes a report except `fields:` and `captured:`:

- the headline, culprit frame, `wrapped by:`, distilled stack
- the `story` (events leading up to the error), dedup, `seen:` recurrence
- redaction, storm control, session markers
- writing `errors-ai.log` and the MCP server reading it

None of these touch reflection or dynamic resources.

## `env:` — handled for you

The `env:` line reads two classpath resources (`git.properties` and
`META-INF/build-info.properties`). Native-image drops unregistered resources, so
stacktale ships the metadata to keep them:

- **stacktale-core** bundles
  `META-INF/native-image/io.github.gabrielbbaldez/stacktale-core/resource-config.json`.
- **the Spring Boot starter** additionally registers them through a
  `RuntimeHintsRegistrar` (`StacktaleRuntimeHints`).

Without these you don't get a crash — just `app=?` with no version/sha. With them,
`env:` is complete in native too.

## `fields:` — register your exception types (the escape hatch)

`fields:` reads the getters and public fields of *your* domain exceptions
(`order.getOrderId()`, `retryable`). Under native-image, reflection over a class only
works if that class is registered, and stacktale cannot know your exception types ahead
of time. Unregistered, `fields:` simply comes back empty (no crash) — a graceful
degradation, not a failure.

To keep `fields:` working, register your exception types. Two ways:

**Spring Boot** — point a hint at your exceptions (a package's worth in one line):

```java
@Configuration
@RegisterReflectionForBinding({ OrderException.class, PaymentException.class })
class NativeHints {}
```

or a registrar if you prefer:

```java
class MyHints implements RuntimeHintsRegistrar {
    public void registerHints(RuntimeHints hints, ClassLoader cl) {
        hints.reflection().registerType(OrderException.class,
                MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.DECLARED_FIELDS);
    }
}
```

**Plain native-image** — add a `reflect-config.json` (under
`META-INF/native-image/<your-group>/<your-artifact>/`):

```json
[
  {
    "name": "com.acme.OrderException",
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

Only exception types you care about seeing state for need this — stacktale reads at most
8 value-typed getters/fields per report.

## `captured:` — not available in native

The `captured:` section comes from the optional `stacktale-agent`, a `-javaagent`.
Java agents instrument bytecode at class-load time and do not apply to a native image,
so `captured:` is simply absent in native builds. `AgentCaptures` no-ops when the agent
class isn't present — again, graceful, no crash.

## Verifying

With a GraalVM JDK, build your app native the usual way — stacktale needs no special flags
beyond registering your exception types (above). For a Spring Boot app using the starter:

```bash
mvn -Pnative native:compile
./target/your-app     # trigger an error, then check ./errors-ai.log
```

`env:` should keep your version/git sha (the bundled resource metadata + the starter's
`RuntimeHintsRegistrar` handle that), and `fields:` should be populated for every exception
type you registered. There is no native job in CI — native-image needs a GraalVM toolchain
and minutes per build — so run this check yourself when validating a native deployment.

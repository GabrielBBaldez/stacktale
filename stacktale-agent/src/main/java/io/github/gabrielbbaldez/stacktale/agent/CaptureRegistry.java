package io.github.gabrielbbaldez.stacktale.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the agent parks what it saw at the throw site: for each in-flight throwable, the
 * method frames it escaped through with their argument values. The core library reads
 * this reflectively (it never depends on the agent) and renders the {@code captured:}
 * section. Weak keys: entries die with the throwable. Everything here is bounded and
 * exception-proof — the agent must never make a failing app worse.
 */
public final class CaptureRegistry {

    private static volatile int maxFrames = 5;
    private static volatile int maxValueLength = 60;
    private static volatile boolean renderToString = true;

    private static final Map<Throwable, Deque<String>> CAPTURES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<String, String[]> PARAMETER_NAMES = new ConcurrentHashMap<>();

    private CaptureRegistry() {}

    /** Applied once at agent install from the {@code -javaagent} arguments. */
    public static void configure(int frames, int valueLength, boolean toString) {
        maxFrames = Math.max(1, frames);
        maxValueLength = Math.max(8, valueLength);
        renderToString = toString;
    }

    /** Called from instrumented methods (via advice) when they exit with a throwable. */
    public static void record(Throwable thrown, String className, String methodName, Object[] args) {
        try {
            Deque<String> frames = CAPTURES.computeIfAbsent(thrown, k -> new ArrayDeque<>());
            synchronized (frames) {
                if (frames.size() >= maxFrames) return;
                frames.addLast(formatFrame(className, methodName, args));
            }
        } catch (Throwable ignored) {
            // never make a failing app worse
        }
    }

    /** Read by stacktale-core via reflection. */
    public static List<String> get(Throwable thrown) {
        Deque<String> frames = CAPTURES.get(thrown);
        if (frames == null) return List.of();
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }

    private static String formatFrame(String className, String methodName, Object[] args) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        StringBuilder sb = new StringBuilder(simple).append('.').append(methodName).append('(');
        String[] names = parameterNames(className, methodName, args == null ? 0 : args.length);
        for (int i = 0; args != null && i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names != null && i < names.length ? names[i] : "arg" + i).append('=').append(render(args[i]));
        }
        return sb.append(')').toString();
    }

    private static String render(Object value) {
        try {
            if (value == null) return "null";
            if (value.getClass().isArray()) {
                return value.getClass().getComponentType().getSimpleName()
                        + "[" + java.lang.reflect.Array.getLength(value) + "]";
            }
            // privacy mode: for non-value types, record the type name only, never the
            // toString() (which may hold PII). Primitives/wrappers/String/enum are shown.
            if (!renderToString && !isValueType(value.getClass())) {
                return value.getClass().getSimpleName();
            }
            String s = String.valueOf(value);
            return s.length() > maxValueLength ? s.substring(0, maxValueLength) + "…" : s;
        } catch (Throwable t) {
            return "<toString failed>";
        }
    }

    private static boolean isValueType(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class
                || Number.class.isAssignableFrom(type) || type == Boolean.class || type == Character.class;
    }

    /** Real parameter names when the class was compiled with -parameters; argN otherwise. */
    private static String[] parameterNames(String className, String methodName, int argCount) {
        String key = className + '#' + methodName + '#' + argCount;
        return PARAMETER_NAMES.computeIfAbsent(key, k -> {
            try {
                Class<?> cls = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                Method match = null;
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getName().equals(methodName) && m.getParameterCount() == argCount) {
                        if (match != null) return new String[0]; // overload ambiguity — fall back to argN
                        match = m;
                    }
                }
                if (match == null) return new String[0];
                Parameter[] parameters = match.getParameters();
                String[] names = new String[parameters.length];
                for (int i = 0; i < parameters.length; i++) names[i] = parameters[i].getName();
                return names;
            } catch (Throwable t) {
                return new String[0];
            }
        });
    }
}

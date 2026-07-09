package io.github.gabrielbbaldez.stacktale;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pulls the state a domain exception carries — {@code orderId}, {@code statusCode},
 * {@code retryable} — out of its public zero-arg getters and public fields. This is the
 * single most valuable signal for an AI reader after the story, and it is thrown away by
 * every classic format.
 *
 * <p>Deliberately shallow and paranoid: only value-ish types (primitives, wrappers,
 * String, enums), only members declared below {@link Throwable}, hard caps on count and
 * length, and a poisonous getter can never break the pipeline.
 */
final class FieldExtractor {

    private static final int MAX_FIELDS = 8;
    private static final int MAX_VALUE_LENGTH = 80;
    private static final Set<String> SKIPPED_GETTERS = Set.of(
            "getMessage", "getLocalizedMessage", "getCause", "getStackTrace", "getSuppressed", "getClass");

    private FieldExtractor() {}

    static Map<String, String> extract(Throwable t) {
        TreeMap<String, String> out = new TreeMap<>();
        if (t == null) return out;
        try {
            for (Class<?> cls = t.getClass(); cls != null && cls != Throwable.class && Throwable.class.isAssignableFrom(cls);
                 cls = cls.getSuperclass()) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (out.size() >= MAX_FIELDS) return out;
                    String name = getterName(m);
                    if (name == null || out.containsKey(name)) continue;
                    readInto(out, name, () -> m.invoke(t), m.getReturnType(), Modifier.isPublic(m.getModifiers())
                            && !Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0);
                }
                for (Field f : cls.getDeclaredFields()) {
                    if (out.size() >= MAX_FIELDS) return out;
                    if (out.containsKey(f.getName())) continue;
                    readInto(out, f.getName(), () -> f.get(t), f.getType(), Modifier.isPublic(f.getModifiers())
                            && !Modifier.isStatic(f.getModifiers()));
                }
            }
        } catch (Throwable ignored) {
            // field capture is opportunistic enrichment; never let it break a report
        }
        return out;
    }

    private interface ValueReader {
        Object read() throws Exception;
    }

    private static void readInto(Map<String, String> out, String name, ValueReader reader,
                                 Class<?> type, boolean accessible) {
        if (!accessible || !isValueType(type)) return;
        try {
            Object value = reader.read();
            String s = String.valueOf(value);
            if (s.length() > MAX_VALUE_LENGTH) s = s.substring(0, MAX_VALUE_LENGTH) + "…";
            out.put(name, s);
        } catch (Throwable ignored) {
            // poisonous getter — skip it, keep the rest
        }
    }

    private static String getterName(Method m) {
        String n = m.getName();
        if (SKIPPED_GETTERS.contains(n)) return null;
        if (n.startsWith("get") && n.length() > 3) return decapitalize(n.substring(3));
        if (n.startsWith("is") && n.length() > 2
                && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
            return decapitalize(n.substring(2));
        }
        return null;
    }

    private static boolean isValueType(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || type == String.class
                || type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
                || type == Double.class || type == Float.class || type == Boolean.class || type == Character.class;
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}

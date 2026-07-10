package io.github.gabrielbbaldez.stacktale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a throwable chain into a {@link DistilledStack}: the deepest cause becomes the
 * headline, wrappers shrink to one line each, and runs of framework frames collapse into
 * a single counted marker. App frames are detected via configured packages, or — when
 * none are configured — by not matching the known framework prefixes.
 */
final class StackDistiller {

    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int MAX_SHOWN_FRAMES = 15;
    private static final int MAX_SUPPRESSED = 3;
    private static final int MAX_WRAPPER_MSG = 80;

    // prefix -> group label; insertion order matters (first match wins)
    private static final Map<String, String> FRAMEWORK_GROUPS = new LinkedHashMap<>();
    static {
        FRAMEWORK_GROUPS.put("org.springframework.", "spring");
        FRAMEWORK_GROUPS.put("org.apache.catalina.", "tomcat");
        FRAMEWORK_GROUPS.put("org.apache.tomcat.", "tomcat");
        FRAMEWORK_GROUPS.put("org.apache.coyote.", "tomcat");
        FRAMEWORK_GROUPS.put("jakarta.servlet.", "servlet");
        FRAMEWORK_GROUPS.put("javax.servlet.", "servlet");
        FRAMEWORK_GROUPS.put("java.", "jdk");
        FRAMEWORK_GROUPS.put("jdk.", "jdk");
        FRAMEWORK_GROUPS.put("sun.", "jdk");
        FRAMEWORK_GROUPS.put("com.sun.", "jdk");
        FRAMEWORK_GROUPS.put("org.hibernate.", "hibernate");
        FRAMEWORK_GROUPS.put("com.zaxxer.hikari.", "hikari");
        FRAMEWORK_GROUPS.put("io.netty.", "netty");
        FRAMEWORK_GROUPS.put("reactor.", "reactor");
        FRAMEWORK_GROUPS.put("org.junit.", "test");
        FRAMEWORK_GROUPS.put("org.mockito.", "test");
        FRAMEWORK_GROUPS.put("ch.qos.logback.", "logging");
        FRAMEWORK_GROUPS.put("org.apache.logging.log4j.", "logging");
        FRAMEWORK_GROUPS.put("org.slf4j.", "logging");
    }

    private final List<String> appPackages;

    StackDistiller(List<String> appPackages) {
        this.appPackages = appPackages;
    }

    DistilledStack distill(Throwable throwable) {
        List<Throwable> chain = causeChain(throwable);
        Throwable root = chain.get(chain.size() - 1);

        StackTraceElement[] frames = root.getStackTrace();
        if (frames == null) frames = new StackTraceElement[0];

        String culprit = null;
        boolean culpritIsApp = false;
        int culpritIdx = -1;
        for (int i = 0; i < frames.length; i++) {
            if (isAppFrame(frames[i])) {
                culprit = location(frames[i]);
                culpritIdx = i;
                culpritIsApp = true;
                break;
            }
        }
        if (culprit == null && frames.length > 0) {
            // no app frame anywhere — fall back to the throwing frame, but don't claim it's the user's
            culprit = location(frames[0]);
            culpritIdx = 0;
        }

        List<String> frameLines = renderFrames(frames, culpritIdx);

        List<String> wrappedBy = new ArrayList<>();
        for (int i = chain.size() - 2; i >= 0; i--) {
            Throwable w = chain.get(i);
            wrappedBy.add(simpleName(w.getClass().getName()) + "(\"" + truncate(nullToEmpty(w.getMessage()), MAX_WRAPPER_MSG)
                    + "\") at " + firstLocation(w));
        }

        List<String> suppressed = new ArrayList<>();
        Throwable[] sup = root.getSuppressed();
        if (sup != null) {
            for (int i = 0; i < sup.length && i < MAX_SUPPRESSED; i++) {
                suppressed.add("suppressed: " + simpleName(sup[i].getClass().getName()) + "(\""
                        + truncate(nullToEmpty(sup[i].getMessage()), MAX_WRAPPER_MSG) + "\") at " + firstLocation(sup[i]));
            }
            if (sup.length > MAX_SUPPRESSED) suppressed.add("… " + (sup.length - MAX_SUPPRESSED) + " more suppressed");
        }

        int shown = (int) frameLines.stream().filter(l -> !l.startsWith("…")).count();
        return new DistilledStack(simpleName(root.getClass().getName()), root.getMessage(), culprit, culpritIsApp,
                wrappedBy, frameLines, frames.length, shown, suppressed);
    }

    private List<String> renderFrames(StackTraceElement[] frames, int culpritIdx) {
        List<String> out = new ArrayList<>();
        int shown = 0;
        int i = 0;
        while (i < frames.length) {
            StackTraceElement el = frames[i];
            boolean mustShow = i == 0 || i == culpritIdx || isAppFrame(el);
            if (mustShow) {
                if (shown >= MAX_SHOWN_FRAMES) {
                    out.add("… " + (frames.length - i) + " more frames");
                    break;
                }
                out.add(location(frames[i]) + (i == culpritIdx ? " ← culprit" : ""));
                shown++;
                i++;
            } else {
                int start = i;
                Map<String, Integer> groups = new LinkedHashMap<>();
                while (i < frames.length && i != culpritIdx && !isAppFrame(frames[i])) {
                    groups.merge(groupOf(frames[i].getClassName()), 1, Integer::sum);
                    i++;
                }
                int run = i - start;
                if (run == 1) {
                    if (shown >= MAX_SHOWN_FRAMES) {
                        out.add("… " + (frames.length - start) + " more frames");
                        break;
                    }
                    out.add(location(frames[start]));
                    shown++;
                } else {
                    StringBuilder sb = new StringBuilder("… ").append(run).append(" collapsed (");
                    boolean first = true;
                    for (var e : groups.entrySet()) {
                        if (!first) sb.append(", ");
                        sb.append(e.getKey()).append(" ×").append(e.getValue());
                        first = false;
                    }
                    out.add(sb.append(")").toString());
                }
            }
        }
        return out;
    }

    private List<Throwable> causeChain(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cur = throwable;
        while (cur != null && chain.size() < MAX_CAUSE_DEPTH && seen.add(cur)) {
            chain.add(cur);
            cur = cur.getCause();
        }
        return chain;
    }

    private boolean isAppFrame(StackTraceElement el) {
        String cls = el.getClassName();
        if (!appPackages.isEmpty()) {
            return appPackages.stream().anyMatch(cls::startsWith);
        }
        return FRAMEWORK_GROUPS.keySet().stream().noneMatch(cls::startsWith);
    }

    private String groupOf(String className) {
        for (var e : FRAMEWORK_GROUPS.entrySet()) {
            if (className.startsWith(e.getKey())) return e.getValue();
        }
        return "other";
    }

    private String firstLocation(Throwable t) {
        StackTraceElement[] f = t.getStackTrace();
        return (f == null || f.length == 0) ? "(no stack)" : location(f[0]);
    }

    private static String location(StackTraceElement el) {
        String cls = simpleName(el.getClassName());
        String file = el.getFileName() == null ? "Unknown" : el.getFileName();
        return cls + "." + el.getMethodName() + "(" + file + ":" + el.getLineNumber() + ")";
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

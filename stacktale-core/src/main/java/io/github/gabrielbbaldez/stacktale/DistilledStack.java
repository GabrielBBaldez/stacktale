package io.github.gabrielbbaldez.stacktale;

import java.util.List;

/**
 * A throwable chain distilled for an AI reader: root cause first, culprit frame
 * identified, framework noise collapsed.
 */
public record DistilledStack(
        String rootType,
        String rootMessage,
        String culpritLine,
        boolean culpritIsAppCode,
        List<String> wrappedBy,
        List<String> frameLines,
        int totalFrames,
        int shownFrames,
        List<String> suppressed
) {}

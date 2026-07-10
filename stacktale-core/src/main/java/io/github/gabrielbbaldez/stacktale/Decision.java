package io.github.gabrielbbaldez.stacktale;

/** Dedup verdict for one error occurrence. */
record Decision(Kind kind, int count, long lastSeenMillis) {}

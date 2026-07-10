package io.github.gabrielbbaldez.stacktale;

/** One line of the story: a recent log event in compact form. */
public record StoryEntry(long epochMillis, String level, String logger, String message) {}

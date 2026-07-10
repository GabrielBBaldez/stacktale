package io.github.gabrielbbaldez.stacktale;

import java.util.List;

/** The events leading up to (and including) an error, plus where they came from. */
public record Story(List<StoryEntry> entries, String contextLabel) {}

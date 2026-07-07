package io.github.welingtonmonteiro.multiplerun;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An advanced, composable filter for the aggregated Logs tab, kept free of IDE dependencies so it
 * is unit-testable in isolation. A line is shown when it passes, in order:
 * <ol>
 *   <li><b>App</b> - only lines from the selected application (or every app when none is set);</li>
 *   <li><b>Level</b> - only lines at or above a minimum log level, detected from the text;</li>
 *   <li><b>Terms</b> - several comma-separated terms combined with {@link Mode#ANY}/{@link Mode#ALL},
 *       matched as substrings or regular expressions, case-sensitive or not, and either kept
 *       (include) or dropped ({@link #exclude}).</li>
 * </ol>
 */
public final class LogFilter {

    /** Ordered by severity, so a minimum level is a simple ordinal comparison. */
    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR }

    /** How several terms combine. */
    public enum Mode { ANY, ALL }

    private static final Pattern P_ERROR = Pattern.compile("\\b(ERROR|ERR|FATAL|SEVERE)\\b");
    private static final Pattern P_WARN = Pattern.compile("\\b(WARN|WARNING)\\b");
    private static final Pattern P_INFO = Pattern.compile("\\bINFO\\b");
    private static final Pattern P_DEBUG = Pattern.compile("\\bDEBUG\\b");
    private static final Pattern P_TRACE = Pattern.compile("\\bTRACE\\b");

    private final String app;            // null = every app
    private final List<String> terms;    // already trimmed, empties dropped
    private final Mode mode;
    private final boolean exclude;       // true = hide matching lines; false = show only matches
    private final boolean caseSensitive;
    private final boolean regex;
    private final Level minLevel;        // null = every level
    private final List<Pattern> patterns; // compiled regex per term (null slot = invalid regex)

    public LogFilter(String app, List<String> terms, Mode mode, boolean exclude,
                     boolean caseSensitive, boolean regex, Level minLevel) {
        this.app = app == null || app.trim().isEmpty() ? null : app;
        this.terms = terms == null ? new ArrayList<>() : terms;
        this.mode = mode == null ? Mode.ANY : mode;
        this.exclude = exclude;
        this.caseSensitive = caseSensitive;
        this.regex = regex;
        this.minLevel = minLevel;
        this.patterns = new ArrayList<>();
        if (regex) {
            final int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            for (String term : this.terms) {
                try {
                    patterns.add(Pattern.compile(term, flags));
                } catch (PatternSyntaxException e) {
                    patterns.add(null); // an invalid regex simply never matches
                }
            }
        }
    }

    /** The identity filter: every line of every app passes. */
    public static LogFilter all() {
        return new LogFilter(null, new ArrayList<>(), Mode.ANY, false, false, false, null);
    }

    /**
     * Convenience factory. In substring mode {@code rawTerms} is split on commas into several terms
     * (combined by {@code mode}); in regex mode the whole string is a single pattern, since a regex
     * quantifier like {@code \d{3,}} legitimately contains a comma.
     */
    public static LogFilter from(String app, String rawTerms, Mode mode, boolean exclude,
                                 boolean caseSensitive, boolean regex, Level minLevel) {
        final List<String> terms;
        if (regex) {
            final String single = rawTerms == null ? "" : rawTerms.trim();
            terms = single.isEmpty() ? new ArrayList<>() : new ArrayList<>(java.util.Collections.singletonList(single));
        } else {
            terms = splitTerms(rawTerms);
        }
        return new LogFilter(app, terms, mode, exclude, caseSensitive, regex, minLevel);
    }

    /** Splits a comma-separated filter string into trimmed, non-empty terms. */
    public static List<String> splitTerms(String raw) {
        final List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            final String term = part.trim();
            if (!term.isEmpty()) {
                out.add(term);
            }
        }
        return out;
    }

    /** True when {@code line} from {@code lineApp} passes every enabled criterion. */
    public boolean accepts(String lineApp, String line) {
        if (app != null && !app.equals(lineApp)) {
            return false;
        }
        if (minLevel != null) {
            final Level level = detectLevel(line);
            if (level == null || level.ordinal() < minLevel.ordinal()) {
                return false;
            }
        }
        if (terms.isEmpty()) {
            return true; // nothing to include/exclude
        }
        final boolean combined = matchesTerms(line == null ? "" : line);
        return exclude ? !combined : combined;
    }

    private boolean matchesTerms(String line) {
        boolean any = false;
        boolean all = true;
        for (int i = 0; i < terms.size(); i++) {
            final boolean matched = termMatches(i, line);
            any |= matched;
            all &= matched;
        }
        return mode == Mode.ALL ? all : any;
    }

    private boolean termMatches(int index, String line) {
        if (regex) {
            final Pattern pattern = patterns.get(index);
            return pattern != null && pattern.matcher(line).find();
        }
        final String term = terms.get(index);
        return caseSensitive
                ? line.contains(term)
                : line.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT));
    }

    /** Best-effort log level of a line from common level tokens, or null when none is found. */
    public static Level detectLevel(String line) {
        if (line == null) {
            return null;
        }
        final String upper = line.toUpperCase(Locale.ROOT);
        if (P_ERROR.matcher(upper).find()) {
            return Level.ERROR;
        }
        if (P_WARN.matcher(upper).find()) {
            return Level.WARN;
        }
        if (P_INFO.matcher(upper).find()) {
            return Level.INFO;
        }
        if (P_DEBUG.matcher(upper).find()) {
            return Level.DEBUG;
        }
        if (P_TRACE.matcher(upper).find()) {
            return Level.TRACE;
        }
        return null;
    }
}

package io.github.welingtonmonteiro.lanes

import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * An advanced, composable filter for the aggregated Logs tab, kept free of IDE dependencies so it
 * is unit-testable in isolation. A line is shown when it passes, in order:
 *  1. **App** - only lines from the selected application (or every app when none is set);
 *  2. **Level** - only lines at or above a minimum log level, detected from the text;
 *  3. **Terms** - several comma-separated terms combined with [Mode.ANY]/[Mode.ALL], matched as
 *     substrings or regular expressions, case-sensitive or not, and either kept (include) or
 *     dropped ([exclude]).
 */
class LogFilter(
    app: String?,
    terms: List<String>?,
    mode: Mode?,
    private val exclude: Boolean,
    private val caseSensitive: Boolean,
    private val regex: Boolean,
    private val minLevel: Level?,
) {

    /** Ordered by severity, so a minimum level is a simple ordinal comparison. */
    enum class Level { TRACE, DEBUG, INFO, WARN, ERROR }

    /** How several terms combine. */
    enum class Mode { ANY, ALL }

    private val app: String? = if (app == null || app.trim().isEmpty()) null else app
    private val terms: List<String> = terms ?: emptyList()
    private val mode: Mode = mode ?: Mode.ANY

    /** Compiled regex per term (a null slot is an invalid regex, which never matches). */
    private val patterns: List<Pattern?> =
        if (regex) {
            val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            this.terms.map { term ->
                try {
                    Pattern.compile(term, flags)
                } catch (e: PatternSyntaxException) {
                    null
                }
            }
        } else {
            emptyList()
        }

    /** True when [line] from [lineApp] passes every enabled criterion. */
    fun accepts(lineApp: String?, line: String?): Boolean {
        if (app != null && app != lineApp) {
            return false
        }
        if (minLevel != null) {
            val level = detectLevel(line)
            if (level == null || level.ordinal < minLevel.ordinal) {
                return false
            }
        }
        if (terms.isEmpty()) {
            return true // nothing to include/exclude
        }
        val combined = matchesTerms(line ?: "")
        return if (exclude) !combined else combined
    }

    private fun matchesTerms(line: String): Boolean {
        var any = false
        var all = true
        for (i in terms.indices) {
            val matched = termMatches(i, line)
            any = any || matched
            all = all && matched
        }
        return if (mode == Mode.ALL) all else any
    }

    private fun termMatches(index: Int, line: String): Boolean {
        if (regex) {
            val pattern = patterns[index]
            return pattern != null && pattern.matcher(line).find()
        }
        val term = terms[index]
        return if (caseSensitive) {
            line.contains(term)
        } else {
            line.lowercase(Locale.ROOT).contains(term.lowercase(Locale.ROOT))
        }
    }

    companion object {
        private val P_ERROR = Pattern.compile("\\b(ERROR|ERR|FATAL|SEVERE)\\b")
        private val P_WARN = Pattern.compile("\\b(WARN|WARNING)\\b")
        private val P_INFO = Pattern.compile("\\bINFO\\b")
        private val P_DEBUG = Pattern.compile("\\bDEBUG\\b")
        private val P_TRACE = Pattern.compile("\\bTRACE\\b")

        /** The identity filter: every line of every app passes. */
        @JvmStatic
        fun all(): LogFilter = LogFilter(null, emptyList(), Mode.ANY, false, false, false, null)

        /**
         * Convenience factory. In substring mode [rawTerms] is split on commas into several terms
         * (combined by [mode]); in regex mode the whole string is a single pattern, since a regex
         * quantifier like `\d{3,}` legitimately contains a comma.
         */
        @JvmStatic
        fun from(
            app: String?,
            rawTerms: String?,
            mode: Mode?,
            exclude: Boolean,
            caseSensitive: Boolean,
            regex: Boolean,
            minLevel: Level?,
        ): LogFilter {
            val terms: List<String> = if (regex) {
                val single = rawTerms?.trim() ?: ""
                if (single.isEmpty()) emptyList() else listOf(single)
            } else {
                splitTerms(rawTerms)
            }
            return LogFilter(app, terms, mode, exclude, caseSensitive, regex, minLevel)
        }

        /** Splits a comma-separated filter string into trimmed, non-empty terms. */
        @JvmStatic
        fun splitTerms(raw: String?): List<String> {
            val out = ArrayList<String>()
            if (raw == null) {
                return out
            }
            for (part in raw.split(",")) {
                val term = part.trim()
                if (term.isNotEmpty()) {
                    out.add(term)
                }
            }
            return out
        }

        /** Best-effort log level of a line from common level tokens, or null when none is found. */
        @JvmStatic
        fun detectLevel(line: String?): Level? {
            if (line == null) {
                return null
            }
            val upper = line.uppercase(Locale.ROOT)
            return when {
                P_ERROR.matcher(upper).find() -> Level.ERROR
                P_WARN.matcher(upper).find() -> Level.WARN
                P_INFO.matcher(upper).find() -> Level.INFO
                P_DEBUG.matcher(upper).find() -> Level.DEBUG
                P_TRACE.matcher(upper).find() -> Level.TRACE
                else -> null
            }
        }
    }
}

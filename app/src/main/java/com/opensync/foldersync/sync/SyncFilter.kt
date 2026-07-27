package com.opensync.foldersync.sync

/**
 * Include/exclude matching using simple comma-separated glob patterns (`*`, `?`),
 * matched against file or directory names case-insensitively.
 */
class SyncFilter(includeCsv: String, excludeCsv: String) {

    private val includes = parse(includeCsv)
    private val excludes = parse(excludeCsv)

    fun acceptsFile(name: String): Boolean {
        if (excludes.any { it.matches(name) }) return false
        if (includes.isEmpty()) return true
        return includes.any { it.matches(name) }
    }

    fun excludesDir(name: String): Boolean = excludes.any { it.matches(name) }

    private fun parse(csv: String): List<Regex> =
        csv.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { globToRegex(it) }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("(?i)^")
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}

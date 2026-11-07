package com.t3tools.t3code.wear.domain

private val FENCE = Regex("""^\s*```.*$""")
private val HEADING = Regex("""^#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[-*+]\s+""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")
private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
private val ABSOLUTE_PATH = Regex("""(?<![\w.])/(?:[^/\s`'"]+/)+([^/\s`'"]+)""")

/**
 * Markdown reads fine as plain text on a watch once the markers that only style it are gone.
 * Code stays as its text, tables become `a · b` rows, and links keep their label.
 */
fun watchText(markdown: String): String =
    markdown.lineSequence()
        .filterNot { FENCE.matches(it) || TABLE_SEPARATOR.matches(it) }
        .map { line ->
            val trimmed = line.trim()
            val row = if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 1) {
                trimmed.trim('|').split('|').joinToString(" · ") { it.trim() }
            } else {
                line
            }
            row.replace(HEADING, "")
                .replace(BULLET, "$1• ")
                .replace(LINK, "$1")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** `/Users/me/project/src/login.ts` reads as `login.ts` in a one-line label. */
fun shortenPaths(text: String): String = ABSOLUTE_PATH.replace(text, "$1")

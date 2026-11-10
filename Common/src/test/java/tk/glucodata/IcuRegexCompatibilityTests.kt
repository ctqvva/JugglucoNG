package tk.glucodata

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's Pattern is ICU-backed and stricter than the JVM's: a bare `}` that
 * is not closing a `{n,m}` quantifier is a PatternSyntaxException on Android 16
 * even though java.util.regex accepts it. Unit tests run on the JVM, so the
 * only way to catch that on CI is to inspect the pattern literals themselves.
 * Regression guard for the 1.2.0 OutboundApi.<clinit> force-close.
 */
class IcuRegexCompatibilityTests {

    private val literalRegex = Regex(
        """(?:Regex\(|Pattern\.compile\(|\.toRegex\()\s*("(?:[^"\\]|\\.)*"|\"\"\"[\s\S]*?\"\"\")"""
    )

    @Test
    fun regexLiteralsHaveNoBareClosingBrace() {
        val roots = listOf("main", "mobile", "wear").map { File("src/$it/java") }.filter { it.isDirectory }
        assertTrue("no source roots found from ${File(".").absolutePath}", roots.isNotEmpty())
        val offenders = mutableListOf<String>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val text = file.readText()
                    literalRegex.findAll(text).forEach { match ->
                        val pattern = unquote(match.groupValues[1])
                        if (hasBareClosingBrace(pattern)) {
                            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                            offenders += "${file.path}:$line  $pattern"
                        }
                    }
                }
        }
        assertTrue("bare `}` in regex literal (ICU rejects it):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun unquote(literal: String): String {
        if (literal.startsWith("\"\"\"")) return literal.removeSurrounding("\"\"\"")
        val body = literal.removeSurrounding("\"")
        // Collapse Kotlin/Java string escapes so `\\{` becomes the regex text `\{`.
        val marker = ''
        return body.replace("\\\\", marker.toString()).replace("\\\"", "\"").replace(marker, '\\')
    }

    private val quantifier = Regex("""\{\d+(,\d*)?\}""")

    private fun hasBareClosingBrace(pattern: String): Boolean {
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                // Kotlin `${...}` template: not regex text, skip to the matching brace.
                c == '$' && pattern.getOrNull(i + 1) == '{' -> {
                    var depth = 0
                    var j = i + 1
                    while (j < pattern.length) {
                        if (pattern[j] == '{') depth++
                        if (pattern[j] == '}' && --depth == 0) break
                        j++
                    }
                    i = j + 1
                    continue
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val q = quantifier.matchAt(pattern, i)
                    if (q != null) { i = q.range.last + 1; continue }
                }
                c == '}' -> return true
            }
            i++
        }
        return false
    }
}

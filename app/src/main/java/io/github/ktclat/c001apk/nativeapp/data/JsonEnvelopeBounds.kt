// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

/** Linear lexical preflight, not a JSON parser. Quoted/escaped delimiters are ignored.
 * The authoritative parser still validates grammar. Transport independently caps bytes at 4 MiB.
 */
internal fun boundedJsonEnvelope(source: String, maxDepth: Int = 64, maxNodes: Int = 20_000): Boolean {
    var depth = 0
    var nodes = 0
    var quoted = false
    var escaped = false
    var primitive = false
    for (c in source) {
        if (quoted) {
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') quoted = false
            continue
        }
        when (c) {
            '"' -> { quoted = true; primitive = false; nodes++ }
            '{', '[' -> { depth++; nodes++; primitive = false; if (depth > maxDepth) return false }
            '}', ']' -> { depth--; primitive = false; if (depth < 0) return false }
            ',', ':', ' ', '\t', '\r', '\n' -> primitive = false
            else -> if (!primitive) { nodes++; primitive = true }
        }
        if (nodes > maxNodes) return false
    }
    return depth == 0 && !quoted
}

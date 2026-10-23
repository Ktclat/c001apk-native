// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

/** Linear lexical preflight, not a grammar parser. Transport separately caps response bytes. */
internal enum class JsonEnvelopeStatus { WITHIN_LIMITS, MALFORMED, LIMIT_EXCEEDED }
internal fun inspectJsonEnvelope(source: String, maxDepth: Int = 64, maxNodes: Int = 20_000,
    checkActive: () -> Unit = {}): JsonEnvelopeStatus {
    var depth = 0; var nodes = 0; var quoted = false; var escaped = false; var primitive = false
    for ((index, c) in source.withIndex()) {
        if (index % 1024 == 0) checkActive()
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            continue
        }
        when (c) {
            '"' -> { quoted = true; primitive = false; nodes++ }
            '{', '[' -> { depth++; nodes++; primitive = false; if (depth > maxDepth) return JsonEnvelopeStatus.LIMIT_EXCEEDED }
            '}', ']' -> { depth--; primitive = false; if (depth < 0) return JsonEnvelopeStatus.MALFORMED }
            ',', ':', ' ', '\t', '\r', '\n' -> primitive = false
            else -> if (!primitive) { nodes++; primitive = true }
        }
        if (nodes > maxNodes) return JsonEnvelopeStatus.LIMIT_EXCEEDED
    }
    checkActive()
    return if (depth == 0 && !quoted) JsonEnvelopeStatus.WITHIN_LIMITS else JsonEnvelopeStatus.MALFORMED
}
internal fun boundedJsonEnvelope(source: String, maxDepth: Int = 64, maxNodes: Int = 20_000,
    checkActive: () -> Unit = {}): Boolean = inspectJsonEnvelope(source, maxDepth, maxNodes, checkActive) == JsonEnvelopeStatus.WITHIN_LIMITS

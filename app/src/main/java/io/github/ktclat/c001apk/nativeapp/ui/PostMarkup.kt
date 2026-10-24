package io.github.ktclat.c001apk.nativeapp.ui

/** Source-compatible paragraph/limited Markdown normalization. Call only on bounded display input. */
internal fun normalizePostMarkup(text: String): String = text
    .replace("\r\n", "\n").replace("\r", "\n")
    .replace(Regex("\n(?:[ \t]*\n){2,}"), "\n\n")
    .replace(Regex("\\*\\*([^*<>\n]+)\\*\\*")) { "<strong>${it.groupValues[1]}</strong>" }
    .replace("\n", "<br/>")

package com.bivouac.app.ui.journal

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

// Purely cosmetic: the underlying stored text is untouched plain text, never Markdown. A line
// starting with "-" (any amount of whitespace before or after the dash, including none) displays
// as " • " instead, so "-Texte", "- Texte" and "  -   Texte" all render identically. Unlike a
// one-to-one char swap, normalizing the whitespace changes the visible length, so this needs a
// real OffsetMapping rather than Identity.
object BulletVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val original = text.text
        val matches = findBulletPrefixes(original)
        if (matches.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val builder = StringBuilder()
        val transformedStarts = IntArray(matches.size)
        var cursor = 0
        matches.forEachIndexed { i, match ->
            builder.append(original, cursor, match.first)
            transformedStarts[i] = builder.length
            builder.append(BULLET_REPLACEMENT)
            cursor = match.last + 1
        }
        builder.append(original, cursor, original.length)

        val mapping = BulletOffsetMapping(matches, transformedStarts)
        return TransformedText(AnnotatedString(builder.toString()), mapping)
    }
}

private const val BULLET_REPLACEMENT = " • "

private class BulletOffsetMapping(
    private val originalMatches: List<IntRange>,
    private val transformedStarts: IntArray,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        var delta = 0
        for (i in originalMatches.indices) {
            val match = originalMatches[i]
            if (offset <= match.first) return offset + delta
            if (offset <= match.last) return transformedStarts[i] + BULLET_REPLACEMENT.length
            delta += BULLET_REPLACEMENT.length - match.length
        }
        return offset + delta
    }

    override fun transformedToOriginal(offset: Int): Int {
        var delta = 0
        for (i in originalMatches.indices) {
            val match = originalMatches[i]
            val replacementEnd = transformedStarts[i] + BULLET_REPLACEMENT.length
            if (offset <= transformedStarts[i]) return offset - delta
            if (offset < replacementEnd) return match.last + 1
            delta += BULLET_REPLACEMENT.length - match.length
        }
        return offset - delta
    }
}

private val IntRange.length: Int get() = last - first + 1

// For each line, the span from the line start through any leading whitespace, the "-", and any
// whitespace right after it: this whole span gets collapsed into a single " • ".
private fun findBulletPrefixes(text: String): List<IntRange> {
    val matches = mutableListOf<IntRange>()
    var lineStart = 0
    while (lineStart <= text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
        var i = lineStart
        while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
        if (i < lineEnd && text[i] == '-') {
            i++
            while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
            matches.add(lineStart until i)
        }
        lineStart = lineEnd + 1
    }
    return matches
}

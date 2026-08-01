/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

/**
 * Parses and applies user-defined voice shortcuts (e.g. saying "my email" to have it expanded
 * to a full email address) to transcribed text.
 */
object TextShortcuts {
    data class Shortcut(val trigger: String, val replacement: String)

    // One shortcut per line, formatted as "trigger=replacement". Blank lines and lines without
    // a trigger before the separator are ignored.
    fun parse(raw: String): List<Shortcut> {
        return raw.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                val separatorIndex = trimmed.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null
                val trigger = trimmed.substring(0, separatorIndex).trim()
                val replacement = trimmed.substring(separatorIndex + 1).trim()
                if (trigger.isEmpty()) null else Shortcut(trigger, replacement)
            }
            .toList()
    }

    fun apply(text: String, shortcuts: List<Shortcut>): String {
        var result = text
        for (shortcut in shortcuts) {
            result = replacePhrase(result, shortcut.trigger, shortcut.replacement)
        }
        return result
    }

    // Replaces whole-phrase occurrences of `trigger` in `text`, ignoring case. Matches are only
    // accepted at letter/digit boundaries so that a trigger like "hi" doesn't match inside
    // "this" - a plain String.replace() would do that. Character.isLetterOrDigit() is
    // Unicode-aware, so this also works correctly for Hebrew phrases.
    private fun replacePhrase(text: String, trigger: String, replacement: String): String {
        if (trigger.isEmpty()) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val afterIndex = i + trigger.length
            if (afterIndex <= text.length &&
                text.regionMatches(i, trigger, 0, trigger.length, ignoreCase = true)
            ) {
                val beforeOk = i == 0 || !Character.isLetterOrDigit(text[i - 1])
                val afterOk = afterIndex == text.length || !Character.isLetterOrDigit(text[afterIndex])
                if (beforeOk && afterOk) {
                    sb.append(replacement)
                    i = afterIndex
                    continue
                }
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }
}

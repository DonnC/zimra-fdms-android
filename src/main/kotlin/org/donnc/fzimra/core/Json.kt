package org.donnc.fzimra.core

/**
 * A minimal JSON model, writer and parser for fzimra's own stored data.
 *
 * Numbers keep their exact text; amounts are stored as strings so that they
 * never pass through floating point.
 */
internal sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val text: String) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
}

internal object Json {
    private val NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$")

    fun write(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.entries.forEachIndexed { i, (key, field) ->
                    if (i > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(field, out)
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> out.append(value.text)
            is JsonValue.Bool -> out.append(value.value)
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** @throws FiscalStoreException if [text] is not valid JSON. */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipSpace()
        if (!parser.atEnd()) parser.fail("unexpected text after the value")
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun fail(message: String): Nothing = throw FiscalStoreException("stored data is not valid JSON at $pos: $message")

        fun skipSpace() {
            while (!atEnd() && text[pos] in " \t\r\n") pos++
        }

        fun value(): JsonValue {
            skipSpace()
            if (atEnd()) fail("unexpected end")
            return when (text[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(string())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun number(): JsonValue {
            val start = pos
            while (!atEnd() && text[pos] in "+-0123456789.eE") pos++
            val token = text.substring(start, pos)
            if (!NUMBER.matches(token)) fail("invalid number '$token'")
            return JsonValue.Num(token)
        }

        private fun obj(): JsonValue {
            pos++
            val fields = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipSpace()
                if (atEnd() || text[pos] != '"') fail("expected a key")
                val key = string()
                skipSpace()
                if (atEnd() || text[pos] != ':') fail("expected ':'")
                pos++
                fields[key] = value()
                skipSpace()
                if (atEnd()) fail("unexpected end in object")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(fields)
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(): JsonValue {
            pos++
            val items = mutableListOf<JsonValue>()
            skipSpace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += value()
                skipSpace()
                if (atEnd()) fail("unexpected end in array")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) fail("short unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail("invalid unicode escape"))
                                pos += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }
    }
}

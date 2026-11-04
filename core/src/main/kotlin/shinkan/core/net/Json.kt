package shinkan.core.net

/**
 * A small JSON reader, for the one answer core reads (SPEC §7.5): objects as maps,
 * arrays as lists, numbers as doubles. Fails with [Malformed]. Core takes no library.
 */
internal object Json {
    class Malformed(why: String) : IllegalArgumentException(why)

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.space()
        if (!reader.done) throw Malformed("text after the value")
        return value
    }

    private class Reader(private val s: String) {
        private var i = 0
        val done get() = i >= s.length

        fun space() {
            while (!done && s[i] in " \t\r\n") i++
        }

        private fun peek(): Char = if (done) throw Malformed("ends early") else s[i]

        private fun expect(c: Char) {
            if (peek() != c) throw Malformed("expected $c at $i")
            i++
        }

        fun value(): Any? {
            space()
            return when (peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> number()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw Malformed("bad word at $i")
            i += w.length
            return v
        }

        private fun number(): Double {
            val from = i
            while (!done && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(from, i).toDoubleOrNull() ?: throw Malformed("bad number at $from")
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek()
                i++
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escape())
                    else -> out.append(c)
                }
            }
        }

        private fun escape(): Char {
            val c = peek()
            i++
            return when (c) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000C'
                'u' -> {
                    if (i + 4 > s.length) throw Malformed("ends early")
                    val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw Malformed("bad escape at $i")
                    i += 4
                    code.toChar()
                }
                else -> c
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val out = mutableListOf<Any?>()
            space()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                space()
                if (peek() == ']') {
                    i++
                    return out
                }
                expect(',')
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = mutableMapOf<String, Any?>()
            space()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                space()
                val key = string()
                space()
                expect(':')
                out[key] = value()
                space()
                if (peek() == '}') {
                    i++
                    return out
                }
                expect(',')
            }
        }
    }
}

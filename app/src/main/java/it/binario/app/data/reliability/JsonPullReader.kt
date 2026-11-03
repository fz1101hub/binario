package it.binario.app.data.reliability

import java.io.IOException
import java.io.InputStream
import java.io.Reader

/** Malformed or out-of-bounds JSON: the reader stops at the first problem. */
internal class JsonFormatException(message: String) : IOException(message)

/**
 * A small pull reader for the JSON of the embedded statistics file, so that a file of several megabytes is read
 * value by value instead of as one object tree (`android.util.JsonReader` does not run in the JVM unit tests). It
 * accepts RFC 8259 JSON only, with bounded nesting and string length, and fails with [JsonFormatException].
 */
internal class JsonPullReader(
    private val reader: Reader,
    private val maxStringLength: Int = 256,
    maxDepth: Int = 16
) {
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    private val buffer = CharArray(16 * 1024)
    private var position = 0
    private var limit = 0
    private val stack = IntArray(maxDepth + 1).also { it[0] = EMPTY_DOCUMENT }
    private var depth = 1
    private var peeked: Token? = null
    private var peekedText: String? = null

    fun peek(): Token {
        peeked?.let { return it }
        val token = when (stack[depth - 1]) {
            EMPTY_DOCUMENT -> {
                stack[depth - 1] = NONEMPTY_DOCUMENT
                value(nextNonWhitespace())
            }
            NONEMPTY_DOCUMENT -> if (nextNonWhitespace() == -1) Token.END_DOCUMENT else fail("data after the end of the document")
            EMPTY_ARRAY -> {
                stack[depth - 1] = NONEMPTY_ARRAY
                val c = nextNonWhitespace()
                if (c == ']'.code) Token.END_ARRAY else value(c)
            }
            NONEMPTY_ARRAY -> when (nextNonWhitespace()) {
                ']'.code -> Token.END_ARRAY
                ','.code -> value(nextNonWhitespace())
                else -> fail("',' or ']' expected")
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                var c = nextNonWhitespace()
                if (c == '}'.code) {
                    Token.END_OBJECT
                } else {
                    if (stack[depth - 1] == NONEMPTY_OBJECT) {
                        if (c != ','.code) fail("',' or '}' expected")
                        c = nextNonWhitespace()
                    }
                    if (c != '"'.code) fail("name expected")
                    peekedText = string()
                    stack[depth - 1] = DANGLING_NAME
                    Token.NAME
                }
            }
            DANGLING_NAME -> {
                if (nextNonWhitespace() != ':'.code) fail("':' expected")
                stack[depth - 1] = NONEMPTY_OBJECT
                value(nextNonWhitespace())
            }
            else -> fail("invalid state")
        }
        peeked = token
        return token
    }

    fun hasNext(): Boolean = peek().let { it != Token.END_OBJECT && it != Token.END_ARRAY && it != Token.END_DOCUMENT }

    fun beginObject() {
        consume(Token.BEGIN_OBJECT)
        push(EMPTY_OBJECT)
    }

    fun endObject() {
        consume(Token.END_OBJECT)
        depth--
    }

    fun beginArray() {
        consume(Token.BEGIN_ARRAY)
        push(EMPTY_ARRAY)
    }

    fun endArray() {
        consume(Token.END_ARRAY)
        depth--
    }

    fun nextName(): String = text(Token.NAME)

    fun nextString(): String = text(Token.STRING)

    fun nextDouble(): Double = text(Token.NUMBER).toDouble()

    /** An integral number in the Int range ("27", also "27.0"); anything else fails. */
    fun nextInt(): Int {
        val raw = text(Token.NUMBER)
        raw.toIntOrNull()?.let { return it }
        val value = raw.toDouble()
        if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) fail("integer expected")
        return value.toInt()
    }

    /** The string value, or null after skipping a value of another type. */
    fun optString(): String? = if (peek() == Token.STRING) nextString() else null.also { skipValue() }

    fun optInt(): Int? = if (peek() == Token.NUMBER) runCatching { nextInt() }.getOrNull() else null.also { skipValue() }

    fun optDouble(): Double? = if (peek() == Token.NUMBER) nextDouble() else null.also { skipValue() }

    /** Skips the next value, with everything nested in it. */
    fun skipValue() {
        var level = 0
        do {
            when (peek()) {
                Token.BEGIN_OBJECT -> { beginObject(); level++ }
                Token.BEGIN_ARRAY -> { beginArray(); level++ }
                Token.END_OBJECT -> { endObject(); level-- }
                Token.END_ARRAY -> { endArray(); level-- }
                Token.END_DOCUMENT -> fail("unexpected end of the document")
                else -> peeked = null
            }
        } while (level > 0)
    }

    private fun consume(expected: Token) {
        if (peek() != expected) fail("$expected expected, found $peeked")
        peeked = null
    }

    private fun text(expected: Token): String {
        consume(expected)
        return peekedText ?: fail("missing value")
    }

    private fun push(scope: Int) {
        if (depth == stack.size) fail("nesting too deep")
        stack[depth++] = scope
    }

    private fun value(c: Int): Token = when (c) {
        '{'.code -> Token.BEGIN_OBJECT
        '['.code -> Token.BEGIN_ARRAY
        '"'.code -> { peekedText = string(); Token.STRING }
        't'.code -> { literal("rue"); peekedText = "true"; Token.BOOLEAN }
        'f'.code -> { literal("alse"); peekedText = "false"; Token.BOOLEAN }
        'n'.code -> { literal("ull"); peekedText = null; Token.NULL }
        -1 -> fail("unexpected end of the document")
        else -> if (c == '-'.code || c in '0'.code..'9'.code) {
            peekedText = number(c)
            Token.NUMBER
        } else fail("unexpected character")
    }

    private fun literal(rest: String) {
        for (expected in rest) if (read() != expected.code) fail("invalid literal")
    }

    private fun number(first: Int): String {
        val text = StringBuilder().append(first.toChar())
        while (true) {
            val c = read()
            if (c == -1) break
            if (c in '0'.code..'9'.code || c == '.'.code || c == 'e'.code || c == 'E'.code || c == '+'.code || c == '-'.code) {
                text.append(c.toChar())
                if (text.length > MAX_NUMBER_LENGTH) fail("number too long")
            } else {
                position--
                break
            }
        }
        val value = text.toString()
        if (!NUMBER.matches(value)) fail("invalid number")
        return value
    }

    private fun string(): String {
        val text = StringBuilder()
        while (true) {
            val c = read()
            when {
                c == -1 -> fail("unterminated string")
                c == '"'.code -> return text.toString()
                c == '\\'.code -> text.append(escape())
                c < 0x20 -> fail("control character in a string")
                else -> text.append(c.toChar())
            }
            if (text.length > maxStringLength) fail("string too long")
        }
    }

    private fun escape(): Char = when (read()) {
        '"'.code -> '"'
        '\\'.code -> '\\'
        '/'.code -> '/'
        'b'.code -> '\b'
        'f'.code -> '\u000C'
        'n'.code -> '\n'
        'r'.code -> '\r'
        't'.code -> '\t'
        'u'.code -> {
            var value = 0
            repeat(4) {
                val c = read()
                val digit = if (c == -1) -1 else Character.digit(c, 16)
                if (digit < 0) fail("invalid unicode escape")
                value = value * 16 + digit
            }
            value.toChar()
        }
        else -> fail("invalid escape")
    }

    private fun nextNonWhitespace(): Int {
        while (true) {
            val c = read()
            if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
        }
    }

    private fun read(): Int {
        if (position == limit) {
            val count = reader.read(buffer, 0, buffer.size)
            position = 0
            limit = if (count > 0) count else 0
            if (limit == 0) return -1
        }
        return buffer[position++].code
    }

    private fun fail(message: String): Nothing = throw JsonFormatException(message)

    private companion object {
        const val EMPTY_DOCUMENT = 0
        const val NONEMPTY_DOCUMENT = 1
        const val EMPTY_ARRAY = 2
        const val NONEMPTY_ARRAY = 3
        const val EMPTY_OBJECT = 4
        const val DANGLING_NAME = 5
        const val NONEMPTY_OBJECT = 6
        const val MAX_NUMBER_LENGTH = 32
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

/** At most [maxBytes] bytes of [input]; reading past them fails, so a gzip bomb or an oversized file stops early. */
internal class BoundedInputStream(private val input: InputStream, private val maxBytes: Long) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val value = input.read()
        if (value >= 0) checkCount(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = input.read(b, off, len)
        if (read > 0) checkCount(read.toLong())
        return read
    }

    override fun close() = input.close()

    private fun checkCount(added: Long) {
        count += added
        if (count > maxBytes) throw JsonFormatException("more than $maxBytes bytes")
    }
}

package com.aceinandroid.inkflow.core.dictionary

import java.io.Reader

/** Streaming top-level map: a whole Chinese shard never needs to be materialized in memory. */
internal class DictionaryJson(private val reader: Reader) {
    private var ch = reader.read()
    private var count = 0

    fun entries(consume: (String, Any?) -> Unit) {
        whitespace()
        expect('{')
        whitespace()
        if (ch != '}'.code) while (true) {
            val key = string()
            whitespace(); expect(':')
            consume(key, value(0))
            whitespace()
            if (ch == '}'.code) break
            expect(','); whitespace()
        }
        expect('}'); whitespace()
        require(ch == -1) { "Trailing dictionary data" }
    }

    private fun value(depth: Int): Any? {
        require(depth <= 12) { "Dictionary JSON nesting limit exceeded" }
        whitespace()
        return when (ch) {
            '"'.code -> string()
            '{'.code -> {
                next(); whitespace()
                val out = linkedMapOf<String, Any?>()
                if (ch != '}'.code) while (true) {
                    require(out.size < 256) { "Dictionary object is too large" }
                    val key = string(); whitespace(); expect(':')
                    require(!out.containsKey(key)) { "Duplicate dictionary field" }
                    out[key] = value(depth + 1); whitespace()
                    if (ch == '}'.code) break
                    expect(','); whitespace()
                }
                expect('}'); out
            }
            '['.code -> {
                next(); whitespace()
                val out = mutableListOf<Any?>()
                if (ch != ']'.code) while (true) {
                    require(out.size < 256) { "Too many dictionary senses" }
                    out += value(depth + 1); whitespace()
                    if (ch == ']'.code) break
                    expect(','); whitespace()
                }
                expect(']'); out
            }
            'n'.code -> { "null".forEach(::expect); null }
            else -> error("Expected dictionary text, object, or array")
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (ch != '"'.code) {
            require(ch >= 32) { "Invalid dictionary JSON string" }
            if (ch == '\\'.code) {
                next()
                out.append(when (ch.toChar()) {
                    '"', '\\', '/' -> ch.toChar().also { next() }
                    'b' -> '\b'.also { next() }
                    'f' -> '\u000c'.also { next() }
                    'n' -> '\n'.also { next() }
                    'r' -> '\r'.also { next() }
                    't' -> '\t'.also { next() }
                    'u' -> {
                        var code = 0
                        repeat(4) {
                            next()
                            val digit = ch.toChar().digitToIntOrNull(16)
                            require(digit != null) { "Invalid unicode escape" }
                            code = code * 16 + digit
                        }
                        next(); code.toChar()
                    }
                    else -> error("Invalid dictionary JSON escape")
                })
            } else { out.append(ch.toChar()); next() }
            require(out.length <= 16000) { "Dictionary string is too long" }
        }
        expect('"')
        return out.toString()
    }

    private fun whitespace() { while (ch == 32 || ch == 9 || ch == 10 || ch == 13) next() }
    private fun expect(expected: Char) {
        require(ch == expected.code) { "Invalid dictionary JSON" }
        next()
    }
    private fun next() {
        require(++count <= 32 * 1024 * 1024) { "Dictionary JSON is too large" }
        ch = reader.read()
    }
}

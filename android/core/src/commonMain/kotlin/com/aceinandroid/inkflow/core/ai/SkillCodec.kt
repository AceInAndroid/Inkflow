package com.aceinandroid.inkflow.core.ai

/** Dependency-free, bounded interchange shared by file, paste, backup and future WiFi import. */
object SkillCodec {
    const val SCHEMA_VERSION = 2
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_INSTRUCTIONS = 16_000
    const val MAX_SKILLS = 2048
    const val MAX_STORED_BYTES = 2 * 1024 * 1024
    private val fields = setOf("schemaVersion", "version", "id", "title", "summary", "instructions",
        "category", "recommendedAgent", "quickPrompts", "outputFormat")
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

    fun validate(skill: ReadingSkill) {
        require(skill.schemaVersion in 1..SCHEMA_VERSION) { "Unsupported skill schema version" }
        require(skill.id.length in 1..100 && skill.id.all { it.isLetterOrDigit() || it in "._-" }) { "Invalid skill ID" }
        require(skill.title.isNotBlank()) { "Invalid skill title" }
        require(skill.instructions.length <= MAX_INSTRUCTIONS) { "Skill instructions exceed 16000 characters" }
        require(identifier.matches(skill.category)) { "Invalid skill category" }
        require(skill.recommendedAgent == null || identifier.matches(skill.recommendedAgent)) { "Invalid recommended agent" }
        require(skill.quickPrompts.size <= 12 && skill.quickPrompts.all { it.isNotBlank() && it.length <= 1000 }) { "Invalid quick prompts" }
        require(skill.outputFormat in setOf("markdown", "text", "json")) { "Unsupported output format" }
        (listOf(skill.title, skill.summary, skill.instructions) + skill.quickPrompts).forEach {
            require(it.none { c -> c.code < 32 && c !in "\n\r\t" }) { "Control characters are not allowed" }
            it.encodeToByteArray(throwOnInvalidSequence = true)
        }
    }

    fun decode(bytes: ByteArray): List<ReadingSkill> {
        require(bytes.size <= MAX_FILE_BYTES) { "Skill file exceeds 64 KiB" }
        return decode(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    fun decode(text: String): List<ReadingSkill> {
        checkSize(text, MAX_FILE_BYTES)
        val source = text.removePrefix("\uFEFF").trimStart()
        return if (source.startsWith("---")) listOf(markdown(source)) else json(source, allowLegacyArray = false)
    }

    /** Existing preference arrays are storage, never accepted as an unversioned interchange file. */
    fun decodeStored(text: String): List<ReadingSkill> {
        checkSize(text, MAX_STORED_BYTES)
        return json(text, allowLegacyArray = true, allowEmpty = true).also { ReadingSkillRegistry(it) }
    }

    fun encodeJson(skill: ReadingSkill): String = encodeJson(listOf(skill), collection = false)
    fun encodeJson(skills: List<ReadingSkill>, collection: Boolean = true): String {
        validateList(skills)
        val result = if (collection) "{\"schemaVersion\":$SCHEMA_VERSION,\"skills\":[${skills.joinToString(",", transform = ::skillJson)}]}"
            else { require(skills.size == 1); skillJson(skills.single()) }
        checkSize(result, MAX_FILE_BYTES)
        return result
    }

    fun encodeStored(skills: List<ReadingSkill>): String {
        ReadingSkillRegistry(skills)
        return "{\"schemaVersion\":$SCHEMA_VERSION,\"skills\":[${skills.joinToString(",", transform = ::skillJson)}]}"
            .also { checkSize(it, MAX_STORED_BYTES) }
    }

    fun encodeMarkdown(skill: ReadingSkill): String {
        validate(skill)
        return buildString {
            appendLine("---")
            appendLine("schemaVersion: $SCHEMA_VERSION")
            appendLine("id: ${quote(skill.id)}")
            appendLine("title: ${quote(skill.title)}")
            appendLine("summary: ${quote(skill.summary)}")
            appendLine("category: ${quote(skill.category)}")
            skill.recommendedAgent?.let { appendLine("recommendedAgent: ${quote(it)}") }
            appendLine("outputFormat: ${quote(skill.outputFormat)}")
            appendLine("quickPrompts: [${skill.quickPrompts.joinToString(", ", transform = ::quote)}]")
            appendLine("---")
            append(skill.instructions)
        }.also { checkSize(it, MAX_FILE_BYTES) }
    }

    private fun checkSize(text: String, limit: Int) {
        require(text.length <= limit && text.encodeToByteArray(throwOnInvalidSequence = true).size <= limit) {
            if (limit == MAX_FILE_BYTES) "Skill file exceeds 64 KiB" else "Skill storage is too large"
        }
    }

    private fun validateList(skills: List<ReadingSkill>, allowEmpty: Boolean = false) {
        require(skills.size <= MAX_SKILLS && (allowEmpty || skills.isNotEmpty())) { "Invalid skill count" }
        skills.forEach(::validate)
        require(skills.map { it.id }.distinct().size == skills.size) { "Duplicate skill IDs" }
    }

    private fun json(text: String, allowLegacyArray: Boolean, allowEmpty: Boolean = false): List<ReadingSkill> {
        val value = SkillJsonParser(text).parse()
        val list = when {
            value is Map<*, *> && "skills" in value -> {
                require(value.keys.all { it in setOf("schemaVersion", "version", "skills") }) { "Unknown collection field" }
                version(value, required = true)
                value["skills"] as? List<*> ?: throw IllegalArgumentException("Expected skills array")
            }
            value is Map<*, *> -> listOf(value)
            allowLegacyArray && value is List<*> -> value
            else -> throw IllegalArgumentException("Expected a skill object or versioned collection")
        }
        require(list.size <= MAX_SKILLS) { "Too many skills" }
        return list.map { fromObject(it as? Map<*, *> ?: throw IllegalArgumentException("Expected skill object")) }
            .also { validateList(it, allowEmpty) }
    }

    private fun version(value: Map<*, *>, required: Boolean = false): Int {
        require(!("version" in value && "schemaVersion" in value)) { "Ambiguous schema version" }
        val raw = value["schemaVersion"] ?: value["version"]
        if (raw == null) {
            require(!required && "schemaVersion" !in value && "version" !in value) { "Missing schema version" }
            return 1
        }
        require(raw is Int && raw in 1..SCHEMA_VERSION) { "Unsupported skill schema version" }
        return raw
    }

    private fun fromObject(value: Map<*, *>): ReadingSkill {
        require(value.keys.all { it in fields }) { "Unknown skill field" }
        fun string(key: String, default: String? = null): String =
            if (key !in value && default != null) default else value[key] as? String
                ?: throw IllegalArgumentException("Expected string: $key")
        val prompts = if ("quickPrompts" !in value) emptyList() else {
            val list = value["quickPrompts"] as? List<*> ?: throw IllegalArgumentException("Expected quick prompts list")
            list.map { it as? String ?: throw IllegalArgumentException("Expected quick prompt string") }
        }
        return ReadingSkill(string("id"), string("title"), string("summary", ""), string("instructions"),
            version(value), string("category", "general"),
            if (value["recommendedAgent"] == null) null else string("recommendedAgent"),
            prompts, string("outputFormat", "markdown")).also(::validate)
    }

    private fun skillJson(skill: ReadingSkill): String = buildString {
        append("{\"schemaVersion\":$SCHEMA_VERSION,\"id\":${quote(skill.id)},\"title\":${quote(skill.title)},")
        append("\"summary\":${quote(skill.summary)},\"instructions\":${quote(skill.instructions)},")
        append("\"category\":${quote(skill.category)},\"recommendedAgent\":${skill.recommendedAgent?.let(::quote) ?: "null"},")
        append("\"quickPrompts\":[${skill.quickPrompts.joinToString(",", transform = ::quote)}],\"outputFormat\":${quote(skill.outputFormat)}}")
    }

    internal fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
        } }
        append('"')
    }

    /** Deliberately not a YAML loader: no tags, aliases, anchors, objects, folding or directives. */
    private fun markdown(text: String): ReadingSkill {
        val lines = text.replace("\r\n", "\n").split('\n')
        require(lines.first() == "---") { "Expected Markdown frontmatter" }
        val end = (1 until lines.size).firstOrNull { lines[it] == "---" }
            ?: throw IllegalArgumentException("Unclosed frontmatter")
        val values = linkedMapOf<String, Any?>()
        var list: MutableList<String>? = null
        for (line in lines.subList(1, end)) {
            if (line.isBlank() || line.startsWith('#')) continue
            require('\t' !in line) { "Tabs are not supported in frontmatter" }
            if (line.startsWith("  - ")) {
                val active = list ?: throw IllegalArgumentException("Unexpected list item")
                active += scalar(line.substring(4).trim())
                continue
            }
            require(!line.first().isWhitespace()) { "Nested frontmatter is not supported" }
            val colon = line.indexOf(':')
            require(colon > 0) { "Expected scalar frontmatter field" }
            val key = line.substring(0, colon)
            require(key in fields && key != "instructions" && key !in values) { "Unknown or duplicate frontmatter field" }
            val raw = line.substring(colon + 1).trim()
            list = null
            when (key) {
                "schemaVersion", "version" -> values[key] = raw.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid schema version")
                "quickPrompts" -> {
                    val prompts = mutableListOf<String>()
                    if (raw.isNotEmpty()) {
                        require(raw.startsWith('[') && raw.endsWith(']')) { "Expected scalar list" }
                        prompts += scalarList(raw.substring(1, raw.length - 1))
                    } else list = prompts
                    values[key] = prompts
                }
                else -> values[key] = scalar(raw)
            }
        }
        values["instructions"] = lines.drop(end + 1).joinToString("\n")
        return fromObject(values)
    }

    private fun scalar(raw: String): String {
        if (raw.isEmpty()) return ""
        if (raw.startsWith('"')) return SkillJsonParser(raw).parse() as? String
            ?: throw IllegalArgumentException("Expected quoted scalar")
        if (raw.startsWith('\'')) {
            require(raw.length >= 2 && raw.endsWith('\'')) { "Unclosed quoted scalar" }
            val body = raw.substring(1, raw.length - 1)
            require('\'' !in body.replace("''", "")) { "Invalid single quoted scalar" }
            return body.replace("''", "'")
        }
        require(raw.none { it in "!&*{}[]|>`%\"'" } && !raw.startsWith('-') &&
            ": " !in raw && " #" !in raw && !raw.startsWith('#')) { "Unsupported frontmatter syntax; quote scalar text" }
        return raw
    }

    private fun scalarList(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        var start = 0; var quote: Char? = null; var escaped = false
        for (i in body.indices) {
            val c = body[i]
            if (escaped) { escaped = false; continue }
            if (quote == '"' && c == '\\') { escaped = true; continue }
            if (quote != null) { if (c == quote) quote = null }
            else if (c == '"' || c == '\'') quote = c
            else if (c == ',') { parts += scalar(body.substring(start, i).trim()); start = i + 1 }
        }
        require(quote == null && !escaped) { "Unclosed scalar list" }
        parts += scalar(body.substring(start).trim())
        return parts
    }
}

/** Strict JSON subset needed by the schema; rejects duplicates, trailing tokens and deep input. */
private class SkillJsonParser(private val source: String) {
    private var at = 0
    fun parse(): Any? = value(0).also { whitespace(); require(at == source.length) { "Trailing JSON content" } }
    private fun whitespace() { while (at < source.length && source[at] in " \t\r\n") at++ }
    private fun take(c: Char): Boolean { whitespace(); return if (at < source.length && source[at] == c) { at++; true } else false }
    private fun expect(c: Char) { require(take(c)) { "Expected '$c' in JSON" } }
    private fun value(depth: Int): Any? {
        require(depth <= 8) { "JSON nesting exceeds limit" }; whitespace()
        require(at < source.length) { "Unexpected JSON end" }
        return when (source[at]) {
            '"' -> string()
            '{' -> {
                at++; val map = linkedMapOf<String, Any?>()
                if (!take('}')) {
                    do {
                        whitespace(); require(at < source.length && source[at] == '"') { "Expected JSON key" }
                        val key = string(); require(key !in map) { "Duplicate JSON key" }; expect(':')
                        map[key] = value(depth + 1)
                    } while (take(','))
                    expect('}')
                }
                map
            }
            '[' -> {
                at++; val list = mutableListOf<Any?>()
                if (!take(']')) { do { list += value(depth + 1) } while (take(',')); expect(']') }
                list
            }
            'n' -> { require(source.substring(at).startsWith("null")) { "Invalid JSON literal" }; at += 4; null }
            in '0'..'9' -> {
                val start = at
                while (at < source.length && source[at] in '0'..'9') at++
                val raw = source.substring(start, at)
                require(raw.length == 1 || raw.first() != '0') { "Invalid JSON number" }
                raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid JSON integer")
            }
            else -> throw IllegalArgumentException("Invalid JSON value")
        }
    }
    private fun string(): String {
        expect('"'); val out = StringBuilder()
        while (at < source.length) {
            val c = source[at++]
            if (c == '"') return out.toString()
            require(c.code >= 32) { "Unescaped JSON control character" }
            if (c != '\\') out.append(c) else {
                require(at < source.length) { "Invalid JSON escape" }
                out.append(when (val escape = source[at++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> {
                        require(at + 4 <= source.length) { "Invalid unicode escape" }
                        val hex = source.substring(at, at + 4)
                        require(hex.all { it.digitToIntOrNull(16) != null }) { "Invalid unicode escape" }
                        at += 4; hex.toInt(16).toChar()
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape")
                })
            }
        }
        throw IllegalArgumentException("Unclosed JSON string")
    }
}

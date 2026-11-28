package com.aceinandroid.inkflow.core

/**
 * Immutable, local selection vocabulary. Entries are 2–16 Han code points; punctuation,
 * whitespace and other scripts are rejected, so a match cannot bridge a paragraph or symbol.
 * An injected instance replaces the default vocabulary for that call; no global state changes.
 */
class LocalChineseWordLexicon(terms: Collection<String>) {
    private class Node {
        val children = mutableMapOf<Int, Node>()
        var terminal = false
    }

    private val root = Node()
    private var maxLength = 0

    init {
        for (term in terms) {
            var index = 0
            var length = 0
            var node = root
            while (index < term.length) {
                val code = TextCharacterBoundaries.codePointAt(term, index)
                require(isHan(code)) { "Selection vocabulary accepts only Han characters" }
                length++
                require(length <= MAX_WORD_CHARACTERS) { "Selection vocabulary word is too long" }
                node = node.children.getOrPut(code) { Node() }
                index = TextCharacterBoundaries.nextCodePoint(term, index)
            }
            require(length >= 2) { "Selection vocabulary needs at least two Han characters" }
            node.terminal = true
            maxLength = maxOf(maxLength, length)
        }
    }

    /** Longest match containing the anchor; equal lengths choose the earliest UTF-16 start. */
    internal fun rangeAt(text: String, anchor: Int): TextSelectionRange? {
        var first = anchor
        repeat((maxLength - 1).coerceAtLeast(0)) {
            if (first > 0) {
                val previous = TextCharacterBoundaries.previousCodePoint(text, first)
                if (isHan(TextCharacterBoundaries.codePointAt(text, previous))) first = previous
            }
        }
        var best: TextSelectionRange? = null
        var bestLength = 0
        var start = first
        while (start <= anchor) {
            var node = root
            var end = start
            var length = 0
            while (end < text.length && length < maxLength) {
                node = node.children[TextCharacterBoundaries.codePointAt(text, end)] ?: break
                end = TextCharacterBoundaries.nextCodePoint(text, end)
                length++
                if (node.terminal && end > anchor && length > bestLength &&
                    TextCharacterBoundaries.isBoundary(text, start) && TextCharacterBoundaries.isBoundary(text, end)) {
                    best = TextSelectionRange(start, end)
                    bestLength = length
                }
            }
            start = TextCharacterBoundaries.nextCodePoint(text, start)
        }
        return best
    }

    companion object {
        const val MAX_WORD_CHARACTERS = 16

        /**
         * Hand-authored Inkflow seed vocabulary (project source, no imported corpus/dictionary).
         * Common reading, narrative, time and everyday terms; deliberately small, not a segmenter.
         * Applications can supply local headwords with [LocalChineseWordLexicon] as needed.
         */
        val CommonReading = LocalChineseWordLexicon(
            ("阅读 读者 作者 作品 文字 文学 小说 故事 人物 角色 章节 段落 句子 词语 语言 文化 历史 " +
                "知识 思想 理解 思考 记忆 想象 意义 问题 答案 生活 生命 世界 自然 社会 人类 人生 " +
                "朋友 家人 孩子 父亲 母亲 先生 少年 少女 心情 感情 心理 希望 梦想 幸福 快乐 悲伤 " +
                "时间 时候 现在 过去 未来 今天 明天 昨天 早晨 夜晚 春天 夏天 秋天 冬天 " +
                "地方 城市 乡村 道路 山川 河流 海洋 天空 阳光 月光 声音 眼睛 窗户 房间 " +
                "开始 结束 发现 觉得 知道 看见 听见 告诉 回答 继续 已经 因为 所以 虽然 但是 " +
                "如果 然后 于是 终于 突然 慢慢 静静 认真 自己 我们 他们 你们 一个 一起 一切 " +
                "中国 中文 汉字 学习 学生 学校 大学 科学 技术 研究 自然科学 图书馆 人工智能").split(' ')
        )

        internal fun isHan(code: Int): Boolean = code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF || code in 0x20000..0x2A6DF || code in 0x2A700..0x2EE5F ||
            code in 0x2F800..0x2FA1F || code in 0x30000..0x323AF
    }
}

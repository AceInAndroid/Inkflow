package com.aceinandroid.inkflow.core.ai

/** Portable method data. Metadata never grants tool, provider or mutation authority. */
data class ReadingSkill(
    val id: String,
    val title: String,
    val summary: String,
    val instructions: String,
    val schemaVersion: Int = SkillCodec.SCHEMA_VERSION,
    val category: String = "general",
    val recommendedAgent: String? = null,
    val quickPrompts: List<String> = emptyList(),
    val outputFormat: String = "markdown",
)

class ReadingSkillRegistry(custom: List<ReadingSkill> = emptyList()) {
    val skills: List<ReadingSkill> = builtIns + custom.filter { it.id.startsWith("custom.") }
    init {
        require(custom.all { it.id.startsWith("custom.") })
        custom.forEach(SkillCodec::validate)
        require(custom.size <= SkillCodec.MAX_SKILLS)
        require(skills.map { it.id }.distinct().size == skills.size)
    }
    fun find(id: String?): ReadingSkill? = skills.firstOrNull { it.id == id }
    fun summary(id: String): String? = find(id)?.summary
    fun full(id: String): String? = find(id)?.let { "${it.summary}\n${it.instructions}" }

    companion object {
        val builtIns = listOf(
            ReadingSkill("reading.general", "通用阅读", "帮助理解当前已读内容。", "先直接回答，再给出原文依据；区分事实与推断。"),
            ReadingSkill("reading.socratic", "苏格拉底教学", "用问题帮助读者主动思考。", "先澄清理解，只提出一个可由已读内容回答的问题，不提前给出全部答案。"),
            ReadingSkill("reading.argument", "论证拆解", "梳理论点、前提与证据。", "区分主张、证据、隐含前提与可能反例；不虚构原文证据。"),
            ReadingSkill("reading.characters", "人物追踪", "追踪已出现人物和关系。", "仅根据已读内容梳理人物动机、关系与变化；未知部分明确保留。"),
            ReadingSkill("reading.history", "史料核查", "区分原文叙述与待核实史实。", "列出作者观点及其来源；未实际查证的事实必须标记待核实。"),
            ReadingSkill("reading.language", "外语精读", "解释语法、措辞与语境。", "自然翻译选区，解释重要词语、句法及作者语气。"),
            ReadingSkill("reading.recap", "章节回顾", "回顾已读章节的要点。", "用三至五点概括已提供的上文；不推测尚未提供的章节。"),
            ReadingSkill("reading.concepts", "概念梳理", "解释概念及其相互联系。", "给出简明定义、原文用法与一个有帮助的例子，明确标记自拟例子。"),
            ReadingSkill("reading.critical", "批判阅读", "检视文本的假设与局限。", "公平复述作者观点，再检视假设、证据局限与替代解释。"),
            ReadingSkill("reading.reflect", "阅读反思", "将阅读转化为个人理解。", "围绕已读内容提出一个反思方向，不替读者编造经历或学习成果。"),
        ).map { skill ->
            val agent = when (skill.id) {
                "reading.argument", "reading.concepts", "reading.critical" -> "agent.argument"
                "reading.history" -> "agent.background"
                "reading.characters" -> "agent.story"
                "reading.language" -> "agent.language"
                "reading.recap", "reading.reflect" -> ReadingAgentRegistry.DEFAULT_ID
                else -> ReadingAgentRegistry.DEFAULT_ID
            }
            skill.copy(category = when (agent) {
                "agent.argument" -> "study"
                "agent.background" -> "history"
                "agent.story" -> "fiction"
                "agent.language" -> "language"
                else -> "general"
            }, recommendedAgent = agent, quickPrompts = listOf(skill.summary))
        }
    }
}

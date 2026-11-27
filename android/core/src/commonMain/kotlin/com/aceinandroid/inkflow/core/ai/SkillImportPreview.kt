package com.aceinandroid.inkflow.core.ai

/** A preview has no persistence/model side effects; conflict policy is explicit at application. */
data class SkillImportPreview(val skills: List<ReadingSkill>, val conflictingIds: Set<String>) {
    companion object {
        fun create(skills: List<ReadingSkill>, existing: ReadingSkillRegistry): SkillImportPreview {
            require(skills.isNotEmpty() && skills.size <= SkillCodec.MAX_SKILLS)
            skills.forEach(SkillCodec::validate)
            require(skills.map { it.id }.distinct().size == skills.size) { "Duplicate skill IDs" }
            return SkillImportPreview(skills, skills.filter { existing.find(it.id) != null }.map { it.id }.toSet())
        }
    }

    /** Rechecks against the latest registry, so a stale preview cannot silently overwrite data. */
    fun merge(existing: ReadingSkillRegistry, overwrite: Boolean = false): List<ReadingSkill> {
        // Revalidate even when callers construct a preview directly.
        create(skills, existing)
        val result = existing.skills.filter { it.id.startsWith("custom.") }.toMutableList()
        val used = existing.skills.map { it.id }.toMutableSet()
        for (skill in skills) {
            val replace = overwrite && skill.id in conflictingIds && skill.id.startsWith("custom.")
            var id = skill.id
            if (!id.startsWith("custom.") || (id in used && !replace)) {
                val base = (if (id.startsWith("custom.")) id else "custom.$id").take(88)
                var suffix = 1
                id = base
                while (id in used) { id = "$base.copy$suffix"; suffix++ }
            }
            if (replace) result.removeAll { it.id == id }
            result += skill.copy(id = id, schemaVersion = SkillCodec.SCHEMA_VERSION)
            used += id
        }
        ReadingSkillRegistry(result)
        return result
    }
}

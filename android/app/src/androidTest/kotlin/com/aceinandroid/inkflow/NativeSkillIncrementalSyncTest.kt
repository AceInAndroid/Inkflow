package com.aceinandroid.inkflow

import android.database.sqlite.SQLiteDatabase
import android.content.Context
import android.content.ContextWrapper
import com.aceinandroid.inkflow.ai.NativeAiStore
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aceinandroid.inkflow.core.ai.ReadingSkill
import com.aceinandroid.inkflow.core.ai.ReadingSkillRegistry
import com.aceinandroid.inkflow.core.ai.SkillCodec
import com.aceinandroid.inkflow.core.sync.DeltaRow
import com.aceinandroid.inkflow.core.sync.ReadingAgentDelta
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSkillIncrementalSyncTest {
    private val skill = ReadingSkill("custom.sync", "Sync", "Summary", "Definition only")
    private fun database(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.create(null).use { db ->
            val schema = JSONArray(InstrumentationRegistry.getInstrumentation().targetContext.assets
                .open("schema_v22.json").bufferedReader().use { it.readText() })
            for (i in 0 until schema.length()) db.execSQL(schema.getString(i))
            block(db)
        }
    }
    private fun controller(skills: () -> List<ReadingSkill>, apply: (List<ReadingSkill>) -> Unit) =
        NativeSkillSyncController({ SkillCodec.encodeStored(skills()) },
            { apply(it.merge(ReadingSkillRegistry(skills()))) }, { _, _ -> 100L }, { false }, {})

    @Test fun conflictingDeviceCopiesKeepProvenanceAcrossRealPreferenceControllerRestarts() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val names = mutableSetOf<String>()
        fun device(): Context = object : ContextWrapper(base) {
            private val prefix = "skill-sync-device-${UUID.randomUUID()}"
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val privateName = "$prefix-$name"
                names += privateName
                return base.getSharedPreferences(privateName, mode)
            }
        }
        val devices = listOf(device(), device(), device())
        val variants = listOf(skill.copy(instructions = "Device A definition"), skill.copy(instructions = "Device B definition"))
        try {
            devices.take(2).forEachIndexed { index, context ->
                val ai = NativeAiStore(context)
                ai.applySkillImport(ai.previewSkillImport(SkillCodec.encodeJson(variants[index])))
                ai.selectAgent("agent.argument")
            }
            repeat(10) { round ->
                val active = if (round < 3) devices.take(2) else devices
                val snapshots = active.map { context ->
                    val controller = NativeSkillSyncController(context)
                    controller.capture(NativeSkillSyncController.BOOK_KEY, 0L).mapNotNull(controller::definition)
                }
                active.forEachIndexed { index, context ->
                    // Reconstruct against real SharedPreferences, retaining no controller memory.
                    snapshots.forEachIndexed { source, definitions ->
                        if (index != source) NativeSkillSyncController(context).merge(definitions)
                    }
                    val stored = SkillCodec.decodeStored(NativeAiStore(context).exportStoredSkills())
                    assertEquals(2, stored.size)
                    assertEquals(variants.map { it.copy(id = "custom.definition") }.toSet(),
                        stored.map { it.copy(id = "custom.definition") }.toSet())
                    val restarted = NativeSkillSyncController(context)
                    val published = restarted.capture(NativeSkillSyncController.BOOK_KEY, 0L).mapNotNull(restarted::definition)
                    assertEquals(2, published.size)
                    assertEquals(variants.toSet(), published.toSet())
                    if (index < 2) assertEquals("agent.argument", NativeAiStore(context).selectedAgent().id)
                    assertFalse(context.getSharedPreferences("native_ai_workspace", Context.MODE_PRIVATE)
                        .contains("token_usage"))
                }
            }
        } finally { names.forEach(base::deleteSharedPreferences) }
    }

    @Test fun globalDefinitionsRoundTripExistingEnvelopeWithoutLibraryBooksAndNeverSelectOrExecute() = database { db ->
        var definitions = listOf(skill)
        val bridge = controller({ definitions }, { definitions = it })
        val store = NativeIncrementalSyncStore(db, "local", bridge)
        val encoded = store.capture(NativeSkillSyncController.BOOK_KEY, 0L).encode()
        val restored = NativeIncrementalSyncPackage.decode(encoded)
        assertEquals(ReadingAgentDelta.TYPE, restored.type)
        assertEquals(setOf("tb_reading_artifacts"), restored.rows.keys)
        assertEquals(skill, bridge.definition(restored.rows.getValue("tb_reading_artifacts").single()))
        definitions = emptyList()
        store.merge(restored, 0L)
        assertEquals(listOf(skill), definitions)
        // Known definitions are projected via NativeAiStore, not written into book AI tables.
        db.rawQuery("SELECT COUNT(*) FROM tb_reading_artifacts", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        assertTrue(store.books().isEmpty())
    }

    @Test fun futureArtifactVersionIsPreservedAndMalformedKnownDefinitionRejectsBeforeAnyWrite() = database { db ->
        var applications = 0
        val bridge = controller({ listOf(skill) }, { applications++ })
        val store = NativeIncrementalSyncStore(db, "local", bridge)
        val row = bridge.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        fun pack(vararg rows: DeltaRow) = NativeIncrementalSyncPackage(ReadingAgentDelta.TYPE, "remote", 100L,
            mapOf("tb_reading_artifacts" to rows.toList()), NativeSkillSyncController.BOOK_KEY)
        val future = row + mapOf("schema_version" to 99L, "payload_json" to "{\"future\":true}")
        store.merge(pack(future), 0L)
        val captured = store.capture(NativeSkillSyncController.BOOK_KEY, 0L).rows.getValue("tb_reading_artifacts")
        assertEquals(2, captured.size)
        val opaque = captured.single { bridge.definition(it) == null }
        val local = captured.single { bridge.definition(it) != null }
        assertEquals(skill, bridge.definition(local))
        assertNotEquals(opaque["id"], local["id"])
        assertEquals(99L, opaque["schema_version"])
        assertEquals("{\"future\":true}", opaque["payload_json"])
        try {
            store.merge(pack(row + ("id" to "valid"), row + mapOf("id" to "invalid", "payload_json" to "{}")), 0L)
            fail("Known malformed payload accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, applications)
        db.rawQuery("SELECT COUNT(*) FROM tb_reading_artifacts", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun collidingFutureRecordsAndEditedLocalDefinitionRoundTripWithoutExecutingUnknowns() = database { db ->
        var definitions = listOf(skill)
        var sourceApplications = 0
        val bridge = controller({ definitions }, { sourceApplications++; definitions = it })
        val store = NativeIncrementalSyncStore(db, "source", bridge)
        val canonical = bridge.capture(NativeSkillSyncController.BOOK_KEY, 0L).single()
        fun pack(rows: List<DeltaRow>) = NativeIncrementalSyncPackage(ReadingAgentDelta.TYPE, "remote", 100L,
            mapOf("tb_reading_artifacts" to rows), NativeSkillSyncController.BOOK_KEY)
        val future = canonical + mapOf("schema_version" to 99L, "payload_json" to "{\"future\":true}")
        store.merge(pack(listOf(future)), 0L)
        val first = store.capture(NativeSkillSyncController.BOOK_KEY, 0L).rows.getValue("tb_reading_artifacts")
            .single { bridge.definition(it) != null }
        val anotherFuture = first + mapOf("schema_version" to 100L, "payload_json" to "{\"future\":2}")
        store.merge(pack(listOf(anotherFuture)), 0L)
        val backup = store.capture(NativeSkillSyncController.BOOK_KEY, 0L)
        val supported = backup.rows.getValue("tb_reading_artifacts").single { bridge.definition(it) != null }
        assertFalse(supported["id"] in setOf(future["id"], anotherFuture["id"]))
        definitions = listOf(skill.copy(instructions = "Latest source-device edit"))
        val edited = store.capture(NativeSkillSyncController.BOOK_KEY, 0L)
        assertEquals(supported["id"], edited.rows.getValue("tb_reading_artifacts").single { bridge.definition(it) != null }["id"])
        assertEquals(0, sourceApplications)
        database { destination ->
            var restored = emptyList<ReadingSkill>()
            var applications = 0
            val restoreBridge = controller({ restored }, { applications++; restored = it })
            val receiver = NativeIncrementalSyncStore(destination, "receiver", restoreBridge)
            val decoded = NativeIncrementalSyncPackage.decode(edited.encode())
            repeat(2) { receiver.merge(decoded, 0L) }
            assertEquals(definitions, restored)
            assertEquals(1, applications)
            val unknowns = receiver.capture(NativeSkillSyncController.BOOK_KEY, 0L).rows.getValue("tb_reading_artifacts")
                .filter { restoreBridge.definition(it) == null }
            assertEquals(setOf(future["id"], anotherFuture["id"]), unknowns.map { it["id"] }.toSet())
            assertEquals(setOf("{\"future\":true}", "{\"future\":2}"), unknowns.map { it["payload_json"] }.toSet())
        }
    }
}

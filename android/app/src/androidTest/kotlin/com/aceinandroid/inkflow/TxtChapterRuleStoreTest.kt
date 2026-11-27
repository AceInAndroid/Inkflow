package com.aceinandroid.inkflow

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TxtChapterRuleStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun importedBookKeepsItsRuleAfterGlobalSelectionAndDeletion() {
        val original = NativeSettingsBackup.capture(context)
        val rules = TxtChapterRuleStore(context)
        val source = File(context.cacheDir, "native-rule-snapshot.txt")
        val store = BookStore(context)
        var bookId: Long? = null
        try {
            source.writeText("Part 1: Arrival\nFirst.\nPart 2: Return\nSecond.")
            val custom = TxtChapterRule(TxtChapterRuleStore.newId(), "Parts", "^Part [0-9]+:.*$")
            rules.saveCustom(custom)
            rules.select(custom.id)
            val imported = store.import(Uri.fromFile(source))
            bookId = imported.id
            rules.select(TxtChapterRuleStore.DEFAULT_ID)
            rules.deleteCustom(custom.id)
            assertEquals(2, BookParser.inspect(imported.file, imported.title,
                rules.ruleForBook(imported.id)).chapters.size)
            assertEquals(1, BookParser.inspect(imported.file, imported.title).chapters.size)
            val backup = NativeSettingsBackup.capture(context)
            assertEquals(5, backup.getInt("version"))
            context.getSharedPreferences("native_txt_rules", Context.MODE_PRIVATE).edit().clear().commit()
            NativeSettingsBackup.apply(context, backup)
            assertEquals(2, BookParser.inspect(imported.file, imported.title,
                TxtChapterRuleStore(context).ruleForBook(imported.id)).chapters.size)
            store.removeBook(imported.id)
            bookId = null
            assertFalse(context.getSharedPreferences("native_txt_rules", Context.MODE_PRIVATE)
                .contains("book:${imported.id}"))
        } finally {
            bookId?.let(store::removeBook)
            store.close()
            source.delete()
            NativeSettingsBackup.apply(context, original)
        }
    }

    @Test fun flutterRulesImportAndOldNativeBackupRemainReadable() {
        val original = NativeSettingsBackup.capture(context)
        try {
            val custom = TxtChapterRule("flutter-rule", "Flutter parts", "^Part [0-9]+:.*$",
                listOf("Part 1: Arrival"))
            val flutter = JSONObject().put("__prefsBackupVersion", 1)
                .put("chapterSplitCustomRules", JSONObject().put("type", "string")
                    .put("value", JSONArray().put(custom.toJson()).toString()))
                .put("chapterSplitSelectedRuleId", JSONObject().put("type", "string")
                    .put("value", custom.id))
            NativeSettingsBackup.applyFlutterPreferences(context, flutter)
            assertEquals(custom.id, TxtChapterRuleStore(context).activeRule().id)
            assertTrue(TxtChapterRuleStore(context).activeRule().expression()
                .containsMatchIn("Part 1: Arrival"))
            val legacy = NativeSettingsBackup.capture(context)
            legacy.put("version", 1)
            legacy.remove("native_txt_rules")
            legacy.remove("native_bookshelf")
            NativeSettingsBackup.parse(legacy)
            val versionTwo = NativeSettingsBackup.capture(context)
            versionTwo.put("version", 2).remove("native_bookshelf")
            NativeSettingsBackup.parse(versionTwo)
            val invalid = NativeSettingsBackup.capture(context)
            invalid.getJSONObject("native_txt_rules").put("book:42",
                JSONObject().put("type", "string").put("value", "{bad"))
            assertThrows(java.io.IOException::class.java) { NativeSettingsBackup.parse(invalid) }
        } finally {
            NativeSettingsBackup.apply(context, original)
        }
    }
}

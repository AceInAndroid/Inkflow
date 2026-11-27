package com.aceinandroid.inkflow

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses private files and preferences; never changes the application's real reading appearance. */
@RunWith(AndroidJUnit4::class)
class ReaderScriptFontsDeviceTest {
    private fun sandbox(test: (Context, ReaderAppearanceStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "script-fonts-${UUID.randomUUID()}").apply { mkdirs() }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "${root.name}-$name"
                names += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        try { test(context, ReaderAppearanceStore(context)) } finally {
            names.forEach(base::deleteSharedPreferences)
            root.deleteRecursively()
        }
    }

    private fun importFont(store: ReaderAppearanceStore): ImportedFont {
        val file = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSerif-Regular.ttf")
            .map(::File).first { it.isFile }
        return store.importFont(file.name) { file.inputStream() }
    }

    @Test fun legacyPreferencesKeepGeneralFontAndInheritBothScripts() = sandbox { context, store ->
        context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE).edit()
            .putString("font", ReaderFont.SERIF.name).commit()
        val appearance = store.load()
        assertEquals(ReaderFont.SERIF, appearance.font)
        assertNull(appearance.cjkFont)
        assertNull(appearance.latinFont)
        assertEquals(ReaderScriptFontFamilies(), store.scriptFontFamilies(appearance))
        assertEquals(ReaderFont.SERIF.family, store.fontFamily(appearance))
    }

    @Test fun independentBuiltinChoicesPersistAndCanReturnToInheritance() = sandbox { context, store ->
        val appearance = ReaderAppearance(font = ReaderFont.MONOSPACE,
            cjkFont = ReaderScriptFont(ReaderFont.SERIF), latinFont = ReaderScriptFont(ReaderFont.SYSTEM))
        store.save(appearance)
        val reopened = ReaderAppearanceStore(context)
        assertEquals(appearance, reopened.load())
        assertEquals(ReaderScriptFontFamilies(FontFamily.Serif, FontFamily.Default), reopened.scriptFontFamilies(appearance))
        reopened.save(appearance.copy(cjkFont = null))
        assertNull(reopened.load().cjkFont)
        assertEquals(appearance.latinFont, reopened.load().latinFont)
        val prefs = context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("cjk_font"))
        assertFalse(prefs.contains("cjk_custom_font_id"))
    }

    @Test fun importedFacesPersistIndependentlyAndRemovalClearsEveryReference() = sandbox { context, store ->
        val first = importFont(store)
        val second = importFont(store)
        val appearance = ReaderAppearance(customFontId = first.id,
            cjkFont = ReaderScriptFont(ReaderFont.SERIF, first.id),
            latinFont = ReaderScriptFont(ReaderFont.MONOSPACE, second.id))
        store.save(appearance)
        assertEquals(appearance, ReaderAppearanceStore(context).load())
        val families = store.scriptFontFamilies(appearance)
        assertNotEquals(FontFamily.Serif, families.cjk)
        assertNotEquals(FontFamily.Monospace, families.latin)
        store.removeFont(first.id)
        val remaining = store.load()
        assertNull(remaining.customFontId)
        assertEquals(ReaderScriptFont(ReaderFont.SERIF), remaining.cjkFont)
        assertEquals(appearance.latinFont, remaining.latinFont)
        assertEquals(FontFamily.Serif, store.scriptFontFamilies(remaining).cjk)
        assertTrue(store.fonts().any { it.id == second.id })
    }

    @Test fun missingOrCorruptImportedFacesFallBackToEachScriptsBuiltin() = sandbox { context, store ->
        val font = importFont(store)
        val appearance = ReaderAppearance(cjkFont = ReaderScriptFont(ReaderFont.SERIF, font.id),
            latinFont = ReaderScriptFont(ReaderFont.MONOSPACE, font.id))
        store.save(appearance)
        // Simulate a corrupt, then externally removed font without changing its preference metadata.
        val file = File(context.filesDir, "fonts/${font.id}")
        file.writeText("invalid font bytes")
        assertEquals(ReaderScriptFontFamilies(FontFamily.Serif, FontFamily.Monospace), store.scriptFontFamilies(appearance))
        assertTrue(file.delete())
        assertEquals(ReaderScriptFontFamilies(FontFamily.Serif, FontFamily.Monospace), store.scriptFontFamilies(appearance))
        assertEquals(ReaderScriptFont(ReaderFont.SERIF), store.load().cjkFont)
        assertEquals(ReaderScriptFont(ReaderFont.MONOSPACE), store.load().latinFont)
    }

    @Test fun versionFiveBackupRoundTripsBothSelectionsAndExistingFontLibrary() = sandbox { context, store ->
        val first = importFont(store)
        val second = importFont(store)
        val appearance = ReaderAppearance(cjkFont = ReaderScriptFont(ReaderFont.SERIF, first.id),
            latinFont = ReaderScriptFont(ReaderFont.MONOSPACE, second.id))
        store.save(appearance)
        val document = NativeSettingsBackup.capture(context)
        assertEquals(5, document.getInt("version"))
        assertEquals(setOf(first.id, second.id), NativeSettingsBackup.selectedFontIds(document))
        store.save(ReaderAppearance())
        NativeSettingsBackup.apply(context, document)
        assertEquals(appearance, ReaderAppearanceStore(context).load())
        assertEquals(setOf(first.id, second.id), store.fonts().map { it.id }.toSet())
    }

    @Test fun oldBackupsWithoutScriptKeysRestoreInheritance() = sandbox { context, store ->
        val old = NativeSettingsBackup.capture(context)
        old.getJSONObject("native_reader_appearance").put("font", setting("string", "SERIF"))
        for (version in 1..5) {
            store.save(ReaderAppearance(cjkFont = ReaderScriptFont(ReaderFont.MONOSPACE),
                latinFont = ReaderScriptFont(ReaderFont.SERIF)))
            NativeSettingsBackup.apply(context, JSONObject(old.toString()).put("version", version))
            assertNull(store.load().cjkFont)
            assertNull(store.load().latinFont)
            assertEquals(ReaderFont.SERIF, store.load().font)
        }
    }

    @Test fun backupRejectsInvalidScriptFontTypesEnumsAndUnsafePaths() = sandbox { context, _ ->
        val baseline = NativeSettingsBackup.capture(context)
        listOf("cjk", "latin").forEach { script ->
            listOf("${script}_font" to setting("int", 3),
                "${script}_font" to setting("string", "UNKNOWN"),
                "${script}_custom_font_id" to setting("string", "../outside.ttf"))
                .forEach { (key, value) ->
                    val document = JSONObject(baseline.toString())
                    document.getJSONObject("native_reader_appearance").put(key, value)
                    assertThrows(IOException::class.java) { NativeSettingsBackup.parse(document) }
                }
        }
    }

    private fun setting(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
}

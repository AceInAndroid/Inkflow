package com.aceinandroid.inkflow

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderAppearanceTest {
    @Test fun importedBackgroundPersistsAndDecodesWithinBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderAppearanceStore(context)
        val original = store.load()
        val bytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }.toByteArray()
        var id: String? = null
        var nightId: String? = null
        val nightSource = File(context.cacheDir, "reader-night-${System.nanoTime()}.png")
        try {
            id = store.importImage("red.png") { ByteArrayInputStream(bytes) }
            val image = ReaderBackgroundImage("localFile", id, opacity = 0.4f, fit = "stretch")
            store.save(original.copy(backgroundImage = image))
            val restored = ReaderAppearanceStore(context).load().backgroundImage
            assertEquals(image, restored)
            assertEquals(android.graphics.Color.RED, store.bitmap(image)!!.getPixel(0, 0))
            assertTrue(id in store.images())
            nightSource.writeBytes(bytes)
            nightId = store.importNightImage(id, Uri.fromFile(nightSource))
            assertTrue(nightId !in store.images())
            assertEquals(nightId, store.backgroundForLocal(id).nightPath)
            val nightImage = image.copy(nightPath = nightId, selectedMode = "night")
            assertEquals(nightId, nightImage.selectedPath())
            assertEquals(id, nightImage.copy(autoAdjust = true).selectedPath(isDark = false))
            assertEquals(nightId, nightImage.copy(autoAdjust = true).selectedPath(isDark = true))
            store.removeImage(nightImage)
            assertTrue(id !in store.images())
            assertThrows(IllegalArgumentException::class.java) {
                ReaderBackgroundImage.parse("""{"type":"localFile","path":"../outside.png","alignment":"center"}""")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.importImage("bad.png") { ByteArrayInputStream("not an image".toByteArray()) }
            }
        } finally {
            store.save(original)
            id?.let { File(context.filesDir, "bgimg/$it").delete() }
            nightId?.let { File(context.filesDir, "bgimg/$it").delete() }
            nightSource.delete()
        }
    }

    @Test fun savesAndLoadsReadingAppearance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderAppearanceStore(context)
        val original = store.load()
        try {
            val selected = ReaderAppearance(23, 1.9f, ReaderPalette.DARK, ReaderFont.SERIF)
            store.save(selected)
            assertEquals(selected, ReaderAppearanceStore(context).load())

            context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE).edit()
                .putInt("font_size", 100)
                .putFloat("line_spacing", Float.NaN)
                .putString("palette", "unknown")
                .putString("font", "unknown")
                .commit()
            assertEquals(ReaderAppearance(28, 1.6f), store.load())
        } finally {
            store.save(original)
        }
    }

    @Test fun customColorsPersistAndInvalidColorsFallBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderAppearanceStore(context)
        val original = store.load()
        val preferences = context.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE)
        try {
            val custom = original.copy(palette = ReaderPalette.CUSTOM,
                customBackground = "FF121212", customForeground = "FFCCCCCC")
            store.save(custom)
            assertEquals(custom, ReaderAppearanceStore(context).load())
            assertEquals(custom.background, ReaderAppearanceStore(context).load().colors.background)
            assertTrue(custom.isDark)

            preferences.edit().putString("custom_background", "invalid").commit()
            assertEquals(ReaderPalette.LIGHT, store.load().palette)
        } finally {
            store.save(original)
        }
    }

    @Test fun narrationDefaultsPersistForNewReaderSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NarrationSettingsStore(context)
        val original = store.load()
        try {
            store.save(NarrationSettings(1.3f, 0.8f, continuous = true))
            assertEquals(NarrationSettings(1.3f, 0.8f, continuous = true), NarrationSettingsStore(context).load())
            val narrator = NativeNarrator(context)
            try {
                assertEquals(1.3f, narrator.rate)
                assertEquals(0.8f, narrator.pitch)
                assertEquals(true, narrator.continuous)
            } finally {
                narrator.close()
            }
            store.save(NarrationSettings(Float.NaN, 4f))
            assertEquals(NarrationSettings(1f, 2f), store.load())
        } finally {
            store.save(original)
        }
    }

    @Test fun importedFontsPersistAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderAppearanceStore(context)
        val original = store.load()
        val imported = mutableListOf<ImportedFont>()
        try {
            val font = store.importFont("Roboto-Regular.ttf") {
                File("/system/fonts/Roboto-Regular.ttf").inputStream()
            }
            imported += font
            val second = store.importFont("NotoSerif-Regular.ttf") {
                File("/system/fonts/NotoSerif-Regular.ttf").inputStream()
            }
            imported += second
            assertTrue(store.fonts().contains(font))
            assertTrue(store.fonts().contains(second))
            store.save(original.copy(customFontId = second.id))
            val reopened = ReaderAppearanceStore(context)
            assertEquals(second.id, reopened.load().customFontId)
            assertTrue(reopened.fontFamily(reopened.load()) != ReaderFont.SYSTEM.family)

            reopened.removeFont(font.id)
            assertTrue(reopened.fonts().none { it.id == font.id })
            assertEquals(second.id, reopened.load().customFontId)
            reopened.removeFont(second.id)
            assertEquals(null, reopened.load().customFontId)
        } finally {
            imported.forEach { store.removeFont(it.id) }
            store.save(original)
        }
    }

    @Test fun invalidFontImportLeavesLibraryUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderAppearanceStore(context)
        val before = store.fonts()
        assertThrows(IllegalArgumentException::class.java) {
            store.importFont("unsupported.woff") { ByteArrayInputStream(byteArrayOf(1)) }
        }
        assertThrows(RuntimeException::class.java) {
            store.importFont("broken.ttf") { ByteArrayInputStream("not a font".toByteArray()) }
        }
        assertEquals(before, store.fonts())
    }

    @Test fun openTypeAndCollectionFontsCanBeSelected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = listOf(
            File("/system/fonts/NotoSansArmenian-Regular.otf"),
            File("/system/fonts/NotoSansCJK-Regular.ttc"),
        )
        samples.forEach { assumeTrue("Missing system font: $it", it.isFile) }
        val store = ReaderAppearanceStore(context)
        val original = store.load()
        val imported = mutableListOf<ImportedFont>()
        try {
            samples.forEach { sample ->
                val font = store.importFont(sample.name) { sample.inputStream() }
                imported += font
                store.save(original.copy(customFontId = font.id))
                val reopened = ReaderAppearanceStore(context)
                assertEquals(font.id, reopened.load().customFontId)
                assertTrue(reopened.fontFamily(reopened.load()) != ReaderFont.SYSTEM.family)
            }
        } finally {
            imported.forEach { store.removeFont(it.id) }
            store.save(original)
        }
    }
}

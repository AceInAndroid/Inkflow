package com.aceinandroid.inkflow

import android.content.Context
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

data class ReaderBackgroundImage(
    val type: String,
    val path: String,
    val nightPath: String? = null,
    val alignment: String = "center",
    val selectedMode: String? = null,
    val blur: Float = 0f,
    val opacity: Float = 1f,
    val fit: String = "cover",
    val autoAdjust: Boolean = false,
) {
    fun selectedPath(isDark: Boolean = false): String =
        if (nightPath != null && (if (autoAdjust) isDark else selectedMode == "night"))
            nightPath else path

    fun toPreference(): String = JSONObject().apply {
        put("type", type)
        put("path", path)
        put("nightPath", nightPath)
        put("alignment", alignment)
        put("selectedMode", selectedMode)
        put("blur", blur.toDouble())
        put("opacity", opacity.toDouble())
        put("autoAdjust", autoAdjust)
    }.toString()

    companion object {
        private val localName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)
        private val assetName = Regex("assets/images/bgimg/bg[1-6]\\.jpg")

        fun validPath(type: String, path: String): Boolean = when (type) {
            "localFile" -> localName.matches(path) && !path.contains("..")
            "assets" -> assetName.matches(path)
            else -> false
        }

        fun parse(raw: String, fit: String = "cover"): ReaderBackgroundImage {
            val json = JSONObject(raw)
            val type = json.getString("type")
            val path = json.getString("path")
            val night = json.optString("nightPath").takeIf { it.isNotBlank() && it != "null" }
            val alignment = json.optString("alignment", "center")
            val mode = json.optString("selectedMode").takeIf { it in setOf("day", "night") }
            val blur = json.optDouble("blur", 0.0)
            val opacity = json.optDouble("opacity", 1.0)
            val autoAdjust = json.optBoolean("autoAdjust", false)
            require(validPath(type, path) && (night == null || validPath(type, night)))
            require(alignment in setOf("center", "top", "bottom", "left", "right"))
            require(blur.isFinite() && blur in 0.0..20.0 && opacity.isFinite() && opacity in 0.0..1.0)
            require(fit in setOf("cover", "stretch"))
            return ReaderBackgroundImage(type, path, night, alignment, mode, blur.toFloat(), opacity.toFloat(), fit, autoAdjust)
        }
    }
}

enum class ReaderPalette(val background: Color, val foreground: Color) {
    LIGHT(Color(0xFFFBFBF3), Color(0xFF343434)),
    WARM(Color(0xFFF2EBDB), Color(0xFF302F2B)),
    DARK(Color(0xFF161A19), Color(0xFFE5E9E6)),
    CUSTOM(Color(0xFFFBFBF3), Color(0xFF343434)),
}

internal fun readerThemeColor(value: String?): Color? = value
    ?.takeIf { it.matches(Regex("[0-9A-Fa-f]{8}")) }
    ?.let { Color(it.toLong(16)) }

enum class ReaderFont(val family: FontFamily) {
    SYSTEM(FontFamily.Default),
    SERIF(FontFamily.Serif),
    MONOSPACE(FontFamily.Monospace),
}

enum class ReaderAlignment(val textAlign: TextAlign?) {
    BOOK(null), START(TextAlign.Start), JUSTIFY(TextAlign.Justify), CENTER(TextAlign.Center),
}

data class ReaderAppearance(
    val fontSize: Int = 18,
    val lineSpacing: Float = 1.6f,
    val palette: ReaderPalette = ReaderPalette.LIGHT,
    val font: ReaderFont = ReaderFont.SYSTEM,
    val customFontId: String? = null,
    val customBackground: String? = null,
    val customForeground: String? = null,
    val backgroundImage: ReaderBackgroundImage? = null,
    val pageMargin: Int = 20,
    val paragraphSpacing: Int = 8,
    val firstLineIndent: Int = 0,
    val alignment: ReaderAlignment = ReaderAlignment.BOOK,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showTime: Boolean = false,
    val showBattery: Boolean = false,
    val cjkFont: ReaderScriptFont? = null,
    val latinFont: ReaderScriptFont? = null,
) {
    val textAlign: TextAlign? get() = alignment.textAlign
    val background: Color get() = if (palette == ReaderPalette.CUSTOM)
        readerThemeColor(customBackground) ?: ReaderPalette.LIGHT.background else palette.background
    val foreground: Color get() = if (palette == ReaderPalette.CUSTOM)
        readerThemeColor(customForeground) ?: ReaderPalette.LIGHT.foreground else palette.foreground
    val isDark: Boolean get() = background.luminance() < 0.5f

    val colors: ColorScheme
        get() {
            val container = if (palette == ReaderPalette.WARM) Color(0xFFE8DDC8)
                else Color(0xFFE7EFEA)
            return if (isDark) darkColorScheme(
                primary = Color(0xFF9AD8B8), secondary = Color(0xFF9AD8B8),
                primaryContainer = Color(0xFF284B3D), secondaryContainer = Color(0xFF284B3D),
                surfaceVariant = Color(0xFF303B36), outline = Color(0xFF84968D),
                surfaceContainer = Color(0xFF232B27), surfaceContainerHigh = Color(0xFF2B3530),
                background = background, surface = background,
                onBackground = foreground, onSurface = foreground,
            ) else lightColorScheme(
                primary = Color(0xFF2B6B58), secondary = Color(0xFF2B6B58),
                primaryContainer = container, secondaryContainer = container,
                surfaceVariant = container, outline = Color(0xFF9BAEA2),
                surfaceContainer = container, surfaceContainerHigh = container,
                background = background, surface = background,
                onBackground = foreground, onSurface = foreground,
            )
        }
}

data class ImportedFont(val id: String, val name: String)

class ReaderAppearanceStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("native_reader_appearance", Context.MODE_PRIVATE)
    private val fontDir = File(appContext.filesDir, "fonts")
    private val imageDir = File(appContext.filesDir, "bgimg")
    private val fontIdPattern = importedReaderFontIdPattern

    fun load(): ReaderAppearance {
        val background = preferences.getString("custom_background", null)
            ?.takeIf { readerThemeColor(it) != null }
        val foreground = preferences.getString("custom_foreground", null)
            ?.takeIf { readerThemeColor(it) != null }
        val palette = enumValueOrDefault(preferences.getString("palette", null), ReaderPalette.LIGHT)
            .let { if (it == ReaderPalette.CUSTOM && (background == null || foreground == null))
                ReaderPalette.LIGHT else it }
        return ReaderAppearance(
            fontSize = preferences.getInt("font_size", 18).coerceIn(14, 28),
            lineSpacing = preferences.getFloat("line_spacing", 1.6f).let {
                if (it.isFinite()) it.coerceIn(1.2f, 2.2f) else 1.6f
            },
            pageMargin = preferences.getInt("page_margin", 20).coerceIn(4, 48),
            paragraphSpacing = preferences.getInt("paragraph_spacing", 8).coerceIn(0, 32),
            firstLineIndent = preferences.getInt("first_line_indent", 0).coerceIn(0, 64),
            alignment = enumValueOrDefault(preferences.getString("text_alignment", null), ReaderAlignment.BOOK),
            showHeader = preferences.getBoolean("show_header", true),
            showFooter = preferences.getBoolean("show_footer", true),
            showTime = preferences.getBoolean("show_time", false),
            showBattery = preferences.getBoolean("show_battery", false),
            palette = palette,
            font = enumValueOrDefault(preferences.getString("font", null), ReaderFont.SYSTEM),
            customFontId = preferences.getString("custom_font_id", null)
                ?.takeIf { fontIdPattern.matches(it) && File(fontDir, it).isFile },
            cjkFont = loadScriptFont("cjk"),
            latinFont = loadScriptFont("latin"),
            customBackground = background,
            customForeground = foreground,
            backgroundImage = runCatching {
                preferences.getString("background_image", null)?.let {
                    ReaderBackgroundImage.parse(it, preferences.getString("background_image_fit", "cover") ?: "cover")
                }?.takeIf(::imageExists)
            }.getOrNull(),
        )
    }

    fun save(value: ReaderAppearance) {
        require(value.palette != ReaderPalette.CUSTOM ||
            (readerThemeColor(value.customBackground) != null && readerThemeColor(value.customForeground) != null))
        preferences.edit()
            .putInt("font_size", value.fontSize.coerceIn(14, 28))
            .putFloat("line_spacing", value.lineSpacing.coerceIn(1.2f, 2.2f))
            .putInt("page_margin", value.pageMargin.coerceIn(4, 48))
            .putInt("paragraph_spacing", value.paragraphSpacing.coerceIn(0, 32))
            .putInt("first_line_indent", value.firstLineIndent.coerceIn(0, 64))
            .putString("text_alignment", value.alignment.name)
            .putBoolean("show_header", value.showHeader)
            .putBoolean("show_footer", value.showFooter)
            .putBoolean("show_time", value.showTime)
            .putBoolean("show_battery", value.showBattery)
            .putString("palette", value.palette.name)
            .putString("custom_background", value.customBackground)
            .putString("custom_foreground", value.customForeground)
            .putString("font", value.font.name)
            .putString("custom_font_id", value.customFontId?.takeIf {
                fontIdPattern.matches(it) && File(fontDir, it).isFile
            })
            .putString("cjk_font", value.cjkFont?.font?.name)
            .putString("cjk_custom_font_id", existingFontId(value.cjkFont?.customFontId))
            .putString("latin_font", value.latinFont?.font?.name)
            .putString("latin_custom_font_id", existingFontId(value.latinFont?.customFontId))
            .putString("background_image", value.backgroundImage?.takeIf {
                imageExists(it)
            }?.toPreference())
            .putString("background_image_fit", value.backgroundImage?.fit ?: "cover")
            .apply()
    }

    fun images(): List<String> = imageDir.listFiles()?.filter { it.isFile &&
        ReaderBackgroundImage.validPath("localFile", it.name) &&
            !it.name.substringBeforeLast('.').endsWith("_night") &&
            it.length() in 1..(20L * 1024 * 1024)
    }?.map(File::getName)?.sorted() ?: emptyList()

    fun backgroundForLocal(name: String): ReaderBackgroundImage {
        require(ReaderBackgroundImage.validPath("localFile", name))
        val night = "${name.substringBeforeLast('.')}_night.${name.substringAfterLast('.')}"
            .takeIf { File(imageDir, it).isFile }
        return ReaderBackgroundImage("localFile", name, night)
    }

    private fun imageExists(image: ReaderBackgroundImage): Boolean = when (image.type) {
        "localFile" -> imageFile(image, image.path)?.let { it.isFile && it.length() in 1..(20L * 1024 * 1024) } == true
        "assets" -> runCatching { appContext.assets.open(image.path).use { true } }.getOrDefault(false)
        else -> false
    }

    private fun imageFile(image: ReaderBackgroundImage, path: String): File? = when (image.type) {
        "localFile" -> if (ReaderBackgroundImage.validPath(image.type, path)) File(imageDir, path) else null
        else -> null
    }

    fun bitmap(image: ReaderBackgroundImage, isDark: Boolean = false): Bitmap? = runCatching {
        val path = image.selectedPath(isDark)
        val input = if (image.type == "assets" && ReaderBackgroundImage.validPath(image.type, path)) {
            { appContext.assets.open(path) }
        } else {
            val file = imageFile(image, path) ?: return@runCatching null
            if (!file.isFile || file.length() !in 1..(20L * 1024 * 1024)) return@runCatching null
            { file.inputStream() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth !in 1..12_000 || bounds.outHeight !in 1..12_000) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048 ||
            bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
        input().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    fun importImage(name: String, night: Boolean = false, open: () -> InputStream): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("jpg", "jpeg", "png", "webp")) { "Select a JPEG, PNG, or WebP image" }
        if (!imageDir.isDirectory && !imageDir.mkdirs()) throw IOException("Cannot create image directory")
        val temporary = File.createTempFile("import-", ".$extension", imageDir)
        try {
            open().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 20L * 1024 * 1024) throw IOException("Image exceeds 20 MB")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("Image is empty")
            } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            require(bounds.outWidth in 1..12_000 && bounds.outHeight in 1..12_000) { "Invalid image dimensions" }
            val id = "${UUID.randomUUID()}${if (night) "_night" else ""}.$extension"
            if (!temporary.renameTo(File(imageDir, id))) throw IOException("Cannot save image")
            return id
        } finally { temporary.delete() }
    }

    fun importImage(uri: Uri, night: Boolean = false): String {
        val name = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment ?: "image.jpg"
        return importImage(name, night) {
            appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open image")
        }
    }

    fun importNightImage(dayName: String, uri: Uri): String {
        require(ReaderBackgroundImage.validPath("localFile", dayName))
        val imported = importImage(uri, night = true)
        val name = "${dayName.substringBeforeLast('.')}_night.${imported.substringAfterLast('.')}"
        val target = File(imageDir, name)
        val source = File(imageDir, imported)
        if (target.exists() && !target.delete()) throw IOException("Cannot replace night image")
        if (!source.renameTo(target)) {
            source.delete()
            throw IOException("Cannot save night image")
        }
        return name
    }

    fun removeImage(image: ReaderBackgroundImage) {
        require(image.type == "localFile")
        listOfNotNull(image.path, image.nightPath).forEach { name ->
            require(ReaderBackgroundImage.validPath("localFile", name))
            val file = File(imageDir, name)
            if (file.exists() && !file.delete()) throw IOException("Cannot remove image")
        }
    }

    fun fontFamily(appearance: ReaderAppearance): FontFamily {
        val id = appearance.customFontId ?: return appearance.font.family
        if (!fontIdPattern.matches(id)) return appearance.font.family
        val file = File(fontDir, id)
        return if (file.isFile) FontFamily(Font(file)) else appearance.font.family
    }

    private fun existingFontId(id: String?): String? = id?.takeIf {
        fontIdPattern.matches(it) && File(fontDir, it).isFile
    }

    private fun loadScriptFont(script: String): ReaderScriptFont? {
        val builtin = preferences.getString("${script}_font", null)
        val custom = preferences.getString("${script}_custom_font_id", null)
        if (builtin == null && custom == null) return null
        return ReaderScriptFont(enumValueOrDefault(builtin, ReaderFont.SYSTEM), existingFontId(custom))
    }

    fun scriptFontFamilies(appearance: ReaderAppearance): ReaderScriptFontFamilies {
        fun resolve(choice: ReaderScriptFont?): FontFamily? {
            if (choice == null) return null
            val file = existingFontId(choice.customFontId)?.let { File(fontDir, it) }
            return if (file != null && validFontFile(file))
                runCatching { FontFamily(Font(file)) }.getOrElse { choice.font.family }
            else choice.font.family
        }
        return ReaderScriptFontFamilies(resolve(appearance.cjkFont), resolve(appearance.latinFont))
    }

    suspend fun scriptFontCacheIdentity(appearance: ReaderAppearance): String =
        readerScriptFontCacheIdentity(appearance, fontDir)

    fun fonts(): List<ImportedFont> = buildList {
        val entries = fontEntries()
        val keys = entries.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (fontIdPattern.matches(id) && File(fontDir, id).isFile) {
                add(ImportedFont(id, entries.optString(id, id)))
            }
        }
    }.sortedWith(compareBy(ImportedFont::name, ImportedFont::id))

    fun importFont(uri: Uri): ImportedFont {
        val name = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment ?: "font.ttf"
        return importFont(name) {
            appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open font")
        }
    }

    internal fun importFont(name: String, open: () -> InputStream): ImportedFont {
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("ttf", "otf", "ttc")) { "Select a TTF, OTF, or TTC font" }
        if (!fontDir.isDirectory && !fontDir.mkdirs()) throw IOException("Cannot create font directory")
        val temporary = File.createTempFile("import-", ".font", fontDir)
        try {
            open().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 64L * 1024 * 1024) throw IOException("Font exceeds 64 MB")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("Font is empty")
            } }
            require(validFontFile(temporary)) { "Invalid font file" }
            Typeface.createFromFile(temporary)
            val id = "${UUID.randomUUID()}.$extension"
            if (!temporary.renameTo(File(fontDir, id))) throw IOException("Cannot save font")
            val entry = ImportedFont(id, name.take(80))
            val entries = fontEntries().apply { put(id, entry.name) }
            preferences.edit().putString("custom_fonts", entries.toString()).apply()
            return entry
        } finally {
            temporary.delete()
        }
    }

    fun removeFont(id: String) {
        require(fontIdPattern.matches(id))
        val file = File(fontDir, id)
        if (file.exists() && !file.delete()) throw IOException("Cannot remove font")
        val entries = fontEntries().apply { remove(id) }
        val editor = preferences.edit().putString("custom_fonts", entries.toString())
        listOf("custom_font_id", "cjk_custom_font_id", "latin_custom_font_id").forEach { key ->
            if (preferences.getString(key, null) == id) editor.remove(key)
        }
        editor.apply()
    }

    private fun fontEntries(): JSONObject = runCatching {
        JSONObject(preferences.getString("custom_fonts", "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun validFontFile(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < 12) return@use false
            val signature = input.readInt()
            val offset = if (signature == 0x74746366) {
                input.skipBytes(4)
                val count = input.readInt()
                if (count !in 1..64 || length < 12L + count * 4L) return@use false
                input.readInt().toLong() and 0xffffffffL
            } else 0L
            if (offset > length - 12) return@use false
            input.seek(offset)
            if (input.readInt() !in setOf(0x00010000, 0x4F54544F, 0x74727565)) return@use false
            val tables = input.readUnsignedShort()
            if (tables !in 1..256 || offset + 12L + tables * 16L > length) return@use false
            input.skipBytes(6)
            var hasCmap = false
            var hasHead = false
            repeat(tables) {
                val tag = input.readInt()
                input.skipBytes(4)
                val tableOffset = input.readInt().toLong() and 0xffffffffL
                val tableLength = input.readInt().toLong() and 0xffffffffL
                if (tableOffset > length || tableLength > length - tableOffset) return@use false
                if (tag == 0x636D6170) hasCmap = true
                if (tag == 0x68656164) hasHead = true
            }
            hasCmap && hasHead
        }
    }.getOrDefault(false)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

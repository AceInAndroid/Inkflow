package com.aceinandroid.inkflow

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class NativeBackupManagerTest {
    private val testPrefs = mutableSetOf<String>()

    @Test fun restoresFlutterStyleArchiveWithoutKeepingOldBookFiles() = withSandbox { root ->
        val source = SandboxContext(root, "source")
        val sourceStore = BookStore(source)
        val sourceFile = addBook(source, sourceStore, "Restored book", "restored.txt")
        sourceStore.close()
        val archive = File(root, "flutter-backup.zip")
        archive(archive, listOf(
            "databases/app_database.db" to source.getDatabasePath("app_database.db"),
            "file/restored.txt" to sourceFile,
        ))

        val destination = SandboxContext(root, "destination")
        val destinationStore = BookStore(destination)
        val oldFile = addBook(destination, destinationStore, "Old book", "old.txt")
        destinationStore.saveTextPosition(1, 0, 17)
        val oldRule = TxtChapterRule(TxtChapterRuleStore.newId(), "Old rule", "^Part [0-9]+:.*$")
        TxtChapterRuleStore(destination).apply {
            saveCustom(oldRule)
            select(oldRule.id)
            bindBook(1, oldRule)
        }
        val appearance = ReaderAppearance(22, 1.8f, ReaderPalette.WARM, ReaderFont.SERIF)
        ReaderAppearanceStore(destination).save(appearance)
        val narration = NarrationSettings(1.2f, 0.9f, continuous = true)
        NarrationSettingsStore(destination).save(narration)
        ReaderControlsStore(destination).setVolumeKeysTurnPages(false)
        NativeBackupManager(destination).restore(Uri.fromFile(archive), destinationStore)

        val restored = destinationStore.all()
        assertEquals(listOf("Restored book"), restored.map { it.title })
        assertTrue(restored.single().file.readText() == "Restored book")
        assertFalse(oldFile.exists())
        assertEquals(0, destinationStore.textOffset(restored.single().id, 0))
        assertEquals(TxtChapterRuleStore.DEFAULT_ID, TxtChapterRuleStore(destination).activeRule().id)
        assertFalse(destination.getSharedPreferences("native_txt_rules", Context.MODE_PRIVATE)
            .contains("book:1"))
        assertEquals(appearance, ReaderAppearanceStore(destination).load())
        assertEquals(narration, NarrationSettingsStore(destination).load())
        assertFalse(ReaderControlsStore(destination).volumeKeysTurnPages())
        assertFalse(File(destination.filesDir, ".native-restore-marker").exists())
        destinationStore.close()
    }

    @Test fun nativeExportRestoresBooksAndPreservedPreferences() = withSandbox { root ->
        val source = SandboxContext(root, "source")
        val sourceStore = BookStore(source)
        sourceStore.setWriteAheadLoggingEnabled(true)
        addBook(source, sourceStore, "Native backup", "native.txt")
        val bookId = sourceStore.all().single().id
        sourceStore.saveTextPosition(bookId, 2, 34)
        val chapterRule = TxtChapterRule(TxtChapterRuleStore.newId(), "Parts", "^Part [0-9]+:.*$")
        TxtChapterRuleStore(source).apply {
            saveCustom(chapterRule)
            select(chapterRule.id)
            bindBook(bookId, chapterRule)
        }
        ReaderControlsStore(source).setVolumeKeysTurnPages(false)
        BookshelfSortStore(source).save(BookshelfSort(BookSortField.TITLE, descending = false))
        val fontSample = File("/system/fonts/Roboto-Regular.ttf")
        assertTrue(fontSample.isFile)
        val font = ReaderAppearanceStore(source).importFont(fontSample.name) { fontSample.inputStream() }
        val appearance = ReaderAppearance(23, 1.9f, ReaderPalette.CUSTOM, ReaderFont.SERIF, font.id,
            "FF112233", "FFEEDDCC")
        val imageBytes = ByteArrayOutputStream().also { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN)
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }.toByteArray()
        val imageId = ReaderAppearanceStore(source).importImage("green.png") {
            imageBytes.inputStream()
        }
        val imageAppearance = appearance.copy(backgroundImage =
            ReaderBackgroundImage("localFile", imageId, opacity = 0.5f, autoAdjust = true))
        ReaderAppearanceStore(source).save(imageAppearance)
        val narration = NarrationSettings(1.3f, 0.8f, continuous = true)
        NarrationSettingsStore(source).save(narration)
        assertTrue(File("${source.getDatabasePath("app_database.db").path}-wal").isFile)
        val flutterPreferences = """{"__prefsBackupVersion":1,"ttsRate":{"type":"double","value":0.5},"readTheme":{"type":"string","value":"{\"backgroundColor\":\"fffbfbf3\",\"textColor\":\"ff343434\",\"backgroundImagePath\":\"\"}"}}"""
        File(source.filesDir, "flutter_preferences_backup.json").writeText(flutterPreferences)
        val cover = File(source.filesDir, "cover/sample.png").apply {
            parentFile!!.mkdirs()
            writeText("cover")
        }
        val archive = File(root, "native-backup.zip")
        val secretToken = "progress-secret-${UUID.randomUUID()}"
        val secretEndpoint = "https://private-sync.example"
        source.getSharedPreferences("native_progress_sync", Context.MODE_PRIVATE).edit()
            .putString("token", secretToken).putString("endpoint", secretEndpoint)
            .putString("cursor", "123").commit()

        NativeBackupManager(source).export(Uri.fromFile(archive), sourceStore)
        assertEquals("Native backup", sourceStore.all().single().title)
        ZipFile(archive).use { zip ->
            for (path in listOf("databases/app_database.db", "file/native.txt",
                "cover/sample.png", "anx_shared_prefs.json", NativeSettingsBackup.ENTRY,
                "native/fonts/${font.id}", "bgimg/$imageId")) {
                assertTrue("Missing $path", zip.getEntry(path) != null)
            }
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val contents = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                assertFalse("Token leaked into ${entry.name}", contents.contains(secretToken))
                assertFalse("Endpoint leaked into ${entry.name}", contents.contains(secretEndpoint))
            }
        }
        assertTrue(cover.isFile)
        sourceStore.close()

        val destination = SandboxContext(root, "destination")
        val destinationStore = BookStore(destination)
        val obsoleteFont = File(destination.filesDir, "fonts/obsolete.ttf").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        NativeBackupManager(destination).restore(Uri.fromFile(archive), destinationStore)
        assertEquals("Native backup", destinationStore.all().single().title)
        assertEquals("Native backup", destinationStore.all().single().file.readText())
        assertEquals(2, destinationStore.all().single().position)
        assertEquals(34, destinationStore.textOffset(bookId, 2))
        assertEquals(chapterRule.id, TxtChapterRuleStore(destination).activeRule().id)
        assertTrue(TxtChapterRuleStore(destination).ruleForBook(bookId)
            .containsMatchIn("Part 2: Return"))
        assertFalse(ReaderControlsStore(destination).volumeKeysTurnPages())
        assertEquals(BookshelfSort(BookSortField.TITLE, descending = false),
            BookshelfSortStore(destination).load())
        assertEquals(imageAppearance, ReaderAppearanceStore(destination).load())
        assertEquals(android.graphics.Color.GREEN,
            ReaderAppearanceStore(destination).bitmap(imageAppearance.backgroundImage!!)!!.getPixel(0, 0))
        assertTrue(ReaderAppearanceStore(destination).fonts().any { it.id == font.id })
        assertFalse(obsoleteFont.exists())
        assertEquals(narration, NarrationSettingsStore(destination).load())
        assertEquals("cover", File(destination.filesDir, "cover/sample.png").readText())
        assertEquals(NativeLegacyPreferenceFilter.portable(JSONObject(flutterPreferences)).toString(),
            File(destination.filesDir, "flutter_preferences_backup.json").readText())
        assertFalse(NativeProgressSync(destination).account().signedIn)
        destinationStore.close()
    }

    @Test fun nativeAndFlutterStyleArchivesPreserveEditedWorkspaceNotes() = withSandbox { root ->
        val source = SandboxContext(root, "source")
        val sourceStore = BookStore(source)
        val bookFile = addBook(source, sourceStore, "Notes backup", "notes.txt")
        val bookId = sourceStore.all().single().id
        val workspace = ReadingNoteWorkspaceStore(sourceStore)
        val noteId = workspace.createManual(bookId, "First title", "First body",
            listOf("Original"), "active", false)
        workspace.save(noteId, "Edited title", "Edited body", listOf("Topic", "Focus"),
            "archived", true)
        val nativeArchive = File(root, "notes-native.zip")
        NativeBackupManager(source).export(Uri.fromFile(nativeArchive), sourceStore)
        val flutterStyleArchive = File(root, "notes-flutter-style.zip")
        archive(flutterStyleArchive, listOf(
            "databases/app_database.db" to source.getDatabasePath("app_database.db"),
            "file/notes.txt" to bookFile,
        ))

        for ((name, backup) in listOf("native" to nativeArchive,
            "flutter-style" to flutterStyleArchive)) {
            val destination = SandboxContext(root, name)
            val restoredStore = BookStore(destination)
            try {
                NativeBackupManager(destination).restore(Uri.fromFile(backup), restoredStore)
                val note = restoredStore.libraryNotes().single { it.workspaceId == noteId }
                assertEquals("Edited title", note.title)
                assertEquals("Edited body", note.readerNote)
                assertEquals(listOf("Focus", "Topic"), note.tags)
                assertEquals("archived", note.status)
                assertTrue(note.favorite)
                assertEquals(listOf("Edited body", "First body"),
                    ReadingNoteWorkspaceStore(restoredStore).revisions(noteId).map { it.body })
            } finally { restoredStore.close() }
        }
    }

    @Test fun restoresArchiveWithUncheckpointedWal() = withSandbox { root ->
        val source = SandboxContext(root, "source")
        val sourceStore = BookStore(source)
        sourceStore.setWriteAheadLoggingEnabled(true)
        assertTrue(sourceStore.writableDatabase.isWriteAheadLoggingEnabled)
        val sourceFile = addBook(source, sourceStore, "In WAL", "wal.txt")
        val sourceDb = source.getDatabasePath("app_database.db")
        val sourceWal = File("${sourceDb.path}-wal")
        assertTrue(sourceWal.isFile)
        val archive = File(root, "wal-backup.zip")
        archive(archive, listOf(
            "databases/app_database.db" to sourceDb,
            "databases/app_database.db-wal" to sourceWal,
            "file/wal.txt" to sourceFile,
        ))
        sourceStore.close()

        val destination = SandboxContext(root, "destination")
        val destinationStore = BookStore(destination)
        NativeBackupManager(destination).restore(Uri.fromFile(archive), destinationStore)
        assertEquals("In WAL", destinationStore.all().single().title)
        assertFalse(File("${destination.getDatabasePath("app_database.db").path}-wal").exists())
        destinationStore.close()
    }

    @Test fun restoresArchiveFromFlutterZipEncoder() = withSandbox { root ->
        val destination = SandboxContext(root, "destination")
        val store = BookStore(destination)
        val archive = File(root, "flutter-encoder.zip")
        InstrumentationRegistry.getInstrumentation().context.assets.open("flutter-encoder-backup.zip")
            .use { input -> archive.outputStream().use(input::copyTo) }

        NativeBackupManager(destination).restore(Uri.fromFile(archive), store)
        assertEquals("Flutter fixture", store.all().single().title)
        assertEquals("Flutter fixture", store.all().single().file.readText())
        val preferences = JSONObject(File(destination.filesDir, "flutter_preferences_backup.json").readText())
        assertEquals(1, preferences.getInt("__prefsBackupVersion"))
        assertEquals(1.3, preferences.getJSONObject("ttsRate").getDouble("value"), 0.0001)
        assertEquals(1.8, JSONObject(preferences.getJSONObject("readStyle")
            .getString("value")).getDouble("fontSize"), 0.0001)
        assertEquals(1.6f, ReaderAppearanceStore(destination).load().lineSpacing)
        assertEquals(18, ReaderAppearanceStore(destination).load().fontSize)
        assertEquals(2.6f, NarrationSettingsStore(destination).load().rate, 0.0001f)
        store.close()
    }

    @Test fun flutterSettingsRestoreValidValuesAndIgnoreUnmappedFields() = withSandbox { root ->
        val source = SandboxContext(root, "source")
        val sourceStore = BookStore(source)
        assertEquals(22, sourceStore.writableDatabase.version)
        sourceStore.close()
        val preferences = File(root, "flutter-prefs.json").apply { writeText("""
            {"__prefsBackupVersion":1,
             "readStyle":{"type":"string","value":"{\"fontSize\":2.4,\"lineHeight\":1.9}"},
             "ttsRate":{"type":"double","value":0.6},
             "ttsPitch":{"type":"double","value":1.4},
             "volumeKeyTurnPage":{"type":"bool","value":false},
             "sortField":{"type":"string","value":"progress"},
             "sortOrder":{"type":"string","value":"ascending"},
             "bgimg":{"type":"string","value":"{\"type\":\"localFile\",\"path\":\"flutter.png\",\"alignment\":\"center\",\"blur\":2.0,\"opacity\":0.6}"},
             "bgimgFit":{"type":"string","value":"stretch"},
             "autoAdjustReadingTheme":{"type":"bool","value":true},
             "readTheme":{"type":"string","value":"{\"backgroundColor\":\"ff121212\",\"textColor\":\"ffcccccc\",\"backgroundImagePath\":\"\"}"}}
        """.trimIndent()) }
        val flutterImage = File(root, "flutter.png").apply {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
        }
        val archive = File(root, "flutter-settings.zip")
        archive(archive, listOf(
            "databases/app_database.db" to source.getDatabasePath("app_database.db"),
            "anx_shared_prefs.json" to preferences,
            "bgimg/flutter.png" to flutterImage,
        ))

        val destination = SandboxContext(root, "destination")
        val destinationStore = BookStore(destination)
        ReaderAppearanceStore(destination).save(ReaderAppearance(24, 1.4f, ReaderPalette.DARK))
        NarrationSettingsStore(destination).save(NarrationSettings(1.8f, 0.8f, continuous = true))
        NativeBackupManager(destination).restore(Uri.fromFile(archive), destinationStore)

        val appearance = ReaderAppearanceStore(destination).load()
        assertEquals(24, appearance.fontSize)
        assertEquals(1.9f, appearance.lineSpacing, 0.0001f)
        assertEquals(ReaderPalette.CUSTOM, appearance.palette)
        assertEquals("FF121212", appearance.customBackground)
        assertEquals("FFCCCCCC", appearance.customForeground)
        assertEquals(ReaderBackgroundImage("localFile", "flutter.png", blur = 2f,
            opacity = 0.6f, fit = "stretch", autoAdjust = true), appearance.backgroundImage)
        val narration = NarrationSettingsStore(destination).load()
        assertEquals(1.2f, narration.rate, 0.0001f)
        assertEquals(1.4f, narration.pitch, 0.0001f)
        assertTrue(narration.continuous)
        assertFalse(ReaderControlsStore(destination).volumeKeysTurnPages())
        assertEquals(BookshelfSort(BookSortField.PROGRESS, descending = false),
            BookshelfSortStore(destination).load())
        assertEquals(NativeLegacyPreferenceFilter.portable(JSONObject(preferences.readText())).toString(),
            File(destination.filesDir, "flutter_preferences_backup.json").readText())
        destinationStore.close()
    }

    @Test fun invalidArchivesLeaveCurrentLibraryIntact() = withSandbox { root ->
        val destination = SandboxContext(root, "destination")
        val store = BookStore(destination)
        val oldFile = addBook(destination, store, "Old book", "old.txt")
        val corrupt = File(root, "corrupt.zip")
        val bogus = File(root, "bogus.db").apply { writeText("not sqlite") }
        archive(corrupt, listOf("databases/app_database.db" to bogus))
        assertThrows(IOException::class.java) {
            NativeBackupManager(destination).restore(Uri.fromFile(corrupt), store)
        }
        assertEquals("Old book", store.all().single().title)
        assertTrue(oldFile.isFile)

        val traversal = File(root, "traversal.zip")
        archive(traversal, listOf("file/../outside.txt" to bogus))
        assertThrows(IOException::class.java) {
            NativeBackupManager(destination).restore(Uri.fromFile(traversal), store)
        }
        assertEquals("Old book", store.all().single().title)
        assertFalse(File(destination.filesDir, "outside.txt").exists())
        store.close()
    }

    @Test fun invalidPreferencesLeaveCurrentLibraryAndPreferencesIntact() = withSandbox { root ->
        val source = SandboxContext(root, "source")
        val sourceStore = BookStore(source)
        assertEquals(22, sourceStore.writableDatabase.version)
        sourceStore.close()
        val invalid = File(root, "invalid-prefs.json").apply { writeText("not json") }
        val archive = File(root, "invalid-prefs.zip")
        archive(archive, listOf(
            "databases/app_database.db" to source.getDatabasePath("app_database.db"),
            "anx_shared_prefs.json" to invalid,
        ))

        val destination = SandboxContext(root, "destination")
        val store = BookStore(destination)
        val oldFile = addBook(destination, store, "Old book", "old.txt")
        val oldPrefs = File(destination.filesDir, "flutter_preferences_backup.json")
            .apply { writeText("{\"old\":true}") }
        assertThrows(IOException::class.java) {
            NativeBackupManager(destination).restore(Uri.fromFile(archive), store)
        }
        assertEquals("Old book", store.all().single().title)
        assertTrue(oldFile.isFile)
        assertEquals("{\"old\":true}", oldPrefs.readText())

        val invalidNative = File(root, "invalid-native.json").apply { writeText("{\"version\":99}") }
        val nativeArchive = File(root, "invalid-native.zip")
        archive(nativeArchive, listOf(
            "databases/app_database.db" to source.getDatabasePath("app_database.db"),
            NativeSettingsBackup.ENTRY to invalidNative,
        ))
        assertThrows(IOException::class.java) {
            NativeBackupManager(destination).restore(Uri.fromFile(nativeArchive), store)
        }
        assertEquals("Old book", store.all().single().title)
        assertEquals("{\"old\":true}", oldPrefs.readText())

        val wrongType = NativeSettingsBackup.capture(destination)
        wrongType.getJSONObject("native_reader_appearance").put("font_size",
            JSONObject().put("type", "string").put("value", "invalid"))
        invalidNative.writeText(wrongType.toString())
        archive(nativeArchive, listOf(
            "databases/app_database.db" to source.getDatabasePath("app_database.db"),
            NativeSettingsBackup.ENTRY to invalidNative,
        ))
        assertThrows(IOException::class.java) {
            NativeBackupManager(destination).restore(Uri.fromFile(nativeArchive), store)
        }
        assertEquals("Old book", store.all().single().title)
        store.close()
    }

    @Test fun interruptedReplacementRestoresPreviousFiles() = withSandbox { root ->
        val context = SandboxContext(root, "destination")
        val old = File(context.filesDir, "file/old.txt").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        val rollback = File(context.filesDir, ".native-restore-old/file").apply { parentFile!!.mkdirs() }
        assertTrue(old.parentFile!!.renameTo(rollback))
        File(context.filesDir, "file/new.txt").apply { parentFile!!.mkdirs(); writeText("new") }
        val preferences = File(context.filesDir, "flutter_preferences_backup.json")
            .apply { writeText("old preferences") }
        val previousPreferences = File(context.filesDir, ".native-restore-old/preferences")
        assertTrue(preferences.renameTo(previousPreferences))
        preferences.writeText("new preferences")
        val fontDir = File(context.filesDir, "fonts").apply { mkdirs() }
        val oldFont = File(fontDir, "old.ttf").apply { writeText("old font") }
        val oldFontDirectory = File(context.filesDir, ".native-restore-old/native-fonts")
        assertTrue(fontDir.renameTo(oldFontDirectory))
        File(context.filesDir, "fonts/new.ttf").apply { parentFile!!.mkdirs(); writeText("new font") }
        val appearance = ReaderAppearance(21, 1.7f, ReaderPalette.WARM)
        ReaderAppearanceStore(context).save(appearance)
        File(context.filesDir, ".native-restore-old/native-settings.json")
            .writeText(NativeSettingsBackup.capture(context).toString())
        ReaderAppearanceStore(context).save(ReaderAppearance(25, 2f, ReaderPalette.DARK))
        File(context.filesDir, ".native-restore-marker").writeText(
            JSONObject().put("file", true).put("preferences", true)
                .put("native-fonts", true).toString())

        NativeBackupManager(context).recoverInterrupted()
        assertEquals("old", old.readText())
        assertFalse(File(context.filesDir, "file/new.txt").exists())
        assertEquals("old preferences", preferences.readText())
        assertEquals("old font", oldFont.readText())
        assertFalse(File(context.filesDir, "fonts/new.ttf").exists())
        assertEquals(appearance, ReaderAppearanceStore(context).load())
        assertFalse(File(context.filesDir, ".native-restore-marker").exists())
    }

    @Test fun webDavPublishesDatabaseLastAndRejectsRemoteChanges() = withSandbox { root ->
        val context = SandboxContext(root, "webdav-source")
        val store = BookStore(context)
        addBook(context, store, "Published book", "published.txt")
        val remote = FakeWebDavClient()
        val sync = NativeWebDavSync(context, remote)
        val empty = sync.status()
        sync.upload(store, empty)
        assertEquals(listOf("inkflow/data/file/published.txt", "inkflow/database22.db"), remote.uploads)
        assertEquals(WebDavWriteCondition.Absent, remote.databaseConditions.single())
        assertEquals("Published book", remote.files["inkflow/data/file/published.txt"]!!
            .toString(Charsets.UTF_8))
        val oldStatus = sync.status()
        remote.files["inkflow/database22.db"] = ByteArray(1024)
        assertThrows(IOException::class.java) { sync.upload(store, oldStatus) }
        assertEquals(1, remote.uploads.count { it == "inkflow/database22.db" })
        store.close()
    }

    @Test fun webDavRejectsDatabaseChangeBetweenStatusCheckAndPut() = withSandbox { root ->
        val context = SandboxContext(root, "webdav-race")
        val store = BookStore(context)
        addBook(context, store, "Racing book", "racing.txt")
        val remote = FakeWebDavClient()
        val sync = NativeWebDavSync(context, remote)
        sync.upload(store, sync.status())
        val expected = sync.status()
        val original = remote.files["inkflow/database22.db"]!!.copyOf()
        remote.beforeDatabaseUpload = {
            remote.files["inkflow/database22.db"] = "concurrent update".toByteArray()
        }
        assertThrows(IOException::class.java) { sync.upload(store, expected) }
        assertEquals("concurrent update", remote.files["inkflow/database22.db"]!!.toString(Charsets.UTF_8))
        assertEquals(WebDavWriteCondition.Match(expected.database!!.etag!!),
            remote.databaseConditions.last())
        assertFalse(original.contentEquals(remote.files["inkflow/database22.db"]!!))
        store.close()
    }

    @Test fun webDavWithoutEtagFallsBackToMetadataCheck() = withSandbox { root ->
        val context = SandboxContext(root, "webdav-no-etag")
        val store = BookStore(context)
        addBook(context, store, "No ETag book", "no-etag.txt")
        val remote = FakeWebDavClient()
        val sync = NativeWebDavSync(context, remote)
        sync.upload(store, sync.status())
        remote.provideEtags = false
        sync.upload(store, sync.status())
        assertEquals(null, remote.databaseConditions.last())
        store.close()
    }

    @Test fun webDavDownloadValidatesFilesBeforeReplacingAndPreservesLocalRules() = withSandbox { root ->
        val source = SandboxContext(root, "webdav-remote")
        val sourceStore = BookStore(source)
        val sourceFile = addBook(source, sourceStore, "Remote book", "remote.txt")
        sourceStore.close()
        val remote = FakeWebDavClient()
        remote.files["inkflow/database22.db"] = source.getDatabasePath("app_database.db").readBytes()

        val local = SandboxContext(root, "webdav-local")
        val localStore = BookStore(local)
        val oldFile = addBook(local, localStore, "Local book", "local.txt")
        val oldRule = TxtChapterRule(TxtChapterRuleStore.newId(), "Local rule", "^Part [0-9]+:.*$")
        TxtChapterRuleStore(local).apply { saveCustom(oldRule); select(oldRule.id); bindBook(1, oldRule) }
        localStore.saveTextPosition(1, 0, 23)
        val sync = NativeWebDavSync(local, remote)
        val expected = sync.status()
        assertThrows(IOException::class.java) { sync.download(localStore, expected) }
        assertEquals("Local book", localStore.all().single().title)
        assertTrue(oldFile.isFile)

        remote.files["inkflow/data/file/remote.txt"] = sourceFile.readBytes()
        sync.download(localStore, expected)
        assertEquals("Remote book", localStore.all().single().title)
        assertFalse(oldFile.exists())
        assertEquals(oldRule.id, TxtChapterRuleStore(local).activeRule().id)
        assertFalse(local.getSharedPreferences("native_txt_rules", Context.MODE_PRIVATE)
            .contains("book:1"))
        assertEquals(0, localStore.textOffset(1, 0))
        localStore.close()
    }

    @Test fun groupsRoundTripAndDeletedGroupMovesBooksToParent() = withSandbox { root ->
        val source = SandboxContext(root, "groups-source")
        val store = BookStore(source)
        addBook(source, store, "Grouped book", "grouped.txt")
        val parent = store.createGroup("Stories")
        val child = store.createGroup("Favorites", parent.id)
        store.moveBookToGroup(store.all().single().id, child.id)
        store.renameGroup(child.id, "Top picks")
        assertEquals("Top picks", store.groups().single { it.id == child.id }.name)

        val archive = File(root, "groups.zip")
        NativeBackupManager(source).export(Uri.fromFile(archive), store)
        val restored = SandboxContext(root, "groups-destination")
        val restoredStore = BookStore(restored)
        NativeBackupManager(restored).restore(Uri.fromFile(archive), restoredStore)
        assertEquals(child.id, restoredStore.all().single().groupId)
        assertEquals(parent.id, restoredStore.groups().single { it.id == child.id }.parentId)
        restoredStore.deleteGroup(child.id)
        assertEquals(parent.id, restoredStore.all().single().groupId)
        assertFalse(restoredStore.groups().any { it.id == child.id })
        assertThrows(IllegalArgumentException::class.java) {
            restoredStore.moveBookToGroup(restoredStore.all().single().id, child.id)
        }
        restoredStore.deleteGroup(parent.id)
        assertEquals(0L, restoredStore.all().single().groupId)
        val obsoleteParent = restoredStore.createGroup("Obsolete")
        val orphan = restoredStore.createGroup("Orphan", obsoleteParent.id)
        restoredStore.moveBookToGroup(restoredStore.all().single().id, orphan.id)
        restoredStore.writableDatabase.update("tb_groups", ContentValues().apply {
            put("is_deleted", 1)
        }, "id = ?", arrayOf(obsoleteParent.id.toString()))
        restoredStore.deleteGroup(orphan.id)
        assertEquals(0L, restoredStore.all().single().groupId)
        restoredStore.close()
        store.close()
    }

    @Test fun vocabularyReviewAndBackupRoundTripUseFlutterColumns() = withSandbox { root ->
        val source = SandboxContext(root, "vocabulary-source")
        val sourceStore = BookStore(source)
        val vocabulary = NativeVocabularyStore(sourceStore)
        val book = StoredBook(42, "Source book", "", File(root, "unused.epub"), "epub", 0, "")
        val dictionary = NativeDictionaryEntry("word", "/wɜːd/", "https://example.com/word.mp3",
            "noun", "A unit of language.", "A word in a sentence.")
        assertTrue(vocabulary.add(book, "Chapter one", "Word", "A word in its sentence.",
            "epubcfi(/6/2!/4/2:2)", "释义", dictionary))
        assertFalse(vocabulary.add(book, "Chapter two", " word! ", "Duplicate", "", ""))
        val added = vocabulary.all().single()
        assertEquals("A word in its sentence.", added.sourceSentence)
        assertEquals("释义", added.definitionCn)
        assertEquals("A unit of language.", added.definitionEn)
        assertEquals("/wɜːd/", added.phonetic)
        assertEquals("noun", added.partOfSpeech)
        assertEquals("https://example.com/word.mp3", added.audioUrl)
        sourceStore.writableDatabase.execSQL("UPDATE tb_vocabulary SET next_review_at = ? WHERE id = ?",
            arrayOf("invalid", added.id))
        val recovered = vocabulary.all().single().nextReviewAt
        assertTrue(recovered in System.currentTimeMillis() - 5_000..System.currentTimeMillis() + 5_000)
        val now = 1_700_000_000_000L
        vocabulary.review(added.id, VocabularyReview.HARD, now)
        assertEquals("hard", vocabulary.all().single().familiarity)
        assertEquals(1, vocabulary.all().single().wrongCount)
        vocabulary.review(added.id, VocabularyReview.FAMILIAR, now)
        assertEquals(1, vocabulary.all().single().reviewStage)
        assertEquals(now + 86_400_000L, vocabulary.all().single().nextReviewAt)
        vocabulary.review(added.id, VocabularyReview.MASTERED, now)
        assertTrue(vocabulary.all().single().isMastered)
        assertEquals(2, vocabulary.all().single().correctCount)

        val archive = File(root, "vocabulary.zip")
        NativeBackupManager(source).export(Uri.fromFile(archive), sourceStore)
        val restored = SandboxContext(root, "vocabulary-restored")
        val restoredStore = BookStore(restored)
        NativeBackupManager(restored).restore(Uri.fromFile(archive), restoredStore)
        val restoredVocabulary = NativeVocabularyStore(restoredStore)
        assertEquals("Word", restoredVocabulary.all().single().word)
        assertEquals("mastered", restoredVocabulary.all().single().familiarity)
        assertEquals("A unit of language.", restoredVocabulary.all().single().definitionEn)
        restoredVocabulary.remove(added.id)
        assertTrue(restoredVocabulary.all().isEmpty())
        restoredStore.close()
        sourceStore.close()
    }

    @Test fun vocabularyEnrichmentFillsOnlyMissingFields() = withSandbox { root ->
        val context = SandboxContext(root, "vocabulary-enrichment")
        val store = BookStore(context)
        val vocabulary = NativeVocabularyStore(store)
        val book = StoredBook(42, "Source", "", File(root, "unused.epub"), "epub", 0, "")
        assertTrue(vocabulary.add(book, "Chapter", "Hello", "Hello, friend!", "", "手填释义"))
        vocabulary.enrich(NativeDictionaryEntry("hello", "/həˈloʊ/", null, "exclamation",
            "Used as a greeting.", "Hello, friend!"))
        val entry = vocabulary.all().single()
        assertEquals("手填释义", entry.definitionCn)
        assertEquals("Used as a greeting.", entry.definitionEn)
        assertEquals("/həˈloʊ/", entry.phonetic)
        vocabulary.enrich(NativeDictionaryEntry("hello", "/replacement/", null, null,
            "Replacement", null))
        assertEquals("Used as a greeting.", vocabulary.all().single().definitionEn)
        vocabulary.remove(entry.id)
        vocabulary.enrich(NativeDictionaryEntry("hello", null, null, null, "Late", null))
        assertTrue(vocabulary.all().isEmpty())
        store.close()
    }

    @Test fun movingGroupRejectsCyclesAndUnavailableParents() = withSandbox { root ->
        val context = SandboxContext(root, "group-move")
        val store = BookStore(context)
        val first = store.createGroup("First")
        val child = store.createGroup("Child", first.id)
        val other = store.createGroup("Other")
        store.moveGroup(child.id, other.id)
        assertEquals(other.id, store.groups().single { it.id == child.id }.parentId)
        assertThrows(IllegalArgumentException::class.java) { store.moveGroup(other.id, child.id) }
        assertThrows(IllegalArgumentException::class.java) { store.moveGroup(child.id, child.id) }
        assertEquals(0L, store.groups().single { it.id == other.id }.parentId)
        store.moveGroup(child.id, 0)
        assertEquals(0L, store.groups().single { it.id == child.id }.parentId)
        store.deleteGroup(other.id)
        assertThrows(IllegalArgumentException::class.java) { store.moveGroup(child.id, other.id) }
        assertEquals(0L, store.groups().single { it.id == child.id }.parentId)
        store.close()
    }

    private class FakeWebDavClient : WebDavClient {
        val files = mutableMapOf<String, ByteArray>()
        val uploads = mutableListOf<String>()
        val databaseConditions = mutableListOf<WebDavWriteCondition?>()
        var beforeDatabaseUpload: (() -> Unit)? = null
        var provideEtags = true
        override fun resources(path: String, depth: Int): List<WebDavResource> =
            if (path == "inkflow") files.filterKeys { it.startsWith("inkflow/database") }
                .map { (name, bytes) -> WebDavResource(name.substringAfterLast('/'), false,
                    bytes.size.toLong(), null,
                    if (provideEtags) "\"${bytes.contentHashCode()}\"" else null) }
            else emptyList()
        override fun ensureDirectory(path: String) = Unit
        override fun upload(path: String, file: File, condition: WebDavWriteCondition?) {
            if (path == "inkflow/database22.db") {
                beforeDatabaseUpload?.invoke()
                databaseConditions += condition
                val existing = files[path]
                when (condition) {
                    WebDavWriteCondition.Absent -> if (existing != null) throw IOException("HTTP 412")
                    is WebDavWriteCondition.Match -> if (existing == null ||
                        "\"${existing.contentHashCode()}\"" != condition.etag) throw IOException("HTTP 412")
                    null -> Unit
                }
            }
            files[path] = file.readBytes()
            uploads += path
        }
        override fun download(path: String, target: File, maxBytes: Long) {
            val bytes = files[path] ?: throw IOException("Missing remote file: $path")
            if (bytes.size > maxBytes) throw IOException("Remote file is too large")
            target.writeBytes(bytes)
        }
    }

    private fun addBook(context: Context, store: BookStore, title: String, name: String): File {
        val file = File(context.filesDir, "file/$name").apply {
            parentFile!!.mkdirs()
            writeText(title)
        }
        store.writableDatabase.insertOrThrow("tb_books", null, ContentValues().apply {
            put("title", title)
            put("file_path", "file/$name")
            put("is_deleted", 0)
        })
        return file
    }

    private fun archive(file: File, entries: List<Pair<String, File>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, source) ->
                zip.putNextEntry(ZipEntry(name))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun withSandbox(test: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            test(root)
        } finally {
            root.deleteRecursively()
            testPrefs.forEach(context::deleteSharedPreferences)
        }
    }

    private inner class SandboxContext(root: File, name: String) :
        ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val directory = File(root, name).apply { mkdirs() }
        private val prefsPrefix = "backup-test-${root.name}-$name-"

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(directory, "databases/$name").apply {
            parentFile!!.mkdirs()
        }
        override fun openOrCreateDatabase(name: String, mode: Int,
            factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int,
            factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory,
                SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val key = prefsPrefix + name
            testPrefs += key
            return baseContext.getSharedPreferences(key, mode)
        }
    }
}

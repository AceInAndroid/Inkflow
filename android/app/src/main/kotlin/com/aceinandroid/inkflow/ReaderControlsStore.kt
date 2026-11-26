package com.aceinandroid.inkflow

import android.content.Context
import com.aceinandroid.inkflow.core.BookshelfViewMode
import com.aceinandroid.inkflow.core.EInkDisplayConfig
import com.aceinandroid.inkflow.core.ReaderEngineMode

class ReaderControlsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "native_reader_appearance", Context.MODE_PRIVATE)

    fun swipeToTurnPage(): Boolean = preferences.getBoolean("swipe_to_turn_page", false)

    fun setSwipeToTurnPage(enabled: Boolean) {
        preferences.edit().putBoolean("swipe_to_turn_page", enabled).apply()
    }

    fun volumeKeysTurnPages(): Boolean = preferences.getBoolean("volume_key_turn_page", true)

    fun setVolumeKeysTurnPages(enabled: Boolean) {
        check(preferences.edit().putBoolean("volume_key_turn_page", enabled).commit()) {
            "Cannot save reader controls"
        }
    }

    fun readerEngineMode(): ReaderEngineMode {
        val raw = preferences.getString("reader_engine_mode", null)
        return ReaderEngineMode.entries.firstOrNull { it.name == raw } ?: ReaderEngineMode.NATIVE_PAGED
    }

    fun setReaderEngineMode(mode: ReaderEngineMode) {
        preferences.edit().putString("reader_engine_mode", mode.name).apply()
    }

    fun bookshelfViewMode(): BookshelfViewMode {
        val raw = preferences.getString("bookshelf_view_mode", null)
        return BookshelfViewMode.entries.firstOrNull { it.name == raw } ?: BookshelfViewMode.GRID
    }

    fun setBookshelfViewMode(mode: BookshelfViewMode) {
        preferences.edit().putString("bookshelf_view_mode", mode.name).apply()
    }

    fun eInkConfig(): EInkDisplayConfig = EInkDisplayConfig(
        enabled = preferences.getBoolean("eink_mode_enabled", false),
        highContrast = preferences.getBoolean("eink_high_contrast", true),
        disableAnimations = preferences.getBoolean("eink_disable_animations", true),
        largeTouchTargets = preferences.getBoolean("eink_large_touch_targets", true),
    )

    fun setEInkEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("eink_mode_enabled", enabled).apply()
    }
}

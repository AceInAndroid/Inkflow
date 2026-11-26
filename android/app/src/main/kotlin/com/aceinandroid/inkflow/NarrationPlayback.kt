package com.aceinandroid.inkflow

import android.content.Context

internal object NarrationPlayback {
    private var bookId: Long? = null
    private var narrator: NativeNarrator? = null

    @Synchronized fun obtain(context: Context, id: Long): NativeNarrator {
        if (bookId != id || narrator == null) {
            narrator?.close()
            narrator = NativeNarrator(context.applicationContext)
            bookId = id
        }
        return narrator!!
    }

    @Synchronized fun current(): NativeNarrator? = narrator

    @Synchronized fun releaseIfIdle(id: Long) {
        if (bookId != id) return
        if (narrator?.state in setOf(NarrationState.PLAYING, NarrationState.PAUSED, NarrationState.PREPARING)) return
        narrator?.close()
        narrator = null
        bookId = null
    }
}

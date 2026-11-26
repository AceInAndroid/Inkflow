package com.aceinandroid.inkflow

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavAccountStoreTest {
    @Test fun credentialsAreEncryptedAndExcludedFromNativeBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WebDavAccountStore(context)
        val previous = store.load()
        try {
            store.save("https://example.invalid/dav", "test-user", "test-password-unique")
            val loaded = store.load()!!
            assertEquals("https://example.invalid/dav", loaded.endpoint)
            assertEquals("test-user", loaded.username)
            assertEquals("test-password-unique", loaded.password)
            val preferences = context.getSharedPreferences("native_webdav_account", Context.MODE_PRIVATE)
            assertFalse(preferences.getString("password", "").orEmpty().contains("test-password-unique"))
            assertFalse(NativeSettingsBackup.capture(context).toString().contains("test-password-unique"))
            store.clear()
            assertNull(store.load())
        } finally {
            if (previous == null) store.clear()
            else store.save(previous.endpoint, previous.username, previous.password)
        }
    }
}

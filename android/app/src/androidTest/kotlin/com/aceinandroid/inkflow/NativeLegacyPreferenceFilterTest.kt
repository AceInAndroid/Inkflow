package com.aceinandroid.inkflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NativeLegacyPreferenceFilterTest {
    @Test fun preservesSupportedLegacyValuesButDropsUnknownContainersAndNestedSecrets() {
        val input = JSONObject().put("__prefsBackupVersion", 1)
            .put("readStyle", entry("string", """{"lineHeight":1.8,"fontSize":1.4,"privateToken":"fixture-private"}"""))
            .put("readTheme", entry("string", """{"backgroundColor":"FFFBFBF3","textColor":"FF343434","backgroundImagePath":"","key":"fixture-private"}"""))
            .put("ttsRate", entry("double", .7))
            .put("volumeKeyTurnPage", entry("bool", true))
            .put("chapterSplitCustomRules", entry("string", """[{"id":"fixture","name":"Parts","pattern":"^Part [0-9]+.*$","token":"fixture-private"}]"""))
            .put("retiredAccount", entry("string", "fixture-private"))
            .put("locale", entry("string", "ja"))
            .put("deviceDisplayProfile", entry("string", "eInk"))
        val filtered = NativeLegacyPreferenceFilter.portable(input)
        assertFalse(filtered.toString().contains("fixture-private"))
        assertFalse(filtered.has("retiredAccount"))
        assertFalse(filtered.has("locale"))
        assertFalse(filtered.has("deviceDisplayProfile"))
        assertEquals(1.8, JSONObject(filtered.getJSONObject("readStyle").getString("value")).getDouble("lineHeight"), 0.0)
        assertEquals(.7, filtered.getJSONObject("ttsRate").getDouble("value"), 0.0)
        assertTrue(filtered.getJSONObject("volumeKeyTurnPage").getBoolean("value"))
        assertEquals("fixture", org.json.JSONArray(filtered.getJSONObject("chapterSplitCustomRules").getString("value")).getJSONObject(0).getString("id"))
        assertEquals(filtered.toString(), NativeLegacyPreferenceFilter.portable(filtered).toString())
    }

    @Test fun rejectsUnsupportedVersionsAndDoesNotKeepMalformedValuesOrRemoteImageCredentials() {
        assertThrows(IOException::class.java) { NativeLegacyPreferenceFilter.portable(JSONObject().put("__prefsBackupVersion", 2)) }
        val filtered = NativeLegacyPreferenceFilter.portable(JSONObject().put("__prefsBackupVersion", 1)
            .put("readStyle", entry("string", "invalid"))
            .put("ttsRate", entry("string", "0.7"))
            .put("bgimg", entry("string", """{"type":"localFile","path":"https://private.invalid/image?token=fixture-private"}""")))
        assertEquals(1, filtered.length())
    }

    private fun entry(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
}

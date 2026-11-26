package com.aceinandroid.inkflow.dictionary

import android.content.Context
import com.aceinandroid.inkflow.core.OfflineLookupEntry
import com.aceinandroid.inkflow.core.dictionary.BundledChineseDictionary
import java.io.IOException

/** Asset access starts only when lookup is requested. Missing standard-flavor assets are normal. */
class NativeBundledChineseDictionary(context: Context) {
    private val assets = context.applicationContext.assets
    private val dictionary = BundledChineseDictionary { name ->
        sequenceOf("dictionaries/chinese", "flutter_assets/assets/dictionaries/chinese")
            .mapNotNull { root -> try { assets.open("$root/$name") } catch (_: IOException) { null } }
            .firstOrNull()
    }

    fun lookup(query: String): OfflineLookupEntry? = try { dictionary.lookup(query) }
        catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
        catch (_: IllegalStateException) { null }
}

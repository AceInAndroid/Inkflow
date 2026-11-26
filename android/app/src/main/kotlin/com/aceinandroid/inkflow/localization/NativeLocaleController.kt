package com.aceinandroid.inkflow.localization

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aceinandroid.inkflow.core.localization.AppLanguage
import com.aceinandroid.inkflow.core.localization.AppLanguageResolver
import com.aceinandroid.inkflow.core.localization.LanguageEnvironment
import java.util.Locale
import java.util.TimeZone

/** Device-local UI preference. This preference file is deliberately absent from portable backups. */
object NativeLocaleController {
    private const val PREFERENCES = "native_app_language"
    private const val CHOICE = "choice"
    private const val LAST_APPLIED = "last_applied_tag"
    private const val PLATFORM_INITIALIZED = "platform_initialized"
    private const val AUTOMATIC = "auto"
    private lateinit var application: Application
    private lateinit var localizedApplication: Context
    private var refreshing = false
    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    var choice by mutableStateOf<String>(AUTOMATIC)
        private set
    var resolution by mutableStateOf(AppLanguageResolver.resolve(LanguageEnvironment("", "", emptyList())))
        private set
    val locale: Locale get() = Locale.forLanguageTag(resolution.language.tag)
    val automatic: Boolean get() = choice == AUTOMATIC

    fun initialize(app: Application) {
        if (::application.isInitialized) return
        application = app
        localizedApplication = NativeLocaleContext(app)
        refresh()
    }

    fun applicationContext(): Context = localizedApplication
    internal fun registerCallbacks(callback: ComponentCallbacks) = application.registerComponentCallbacks(callback)
    internal fun unregisterCallbacks(callback: ComponentCallbacks) = application.unregisterComponentCallbacks(callback)

    /** Reads the OS configuration, never the app's previously overridden resources. */
    internal fun environment(): LanguageEnvironment {
        val locales = if (Build.VERSION.SDK_INT >= 33)
            application.getSystemService(LocaleManager::class.java).systemLocales
        else Resources.getSystem().configuration.locales
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        val first = if (locales.isEmpty) null else locales[0]
        val regionalOverride = first?.getUnicodeLocaleType("rg")?.takeIf { it.endsWith("zzzz") }
            ?.dropLast(4)?.takeIf { it.length == 2 }
        return LanguageEnvironment(regionalOverride ?: first?.country.orEmpty(), TimeZone.getDefault().id, tags)
    }

    fun select(language: AppLanguage?) {
        val value = language?.tag ?: AUTOMATIC
        check(preferences().edit().putString(CHOICE, value).commit()) { "Cannot save application language" }
        refresh(reconcilePlatform = false)
    }

    /** Reconcile external Settings changes before resolving automatic mode. All calls are on main. */
    fun refresh(reconcilePlatform: Boolean = true) {
        if (!::application.isInitialized || refreshing) return
        refreshing = true
        try {
            val prefs = preferences()
            var stored = prefs.getString(CHOICE, AUTOMATIC).orEmpty()
            val platformTag = platformTag()
            val previous = prefs.getString(LAST_APPLIED, "").orEmpty()
            if (Build.VERSION.SDK_INT >= 33 && reconcilePlatform) {
                val initialized = prefs.getBoolean(PLATFORM_INITIALIZED, false)
                // After an OS upgrade the platform has no selection yet. Migrate the local
                // manual preference once; later an empty platform list means automatic.
                if ((initialized && platformTag != previous) || (!initialized && platformTag.isNotEmpty())) {
                    stored = supported(platformTag)?.tag ?: AUTOMATIC
                    check(prefs.edit().putString(CHOICE, stored).commit())
                }
            }
            val manual = supported(stored)
            choice = manual?.tag ?: AUTOMATIC
            val oldLanguage = resolution.language
            resolution = AppLanguageResolver.resolve(environment(), manual)
            // Keep Android's "follow system" state for automatic mode. The resource wrapper
            // supplies our region/time-zone resolution without hiding the user's platform choice.
            val platformChoice = manual?.tag.orEmpty()
            if (Build.VERSION.SDK_INT >= 33) {
                // Commit before notifying Android, so its callback is not an external edit.
                check(prefs.edit().putString(LAST_APPLIED, platformChoice)
                    .putBoolean(PLATFORM_INITIALIZED, true).commit())
                if (platformTag != platformChoice) {
                    application.getSystemService(LocaleManager::class.java).applicationLocales =
                        LocaleList.forLanguageTags(platformChoice)
                }
            }
            if (oldLanguage != resolution.language) listeners.toList().forEach { it() }
        } finally {
            refreshing = false
        }
    }

    private fun preferences() = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun platformTag(): String = if (Build.VERSION.SDK_INT >= 33)
        application.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
    else ""
    private fun supported(tag: String): AppLanguage? = AppLanguage.entries.firstOrNull { it.tag.equals(tag, true) }
        ?: when (Locale.forLanguageTag(tag).language) {
            "zh" -> AppLanguage.entries.firstOrNull { it.tag == "zh-Hans" }
            "en", "ja", "ko" -> AppLanguage.entries.firstOrNull { it.tag == Locale.forLanguageTag(tag).language }
            else -> null
        }
}

/** Stable identity keeps remembered stores, reader state and in-flight work alive during UI changes. */
class NativeLocaleContext(base: Context) : ContextWrapper(base) {
    private var cachedConfig: Configuration? = null
    private var cachedResources: Resources? = null
    override fun getResources(): Resources {
        val config = Configuration(baseContext.resources.configuration).apply {
            setLocales(LocaleList(NativeLocaleController.locale))
            setLayoutDirection(NativeLocaleController.locale)
        }
        if (cachedConfig != config) {
            cachedResources = baseContext.createConfigurationContext(config).resources
            cachedConfig = config
        }
        return cachedResources!!
    }
    override fun getApplicationContext(): Context = NativeLocaleController.applicationContext()
    // On older Android, Context's default forwards back through getApplicationContext().
    // The localized application context returns itself, so callbacks must use the raw Application.
    override fun registerComponentCallbacks(callback: ComponentCallbacks) = NativeLocaleController.registerCallbacks(callback)
    override fun unregisterComponentCallbacks(callback: ComponentCallbacks) = NativeLocaleController.unregisterCallbacks(callback)
}

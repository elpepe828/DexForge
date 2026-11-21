package com.example.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

data class AppLanguageOption(
  val tag: String,
  val nativeName: String,
)

object LocaleManager {
  private const val PREFS_NAME = "dexforge_prefs"
  private const val KEY_LANGUAGE_TAG = "app_language_tag"

  val supportedLanguages: List<AppLanguageOption> = listOf(
    AppLanguageOption(tag = "", nativeName = ""), // System default (label from strings.xml)
    AppLanguageOption(tag = "es", nativeName = "Español"),
    AppLanguageOption(tag = "en", nativeName = "English"),
    AppLanguageOption(tag = "pt", nativeName = "Português"),
    AppLanguageOption(tag = "fr", nativeName = "Français"),
    AppLanguageOption(tag = "zh-CN", nativeName = "中文 (简体)"),
  )

  fun getSavedLanguageTag(context: Context): String {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(KEY_LANGUAGE_TAG, "")
      .orEmpty()
  }

  fun saveLanguageTag(context: Context, tag: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_LANGUAGE_TAG, tag)
      .apply()
  }

  fun createLocalizedContext(base: Context, languageTag: String): Context {
    val locale = if (languageTag.isBlank()) {
      Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
    } else {
      Locale.forLanguageTag(languageTag)
    }
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLocales(LocaleList(locale))
    return base.createConfigurationContext(config)
  }

  /**
   * Wraps an Activity [base] context with localized [Resources] while keeping [base]
   * as the [ContextWrapper.getBaseContext] so Jetpack Compose owner lookups
   * (ActivityResultRegistryOwner, OnBackPressedDispatcherOwner, etc.) continue to resolve.
   */
  fun wrapActivityContextForLocale(base: Context, languageTag: String): Context {
    val localizedResourcesContext = createLocalizedContext(base, languageTag)
    return object : ContextWrapper(base) {
      override fun getResources(): Resources = localizedResourcesContext.resources
      override fun getAssets(): AssetManager = localizedResourcesContext.assets
    }
  }
}

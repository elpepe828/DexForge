package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.util.LocaleManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("DexForge", appName)
  }

  @Test
  fun `main activity launches and switches language cleanly`() {
    composeRule.onNodeWithTag("language_picker_btn").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("language_option_en").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("nav_dual_files").assertIsDisplayed()
  }

  @Test
  fun `locale manager resolves all supported languages and persists preference`() {
    val base = ApplicationProvider.getApplicationContext<Context>()

    val enTitle = LocaleManager.createLocalizedContext(base, "en").getString(R.string.nav_dual_manager)
    val esTitle = LocaleManager.createLocalizedContext(base, "es").getString(R.string.nav_dual_manager)
    val ptTitle = LocaleManager.createLocalizedContext(base, "pt").getString(R.string.nav_dual_manager)
    val frTitle = LocaleManager.createLocalizedContext(base, "fr").getString(R.string.nav_dual_manager)
    val zhTitle = LocaleManager.createLocalizedContext(base, "zh-CN").getString(R.string.nav_dual_manager)

    assertEquals("Dual Manager", enTitle)
    assertEquals("Gestor Dual", esTitle)
    assertEquals("Gerenciador Dual", ptTitle)
    assertEquals("Double Panneau", frTitle)
    assertEquals("双栏管理", zhTitle)

    LocaleManager.saveLanguageTag(base, "pt")
    assertEquals("pt", LocaleManager.getSavedLanguageTag(base))
    LocaleManager.saveLanguageTag(base, "")
    assertEquals("", LocaleManager.getSavedLanguageTag(base))
  }
}

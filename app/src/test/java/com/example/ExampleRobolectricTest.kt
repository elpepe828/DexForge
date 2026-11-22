package com.example

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.engine.DeobfuscationPlan
import com.example.model.DecompilerMode
import com.example.model.DeobfuscationSuggestion
import com.example.model.DeobfuscationSymbolKind
import com.example.model.DeobfuscationUiState
import com.example.model.DexClassInfo
import com.example.model.DexEditorState
import com.example.ui.MainViewModel
import com.example.ui.components.JavaCodeEditorScreen
import com.example.ui.theme.DexForgeTheme
import com.example.util.LocaleManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `deobfuscation prompts every 90s without pausing or restarting in-flight job`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val app = ApplicationProvider.getApplicationContext<android.app.Application>()
      val vm = MainViewModel(app)
      vm.deobfuscationIoDispatcher = testDispatcher
      runCurrent()

      val sampleClass = DexClassInfo(
        fullName = "com.demo.A",
        packageName = "com.demo",
        simpleName = "A",
        dexEntryName = "classes.dex",
        methodCount = 1,
        fieldCount = 0,
        isInterface = false,
        isEnum = false,
      )
      vm.seedDexEditorForTest(
        DexEditorState(
          targetFilePath = "/tmp/classes.dex",
          targetApkPath = null,
          targetDisplayName = "classes.dex",
          selectedClass = sampleClass,
          showClassBrowser = false,
          decompilerMode = DecompilerMode.JAVA,
          javaCode = "package com.demo;\npublic class A { int a() { return 1; } }",
        )
      )

      var analyzerCalls = 0
      val deferred = CompletableDeferred<DeobfuscationPlan>()
      vm.deobfuscationAnalyzer = { _, _, _, _, _, onProgress ->
        analyzerCalls++
        onProgress(128)
        deferred.await()
      }

      vm.analyzeObfuscatedSymbols()
      runCurrent()

      // Passes 60s without aborting or prompting yet
      advanceTimeBy(65_000L)
      runCurrent()
      val at65 = vm.uiState.value.dexEditor?.deobfuscationState as DeobfuscationUiState.Loading
      assertEquals(65, at65.elapsedSeconds)
      assertEquals(128, at65.streamedChars)
      assertFalse(at65.awaitingContinueDecision)
      assertEquals(1, analyzerCalls)

      // At 90s (1m30s), prompts user while keeping the exact same analyzer call alive
      advanceTimeBy(25_000L)
      runCurrent()
      val at90 = vm.uiState.value.dexEditor?.deobfuscationState as DeobfuscationUiState.Loading
      assertEquals(90, at90.elapsedSeconds)
      assertTrue(at90.awaitingContinueDecision)
      assertEquals(1, analyzerCalls)

      // Choosing to keep trying hides prompt and schedules next check-in at +90s (180s) without restarting
      vm.continueDeobfuscationWaiting()
      val resumed = vm.uiState.value.dexEditor?.deobfuscationState as DeobfuscationUiState.Loading
      assertFalse(resumed.awaitingContinueDecision)
      assertEquals(1, analyzerCalls)

      advanceTimeBy(89_000L)
      runCurrent()
      val at179 = vm.uiState.value.dexEditor?.deobfuscationState as DeobfuscationUiState.Loading
      assertFalse(at179.awaitingContinueDecision)

      advanceTimeBy(1_000L)
      runCurrent()
      val at180 = vm.uiState.value.dexEditor?.deobfuscationState as DeobfuscationUiState.Loading
      assertEquals(180, at180.elapsedSeconds)
      assertTrue(at180.awaitingContinueDecision)
      assertEquals(1, analyzerCalls)

      // Completing the underlying call while prompt is displayed resolves immediately to Ready
      deferred.complete(
        DeobfuscationPlan(
          summary = "Simple counter helper",
          suggestions = listOf(
            DeobfuscationSuggestion("a", "getInitialCount", DeobfuscationSymbolKind.METHOD, "Returns 1")
          ),
        )
      )
      runCurrent()
      val ready = vm.uiState.value.dexEditor?.deobfuscationState as DeobfuscationUiState.Ready
      assertEquals("Simple counter helper", ready.summary)
      assertEquals(1, ready.suggestions.size)
      assertEquals(1, analyzerCalls)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun `deobfuscation dialog displays 90s continuation card and action buttons`() {
    var continued = false
    var stopped = false
    val sampleClass = DexClassInfo(
      fullName = "com.demo.A",
      packageName = "com.demo",
      simpleName = "A",
      dexEntryName = "classes.dex",
      methodCount = 1,
      fieldCount = 0,
      isInterface = false,
      isEnum = false,
    )
    composeRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        DexForgeTheme(darkTheme = true) {
          JavaCodeEditorScreen(
            state = DexEditorState(
              targetFilePath = "/tmp/classes.dex",
              targetApkPath = null,
              targetDisplayName = "classes.dex",
              selectedClass = sampleClass,
              showClassBrowser = false,
              decompilerMode = DecompilerMode.JAVA,
              javaCode = "class A {}",
              deobfuscationState = DeobfuscationUiState.Loading(
                elapsedSeconds = 90,
                streamedChars = 256,
                awaitingContinueDecision = true,
                useFastModel = false,
              ),
            ),
            onSelectClass = {},
            onOpenPackageFolder = {},
            onNavigateUpFolder = {},
            onOpenClassBrowser = {},
            onReturnToCodeEditor = {},
            onCompileAndReturnToBrowser = {},
            onDiscardAndReturnToBrowser = {},
            onSwitchDexEntry = {},
            onSearchQueryChange = {},
            onSearchScopeChange = {},
            onModeChange = {},
            onCodeChange = {},
            onUndo = {},
            onRedo = {},
            onCompileToDex = {},
            onSaveAndSign = { _, _, _ -> },
            onAnalyzeObfuscated = {},
            onAnalyzeObfuscatedFast = {},
            onContinueDeobfWaiting = { continued = true },
            onToggleDeobfSuggestion = {},
            onSetAllDeobfSelected = {},
            onApplyDeobfSuggestions = {},
            onDismissDeobfDialog = { stopped = true },
            onExportSavedFile = {},
            onShareSavedFile = {},
            onDismissSavedBanner = {},
            onClose = {},
          )
        }
      }
    }

    composeRule.onNodeWithTag("deobf_checkin_card").assertIsDisplayed()
    composeRule.onNodeWithTag("deobf_elapsed_text").assertIsDisplayed()
    composeRule.onNodeWithTag("deobf_continue_btn").assertIsDisplayed().performClick()
    assertTrue(continued)
    composeRule.onNodeWithTag("deobf_stop_btn").assertIsDisplayed().performClick()
    assertTrue(stopped)
  }
}

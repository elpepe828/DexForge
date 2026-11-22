package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.DexForgeApp
import com.example.ui.theme.DexForgeTheme
import com.example.util.LocaleManager
import com.example.util.configureAppCheck

class MainActivity : ComponentActivity() {

  override fun attachBaseContext(newBase: Context) {
    val savedTag = LocaleManager.getSavedLanguageTag(newBase)
    if (savedTag.isBlank()) {
      super.attachBaseContext(newBase)
    } else {
      super.attachBaseContext(LocaleManager.createLocalizedContext(newBase, savedTag))
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge()
    setContent {
      DexForgeTheme(darkTheme = true) {
        DexForgeApp()
      }
    }
  }
}

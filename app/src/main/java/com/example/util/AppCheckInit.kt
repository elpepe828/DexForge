package com.example.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

data class AppCheckDiagStatus(
  val initialized: Boolean = false,
  val isDebugBuild: Boolean = BuildConfig.DEBUG,
  val hasDebugTokenFromIntent: Boolean = false,
  val hasRecaptchaSiteKey: Boolean = false,
  val initErrorMessage: String? = null,
)

class NoNetworkException(message: String) : Exception(message)

class AppCheckAttestationException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

object AppCheckDiagnostics {
  @Volatile
  var lastStatus: AppCheckDiagStatus = AppCheckDiagStatus()
    internal set
}

fun configureAppCheck(context: Context, intent: Intent?) {
  try {
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(context)
    }

    if (BuildConfig.DEBUG) {
      val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
      val hasIntentToken = !debugToken.isNullOrEmpty()

      if (hasIntentToken) {
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(
          "$DEBUG_STORE_PREFIX$persistenceKey",
          Context.MODE_PRIVATE,
        )
        // Synchronous commit() is required before DebugAppCheckProviderFactory initializes.
        prefs.edit()
          .putString(DEBUG_SECRET_KEY, debugToken)
          .commit()
        Log.d(TAG, "App Check debug secret configured successfully.")
      } else {
        Log.w(TAG, "No debug token found in Intent extra.")
      }

      FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
      )
      Log.d(TAG, "DebugAppCheckProviderFactory installed.")
      AppCheckDiagnostics.lastStatus = AppCheckDiagStatus(
        initialized = true,
        isDebugBuild = true,
        hasDebugTokenFromIntent = hasIntentToken,
        hasRecaptchaSiteKey = false,
      )
    } else {
      val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
      if (resId != 0) {
        val siteKey = context.getString(resId)
        if (siteKey.isNotBlank()) {
          FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
          )
          Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
          AppCheckDiagnostics.lastStatus = AppCheckDiagStatus(
            initialized = true,
            isDebugBuild = false,
            hasDebugTokenFromIntent = false,
            hasRecaptchaSiteKey = true,
          )
        } else {
          Log.e(TAG, "recaptcha_site_key resource string is blank.")
          AppCheckDiagnostics.lastStatus = AppCheckDiagStatus(
            initialized = false,
            isDebugBuild = false,
            hasRecaptchaSiteKey = false,
            initErrorMessage = "recaptcha_site_key is blank",
          )
        }
      } else {
        Log.e(TAG, "recaptcha_site_key string resource not found in package.")
        AppCheckDiagnostics.lastStatus = AppCheckDiagStatus(
          initialized = false,
          isDebugBuild = false,
          hasRecaptchaSiteKey = false,
          initErrorMessage = "recaptcha_site_key resource missing",
        )
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to initialize Firebase App Check", e)
    AppCheckDiagnostics.lastStatus = AppCheckDiagStatus(
      initialized = false,
      isDebugBuild = BuildConfig.DEBUG,
      initErrorMessage = e.localizedMessage ?: e::class.java.simpleName,
    )
  }
}

fun isNetworkAvailable(context: Context): Boolean {
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
  val network = cm.activeNetwork ?: return false
  val caps = cm.getNetworkCapabilities(network) ?: return false
  return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

suspend fun verifyAppCheckAndNetwork(
  context: Context,
  timeoutMs: Long = 10_000L,
) {
  if (!isNetworkAvailable(context)) {
    throw NoNetworkException("No active internet connection.")
  }

  val status = AppCheckDiagnostics.lastStatus
  if (!status.initialized && status.initErrorMessage != null) {
    throw AppCheckAttestationException("App Check init error: ${status.initErrorMessage}")
  }

  if (FirebaseApp.getApps(context).isEmpty()) {
    FirebaseApp.initializeApp(context)
  }

  val appCheck = FirebaseAppCheck.getInstance()
  val tokenResult = try {
    withTimeout(timeoutMs) {
      suspendCancellableCoroutine<AppCheckToken> { cont ->
        appCheck.getAppCheckToken(false)
          .addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
          }
          .addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
          }
      }
    }
  } catch (e: TimeoutCancellationException) {
    throw AppCheckAttestationException(
      buildString {
        append("App Check token timed out after ${timeoutMs / 1000}s")
        if (BuildConfig.DEBUG && !status.hasDebugTokenFromIntent) {
          append(" (debug build without launcher debug token)")
        }
      },
      e,
    )
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    throw AppCheckAttestationException(
      buildString {
        append("App Check token failed")
        if (BuildConfig.DEBUG && !status.hasDebugTokenFromIntent) {
          append(" (debug build without launcher debug token)")
        }
        val detail = e.localizedMessage?.trim().orEmpty()
        if (detail.isNotEmpty()) append(": $detail")
      },
      e,
    )
  }

  if (tokenResult.token.isBlank()) {
    throw AppCheckAttestationException("App Check returned an empty token.")
  }
}

package com.example.engine

import android.content.Context
import android.util.Log
import com.example.model.DeobfuscationPhase
import com.example.model.DeobfuscationSymbolKind
import com.example.util.AppCheckAttestationException
import com.example.util.NoNetworkException
import com.example.util.verifyAppCheckAndNetwork
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DeobfuscationProgress(
  val phase: DeobfuscationPhase,
  val streamedChars: Int = 0,
  val chunkCount: Int = 0,
  val activeModelName: String = AiDeobfuscationEngine.PRIMARY_MODEL_NAME,
  val autoFastSelected: Boolean = false,
  val candidateCount: Int = 0,
  val isSlowInitialConnection: Boolean = false,
)

class AiConnectionStallException(
  message: String,
  val modelName: String,
  val waitedSeconds: Int,
  val isInitialStall: Boolean,
) : Exception(message)

object AiDeobfuscationEngine {

  private const val TAG = "AiDeobfuscationEngine"
  private const val MAX_CODE_CHARS_PRIMARY = 18_000
  private const val MAX_CODE_CHARS_FAST = 14_000

  // Aligned with the SDK's 180s socket floor; hung connections are aborted much earlier by our watchdog.
  private const val REQUEST_TIMEOUT_MS = 180_000L
  private const val APP_CHECK_TIMEOUT_MS = 10_000L
  private const val SLOW_FIRST_CHUNK_HINT_MS = 10_000L
  private const val FIRST_CHUNK_STALL_TIMEOUT_MS = 35_000L
  private const val MID_STREAM_IDLE_TIMEOUT_MS = 60_000L

  const val PRIMARY_MODEL_NAME = "gemini-3.8-flash"
  const val FAST_MODEL_NAME = "gemini-3.5-flash-lite"

  private val renamesSchema = Schema.obj(
    properties = mapOf(
      "summary" to Schema.string(
        description = "1-2 concise sentences explaining what this class does."
      ),
      "renames" to Schema.array(
        description = "Suggested contextual renames for obfuscated symbols.",
        items = Schema.obj(
          properties = mapOf(
            "originalName" to Schema.string(
              description = "Exact obfuscated identifier from the code."
            ),
            "suggestedName" to Schema.string(
              description = "Meaningful Java name (camelCase or PascalCase)."
            ),
            "kind" to Schema.enumeration(
              values = listOf("CLASS", "FIELD", "METHOD", "PARAM_OR_VAR"),
              description = "Symbol kind."
            ),
            "reason" to Schema.string(
              description = "Brief reason based on code context (max 12 words)."
            ),
          )
        )
      )
    )
  )

  private val standardModel by lazy {
    Firebase.ai.generativeModel(
      modelName = PRIMARY_MODEL_NAME,
      generationConfig = generationConfig {
        temperature = 0.2f
        maxOutputTokens = 3072
        responseMimeType = "application/json"
        responseSchema = renamesSchema
        thinkingConfig = thinkingConfig {
          thinkingLevel = ThinkingLevel.LOW
        }
      },
      requestOptions = RequestOptions(REQUEST_TIMEOUT_MS),
    )
  }

  private val fastModel by lazy {
    Firebase.ai.generativeModel(
      modelName = FAST_MODEL_NAME,
      generationConfig = generationConfig {
        temperature = 0.15f
        maxOutputTokens = 2048
        responseMimeType = "application/json"
        responseSchema = renamesSchema
        thinkingConfig = thinkingConfig {
          thinkingLevel = ThinkingLevel.MINIMAL
        }
      },
      requestOptions = RequestOptions(REQUEST_TIMEOUT_MS),
    )
  }

  suspend fun analyzeClassForDeobfuscation(
    context: Context,
    fullClassName: String,
    simpleClassName: String,
    javaSource: String,
    languageTag: String,
    useFastModel: Boolean = false,
    onProgress: (DeobfuscationProgress) -> Unit = {},
  ): DeobfuscationPlan {
    // Phase 1: Compact Java context & extract obfuscated symbol candidates locally (~2-10ms).
    onProgress(
      DeobfuscationProgress(
        phase = DeobfuscationPhase.PREPARING_CONTEXT,
        activeModelName = if (useFastModel) FAST_MODEL_NAME else PRIMARY_MODEL_NAME,
      )
    )

    val prepared = JavaSymbolRenamer.prepareCompactContextForAi(
      javaSource = javaSource,
      topLevelClassSimpleName = simpleClassName,
      maxChars = if (useFastModel) MAX_CODE_CHARS_FAST else MAX_CODE_CHARS_PRIMARY,
    )

    if (prepared.candidates.isEmpty()) {
      return DeobfuscationPlan(
        summary = "",
        suggestions = emptyList(),
      )
    }

    val autoFast = !useFastModel && prepared.isSmallClass
    val effectiveFast = useFastModel || autoFast
    val activeModelName = if (effectiveFast) FAST_MODEL_NAME else PRIMARY_MODEL_NAME
    val candidateCount = prepared.candidates.size

    // Phase 2: Pre-flight network + App Check token verification (fails fast in <=10s if misconfigured or offline).
    onProgress(
      DeobfuscationProgress(
        phase = DeobfuscationPhase.CHECKING_APP_CHECK,
        activeModelName = activeModelName,
        autoFastSelected = autoFast,
        candidateCount = candidateCount,
      )
    )
    verifyAppCheckAndNetwork(context, timeoutMs = APP_CHECK_TIMEOUT_MS)

    val explanationLanguage = when {
      languageTag.startsWith("es", ignoreCase = true) -> "Spanish"
      languageTag.startsWith("pt", ignoreCase = true) -> "Portuguese"
      languageTag.startsWith("fr", ignoreCase = true) -> "French"
      languageTag.startsWith("zh", ignoreCase = true) -> "Simplified Chinese"
      languageTag.startsWith("en", ignoreCase = true) -> "English"
      else -> "Spanish"
    }

    val candidateHints = prepared.candidates
      .take(36)
      .joinToString(", ") { cand ->
        val tag = when (cand.kindHint) {
          DeobfuscationSymbolKind.CLASS -> "C"
          DeobfuscationSymbolKind.METHOD -> "M"
          DeobfuscationSymbolKind.FIELD -> "F"
          DeobfuscationSymbolKind.PARAM_OR_VAR -> "V"
        }
        "${cand.name}($tag)"
      }

    val prompt = buildString {
      appendLine("Android Java deobfuscator. Infer idiomatic English Java names for obfuscated identifiers from APIs, strings, types, and control flow.")
      appendLine("Rules:")
      appendLine("- 'summary' & 'reason' in $explanationLanguage (keep reasons concise <=12 words).")
      appendLine("- 'suggestedName' valid unique Java identifier (no keywords, no SDK overrides like onCreate/toString/equals/hashCode/run).")
      appendLine("- Prioritize these detected candidates: $candidateHints")
      appendLine("Class: $fullClassName")
      appendLine("```java")
      appendLine(prepared.compactCode)
      appendLine("```")
    }

    val activeModel = if (effectiveFast) fastModel else standardModel
    val accumulated = StringBuilder()

    // Phase 3: Open SSE stream and monitor initial TTFT / stall watchdogs.
    onProgress(
      DeobfuscationProgress(
        phase = DeobfuscationPhase.CONNECTING_MODEL,
        activeModelName = activeModelName,
        autoFastSelected = autoFast,
        candidateCount = candidateCount,
      )
    )

    val startMs = System.currentTimeMillis()
    val lastActivityMs = AtomicLong(startMs)
    val chunkCounter = AtomicInteger(0)
    val slowHintSent = AtomicReference(false)
    val stallFailure = AtomicReference<AiConnectionStallException?>(null)

    try {
      coroutineScope {
        val watchdog = launch {
          while (isActive) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            val chunks = chunkCounter.get()
            if (chunks == 0) {
              val elapsedInitial = now - startMs
              if (elapsedInitial >= SLOW_FIRST_CHUNK_HINT_MS && slowHintSent.compareAndSet(false, true)) {
                onProgress(
                  DeobfuscationProgress(
                    phase = DeobfuscationPhase.CONNECTING_MODEL,
                    streamedChars = 0,
                    chunkCount = 0,
                    activeModelName = activeModelName,
                    autoFastSelected = autoFast,
                    candidateCount = candidateCount,
                    isSlowInitialConnection = true,
                  )
                )
              }
              if (elapsedInitial >= FIRST_CHUNK_STALL_TIMEOUT_MS) {
                val exc = AiConnectionStallException(
                  message = "No initial response from $activeModelName after ${FIRST_CHUNK_STALL_TIMEOUT_MS / 1000}s.",
                  modelName = activeModelName,
                  waitedSeconds = (elapsedInitial / 1000).toInt(),
                  isInitialStall = true,
                )
                stallFailure.set(exc)
                throw exc
              }
            } else {
              val idleMs = now - lastActivityMs.get()
              if (idleMs >= MID_STREAM_IDLE_TIMEOUT_MS) {
                val exc = AiConnectionStallException(
                  message = "Stream from $activeModelName stalled for ${MID_STREAM_IDLE_TIMEOUT_MS / 1000}s.",
                  modelName = activeModelName,
                  waitedSeconds = (idleMs / 1000).toInt(),
                  isInitialStall = false,
                )
                stallFailure.set(exc)
                throw exc
              }
            }
          }
        }

        try {
          activeModel.generateContentStream(prompt).collect { chunk ->
            val currentChunks = chunkCounter.incrementAndGet()
            lastActivityMs.set(System.currentTimeMillis())

            val chunkText = chunk.text.orEmpty()
            if (chunkText.isNotEmpty()) {
              accumulated.append(chunkText)
              onProgress(
                DeobfuscationProgress(
                  phase = DeobfuscationPhase.STREAMING,
                  streamedChars = accumulated.length,
                  chunkCount = currentChunks,
                  activeModelName = activeModelName,
                  autoFastSelected = autoFast,
                  candidateCount = candidateCount,
                  isSlowInitialConnection = false,
                )
              )
            } else {
              // Thought/metadata SSE chunk arrived while non-thought JSON text hasn't started yet.
              val nextPhase = if (accumulated.isEmpty()) {
                DeobfuscationPhase.THINKING
              } else {
                DeobfuscationPhase.STREAMING
              }
              onProgress(
                DeobfuscationProgress(
                  phase = nextPhase,
                  streamedChars = accumulated.length,
                  chunkCount = currentChunks,
                  activeModelName = activeModelName,
                  autoFastSelected = autoFast,
                  candidateCount = candidateCount,
                  isSlowInitialConnection = false,
                )
              )
            }
          }
        } finally {
          watchdog.cancel()
        }
      }
    } catch (e: CancellationException) {
      stallFailure.get()?.let { throw it }
      throw e
    } catch (e: Exception) {
      stallFailure.get()?.let { throw it }
      Log.e(
        TAG,
        "Deobfuscation stream failed (model=$activeModelName, chunks=${chunkCounter.get()}, streamedChars=${accumulated.length})",
        e,
      )
      throw e
    }

    val rawJson = accumulated.toString().trim()
      .takeIf { it.isNotEmpty() }
      ?: throw IllegalStateException("Empty AI response.")

    onProgress(
      DeobfuscationProgress(
        phase = DeobfuscationPhase.PARSING,
        streamedChars = rawJson.length,
        chunkCount = chunkCounter.get(),
        activeModelName = activeModelName,
        autoFastSelected = autoFast,
        candidateCount = candidateCount,
      )
    )

    return JavaSymbolRenamer.parseDeobfuscationJson(
      rawJson = rawJson,
      javaSource = javaSource,
      topLevelClassSimpleName = simpleClassName,
    )
  }

  fun formatErrorMessage(
    error: Throwable,
    fallback: String,
    offlineText: String? = null,
    stallInitialText: ((String, Int) -> String)? = null,
    appCheckText: ((String) -> String)? = null,
  ): String {
    if (error is NoNetworkException && offlineText != null) {
      return offlineText
    }
    if (error is AiConnectionStallException && error.isInitialStall && stallInitialText != null) {
      return stallInitialText(error.modelName, error.waitedSeconds)
    }
    if (error is AppCheckAttestationException && appCheckText != null) {
      return appCheckText(error.localizedMessage ?: error.message.orEmpty())
    }
    if (error is UnknownHostException && offlineText != null) {
      return "$offlineText (${error.localizedMessage ?: "DNS"})"
    }
    if (error is SocketTimeoutException || error is TimeoutCancellationException) {
      return error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
    }

    val msg = error.localizedMessage?.trim().orEmpty()
    val causeMsg = error.cause?.localizedMessage?.trim().orEmpty()
    return when {
      msg.contains("403") || causeMsg.contains("403") ->
        "HTTP 403 (App Check / Firebase permissions): ${msg.ifEmpty { causeMsg }}"
      msg.isNotEmpty() && causeMsg.isNotEmpty() && !msg.contains(causeMsg) -> "$msg ($causeMsg)"
      msg.isNotEmpty() -> msg
      causeMsg.isNotEmpty() -> causeMsg
      else -> "${error::class.java.simpleName}: $fallback"
    }
  }
}

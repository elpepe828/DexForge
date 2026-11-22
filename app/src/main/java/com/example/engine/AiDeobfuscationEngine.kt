package com.example.engine

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.withTimeout

object AiDeobfuscationEngine {

  private const val MAX_CODE_CHARS = 24_000
  private const val INFERENCE_TIMEOUT_MS = 60_000L

  private val renamesSchema = Schema.obj(
    properties = mapOf(
      "summary" to Schema.string(
        description = "Concise 1-2 sentence explanation of what this class appears to do based on code context."
      ),
      "renames" to Schema.array(
        description = "List of suggested contextual renames for obfuscated symbols.",
        items = Schema.obj(
          properties = mapOf(
            "originalName" to Schema.string(
              description = "Exact obfuscated Java identifier present in the code (e.g. f12a, m3b, C001a, var1, str2)."
            ),
            "suggestedName" to Schema.string(
              description = "Meaningful Java identifier in camelCase (methods/fields/vars) or PascalCase (classes)."
            ),
            "kind" to Schema.enumeration(
              values = listOf("CLASS", "FIELD", "METHOD", "PARAM_OR_VAR"),
              description = "Symbol category."
            ),
            "reason" to Schema.string(
              description = "Short explanation of why this name fits based on surrounding code context."
            ),
          )
        )
      )
    )
  )

  private val model by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = renamesSchema
      },
    )
  }

  suspend fun analyzeClassForDeobfuscation(
    fullClassName: String,
    simpleClassName: String,
    javaSource: String,
    languageTag: String,
  ): DeobfuscationPlan {
    val truncatedSource = if (javaSource.length > MAX_CODE_CHARS) {
      javaSource.take(MAX_CODE_CHARS) + "\n// ... [code truncated for analysis]"
    } else {
      javaSource
    }

    val explanationLanguage = when {
      languageTag.startsWith("es", ignoreCase = true) -> "Spanish"
      languageTag.startsWith("pt", ignoreCase = true) -> "Portuguese"
      languageTag.startsWith("fr", ignoreCase = true) -> "French"
      languageTag.startsWith("zh", ignoreCase = true) -> "Simplified Chinese"
      languageTag.startsWith("en", ignoreCase = true) -> "English"
      else -> "Spanish"
    }

    val prompt = buildString {
      appendLine("You are an expert Android reverse engineer and Java static analyzer.")
      appendLine("Analyze the decompiled Java class below and infer meaningful, idiomatic Java names for OBFUSCATED identifiers (such as short single/two-letter names like a, b, c, i2, z, str, obj, r0, var1, or ProGuard/R8/Jadx patterns like C0012a, f123a, m45b, mo12a) based on string literals, Android/Java APIs, types, log tags, and control flow.")
      appendLine()
      appendLine("Strict rules:")
      appendLine("1. Write 'summary' and each 'reason' in $explanationLanguage.")
      appendLine("2. Every 'suggestedName' MUST be a valid Java identifier in English (camelCase for methods/fields/parameters/variables, PascalCase for classes, UPPER_SNAKE_CASE for static final constants) and MUST NOT be a Java keyword.")
      appendLine("3. NEVER rename Android SDK or Java standard library methods/classes/overrides (e.g. onCreate, onClick, toString, equals, hashCode, run, length, size, getString, findViewById).")
      appendLine("4. Only include identifiers that are genuinely obfuscated or generic/auto-generated and actually appear in the code.")
      appendLine("5. Do NOT suggest duplicate 'suggestedName' values.")
      appendLine()
      appendLine("Class: $fullClassName")
      appendLine("```java")
      appendLine(truncatedSource)
      appendLine("```")
    }

    val response = withTimeout(INFERENCE_TIMEOUT_MS) {
      model.generateContent(prompt)
    }

    val rawJson = response.text?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: throw IllegalStateException("Empty AI response.")

    return JavaSymbolRenamer.parseDeobfuscationJson(
      rawJson = rawJson,
      javaSource = javaSource,
      topLevelClassSimpleName = simpleClassName,
    )
  }
}

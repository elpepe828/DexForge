package com.example.engine

import com.example.model.DeobfuscationSuggestion
import com.example.model.DeobfuscationSymbolKind

data class DeobfuscationPlan(
  val summary: String,
  val suggestions: List<DeobfuscationSuggestion>,
)

data class ObfuscatedCandidate(
  val name: String,
  val kindHint: DeobfuscationSymbolKind,
  val occurrences: Int,
)

data class CompactedJavaContext(
  val compactCode: String,
  val candidates: List<ObfuscatedCandidate>,
  val originalChars: Int,
  val isSmallClass: Boolean,
)

object JavaSymbolRenamer {

  private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
    "null", "var", "record", "sealed", "permits", "yield",
  )

  private val PROTECTED_OVERRIDES = setOf(
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onClick",
    "onBindViewHolder", "onCreateViewHolder", "getItemCount", "onReceive", "onHandleIntent",
    "toString", "equals", "hashCode", "compareTo", "run", "call", "main", "clone",
  )

  private val SAFE_SHORT_IDENTIFIERS = setOf(
    "id", "io", "os", "ui", "db", "to", "of", "in", "on", "is", "it", "ok", "up",
    "go", "by", "at", "or", "as", "if", "do", "vm", "dx", "tv", "iv", "et", "rv",
    "ctx", "tag", "key", "url", "uri", "apk", "dex", "log", "msg", "err", "res",
    "idx", "len", "max", "min", "sum", "map", "set", "out", "buf", "src", "dst",
    "app", "row", "col", "pos", "end",
  )

  private val PROGUARD_NAME_REGEX = Regex(
    """^(?:[fmCp][0-9]+[a-zA-Z0-9_]*|mo[0-9]+[a-zA-Z0-9_]*|(?:Abstract|Interface|Runnable|Callable|ViewOnClickListener|C)[0-9]+[a-zA-Z0-9_]*|[a-z]{1,4}[0-9]+|var[0-9]+|arg[0-9]+|param[0-9]+|str[0-9]*|obj[0-9]*|method_[0-9]+|field_[0-9]+)$"""
  )

  private val STANDARD_IMPORT_PREFIXES = listOf(
    "import java.",
    "import javax.",
    "import android.",
    "import androidx.",
    "import kotlin.",
    "import kotlinx.",
    "import dalvik.",
  )

  fun isValidJavaIdentifier(name: String): Boolean {
    if (name.isEmpty() || name in JAVA_KEYWORDS) return false
    val first = name[0]
    if (!first.isLetter() && first != '_' && first != '$') return false
    for (i in 1 until name.length) {
      val ch = name[i]
      if (!ch.isLetterOrDigit() && ch != '_' && ch != '$') return false
    }
    return true
  }

  fun collectCodeIdentifiers(javaSource: String): Set<String> {
    val ids = HashSet<String>()
    scanJavaTokens(javaSource) { token, inPackageOrImport, _ ->
      if (!inPackageOrImport && token.kind == TokenKind.IDENTIFIER) {
        ids += token.text
      }
    }
    return ids
  }

  fun isLikelyObfuscatedIdentifier(name: String): Boolean {
    if (name.isEmpty() || name in JAVA_KEYWORDS || name in PROTECTED_OVERRIDES) return false
    val lower = name.lowercase()
    if (lower in SAFE_SHORT_IDENTIFIERS) return false
    if (name.length <= 2) return true
    if (name.contains('$')) return true
    if (PROGUARD_NAME_REGEX.matches(name)) return true
    return false
  }

  fun prepareCompactContextForAi(
    javaSource: String,
    topLevelClassSimpleName: String?,
    maxChars: Int = 18_000,
  ): CompactedJavaContext {
    // 1. Collect candidate obfuscated symbols + occurrence counts & heuristic kinds.
    val counts = LinkedHashMap<String, Int>()
    val kindScores = HashMap<String, MutableMap<DeobfuscationSymbolKind, Int>>()
    val tokensList = ArrayList<JavaToken>(1024)
    val tokenInPkgOrImport = ArrayList<Boolean>(1024)
    val tokenIsAnno = ArrayList<Boolean>(1024)

    scanJavaTokens(javaSource) { token, inPkgOrImp, isAnno ->
      if (token.kind != TokenKind.WHITESPACE && token.kind != TokenKind.COMMENT) {
        tokensList += token
        tokenInPkgOrImport += inPkgOrImp
        tokenIsAnno += isAnno
      }
    }

    for (idx in tokensList.indices) {
      val tok = tokensList[idx]
      if (tok.kind != TokenKind.IDENTIFIER || tokenInPkgOrImport[idx] || tokenIsAnno[idx]) continue
      val prev = if (idx > 0) tokensList[idx - 1] else null
      val next = if (idx + 1 < tokensList.size) tokensList[idx + 1] else null
      // Skip external receiver calls like `other.a()` unless it's `this.a` or `ClassName.a`
      if (prev?.kind == TokenKind.DOT) {
        val twoBack = if (idx >= 2) tokensList[idx - 2].text else null
        val isOwnReceiver = twoBack == "this" || twoBack == "super" ||
          (topLevelClassSimpleName != null && twoBack == topLevelClassSimpleName)
        if (!isOwnReceiver) continue
      }
      if (!isLikelyObfuscatedIdentifier(tok.text)) continue

      counts[tok.text] = (counts[tok.text] ?: 0) + 1
      val twoBack = if (idx >= 2) tokensList[idx - 2].text else null
      val kind = when {
        prev?.text in setOf("class", "interface", "enum") -> DeobfuscationSymbolKind.CLASS
        next?.text == "(" -> DeobfuscationSymbolKind.METHOD
        (prev?.text in setOf("private", "protected", "public", "static", "final", "volatile", "transient") ||
          twoBack in setOf("private", "protected", "public", "static", "final", "volatile", "transient")) &&
          next?.text in setOf("=", ";", ",") ->
          DeobfuscationSymbolKind.FIELD
        else -> DeobfuscationSymbolKind.PARAM_OR_VAR
      }
      val map = kindScores.getOrPut(tok.text) { HashMap() }
      map[kind] = (map[kind] ?: 0) + 1
    }

    val candidates = counts.entries
      .sortedByDescending { it.value }
      .map { (name, count) ->
        val scores = kindScores[name].orEmpty()
        val bestKind = when {
          (scores[DeobfuscationSymbolKind.CLASS] ?: 0) > 0 -> DeobfuscationSymbolKind.CLASS
          (scores[DeobfuscationSymbolKind.METHOD] ?: 0) > 0 -> DeobfuscationSymbolKind.METHOD
          (scores[DeobfuscationSymbolKind.FIELD] ?: 0) > 0 -> DeobfuscationSymbolKind.FIELD
          else -> DeobfuscationSymbolKind.PARAM_OR_VAR
        }
        ObfuscatedCandidate(name = name, kindHint = bestKind, occurrences = count)
      }

    // 2. Compact Java source without losing semantic context (API calls, strings, control flow).
    val compactedLines = ArrayList<String>()
    var previousBlank = false
    for (rawLine in javaSource.lineSequence()) {
      val trimmedEnd = rawLine.trimEnd()
      val trimmed = trimmedEnd.trim()
      if (trimmed.isEmpty()) {
        if (!previousBlank && compactedLines.isNotEmpty()) {
          compactedLines += ""
          previousBlank = true
        }
        continue
      }
      if (STANDARD_IMPORT_PREFIXES.any { trimmed.startsWith(it) }) continue
      if (trimmed.startsWith("/* JADX") || trimmed.startsWith("// JADX") ||
        trimmed.startsWith("/* compiled from:") || trimmed.startsWith("/* renamed from:")
      ) {
        continue
      }
      previousBlank = false
      // Shrink 4-space indentation down to 2 spaces to cut token count cleanly.
      var leadingSpaces = 0
      while (leadingSpaces < trimmedEnd.length && trimmedEnd[leadingSpaces] == ' ') {
        leadingSpaces++
      }
      val shrunkIndent = " ".repeat((leadingSpaces + 1) / 2)
      compactedLines += "$shrunkIndent$trimmed"
    }

    val joined = compactedLines.joinToString("\n").trim()
    val finalCode = if (joined.length > maxChars) {
      val cutoff = joined.lastIndexOf('\n', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
      joined.substring(0, cutoff) + "\n// ... [truncated for analysis]"
    } else {
      joined
    }

    val isSmallClass = finalCode.length <= 2_600 && candidates.size <= 10
    return CompactedJavaContext(
      compactCode = finalCode,
      candidates = candidates,
      originalChars = javaSource.length,
      isSmallClass = isSmallClass,
    )
  }

  fun parseDeobfuscationJson(
    rawJson: String,
    javaSource: String,
    topLevelClassSimpleName: String?,
  ): DeobfuscationPlan {
    val parsed = MiniJsonParser(rawJson).parseObject()
    val summary = (parsed["summary"] as? String).orEmpty().trim()
    val renamesRaw = parsed["renames"] as? List<*>
    val existingIds = collectCodeIdentifiers(javaSource)

    val seenOriginals = HashSet<String>()
    val usedTargets = HashSet<String>(existingIds)
    val suggestions = mutableListOf<DeobfuscationSuggestion>()

    if (renamesRaw != null) {
      for (entry in renamesRaw) {
        val item = entry as? Map<*, *> ?: continue
        val original = (item["originalName"] as? String).orEmpty().trim()
        val rawSuggested = (item["suggestedName"] as? String).orEmpty().trim()
        val kindStr = (item["kind"] as? String ?: "PARAM_OR_VAR").trim().uppercase()
        val reason = (item["reason"] as? String).orEmpty().trim()

        if (original.isEmpty() || rawSuggested.isEmpty()) continue
        if (original == rawSuggested) continue
        if (original in JAVA_KEYWORDS || original in PROTECTED_OVERRIDES) continue
        if (!isValidJavaIdentifier(original) || !isValidJavaIdentifier(rawSuggested)) continue
        if (original !in existingIds) continue
        if (!seenOriginals.add(original)) continue

        val kind = when (kindStr) {
          "CLASS" -> DeobfuscationSymbolKind.CLASS
          "FIELD" -> DeobfuscationSymbolKind.FIELD
          "METHOD" -> DeobfuscationSymbolKind.METHOD
          else -> DeobfuscationSymbolKind.PARAM_OR_VAR
        }

        // Disambiguate if the suggested name collides with an existing identifier or prior rename.
        var finalSuggested = rawSuggested
        var suffix = 2
        while (finalSuggested in usedTargets) {
          finalSuggested = "${rawSuggested}$suffix"
          suffix++
        }
        usedTargets += finalSuggested

        // Keep top-level outer class rename unchecked by default so DEX patching stays safe unless user opts in.
        val isTopLevelClass = kind == DeobfuscationSymbolKind.CLASS &&
          topLevelClassSimpleName != null &&
          original == topLevelClassSimpleName

        suggestions += DeobfuscationSuggestion(
          originalName = original,
          suggestedName = finalSuggested,
          kind = kind,
          reason = reason,
          selected = !isTopLevelClass,
        )
      }
    }

    return DeobfuscationPlan(summary = summary, suggestions = suggestions)
  }

  fun applyRenames(
    javaSource: String,
    suggestions: List<DeobfuscationSuggestion>,
    enclosingClassSimpleName: String?,
  ): String {
    val active = suggestions.filter { it.selected && isValidJavaIdentifier(it.suggestedName) }
    if (active.isEmpty()) return javaSource

    val byOriginal = active.associateBy { it.originalName }
    val out = StringBuilder(javaSource.length + 256)

    var prevSignificantKind: TokenKind? = null
    var prevSignificantText: String? = null
    var twoBackText: String? = null

    var cursor = 0
    scanJavaTokens(javaSource) { token, inPackageOrImport, isAnnotationName ->
      if (token.start > cursor) {
        out.append(javaSource, cursor, token.start)
      }

      val replacement = if (!inPackageOrImport && !isAnnotationName && token.kind == TokenKind.IDENTIFIER) {
        val suggestion = byOriginal[token.text]
        if (suggestion != null) {
          val precededByDot = prevSignificantKind == TokenKind.DOT
          if (!precededByDot) {
            suggestion.suggestedName
          } else {
            val receiver = twoBackText
            val isOwnMemberReceiver = receiver == "this" ||
              receiver == "super" ||
              (enclosingClassSimpleName != null && receiver == enclosingClassSimpleName)
            val isMemberKind = suggestion.kind == DeobfuscationSymbolKind.FIELD ||
              suggestion.kind == DeobfuscationSymbolKind.METHOD
            if (isOwnMemberReceiver && isMemberKind) {
              suggestion.suggestedName
            } else {
              token.text
            }
          }
        } else {
          token.text
        }
      } else {
        token.text
      }

      out.append(replacement)
      cursor = token.end

      if (token.kind != TokenKind.WHITESPACE && token.kind != TokenKind.COMMENT) {
        twoBackText = prevSignificantText
        prevSignificantKind = token.kind
        prevSignificantText = token.text
      }
    }

    if (cursor < javaSource.length) {
      out.append(javaSource, cursor, javaSource.length)
    }
    return out.toString()
  }

  private enum class TokenKind {
    WHITESPACE,
    COMMENT,
    STRING_OR_CHAR,
    IDENTIFIER,
    DOT,
    AT,
    SEMICOLON,
    OTHER,
  }

  private data class JavaToken(
    val kind: TokenKind,
    val text: String,
    val start: Int,
    val end: Int,
  )

  private inline fun scanJavaTokens(
    src: String,
    onToken: (token: JavaToken, inPackageOrImport: Boolean, isAnnotationName: Boolean) -> Unit,
  ) {
    val n = src.length
    var i = 0
    var inPackageOrImport = false
    var afterAt = false

    while (i < n) {
      val c = src[i]

      // 1. Whitespace
      if (c.isWhitespace()) {
        val start = i
        i++
        while (i < n && src[i].isWhitespace()) i++
        onToken(JavaToken(TokenKind.WHITESPACE, src.substring(start, i), start, i), inPackageOrImport, false)
        continue
      }

      // 2. Line comment
      if (c == '/' && i + 1 < n && src[i + 1] == '/') {
        val start = i
        i += 2
        while (i < n && src[i] != '\n') i++
        onToken(JavaToken(TokenKind.COMMENT, src.substring(start, i), start, i), inPackageOrImport, false)
        continue
      }

      // 3. Block comment
      if (c == '/' && i + 1 < n && src[i + 1] == '*') {
        val start = i
        i += 2
        while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
        i = if (i + 1 < n) i + 2 else n
        onToken(JavaToken(TokenKind.COMMENT, src.substring(start, i), start, i), inPackageOrImport, false)
        continue
      }

      // 4. String or Char literal
      if (c == '"' || c == '\'') {
        val quote = c
        val start = i
        i++
        while (i < n) {
          val ch = src[i]
          if (ch == '\\' && i + 1 < n) {
            i += 2
          } else if (ch == quote) {
            i++
            break
          } else {
            i++
          }
        }
        afterAt = false
        onToken(JavaToken(TokenKind.STRING_OR_CHAR, src.substring(start, i), start, i), inPackageOrImport, false)
        continue
      }

      // 5. Identifier
      if (c.isLetter() || c == '_' || c == '$') {
        val start = i
        i++
        while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
        val word = src.substring(start, i)
        if (!inPackageOrImport && (word == "package" || word == "import")) {
          inPackageOrImport = true
        }
        val isAnno = afterAt
        afterAt = false
        onToken(JavaToken(TokenKind.IDENTIFIER, word, start, i), inPackageOrImport, isAnno)
        continue
      }

      // 6. Punctuation / Operators
      val start = i
      i++
      val kind = when (c) {
        '.' -> {
          afterAt = false
          TokenKind.DOT
        }
        '@' -> {
          afterAt = true
          TokenKind.AT
        }
        ';' -> {
          afterAt = false
          TokenKind.SEMICOLON
        }
        else -> {
          afterAt = false
          TokenKind.OTHER
        }
      }
      onToken(JavaToken(kind, src.substring(start, i), start, i), inPackageOrImport, false)
      if (kind == TokenKind.SEMICOLON) {
        inPackageOrImport = false
      }
    }
  }

  private class MiniJsonParser(private val src: String) {
    private var pos = 0

    fun parseObject(): Map<String, Any?> {
      skipWs()
      if (pos >= src.length || src[pos] != '{') {
        throw IllegalArgumentException("Expected JSON object")
      }
      return readObject()
    }

    private fun readValue(): Any? {
      skipWs()
      if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON")
      return when (src[pos]) {
        '{' -> readObject()
        '[' -> readArray()
        '"' -> readString()
        't', 'f' -> readBoolean()
        'n' -> readNull()
        '-', in '0'..'9' -> readNumber()
        else -> throw IllegalArgumentException("Unexpected JSON char '${src[pos]}' at $pos")
      }
    }

    private fun readObject(): Map<String, Any?> {
      pos++ // '{'
      val map = LinkedHashMap<String, Any?>()
      skipWs()
      if (pos < src.length && src[pos] == '}') {
        pos++
        return map
      }
      while (pos < src.length) {
        skipWs()
        val key = readString()
        skipWs()
        if (pos >= src.length || src[pos] != ':') {
          throw IllegalArgumentException("Expected ':' at $pos")
        }
        pos++
        map[key] = readValue()
        skipWs()
        if (pos < src.length && src[pos] == ',') {
          pos++
          continue
        }
        if (pos < src.length && src[pos] == '}') {
          pos++
          return map
        }
        throw IllegalArgumentException("Expected ',' or '}' at $pos")
      }
      throw IllegalArgumentException("Unterminated JSON object")
    }

    private fun readArray(): List<Any?> {
      pos++ // '['
      val list = ArrayList<Any?>()
      skipWs()
      if (pos < src.length && src[pos] == ']') {
        pos++
        return list
      }
      while (pos < src.length) {
        list += readValue()
        skipWs()
        if (pos < src.length && src[pos] == ',') {
          pos++
          continue
        }
        if (pos < src.length && src[pos] == ']') {
          pos++
          return list
        }
        throw IllegalArgumentException("Expected ',' or ']' at $pos")
      }
      throw IllegalArgumentException("Unterminated JSON array")
    }

    private fun readString(): String {
      if (pos >= src.length || src[pos] != '"') {
        throw IllegalArgumentException("Expected string at $pos")
      }
      pos++
      val sb = StringBuilder()
      while (pos < src.length) {
        val c = src[pos++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            if (pos >= src.length) break
            when (val esc = src[pos++]) {
              '"', '\\', '/' -> sb.append(esc)
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (pos + 4 <= src.length) {
                  sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                  pos += 4
                }
              }
              else -> sb.append(esc)
            }
          }
          else -> sb.append(c)
        }
      }
      throw IllegalArgumentException("Unterminated JSON string")
    }

    private fun readBoolean(): Boolean {
      return if (src.startsWith("true", pos)) {
        pos += 4
        true
      } else if (src.startsWith("false", pos)) {
        pos += 5
        false
      } else {
        throw IllegalArgumentException("Invalid boolean at $pos")
      }
    }

    private fun readNull(): Any? {
      if (src.startsWith("null", pos)) {
        pos += 4
        return null
      }
      throw IllegalArgumentException("Invalid null at $pos")
    }

    private fun readNumber(): Number {
      val start = pos
      while (pos < src.length && src[pos] in "0123456789.-+eE") pos++
      val raw = src.substring(start, pos)
      return raw.toLongOrNull() ?: raw.toDouble()
    }

    private fun skipWs() {
      while (pos < src.length && src[pos].isWhitespace()) pos++
    }
  }
}

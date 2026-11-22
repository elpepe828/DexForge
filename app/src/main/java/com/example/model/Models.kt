package com.example.model

import android.net.Uri

enum class PaneSourceType {
  LOCAL_WORKSPACE,
  SAF_TREE,
  ARCHIVE_APK,
}

data class FileEntry(
  val id: String,
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val lastModifiedMillis: Long,
  val isArchiveEntry: Boolean = false,
  val isSafEntry: Boolean = false,
  val safUri: Uri? = null,
  val compressedSize: Long = 0L,
) {
  val extension: String
    get() = name.substringAfterLast('.', "").lowercase()

  val isApk: Boolean
    get() = !isDirectory && extension == "apk"

  val isDex: Boolean
    get() = !isDirectory && extension == "dex"

  val isZipLike: Boolean
    get() = !isDirectory && extension in setOf("apk", "zip", "jar", "aar", "xapk")

  val isEditableText: Boolean
    get() = !isDirectory && extension in setOf(
      "java", "kt", "xml", "json", "txt", "smali", "pro", "properties", "gradle", "md", "cfg", "yml", "yaml"
    )
}

data class PaneState(
  val paneId: Int,
  val title: String,
  val sourceType: PaneSourceType = PaneSourceType.LOCAL_WORKSPACE,
  val currentPath: String = "",
  val archiveFilePath: String? = null,
  val archiveInternalDir: String = "",
  val safRootUri: Uri? = null,
  val safCurrentUri: Uri? = null,
  val entries: List<FileEntry> = emptyList(),
  val selectedPaths: Set<String> = emptySet(),
  val searchQuery: String = "",
  val isLoading: Boolean = false,
  val canNavigateUp: Boolean = false,
)

data class DexClassInfo(
  val fullName: String,
  val packageName: String,
  val simpleName: String,
  val dexEntryName: String,
  val methodCount: Int,
  val fieldCount: Int,
  val isInterface: Boolean,
  val isEnum: Boolean,
  val methodNames: List<String> = emptyList(),
)

enum class DexSearchScope {
  ALL,
  CLASSES,
  METHODS,
}

data class DexPackageNode(
  val packageName: String,
  val classes: List<DexClassInfo>,
)

enum class DecompilerMode {
  JAVA,
  SMALI_INFO,
}

data class EditorDiagnostic(
  val line: Int,
  val column: Int,
  val severity: DiagnosticSeverity,
  val message: String,
)

enum class DiagnosticSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class CompilePatchResult(
  val success: Boolean,
  val diagnostics: List<EditorDiagnostic>,
  val logs: List<String>,
  val updatedDexFilePath: String? = null,
  val updatedApkFilePath: String? = null,
  val compiledClassesCount: Int = 0,
)

data class SignatureReport(
  val isVerified: Boolean,
  val v1Verified: Boolean,
  val v2Verified: Boolean,
  val v3Verified: Boolean,
  val signerSubject: String?,
  val sha256Fingerprint: String?,
  val sha1Fingerprint: String?,
  val errors: List<String>,
  val warnings: List<String>,
)

data class ApkMetadataSummary(
  val apkFilePath: String,
  val apkName: String,
  val fileSizeBytes: Long,
  val packageName: String?,
  val versionName: String?,
  val versionCode: String?,
  val dexFiles: List<String>,
  val entryCount: Int,
  val decodedManifestXml: String,
  val signatureReport: SignatureReport,
)

data class KeystoreProfile(
  val id: String,
  val displayName: String,
  val filePath: String,
  val storeType: String,
  val alias: String,
  val storePassword: String,
  val keyPassword: String,
  val subjectDn: String,
  val isBuiltIn: Boolean = false,
)

enum class DeobfuscationSymbolKind {
  CLASS,
  FIELD,
  METHOD,
  PARAM_OR_VAR,
}

data class DeobfuscationSuggestion(
  val originalName: String,
  val suggestedName: String,
  val kind: DeobfuscationSymbolKind,
  val reason: String,
  val selected: Boolean = true,
)

enum class DeobfuscationPhase {
  PREPARING_CONTEXT,
  CHECKING_APP_CHECK,
  CONNECTING_MODEL,
  THINKING,
  STREAMING,
  PARSING,
}

sealed interface DeobfuscationUiState {
  data object Idle : DeobfuscationUiState
  data class Loading(
    val elapsedSeconds: Int = 0,
    val streamedChars: Int = 0,
    val chunkCount: Int = 0,
    val awaitingContinueDecision: Boolean = false,
    val useFastModel: Boolean = false,
    val phase: DeobfuscationPhase = DeobfuscationPhase.PREPARING_CONTEXT,
    val activeModelName: String = "gemini-3.8-flash",
    val autoFastSelected: Boolean = false,
    val candidateCount: Int = 0,
    val isSlowInitialConnection: Boolean = false,
  ) : DeobfuscationUiState
  data class Ready(
    val summary: String,
    val suggestions: List<DeobfuscationSuggestion>,
  ) : DeobfuscationUiState
  data class Error(
    val message: String,
    val usedFastModel: Boolean = false,
  ) : DeobfuscationUiState
}

data class DexEditorState(
  val targetFilePath: String,
  val targetApkPath: String?,
  val targetDisplayName: String,
  val dexEntries: List<String> = emptyList(),
  val activeDexEntry: String = "classes.dex",
  val classes: List<DexClassInfo> = emptyList(),
  val currentPackagePath: String = "",
  val showClassBrowser: Boolean = true,
  val patchedClassNames: Set<String> = emptySet(),
  val searchQuery: String = "",
  val searchScope: DexSearchScope = DexSearchScope.ALL,
  val selectedClass: DexClassInfo? = null,
  val decompilerMode: DecompilerMode = DecompilerMode.JAVA,
  val javaCode: String = "",
  val originalJavaCode: String = "",
  val smaliCode: String = "",
  val isDirty: Boolean = false,
  val isLoadingIndex: Boolean = false,
  val isDecompiling: Boolean = false,
  val isCompiling: Boolean = false,
  val undoStack: List<String> = emptyList(),
  val redoStack: List<String> = emptyList(),
  val lastCompileResult: CompilePatchResult? = null,
  val hasUncommittedDexChanges: Boolean = false,
  val lastSavedApkPath: String? = null,
  val deobfuscationState: DeobfuscationUiState = DeobfuscationUiState.Idle,
)

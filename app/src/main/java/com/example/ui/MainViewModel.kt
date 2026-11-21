package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.WorkspaceRepository
import com.example.engine.ApkArchiveEngine
import com.example.engine.AxmlDecoder
import com.example.engine.JavaDecompilerEngine
import com.example.engine.JavaDecompilerSession
import com.example.engine.JavaToDexCompiler
import com.example.model.ApkMetadataSummary
import com.example.model.DecompilerMode
import com.example.model.DexClassInfo
import com.example.model.DexEditorState
import com.example.model.FileEntry
import com.example.model.KeystoreProfile
import com.example.model.PaneSourceType
import com.example.model.PaneState
import com.example.model.SignatureReport
import com.example.util.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

enum class MainNavDestination {
  DUAL_FILES,
  SIGNER_STUDIO,
}

data class TextEditorModalState(
  val title: String,
  val content: String,
  val originalContent: String,
  val filePath: String?,
  val archivePath: String?,
  val archiveEntryPath: String?,
  val isReadOnly: Boolean = false,
)

data class MainUiState(
  val destination: MainNavDestination = MainNavDestination.DUAL_FILES,
  val activePaneId: Int = 0,
  val leftPane: PaneState = PaneState(paneId = 0, title = "Workspace"),
  val rightPane: PaneState = PaneState(paneId = 1, title = "Exportados"),
  val apkInspector: ApkMetadataSummary? = null,
  val dexEditor: DexEditorState? = null,
  val textEditor: TextEditorModalState? = null,
  val keystoreProfiles: List<KeystoreProfile> = emptyList(),
  val selectedKeystoreId: String? = null,
  val signerApkPath: String? = null,
  val signerVerification: SignatureReport? = null,
  val isSigning: Boolean = false,
  val signV1: Boolean = true,
  val signV2: Boolean = true,
  val signV3: Boolean = true,
  val statusMessage: String? = null,
  val isBusy: Boolean = false,
  val pendingExportFile: File? = null,
  val appLanguageTag: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val repo = WorkspaceRepository(application)
  private val _uiState = MutableStateFlow(
    MainUiState(appLanguageTag = LocaleManager.getSavedLanguageTag(application))
  )
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  private var activeDecompilerSession: JavaDecompilerSession? = null
  private var activeDexWorkingDir: File? = null
  private val activeDexWorkingFiles = ConcurrentHashMap<String, File>()
  private val modifiedDexEntries = ConcurrentHashMap.newKeySet<String>()
  private var siblingDexExtractionJob: Job? = null

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val profiles = repo.ensureInitialized()
      _uiState.update {
        it.copy(
          keystoreProfiles = profiles,
          selectedKeystoreId = profiles.firstOrNull()?.id,
          leftPane = it.leftPane.copy(
            currentPath = repo.apksDir.absolutePath,
            title = str(R.string.quick_folder_apks),
          ),
          rightPane = it.rightPane.copy(
            currentPath = repo.exportsDir.absolutePath,
            title = str(R.string.quick_folder_exports),
          ),
        )
      }
      refreshPane(0)
      refreshPane(1)
    }
  }

  private fun str(@StringRes resId: Int, vararg formatArgs: Any): String {
    val localized = LocaleManager.createLocalizedContext(getApplication(), _uiState.value.appLanguageTag)
    return if (formatArgs.isEmpty()) {
      localized.getString(resId)
    } else {
      localized.getString(resId, *formatArgs)
    }
  }

  private fun localizedQuickFolderTitle(folderKey: String): String {
    return when (folderKey) {
      "APKs" -> str(R.string.quick_folder_apks)
      "Proyectos" -> str(R.string.quick_folder_projects)
      "Firmas" -> str(R.string.quick_folder_keystores)
      "Exportados" -> str(R.string.quick_folder_exports)
      else -> "Workspace"
    }
  }

  private fun relocalizePaneTitleIfKnown(pane: PaneState): PaneState {
    if (pane.sourceType == PaneSourceType.SAF_TREE && pane.safCurrentUri == pane.safRootUri) {
      return pane.copy(title = str(R.string.label_saf_folder))
    }
    if (pane.sourceType != PaneSourceType.LOCAL_WORKSPACE) return pane
    val path = pane.currentPath
    val title = when (path) {
      repo.apksDir.absolutePath -> str(R.string.quick_folder_apks)
      repo.projectsDir.absolutePath -> str(R.string.quick_folder_projects)
      repo.keystoresDir.absolutePath -> str(R.string.quick_folder_keystores)
      repo.exportsDir.absolutePath -> str(R.string.quick_folder_exports)
      repo.workspaceRoot.absolutePath -> "Workspace"
      else -> pane.title
    }
    return pane.copy(title = title)
  }

  fun setAppLanguage(tag: String) {
    LocaleManager.saveLanguageTag(getApplication(), tag)
    _uiState.update { state ->
      state.copy(
        appLanguageTag = tag,
        leftPane = relocalizePaneTitleIfKnown(state.leftPane),
        rightPane = relocalizePaneTitleIfKnown(state.rightPane),
      )
    }
    postMessage(str(R.string.msg_language_updated))
  }

  fun setDestination(dest: MainNavDestination) {
    _uiState.update { it.copy(destination = dest) }
  }

  fun setActivePane(paneId: Int) {
    _uiState.update { it.copy(activePaneId = paneId) }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  fun postMessage(msg: String) {
    _uiState.update { it.copy(statusMessage = msg) }
  }

  fun refreshBothPanes() {
    refreshPane(0)
    refreshPane(1)
  }

  fun refreshPane(paneId: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
      updatePane(paneId) { it.copy(isLoading = true) }

      val entries = when (pane.sourceType) {
        PaneSourceType.LOCAL_WORKSPACE -> {
          val dir = File(pane.currentPath.ifBlank { repo.workspaceRoot.absolutePath })
          repo.listLocalDirectory(dir)
        }
        PaneSourceType.ARCHIVE_APK -> {
          val archFile = pane.archiveFilePath?.let { File(it) }
          if (archFile != null && archFile.exists()) {
            ApkArchiveEngine.listArchiveDirectory(archFile, pane.archiveInternalDir)
          } else {
            emptyList()
          }
        }
        PaneSourceType.SAF_TREE -> {
          val uri = pane.safCurrentUri ?: pane.safRootUri
          if (uri != null) repo.listSafDirectory(uri) else emptyList()
        }
      }

      val canUp = when (pane.sourceType) {
        PaneSourceType.LOCAL_WORKSPACE -> {
          val curr = File(pane.currentPath).canonicalPath
          curr != repo.workspaceRoot.canonicalPath
        }
        PaneSourceType.ARCHIVE_APK -> true
        PaneSourceType.SAF_TREE -> pane.safCurrentUri != null && pane.safCurrentUri != pane.safRootUri
      }

      updatePane(paneId) {
        it.copy(
          entries = entries,
          isLoading = false,
          canNavigateUp = canUp,
          selectedPaths = emptySet(),
        )
      }
    }
  }

  fun navigateInto(paneId: Int, entry: FileEntry) {
    val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
    when {
      entry.isArchiveEntry && entry.isDirectory -> {
        updatePane(paneId) {
          it.copy(
            archiveInternalDir = entry.path,
            title = "${File(it.archiveFilePath ?: "").name}/${entry.name}",
          )
        }
        refreshPane(paneId)
      }
      entry.isArchiveEntry && !entry.isDirectory -> {
        val archPath = pane.archiveFilePath ?: return
        if (entry.isDex) {
          openDexEntryFromApk(archPath, entry.path)
        } else if (entry.isEditableText || entry.extension == "xml") {
          openArchiveTextEntry(archPath, entry.path)
        } else {
          postMessage(str(R.string.msg_internal_archive_file, entry.name, formatSize(entry.sizeBytes)))
        }
      }
      entry.isSafEntry && entry.isDirectory -> {
        updatePane(paneId) {
          it.copy(safCurrentUri = entry.safUri, title = entry.name)
        }
        refreshPane(paneId)
      }
      entry.isSafEntry && !entry.isDirectory -> {
        entry.safUri?.let { uri ->
          importUriToPane(paneId, uri, openIfApk = true)
        }
      }
      entry.isDirectory -> {
        updatePane(paneId) {
          it.copy(
            sourceType = PaneSourceType.LOCAL_WORKSPACE,
            currentPath = entry.path,
            title = entry.name,
          )
        }
        refreshPane(paneId)
      }
      entry.isApk -> {
        inspectApk(File(entry.path))
      }
      entry.isZipLike -> {
        openArchiveInPane(paneId, File(entry.path))
      }
      entry.isDex -> {
        openStandaloneDex(File(entry.path))
      }
      entry.isEditableText -> {
        openLocalTextFile(File(entry.path))
      }
      else -> {
        postMessage(str(R.string.msg_file_info, entry.name, formatSize(entry.sizeBytes)))
      }
    }
  }

  fun navigateUp(paneId: Int) {
    val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
    when (pane.sourceType) {
      PaneSourceType.ARCHIVE_APK -> {
        val internal = pane.archiveInternalDir.trim('/')
        if (internal.isEmpty()) {
          val archFile = pane.archiveFilePath?.let { File(it) }
          val parentDir = archFile?.parentFile ?: repo.workspaceRoot
          updatePane(paneId) {
            it.copy(
              sourceType = PaneSourceType.LOCAL_WORKSPACE,
              currentPath = parentDir.absolutePath,
              archiveFilePath = null,
              archiveInternalDir = "",
              title = parentDir.name.ifEmpty { "Workspace" },
            )
          }
        } else {
          val parentInternal = internal.substringBeforeLast('/', "")
          updatePane(paneId) {
            it.copy(
              archiveInternalDir = parentInternal,
              title = File(it.archiveFilePath ?: "").name + if (parentInternal.isEmpty()) "" else "/$parentInternal",
            )
          }
        }
        refreshPane(paneId)
      }
      PaneSourceType.LOCAL_WORKSPACE -> {
        val current = File(pane.currentPath)
        if (current.canonicalPath == repo.workspaceRoot.canonicalPath) return
        val parent = current.parentFile ?: repo.workspaceRoot
        updatePane(paneId) {
          it.copy(
            currentPath = parent.absolutePath,
            title = if (parent.canonicalPath == repo.workspaceRoot.canonicalPath) "Workspace" else parent.name,
          )
        }
        refreshPane(paneId)
      }
      PaneSourceType.SAF_TREE -> {
        updatePane(paneId) {
          it.copy(safCurrentUri = it.safRootUri, title = str(R.string.label_saf_folder))
        }
        refreshPane(paneId)
      }
    }
  }

  fun switchPaneToWorkspaceQuickFolder(paneId: Int, folderName: String) {
    val target = when (folderName) {
      "APKs" -> repo.apksDir
      "Proyectos" -> repo.projectsDir
      "Firmas" -> repo.keystoresDir
      "Exportados" -> repo.exportsDir
      else -> repo.workspaceRoot
    }
    updatePane(paneId) {
      it.copy(
        sourceType = PaneSourceType.LOCAL_WORKSPACE,
        currentPath = target.absolutePath,
        archiveFilePath = null,
        archiveInternalDir = "",
        safRootUri = null,
        safCurrentUri = null,
        title = localizedQuickFolderTitle(folderName),
      )
    }
    refreshPane(paneId)
  }

  fun mountSafTreeInPane(paneId: Int, treeUri: Uri) {
    updatePane(paneId) {
      it.copy(
        sourceType = PaneSourceType.SAF_TREE,
        safRootUri = treeUri,
        safCurrentUri = treeUri,
        archiveFilePath = null,
        title = str(R.string.label_saf_folder),
      )
    }
    refreshPane(paneId)
  }

  fun updatePaneSearch(paneId: Int, query: String) {
    updatePane(paneId) { it.copy(searchQuery = query) }
  }

  fun toggleEntrySelection(paneId: Int, entryPath: String) {
    updatePane(paneId) { pane ->
      val next = if (entryPath in pane.selectedPaths) {
        pane.selectedPaths - entryPath
      } else {
        pane.selectedPaths + entryPath
      }
      pane.copy(selectedPaths = next)
    }
  }

  fun clearSelection(paneId: Int) {
    updatePane(paneId) { it.copy(selectedPaths = emptySet()) }
  }

  fun openArchiveInPane(paneId: Int, archiveFile: File) {
    updatePane(paneId) {
      it.copy(
        sourceType = PaneSourceType.ARCHIVE_APK,
        archiveFilePath = archiveFile.absolutePath,
        archiveInternalDir = "",
        title = archiveFile.name,
      )
    }
    refreshPane(paneId)
  }

  fun importUriToPane(paneId: Int, uri: Uri, openIfApk: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
      updatePane(paneId) { it.copy(isLoading = true) }
      runCatching {
        val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
        val targetDir = if (pane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
          File(pane.currentPath)
        } else {
          repo.apksDir
        }
        val imported = repo.importDocumentUri(uri, targetDir)
        refreshBothPanes()
        if (openIfApk && imported.extension.equals("apk", ignoreCase = true)) {
          inspectApk(imported)
        }
        postMessage(str(R.string.msg_imported, imported.name))
      }.onFailure { e ->
        updatePane(paneId) { it.copy(isLoading = false) }
        postMessage(str(R.string.msg_import_error, e.message.orEmpty()))
      }
    }
  }

  fun createSampleApkInActivePane() {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isBusy = true) }
      runCatching {
        val profile = currentSelectedKeystore()
          ?: throw IllegalStateException(str(R.string.msg_no_keystore_available))
        val targetDir = repo.apksDir
        val outFile = File(targetDir, "Sandbox_${System.currentTimeMillis() % 10000}.apk")
        JavaToDexCompiler.createSampleApk(repo.cacheDir, outFile, profile)
        refreshBothPanes()
        postMessage(str(R.string.msg_apk_saved_signed, outFile.name))
      }.onFailure { e ->
        postMessage(str(R.string.msg_error_creating_apk, e.message.orEmpty()))
      }
      _uiState.update { it.copy(isBusy = false) }
    }
  }

  fun createNewItem(paneId: Int, name: String, isDirectory: Boolean) {
    val cleanName = name.trim().replace(Regex("""[/\\]"""), "")
    if (cleanName.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
      if (pane.sourceType != PaneSourceType.LOCAL_WORKSPACE) {
        postMessage(str(R.string.msg_switch_to_local_to_create))
        return@launch
      }
      val target = File(pane.currentPath, cleanName)
      if (isDirectory) {
        target.mkdirs()
      } else {
        target.parentFile?.mkdirs()
        target.writeText("")
      }
      refreshPane(paneId)
      postMessage(str(R.string.msg_created, cleanName))
    }
  }

  fun renameEntry(paneId: Int, entry: FileEntry, newName: String) {
    val clean = newName.trim().replace(Regex("""[/\\]"""), "")
    if (clean.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
      if (pane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
        val src = File(entry.path)
        val dst = File(src.parentFile, clean)
        if (src.renameTo(dst)) {
          refreshBothPanes()
          postMessage(str(R.string.msg_renamed, clean))
        } else {
          postMessage(str(R.string.msg_rename_failed, entry.name))
        }
      }
    }
  }

  fun deleteSelected(paneId: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
      if (pane.selectedPaths.isEmpty()) return@launch
      when (pane.sourceType) {
        PaneSourceType.LOCAL_WORKSPACE -> {
          var count = 0
          for (p in pane.selectedPaths) {
            val f = File(p)
            if (f.deleteRecursively()) count++
          }
          refreshBothPanes()
          postMessage(str(R.string.msg_deleted_count, count))
        }
        PaneSourceType.ARCHIVE_APK -> {
          val archPath = pane.archiveFilePath ?: return@launch
          val archFile = File(archPath)
          ApkArchiveEngine.replaceOrAddEntries(
            sourceArchive = archFile,
            outputArchive = archFile,
            replacements = emptyMap(),
            deletions = pane.selectedPaths,
            stripSignatures = archFile.extension.equals("apk", true),
          )
          refreshPane(paneId)
          postMessage(str(R.string.msg_deleted_from_archive))
        }
        PaneSourceType.SAF_TREE -> {
          postMessage(str(R.string.msg_saf_delete_hint))
        }
      }
    }
  }

  fun transferSelectedToOtherPane(sourcePaneId: Int, move: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      val srcPane = if (sourcePaneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
      val dstPane = if (sourcePaneId == 0) _uiState.value.rightPane else _uiState.value.leftPane
      if (srcPane.selectedPaths.isEmpty()) {
        postMessage(str(R.string.msg_select_items_first))
        return@launch
      }

      // Case 1: Local -> Local
      if (srcPane.sourceType == PaneSourceType.LOCAL_WORKSPACE && dstPane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
        val dstDir = File(dstPane.currentPath)
        var count = 0
        for (p in srcPane.selectedPaths) {
          repo.copyLocalItem(File(p), dstDir, move)
          count++
        }
        refreshBothPanes()
        postMessage(
          str(if (move) R.string.msg_moved_count else R.string.msg_copied_count, count, dstPane.title)
        )
        return@launch
      }

      // Case 2: Archive -> Local (Extract)
      if (srcPane.sourceType == PaneSourceType.ARCHIVE_APK && dstPane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
        val archFile = File(srcPane.archiveFilePath ?: return@launch)
        val dstDir = File(dstPane.currentPath)
        var count = 0
        for (entryPath in srcPane.selectedPaths) {
          val entry = srcPane.entries.firstOrNull { it.path == entryPath } ?: continue
          if (entry.isDirectory) {
            count += ApkArchiveEngine.extractArchiveDirectory(archFile, entry.path, File(dstDir, entry.name))
          } else {
            ApkArchiveEngine.extractArchiveEntry(archFile, entry.path, File(dstDir, entry.name))
            count++
          }
        }
        refreshBothPanes()
        postMessage(str(R.string.msg_extracted_count, count, dstPane.title))
        return@launch
      }

      // Case 3: Local -> Archive (Inject/Replace into APK/ZIP)
      if (srcPane.sourceType == PaneSourceType.LOCAL_WORKSPACE && dstPane.sourceType == PaneSourceType.ARCHIVE_APK) {
        val archFile = File(dstPane.archiveFilePath ?: return@launch)
        val prefix = dstPane.archiveInternalDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val replacements = mutableMapOf<String, ByteArray>()
        for (p in srcPane.selectedPaths) {
          val f = File(p)
          if (f.isFile) {
            replacements["$prefix${f.name}"] = f.readBytes()
          }
        }
        if (replacements.isNotEmpty()) {
          ApkArchiveEngine.replaceOrAddEntries(
            sourceArchive = archFile,
            outputArchive = archFile,
            replacements = replacements,
            stripSignatures = archFile.extension.equals("apk", true),
          )
          refreshBothPanes()
          postMessage(str(R.string.msg_added_to_archive, replacements.size, archFile.name))
        }
      }
    }
  }

  fun zipSelectedInPane(paneId: Int) {
    viewModelScope.launch(Dispatchers.IO) {
      val pane = if (paneId == 0) _uiState.value.leftPane else _uiState.value.rightPane
      if (pane.sourceType != PaneSourceType.LOCAL_WORKSPACE || pane.selectedPaths.isEmpty()) return@launch
      val files = pane.selectedPaths.map { File(it) }
      val dest = File(pane.currentPath, "${files.first().nameWithoutExtension}.zip")
      repo.zipFiles(files, dest)
      refreshPane(paneId)
      postMessage(str(R.string.msg_zipped_to, dest.name))
    }
  }

  // --- APK Inspector ---

  fun inspectApk(apkFile: File) {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isBusy = true) }
      runCatching {
        val summary = ApkArchiveEngine.inspectApk(apkFile)
        _uiState.update { it.copy(apkInspector = summary, isBusy = false) }
      }.onFailure { e ->
        _uiState.update { it.copy(isBusy = false) }
        postMessage(str(R.string.msg_apk_open_error, e.message.orEmpty()))
      }
    }
  }

  fun dismissApkInspector() {
    _uiState.update { it.copy(apkInspector = null) }
  }

  fun openApkInsidePane(apkPath: String) {
    val paneId = _uiState.value.activePaneId
    _uiState.update { it.copy(apkInspector = null) }
    openArchiveInPane(paneId, File(apkPath))
  }

  fun sendApkToSigner(apkPath: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val report = ApkArchiveEngine.verifyApkSignature(File(apkPath))
      _uiState.update {
        it.copy(
          apkInspector = null,
          destination = MainNavDestination.SIGNER_STUDIO,
          signerApkPath = apkPath,
          signerVerification = report,
        )
      }
    }
  }

  // --- Text File Editor ---

  fun openLocalTextFile(file: File) {
    viewModelScope.launch(Dispatchers.IO) {
      if (file.length() > 1_500_000L) {
        postMessage(str(R.string.msg_file_too_large_text))
        return@launch
      }
      val text = file.readText(StandardCharsets.UTF_8)
      _uiState.update {
        it.copy(
          textEditor = TextEditorModalState(
            title = file.name,
            content = text,
            originalContent = text,
            filePath = file.absolutePath,
            archivePath = null,
            archiveEntryPath = null,
          )
        )
      }
    }
  }

  fun openArchiveTextEntry(archivePath: String, entryPath: String) {
    viewModelScope.launch(Dispatchers.IO) {
      val raw = ApkArchiveEngine.readArchiveEntryBytes(File(archivePath), entryPath)
      val decoded = if (entryPath.endsWith(".xml", true) && AxmlDecoder.isBinaryXml(raw)) {
        AxmlDecoder.decode(raw)
      } else {
        String(raw, StandardCharsets.UTF_8)
      }
      val isBinaryXml = entryPath.endsWith(".xml", true) && AxmlDecoder.isBinaryXml(raw)
      _uiState.update {
        it.copy(
          textEditor = TextEditorModalState(
            title = if (isBinaryXml) str(R.string.label_axml_decoded, entryPath) else entryPath,
            content = decoded,
            originalContent = decoded,
            filePath = null,
            archivePath = archivePath,
            archiveEntryPath = entryPath,
            isReadOnly = isBinaryXml,
          )
        )
      }
    }
  }

  fun updateTextEditorContent(newText: String) {
    _uiState.update { state ->
      state.copy(textEditor = state.textEditor?.copy(content = newText))
    }
  }

  fun saveTextEditor() {
    val editor = _uiState.value.textEditor ?: return
    if (editor.isReadOnly) {
      _uiState.update { it.copy(textEditor = null) }
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        if (editor.filePath != null) {
          File(editor.filePath).writeText(editor.content, StandardCharsets.UTF_8)
          refreshBothPanes()
          postMessage(str(R.string.msg_saved_file, editor.title))
        } else if (editor.archivePath != null && editor.archiveEntryPath != null) {
          val arch = File(editor.archivePath)
          ApkArchiveEngine.replaceOrAddEntries(
            sourceArchive = arch,
            outputArchive = arch,
            replacements = mapOf(editor.archiveEntryPath to editor.content.toByteArray(StandardCharsets.UTF_8)),
            stripSignatures = arch.extension.equals("apk", true),
          )
          refreshBothPanes()
          postMessage(str(R.string.msg_saved_in_archive_resign, arch.name))
        }
        _uiState.update { it.copy(textEditor = null) }
      }.onFailure { e ->
        postMessage(str(R.string.msg_save_error, e.message.orEmpty()))
      }
    }
  }

  fun closeTextEditor() {
    _uiState.update { it.copy(textEditor = null) }
  }

  // --- DEX -> Java Editor ---

  fun openApkInDexEditor(apkPath: String, initialDexName: String? = null) {
    viewModelScope.launch(Dispatchers.IO) {
      closeDexEditorInternal()
      val apkFile = File(apkPath)
      val dexFiles = ApkArchiveEngine.listDexFilesFast(apkFile)
      if (dexFiles.isEmpty()) {
        postMessage(str(R.string.msg_apk_no_dex))
        return@launch
      }

      val workDir = File(repo.cacheDir, "dex_edit_${System.nanoTime()}").apply { mkdirs() }
      activeDexWorkingDir = workDir
      val targetDexName = initialDexName?.takeIf { it in dexFiles } ?: dexFiles.first()
      val primaryDexOut = File(workDir, targetDexName)
      ApkArchiveEngine.extractArchiveEntry(apkFile, targetDexName, primaryDexOut)
      activeDexWorkingFiles[targetDexName] = primaryDexOut

      val remainingDexNames = dexFiles.filter { it != targetDexName }
      if (remainingDexNames.isNotEmpty()) {
        siblingDexExtractionJob = viewModelScope.launch(Dispatchers.IO) {
          val extractedSiblings = ApkArchiveEngine.extractDexEntriesToDir(apkFile, remainingDexNames, workDir)
          activeDexWorkingFiles.putAll(extractedSiblings)
        }
      }

      _uiState.update {
        it.copy(
          apkInspector = null,
          dexEditor = DexEditorState(
            targetFilePath = primaryDexOut.absolutePath,
            targetApkPath = apkFile.absolutePath,
            targetDisplayName = apkFile.name,
            dexEntries = dexFiles,
            activeDexEntry = targetDexName,
            isLoadingIndex = true,
          ),
        )
      }
      loadDexSessionIntoEditor(primaryDexOut, targetDexName)
    }
  }

  fun openDexEntryFromApk(apkPath: String, dexEntryName: String) {
    openApkInDexEditor(apkPath, initialDexName = dexEntryName)
  }

  fun openStandaloneDex(dexFile: File) {
    viewModelScope.launch(Dispatchers.IO) {
      closeDexEditorInternal()
      val workDir = File(repo.cacheDir, "dex_edit_${System.nanoTime()}").apply { mkdirs() }
      val workingDex = File(workDir, dexFile.name)
      dexFile.copyTo(workingDex, overwrite = true)
      activeDexWorkingDir = workDir
      activeDexWorkingFiles[dexFile.name] = workingDex

      _uiState.update {
        it.copy(
          dexEditor = DexEditorState(
            targetFilePath = workingDex.absolutePath,
            targetApkPath = null,
            targetDisplayName = dexFile.name,
            dexEntries = listOf(dexFile.name),
            activeDexEntry = dexFile.name,
            isLoadingIndex = true,
          )
        )
      }
      loadDexSessionIntoEditor(workingDex, dexFile.name)
    }
  }

  private fun loadDexSessionIntoEditor(dexFile: File, dexEntryName: String) {
    runCatching {
      activeDecompilerSession?.close()
      val session = JavaDecompilerEngine.openDexSession(repo.cacheDir, dexFile, dexEntryName)
      activeDecompilerSession = session
      val classes = session.listClasses()

      _uiState.update { state ->
        state.copy(
          dexEditor = state.dexEditor?.copy(
            targetFilePath = dexFile.absolutePath,
            activeDexEntry = dexEntryName,
            classes = classes,
            currentPackagePath = "",
            showClassBrowser = true,
            searchQuery = "",
            isLoadingIndex = false,
            selectedClass = null,
            javaCode = "",
            originalJavaCode = "",
            smaliCode = "",
            isDirty = false,
            undoStack = emptyList(),
            redoStack = emptyList(),
          )
        )
      }
    }.onFailure { e ->
      _uiState.update { state ->
        state.copy(dexEditor = state.dexEditor?.copy(isLoadingIndex = false))
      }
      postMessage(str(R.string.msg_dex_load_error, e.message.orEmpty()))
    }
  }

  fun switchActiveDexEntry(dexEntryName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      siblingDexExtractionJob?.join()
      val workingDex = activeDexWorkingFiles[dexEntryName] ?: return@launch
      _uiState.update { state ->
        state.copy(dexEditor = state.dexEditor?.copy(isLoadingIndex = true))
      }
      loadDexSessionIntoEditor(workingDex, dexEntryName)
    }
  }

  fun openDexPackageFolder(packagePath: String) {
    _uiState.update { state ->
      state.copy(
        dexEditor = state.dexEditor?.copy(
          currentPackagePath = packagePath,
          searchQuery = "",
        )
      )
    }
  }

  fun navigateUpDexPackageFolder() {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      if (ed.currentPackagePath.isEmpty()) return@update state
      val parent = ed.currentPackagePath.substringBeforeLast('.', "")
      state.copy(dexEditor = ed.copy(currentPackagePath = parent))
    }
  }

  fun openClassBrowser() {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      val pkg = ed.selectedClass?.packageName ?: ed.currentPackagePath
      state.copy(
        dexEditor = ed.copy(
          showClassBrowser = true,
          currentPackagePath = pkg,
        )
      )
    }
  }

  fun returnToCodeEditorFromBrowser() {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      if (ed.selectedClass == null) return@update state
      state.copy(dexEditor = ed.copy(showClassBrowser = false))
    }
  }

  fun discardEditsAndReturnToClassBrowser() {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      state.copy(
        dexEditor = ed.copy(
          javaCode = ed.originalJavaCode,
          isDirty = false,
          undoStack = emptyList(),
          redoStack = emptyList(),
          showClassBrowser = true,
        )
      )
    }
  }

  fun selectDexClass(cls: DexClassInfo) {
    val session = activeDecompilerSession ?: return
    val alreadyCached = session.isClassCached(cls.fullName)
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { state ->
        state.copy(
          dexEditor = state.dexEditor?.copy(
            selectedClass = cls,
            currentPackagePath = cls.packageName,
            showClassBrowser = false,
            decompilerMode = DecompilerMode.JAVA,
            isDecompiling = !alreadyCached,
            smaliCode = "",
            lastCompileResult = null,
          )
        )
      }
      val javaCode = runCatching { session.decompileClassToJava(cls.fullName) }
        .getOrElse { "// Error descompilando: ${it.message}" }

      _uiState.update { state ->
        state.copy(
          dexEditor = state.dexEditor?.copy(
            javaCode = javaCode,
            originalJavaCode = javaCode,
            isDirty = false,
            isDecompiling = false,
            undoStack = emptyList(),
            redoStack = emptyList(),
          )
        )
      }
    }
  }

  fun setDexSearchQuery(query: String) {
    _uiState.update { state ->
      state.copy(dexEditor = state.dexEditor?.copy(searchQuery = query))
    }
  }

  fun setDecompilerMode(mode: DecompilerMode) {
    _uiState.update { state ->
      state.copy(dexEditor = state.dexEditor?.copy(decompilerMode = mode))
    }
    if (mode == DecompilerMode.SMALI_INFO) {
      val ed = _uiState.value.dexEditor ?: return
      val cls = ed.selectedClass ?: return
      if (ed.smaliCode.isEmpty()) {
        val session = activeDecompilerSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
          val smaliCode = runCatching { session.disassembleClassSummary(cls.fullName) }
            .getOrElse { "# Error leyendo bytecode: ${it.message}" }
          _uiState.update { state ->
            val curr = state.dexEditor ?: return@update state
            if (curr.selectedClass?.fullName != cls.fullName) return@update state
            state.copy(dexEditor = curr.copy(smaliCode = smaliCode))
          }
        }
      }
    }
  }

  fun updateJavaEditorCode(newCode: String) {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      if (newCode == ed.javaCode) return@update state
      val nextUndo = (ed.undoStack + ed.javaCode).takeLast(30)
      state.copy(
        dexEditor = ed.copy(
          javaCode = newCode,
          isDirty = newCode != ed.originalJavaCode,
          undoStack = nextUndo,
          redoStack = emptyList(),
        )
      )
    }
  }

  fun undoJavaEdit() {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      if (ed.undoStack.isEmpty()) return@update state
      val prev = ed.undoStack.last()
      state.copy(
        dexEditor = ed.copy(
          javaCode = prev,
          isDirty = prev != ed.originalJavaCode,
          undoStack = ed.undoStack.dropLast(1),
          redoStack = (ed.redoStack + ed.javaCode).takeLast(30),
        )
      )
    }
  }

  fun redoJavaEdit() {
    _uiState.update { state ->
      val ed = state.dexEditor ?: return@update state
      if (ed.redoStack.isEmpty()) return@update state
      val next = ed.redoStack.last()
      state.copy(
        dexEditor = ed.copy(
          javaCode = next,
          isDirty = next != ed.originalJavaCode,
          undoStack = (ed.undoStack + ed.javaCode).takeLast(30),
          redoStack = ed.redoStack.dropLast(1),
        )
      )
    }
  }

  fun compileEditedJavaToDex(returnToBrowserOnSuccess: Boolean = false) {
    val ed = _uiState.value.dexEditor ?: return
    val cls = ed.selectedClass ?: return
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { state ->
        state.copy(dexEditor = state.dexEditor?.copy(isCompiling = true))
      }

      try {
        siblingDexExtractionJob?.join()
        val targetDex = File(ed.targetFilePath)
        val siblings = activeDexWorkingFiles.values.filter { it.absolutePath != targetDex.absolutePath }
        val result = JavaToDexCompiler.compileJavaAndPatchDex(
          workRoot = repo.cacheDir,
          targetDexFile = targetDex,
          siblingDexFiles = siblings,
          fullClassName = cls.fullName,
          javaSource = ed.javaCode,
        )

        if (result.success) {
          modifiedDexEntries += ed.activeDexEntry
          runCatching {
            activeDecompilerSession?.reloadAfterPatch(cls.fullName)
          }
          val updatedSmali = if (ed.decompilerMode == DecompilerMode.SMALI_INFO) {
            runCatching {
              activeDecompilerSession?.disassembleClassSummary(cls.fullName) ?: ed.smaliCode
            }.getOrDefault(ed.smaliCode)
          } else {
            ""
          }

          _uiState.update { state ->
            state.copy(
              dexEditor = state.dexEditor?.copy(
                isCompiling = false,
                isDirty = false,
                originalJavaCode = ed.javaCode,
                smaliCode = updatedSmali,
                lastCompileResult = result,
                hasUncommittedDexChanges = true,
                patchedClassNames = state.dexEditor.patchedClassNames + cls.fullName,
                showClassBrowser = if (returnToBrowserOnSuccess) true else state.dexEditor.showClassBrowser,
              )
            )
          }
          postMessage(str(R.string.msg_java_compiled_patched, ed.activeDexEntry))
        } else {
          _uiState.update { state ->
            state.copy(
              dexEditor = state.dexEditor?.copy(
                isCompiling = false,
                lastCompileResult = result,
              )
            )
          }
        }
      } catch (t: Throwable) {
        _uiState.update { state ->
          state.copy(
            dexEditor = state.dexEditor?.copy(
              isCompiling = false,
              lastCompileResult = com.example.model.CompilePatchResult(
                success = false,
                diagnostics = listOf(
                  com.example.model.EditorDiagnostic(
                    line = 1,
                    column = 1,
                    severity = com.example.model.DiagnosticSeverity.ERROR,
                    message = "${t::class.java.simpleName}: ${t.message ?: str(R.string.msg_unexpected_error)}",
                  )
                ),
                logs = listOf("${t::class.java.name}: ${t.message}"),
              ),
            )
          )
        }
      }
    }
  }

  fun clearSavedApkBanner() {
    _uiState.update { state ->
      state.copy(dexEditor = state.dexEditor?.copy(lastSavedApkPath = null))
    }
  }

  fun saveAndSignPatchedTarget(
    autoSign: Boolean,
    overwriteOriginal: Boolean = true,
    createBackup: Boolean = true,
  ) {
    val ed = _uiState.value.dexEditor ?: return
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { state ->
        state.copy(dexEditor = state.dexEditor?.copy(isCompiling = true, lastSavedApkPath = null))
      }

      try {
        val targetDex = File(ed.targetFilePath)

        // Step 1: If there are uncompiled Java edits, compile Java -> DEX automatically first
        if (ed.isDirty && ed.selectedClass != null) {
          siblingDexExtractionJob?.join()
          val siblings = activeDexWorkingFiles.values.filter { it.absolutePath != targetDex.absolutePath }
          val compileRes = JavaToDexCompiler.compileJavaAndPatchDex(
            workRoot = repo.cacheDir,
            targetDexFile = targetDex,
            siblingDexFiles = siblings,
            fullClassName = ed.selectedClass.fullName,
            javaSource = ed.javaCode,
          )
          if (!compileRes.success) {
            _uiState.update { state ->
              state.copy(
                dexEditor = state.dexEditor?.copy(
                  isCompiling = false,
                  lastCompileResult = compileRes,
                )
              )
            }
            postMessage(str(R.string.msg_java_compile_error_details))
            return@launch
          }
          modifiedDexEntries += ed.activeDexEntry
          runCatching {
            activeDecompilerSession?.reloadAfterPatch(ed.selectedClass.fullName)
          }
        }

        // Step 2: Repackage and sign APK (or save standalone DEX)
        if (ed.targetApkPath != null) {
          val origApk = File(ed.targetApkPath)
          val dexKeysToReplace = if (modifiedDexEntries.isNotEmpty()) {
            modifiedDexEntries
          } else {
            emptySet()
          }
          val replacements = dexKeysToReplace.mapNotNull { key ->
            activeDexWorkingFiles[key]?.let { key to it.readBytes() }
          }.toMap()
          val suffix = if (autoSign) "_signed" else "_patched"
          val outApk = if (overwriteOriginal) {
            if (createBackup && origApk.exists()) {
              val bakFile = File(origApk.parentFile, "${origApk.name}.bak")
              if (!bakFile.exists()) {
                origApk.copyTo(bakFile, overwrite = false)
              }
            }
            origApk
          } else {
            File(repo.exportsDir, "${origApk.nameWithoutExtension}$suffix.apk")
          }

          val tempPatched = File(repo.cacheDir, "patched_${System.nanoTime()}.apk")
          ApkArchiveEngine.replaceOrAddEntries(
            sourceArchive = origApk,
            outputArchive = tempPatched,
            replacements = replacements,
            stripSignatures = true,
          )

          if (autoSign) {
            val profile = currentSelectedKeystore()
              ?: throw IllegalStateException(str(R.string.msg_select_keystore_in_signer))
            ApkArchiveEngine.signApk(
              inputApk = tempPatched,
              outputApk = outApk,
              keystoreProfile = profile,
              enableV1 = _uiState.value.signV1,
              enableV2 = _uiState.value.signV2,
              enableV3 = _uiState.value.signV3,
            )
            tempPatched.delete()
          } else {
            tempPatched.copyTo(outApk, overwrite = true)
            tempPatched.delete()
          }

          refreshBothPanes()
          _uiState.update { state ->
            val updatedPatched = if (ed.isDirty && ed.selectedClass != null) {
              state.dexEditor?.patchedClassNames.orEmpty() + ed.selectedClass.fullName
            } else {
              state.dexEditor?.patchedClassNames.orEmpty()
            }
            state.copy(
              dexEditor = state.dexEditor?.copy(
                isCompiling = false,
                isDirty = false,
                originalJavaCode = ed.javaCode,
                hasUncommittedDexChanges = false,
                patchedClassNames = updatedPatched,
                lastSavedApkPath = outApk.absolutePath,
              ),
              signerApkPath = outApk.absolutePath,
            )
          }
          postMessage(
            when {
              overwriteOriginal && autoSign -> str(R.string.msg_apk_updated_signed, outApk.name)
              overwriteOriginal -> str(R.string.msg_apk_updated_unsigned, outApk.name)
              autoSign -> str(R.string.msg_apk_saved_signed_exports, outApk.name)
              else -> str(R.string.msg_apk_patched_exports, outApk.name)
            }
          )
        } else {
          val outDex = if (overwriteOriginal) File(ed.targetFilePath) else File(repo.exportsDir, ed.targetDisplayName)
          if (outDex.absolutePath != targetDex.absolutePath) {
            targetDex.copyTo(outDex, overwrite = true)
          }
          refreshBothPanes()
          _uiState.update { state ->
            state.copy(
              dexEditor = state.dexEditor?.copy(
                isCompiling = false,
                isDirty = false,
                originalJavaCode = ed.javaCode,
                hasUncommittedDexChanges = false,
                lastSavedApkPath = outDex.absolutePath,
              )
            )
          }
          postMessage(str(R.string.msg_dex_saved_in, outDex.name))
        }
      } catch (t: Throwable) {
        _uiState.update { state ->
          state.copy(dexEditor = state.dexEditor?.copy(isCompiling = false))
        }
        postMessage(str(R.string.msg_save_sign_error, t.message.orEmpty()))
      }
    }
  }

  fun closeDexEditor() {
    closeDexEditorInternal()
    _uiState.update { it.copy(dexEditor = null) }
  }

  private fun closeDexEditorInternal() {
    siblingDexExtractionJob?.cancel()
    siblingDexExtractionJob = null
    activeDecompilerSession?.close()
    activeDecompilerSession = null
    activeDexWorkingDir?.deleteRecursively()
    activeDexWorkingDir = null
    activeDexWorkingFiles.clear()
    modifiedDexEntries.clear()
  }

  // --- Signer Studio ---

  fun selectKeystore(profileId: String) {
    _uiState.update { it.copy(selectedKeystoreId = profileId) }
  }

  fun setSignSchemes(v1: Boolean, v2: Boolean, v3: Boolean) {
    _uiState.update { it.copy(signV1 = v1, signV2 = v2, signV3 = v3) }
  }

  fun pickApkForSigner(apkFile: File) {
    viewModelScope.launch(Dispatchers.IO) {
      val report = ApkArchiveEngine.verifyApkSignature(apkFile)
      _uiState.update {
        it.copy(
          signerApkPath = apkFile.absolutePath,
          signerVerification = report,
        )
      }
    }
  }

  fun createKeystore(
    displayName: String,
    alias: String,
    storePassword: String,
    keyPassword: String,
    commonName: String,
    organization: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        val created = repo.createNewKeystoreProfile(
          displayName = displayName,
          alias = alias,
          storePassword = storePassword,
          keyPassword = keyPassword,
          commonName = commonName,
          organization = organization,
        )
        val updated = repo.loadKeystoreProfiles()
        _uiState.update {
          it.copy(
            keystoreProfiles = updated,
            selectedKeystoreId = created.id,
          )
        }
        refreshBothPanes()
        postMessage(str(R.string.msg_keystore_created, created.displayName))
      }.onFailure { e ->
        postMessage(str(R.string.msg_keystore_create_error, e.message.orEmpty()))
      }
    }
  }

  fun importKeystoreFromUri(
    uri: Uri,
    displayName: String,
    storeType: String,
    alias: String,
    storePassword: String,
    keyPassword: String,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        val importedFile = repo.importDocumentUri(uri, repo.keystoresDir)
        val profile = repo.registerImportedKeystore(
          keystoreFile = importedFile,
          displayName = displayName,
          storeType = storeType,
          alias = alias,
          storePassword = storePassword,
          keyPassword = keyPassword,
        )
        val updated = repo.loadKeystoreProfiles()
        _uiState.update {
          it.copy(
            keystoreProfiles = updated,
            selectedKeystoreId = profile.id,
          )
        }
        refreshBothPanes()
        postMessage(str(R.string.msg_keystore_imported, profile.displayName))
      }.onFailure { e ->
        postMessage(str(R.string.msg_keystore_import_error, e.message.orEmpty()))
      }
    }
  }

  fun signCurrentTargetApk() {
    val apkPath = _uiState.value.signerApkPath ?: return
    val profile = currentSelectedKeystore() ?: run {
      postMessage(str(R.string.msg_select_keystore_first))
      return
    }
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isSigning = true) }
      runCatching {
        val inputFile = File(apkPath)
        val baseName = inputFile.nameWithoutExtension.removeSuffix("_signed")
        val outFile = File(repo.exportsDir, "${baseName}_signed.apk")
        ApkArchiveEngine.signApk(
          inputApk = inputFile,
          outputApk = outFile,
          keystoreProfile = profile,
          enableV1 = _uiState.value.signV1,
          enableV2 = _uiState.value.signV2,
          enableV3 = _uiState.value.signV3,
        )
        val verification = ApkArchiveEngine.verifyApkSignature(outFile)
        refreshBothPanes()
        _uiState.update {
          it.copy(
            isSigning = false,
            signerApkPath = outFile.absolutePath,
            signerVerification = verification,
          )
        }
        postMessage(str(R.string.msg_apk_signed_exports, outFile.name))
      }.onFailure { e ->
        _uiState.update { it.copy(isSigning = false) }
        postMessage(str(R.string.msg_sign_apk_error, e.message.orEmpty()))
      }
    }
  }

  fun requestExportToDevice(file: File) {
    _uiState.update { it.copy(pendingExportFile = file) }
  }

  fun completeExportToUri(destUri: Uri?) {
    val file = _uiState.value.pendingExportFile
    _uiState.update { it.copy(pendingExportFile = null) }
    if (file == null || destUri == null) return
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        repo.exportFileToUri(file, destUri)
        postMessage(str(R.string.msg_exported_to_device, file.name))
      }.onFailure { e ->
        postMessage(str(R.string.msg_export_error, e.message.orEmpty()))
      }
    }
  }

  fun buildShareIntent(file: File): Intent {
    val mime = if (file.extension.equals("apk", true)) {
      "application/vnd.android.package-archive"
    } else {
      "*/*"
    }
    return repo.createShareIntent(file, mime)
  }

  fun listWorkspaceApks(): List<File> {
    val apks = repo.apksDir.listFiles()?.filter { it.extension.equals("apk", true) }.orEmpty()
    val exported = repo.exportsDir.listFiles()?.filter { it.extension.equals("apk", true) }.orEmpty()
    return (apks + exported).sortedByDescending { it.lastModified() }
  }

  private fun currentSelectedKeystore(): KeystoreProfile? {
    val state = _uiState.value
    return state.keystoreProfiles.firstOrNull { it.id == state.selectedKeystoreId }
      ?: state.keystoreProfiles.firstOrNull()
  }

  private fun updatePane(paneId: Int, transform: (PaneState) -> PaneState) {
    _uiState.update { state ->
      if (paneId == 0) state.copy(leftPane = transform(state.leftPane))
      else state.copy(rightPane = transform(state.rightPane))
    }
  }

  override fun onCleared() {
    closeDexEditorInternal()
    super.onCleared()
  }
}

fun formatSize(bytes: Long): String {
  return when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
  }
}

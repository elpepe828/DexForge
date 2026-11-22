package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.CompilePatchResult
import com.example.model.DecompilerMode
import com.example.model.DeobfuscationPhase
import com.example.model.DeobfuscationSuggestion
import com.example.model.DeobfuscationSymbolKind
import com.example.model.DeobfuscationUiState
import com.example.model.DexClassInfo
import com.example.model.DexEditorState
import com.example.model.DexSearchScope
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SyntaxColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavaCodeEditorScreen(
  state: DexEditorState,
  onSelectClass: (DexClassInfo) -> Unit,
  onOpenPackageFolder: (String) -> Unit,
  onNavigateUpFolder: () -> Unit,
  onOpenClassBrowser: () -> Unit,
  onReturnToCodeEditor: () -> Unit,
  onCompileAndReturnToBrowser: () -> Unit,
  onDiscardAndReturnToBrowser: () -> Unit,
  onSwitchDexEntry: (String) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onSearchScopeChange: (DexSearchScope) -> Unit,
  onModeChange: (DecompilerMode) -> Unit,
  onCodeChange: (String) -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onCompileToDex: () -> Unit,
  onSaveAndSign: (autoSign: Boolean, overwriteOriginal: Boolean, createBackup: Boolean) -> Unit,
  onAnalyzeObfuscated: () -> Unit,
  onAnalyzeObfuscatedFast: () -> Unit,
  onContinueDeobfWaiting: () -> Unit,
  onToggleDeobfSuggestion: (Int) -> Unit,
  onSetAllDeobfSelected: (Boolean) -> Unit,
  onApplyDeobfSuggestions: () -> Unit,
  onDismissDeobfDialog: () -> Unit,
  onExportSavedFile: (java.io.File) -> Unit,
  onShareSavedFile: (java.io.File) -> Unit,
  onDismissSavedBanner: () -> Unit,
  onClose: () -> Unit,
) {
  var showFindReplace by remember { mutableStateOf(false) }
  var findText by remember { mutableStateOf("") }
  var replaceText by remember { mutableStateOf("") }
  var showBuildLogs by remember { mutableStateOf(false) }
  var showConfirmSaveDialog by remember { mutableStateOf(false) }
  var showUnsavedReturnDialog by remember { mutableStateOf(false) }

  val requestReturnToExplorer = {
    if (state.isDirty) {
      showUnsavedReturnDialog = true
    } else {
      onOpenClassBrowser()
    }
  }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .imePadding(),
    color = if (state.showClassBrowser) MaterialTheme.colorScheme.background else SyntaxColors.EditorBackground,
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val isWide = maxWidth >= 720.dp

      Column(modifier = Modifier.fillMaxSize()) {
        if (state.showClassBrowser) {
          DexClassExplorerView(
            state = state,
            onSelectClass = onSelectClass,
            onOpenPackageFolder = onOpenPackageFolder,
            onNavigateUpFolder = onNavigateUpFolder,
            onSwitchDexEntry = onSwitchDexEntry,
            onSearchQueryChange = onSearchQueryChange,
            onSearchScopeChange = onSearchScopeChange,
            onReturnToOpenClass = onReturnToCodeEditor,
            onRequestSaveAndSign = { showConfirmSaveDialog = true },
            onCloseEditor = onClose,
            modifier = Modifier.weight(1f),
          )
        } else {
          BackHandler(onBack = requestReturnToExplorer)

          // IDE Top Bar
          Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                IconButton(
                  onClick = requestReturnToExplorer,
                  modifier = Modifier.size(48.dp).testTag("dex_editor_back_btn"),
                ) {
                  Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back_to_folders))
                }

                if (!isWide) {
                  IconButton(
                    onClick = requestReturnToExplorer,
                    modifier = Modifier.size(48.dp).testTag("dex_editor_classes_btn"),
                  ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.desc_class_explorer))
                  }
                }

                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = buildString {
                      append(state.selectedClass?.let { "${it.simpleName}.java" } ?: state.targetDisplayName)
                      if (state.isDirty) append(" *")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = state.selectedClass?.let { cls ->
                      cls.packageName.ifEmpty { stringResource(R.string.root_package_label) }
                    } ?: stringResource(R.string.classes_count_label, state.classes.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }

                IconButton(
                  onClick = onUndo,
                  enabled = state.undoStack.isNotEmpty(),
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.desc_undo))
                }
                IconButton(
                  onClick = onRedo,
                  enabled = state.redoStack.isNotEmpty(),
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.desc_redo))
                }
                IconButton(
                  onClick = { showFindReplace = !showFindReplace },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(Icons.Default.FindReplace, contentDescription = stringResource(R.string.desc_find_replace))
                }
              }

              // Secondary Toolbar: DEX selector, Java/Smali toggle, Compile & Save/Sign
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState())
                  .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                if (state.dexEntries.size > 1) {
                  var dexMenuOpen by remember { mutableStateOf(false) }
                  Box {
                    OutlinedButton(onClick = { dexMenuOpen = true }, modifier = Modifier.height(36.dp)) {
                      Text(state.activeDexEntry, style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(expanded = dexMenuOpen, onDismissRequest = { dexMenuOpen = false }) {
                      state.dexEntries.forEach { entry ->
                        DropdownMenuItem(
                          text = { Text(entry) },
                          onClick = {
                            dexMenuOpen = false
                            onSwitchDexEntry(entry)
                          },
                        )
                      }
                    }
                  }
                }

                FilterChip(
                  selected = state.decompilerMode == DecompilerMode.JAVA,
                  onClick = { onModeChange(DecompilerMode.JAVA) },
                  label = { Text(stringResource(R.string.tab_java)) },
                  leadingIcon = { Icon(Icons.Default.Code, null, modifier = Modifier.size(16.dp)) },
                  modifier = Modifier.height(36.dp).testTag("mode_java_chip"),
                )
                FilterChip(
                  selected = state.decompilerMode == DecompilerMode.SMALI_INFO,
                  onClick = { onModeChange(DecompilerMode.SMALI_INFO) },
                  label = { Text(stringResource(R.string.tab_bytecode)) },
                  modifier = Modifier.height(36.dp).testTag("mode_bytecode_chip"),
                )

                FilledTonalButton(
                  onClick = onAnalyzeObfuscated,
                  enabled = !state.isDecompiling &&
                    !state.isCompiling &&
                    state.selectedClass != null &&
                    state.decompilerMode == DecompilerMode.JAVA &&
                    state.deobfuscationState !is DeobfuscationUiState.Loading,
                  modifier = Modifier.height(36.dp).testTag("deobfuscate_ai_btn"),
                  contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                  if (state.deobfuscationState is DeobfuscationUiState.Loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                  } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                  }
                  Spacer(Modifier.width(6.dp))
                  Text(stringResource(R.string.btn_deobfuscate_ai), style = MaterialTheme.typography.labelLarge)
                }

                Button(
                  onClick = onCompileToDex,
                  enabled = !state.isCompiling && state.selectedClass != null && state.decompilerMode == DecompilerMode.JAVA,
                  colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                  ),
                  modifier = Modifier.height(36.dp).testTag("compile_java_to_dex_btn"),
                  contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                  if (state.isCompiling) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                  } else {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp))
                  }
                  Spacer(Modifier.width(6.dp))
                  Text(stringResource(R.string.btn_compile_to_dex), style = MaterialTheme.typography.labelLarge)
                }

                Button(
                  onClick = { showConfirmSaveDialog = true },
                  enabled = !state.isCompiling,
                  colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                  ),
                  modifier = Modifier.height(36.dp).testTag("save_and_sign_apk_btn"),
                  contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                  Icon(
                    if (state.targetApkPath != null) Icons.Default.VerifiedUser else Icons.Default.Save,
                    null,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(
                    stringResource(if (state.targetApkPath != null) R.string.btn_confirm_and_sign_apk else R.string.btn_save_dex),
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
              }

              // Find & Replace Bar
              AnimatedVisibility(visible = showFindReplace) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  OutlinedTextField(
                    value = findText,
                    onValueChange = { findText = it },
                    placeholder = { Text(stringResource(R.string.hint_find)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  OutlinedTextField(
                    value = replaceText,
                    onValueChange = { replaceText = it },
                    placeholder = { Text(stringResource(R.string.hint_replace)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                  )
                  TextButton(
                    onClick = {
                      if (findText.isNotEmpty() && state.decompilerMode == DecompilerMode.JAVA) {
                        onCodeChange(state.javaCode.replace(findText, replaceText))
                      }
                    },
                  ) {
                    Text(stringResource(R.string.btn_replace_all))
                  }
                }
              }
            }
          }

          HorizontalDivider()

          // Main Workspace Row (Folder Tree Sidebar on Wide + Code Editor)
          Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isWide) {
              Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(290.dp).fillMaxHeight(),
              ) {
                DexPackageBrowserContent(
                  classes = state.classes,
                  currentPackagePath = state.currentPackagePath,
                  selectedClass = state.selectedClass,
                  patchedClassNames = state.patchedClassNames,
                  searchQuery = state.searchQuery,
                  searchScope = state.searchScope,
                  activeDexEntry = state.activeDexEntry,
                  onSearchChange = onSearchQueryChange,
                  onSearchScopeChange = onSearchScopeChange,
                  onOpenPackageFolder = onOpenPackageFolder,
                  onNavigateUpFolder = onNavigateUpFolder,
                  onSelectClass = onSelectClass,
                )
              }
              HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
              when {
                state.isLoadingIndex -> {
                  Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.decompiling_dex_index), color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                state.isDecompiling -> {
                  Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.generating_editable_java), color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                state.selectedClass == null -> {
                  Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.select_class_prompt), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onOpenClassBrowser) {
                      Text(stringResource(R.string.btn_open_class_folders))
                    }
                  }
                }
                else -> {
                  val displayedText = if (state.decompilerMode == DecompilerMode.JAVA) {
                    state.javaCode
                  } else {
                    state.smaliCode
                  }
                  JavaSyntaxCodeCanvas(
                    code = displayedText,
                    readOnly = state.decompilerMode != DecompilerMode.JAVA,
                    onCodeChange = onCodeChange,
                    modifier = Modifier.fillMaxSize(),
                  )
                }
              }
            }
          }

          // Compile Result Banner
          state.lastCompileResult?.let { result ->
            CompileStatusBanner(
              result = result,
              expanded = showBuildLogs,
              onToggleExpand = { showBuildLogs = !showBuildLogs },
            )
          }
        }

        // Ready-to-use Saved & Signed APK Banner (shown in both Browser and Editor)
        state.lastSavedApkPath?.let { savedPath ->
          val savedFile = remember(savedPath) { java.io.File(savedPath) }
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.banner_ready_title, savedFile.name),
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = stringResource(R.string.banner_ready_subtitle),
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              TextButton(onClick = { onExportSavedFile(savedFile) }) {
                Text(stringResource(R.string.btn_export))
              }
              TextButton(onClick = { onShareSavedFile(savedFile) }) {
                Text(stringResource(R.string.action_share))
              }
              IconButton(onClick = onDismissSavedBanner, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_close_banner))
              }
            }
          }
        }
      }

      if (showConfirmSaveDialog) {
        ConfirmApplyAndSignDialog(
          isApk = state.targetApkPath != null,
          apkName = state.targetDisplayName,
          hasUncompiledEdits = state.isDirty,
          onDismiss = { showConfirmSaveDialog = false },
          onConfirm = { autoSign, overwriteOriginal, createBackup ->
            showConfirmSaveDialog = false
            onSaveAndSign(autoSign, overwriteOriginal, createBackup)
          },
        )
      }

      if (showUnsavedReturnDialog) {
        AlertDialog(
          onDismissRequest = { showUnsavedReturnDialog = false },
          title = { Text(stringResource(R.string.unsaved_java_title)) },
          text = {
            val targetName = state.selectedClass?.simpleName ?: stringResource(R.string.this_class_fallback)
            Text(stringResource(R.string.unsaved_java_body, targetName))
          },
          confirmButton = {
            Button(
              onClick = {
                showUnsavedReturnDialog = false
                onCompileAndReturnToBrowser()
              }
            ) {
              Text(stringResource(R.string.btn_compile_and_return))
            }
          },
          dismissButton = {
            Row {
              TextButton(
                onClick = {
                  showUnsavedReturnDialog = false
                  onDiscardAndReturnToBrowser()
                }
              ) {
                Text(stringResource(R.string.btn_discard))
              }
              TextButton(onClick = { showUnsavedReturnDialog = false }) {
                Text(stringResource(R.string.action_cancel))
              }
            }
          },
        )
      }

      if (state.deobfuscationState !is DeobfuscationUiState.Idle) {
        ContextDeobfuscationDialog(
          uiState = state.deobfuscationState,
          topLevelClassName = state.selectedClass?.simpleName,
          onContinueWaiting = onContinueDeobfWaiting,
          onToggleSuggestion = onToggleDeobfSuggestion,
          onSetAllSelected = onSetAllDeobfSelected,
          onApply = onApplyDeobfSuggestions,
          onRetry = { useFast ->
            if (useFast) onAnalyzeObfuscatedFast() else onAnalyzeObfuscated()
          },
          onRetryFast = onAnalyzeObfuscatedFast,
          onDismiss = onDismissDeobfDialog,
        )
      }
    }
  }
}

@Composable
private fun JavaSyntaxCodeCanvas(
  code: String,
  readOnly: Boolean,
  onCodeChange: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val verticalScroll = rememberScrollState()
  val horizontalScroll = rememberScrollState()
  val lineCount = remember(code) { code.count { it == '\n' } + 1 }
  val gutterText = remember(lineCount) {
    (1..lineCount).joinToString("\n")
  }
  val visualTransformation = remember(readOnly) {
    if (readOnly) VisualTransformation.None else JavaSyntaxVisualTransformation()
  }

  Row(
    modifier = modifier
      .background(SyntaxColors.EditorBackground)
      .verticalScroll(verticalScroll),
  ) {
    // Line numbers gutter
    Box(
      modifier = Modifier
        .background(SyntaxColors.GutterBackground)
        .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
      Text(
        text = gutterText,
        style = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 13.sp,
          lineHeight = 20.sp,
          color = SyntaxColors.LineNumber,
        ),
      )
    }

    // Editable code area
    Box(
      modifier = Modifier
        .weight(1f)
        .horizontalScroll(horizontalScroll)
        .padding(12.dp),
    ) {
      BasicTextField(
        value = code,
        onValueChange = { if (!readOnly) onCodeChange(it) },
        readOnly = readOnly,
        textStyle = TextStyle(
          fontFamily = JetBrainsMonoFamily,
          fontSize = 13.sp,
          lineHeight = 20.sp,
          color = SyntaxColors.PlainText,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = visualTransformation,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 500.dp)
          .testTag("java_code_editor_field"),
      )
    }
  }
}

@Composable
private fun CompileStatusBanner(
  result: CompilePatchResult,
  expanded: Boolean,
  onToggleExpand: () -> Unit,
) {
  Surface(
    color = if (result.success) {
      MaterialTheme.colorScheme.tertiaryContainer
    } else {
      MaterialTheme.colorScheme.errorContainer
    },
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggleExpand),
      ) {
        Icon(
          imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
          contentDescription = null,
          tint = if (result.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Text(
          text = if (result.success) {
            stringResource(R.string.compiled_ok_banner, result.compiledClassesCount)
          } else {
            val first = result.diagnostics.firstOrNull()
            if (first != null) {
              stringResource(R.string.compile_error_line, first.line, first.message)
            } else {
              stringResource(R.string.compile_error_generic)
            }
          },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stringResource(if (expanded) R.string.btn_hide_log else R.string.btn_view_log),
          style = MaterialTheme.typography.labelLarge,
        )
      }

      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier
            .padding(top = 8.dp)
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState()),
        ) {
          result.logs.forEach { logLine ->
            Text(
              text = logLine,
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.labelSmall,
            )
          }
          result.diagnostics.forEach { diag ->
            Text(
              text = stringResource(R.string.log_line_prefix, diag.line, diag.message),
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

private class JavaSyntaxVisualTransformation : VisualTransformation {
  companion object {
    private val KEYWORDS = setOf(
      "package", "import", "public", "private", "protected", "class", "interface", "enum",
      "extends", "implements", "static", "final", "abstract", "synchronized", "volatile",
      "transient", "native", "void", "boolean", "byte", "short", "char", "int", "long",
      "float", "double", "if", "else", "for", "while", "do", "switch", "case", "default",
      "break", "continue", "return", "try", "catch", "finally", "throw", "throws", "new",
      "this", "super", "instanceof", "null", "true", "false",
    )
    private val TOKEN_REGEX = Regex(
      """(//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|@\w+|\b\d+(?:\.\d+)?[lLfFdD]?\b|\b[A-Za-z_]\w*\b)"""
    )
  }

  override fun filter(text: AnnotatedString): TransformedText {
    val raw = text.text
    val highlighted = buildAnnotatedString {
      append(raw)
      if (raw.length <= 120_000) {
        for (match in TOKEN_REGEX.findAll(raw)) {
          val tok = match.value
          val start = match.range.first
          val end = match.range.last + 1
          val color = when {
            tok.startsWith("//") || tok.startsWith("/*") -> SyntaxColors.Comment
            tok.startsWith("\"") || tok.startsWith("'") -> SyntaxColors.StringLiteral
            tok.startsWith("@") -> SyntaxColors.Annotation
            tok[0].isDigit() -> SyntaxColors.NumberLiteral
            tok in KEYWORDS -> SyntaxColors.Keyword
            tok[0].isUpperCase() -> SyntaxColors.TypeName
            else -> null
          }
          if (color != null) {
            addStyle(SpanStyle(color = color), start, end)
          }
        }
      }
    }
    return TransformedText(highlighted, OffsetMapping.Identity)
  }
}

@Composable
private fun ConfirmApplyAndSignDialog(
  isApk: Boolean,
  apkName: String,
  hasUncompiledEdits: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (autoSign: Boolean, overwriteOriginal: Boolean, createBackup: Boolean) -> Unit,
) {
  var overwriteOriginal by remember { mutableStateOf(true) }
  var createBackup by remember { mutableStateOf(true) }
  var autoSign by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = if (isApk) Icons.Default.VerifiedUser else Icons.Default.Save,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = {
      Text(
        text = stringResource(if (isApk) R.string.confirm_dialog_title_apk else R.string.confirm_dialog_title_dex),
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = when {
            hasUncompiledEdits && isApk -> stringResource(R.string.confirm_body_uncompiled_apk, apkName)
            hasUncompiledEdits -> stringResource(R.string.confirm_body_uncompiled_dex)
            isApk -> stringResource(R.string.confirm_body_compiled_apk, apkName)
            else -> stringResource(R.string.confirm_body_compiled_dex)
          },
          style = MaterialTheme.typography.bodyMedium,
        )

        if (isApk) {
          HorizontalDivider()
          Text(
            text = stringResource(R.string.label_save_destination),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { overwriteOriginal = true }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = overwriteOriginal,
              onClick = { overwriteOriginal = true },
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.option_overwrite_original), style = MaterialTheme.typography.bodyMedium)
              Text(
                stringResource(R.string.option_overwrite_desc, apkName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          if (overwriteOriginal) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp)
                .clickable { createBackup = !createBackup },
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = createBackup,
                onCheckedChange = { createBackup = it },
              )
              Text(
                stringResource(R.string.option_create_backup),
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { overwriteOriginal = false }
              .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = !overwriteOriginal,
              onClick = { overwriteOriginal = false },
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.option_save_export_copy), style = MaterialTheme.typography.bodyMedium)
              Text(
                stringResource(R.string.option_save_export_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          HorizontalDivider()

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { autoSign = !autoSign }
              .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = autoSign,
              onCheckedChange = { autoSign = it },
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.option_auto_sign), style = MaterialTheme.typography.bodyMedium)
              Text(
                stringResource(R.string.option_auto_sign_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(autoSign, overwriteOriginal, createBackup) },
        modifier = Modifier.testTag("confirm_apply_and_sign_btn"),
      ) {
        Text(
          stringResource(
            when {
              !isApk -> R.string.btn_save_dex
              autoSign -> R.string.btn_apply_and_sign
              else -> R.string.btn_apply_unsigned
            }
          )
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
private fun ContextDeobfuscationDialog(
  uiState: DeobfuscationUiState,
  topLevelClassName: String?,
  onContinueWaiting: () -> Unit,
  onToggleSuggestion: (Int) -> Unit,
  onSetAllSelected: (Boolean) -> Unit,
  onApply: () -> Unit,
  onRetry: (useFastModel: Boolean) -> Unit,
  onRetryFast: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = {
      if (uiState !is DeobfuscationUiState.Loading) {
        onDismiss()
      }
    },
    icon = {
      Icon(
        imageVector = Icons.Default.AutoFixHigh,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    title = {
      Text(stringResource(R.string.deobf_dialog_title))
    },
    text = {
      when (uiState) {
        DeobfuscationUiState.Idle -> Unit
        is DeobfuscationUiState.Loading -> {
          val mins = uiState.elapsedSeconds / 60
          val secs = uiState.elapsedSeconds % 60
          val elapsedText = "%02d:%02d".format(mins, secs)
          val activeModelName = uiState.activeModelName

          val phaseText = when (uiState.phase) {
            DeobfuscationPhase.PREPARING_CONTEXT ->
              stringResource(R.string.deobf_phase_preparing)
            DeobfuscationPhase.CHECKING_APP_CHECK ->
              stringResource(R.string.deobf_phase_app_check)
            DeobfuscationPhase.CONNECTING_MODEL ->
              stringResource(R.string.deobf_connecting_model, activeModelName)
            DeobfuscationPhase.THINKING ->
              stringResource(R.string.deobf_phase_thinking, activeModelName, uiState.chunkCount)
            DeobfuscationPhase.STREAMING ->
              stringResource(R.string.deobf_streaming_progress, uiState.streamedChars)
            DeobfuscationPhase.PARSING ->
              stringResource(R.string.deobf_phase_parsing)
          }

          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              CircularProgressIndicator(modifier = Modifier.size(28.dp))
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = stringResource(R.string.deobf_loading_body),
                  style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                  text = stringResource(R.string.deobf_elapsed_label, elapsedText),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.testTag("deobf_elapsed_text"),
                )
                Text(
                  text = phaseText,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("deobf_phase_text"),
                )
                if (uiState.candidateCount > 0 || uiState.autoFastSelected) {
                  val metaParts = buildList {
                    if (uiState.candidateCount > 0) {
                      add(stringResource(R.string.deobf_candidates_detected, uiState.candidateCount))
                    }
                    if (uiState.autoFastSelected) {
                      add(stringResource(R.string.deobf_auto_fast_badge))
                    }
                  }
                  Text(
                    text = metaParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                  )
                }
              }
            }

            if (uiState.isSlowInitialConnection &&
              !uiState.useFastModel &&
              !uiState.autoFastSelected &&
              !uiState.awaitingContinueDecision
            ) {
              Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("deobf_slow_connect_card"),
              ) {
                Column(
                  modifier = Modifier.padding(10.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = stringResource(R.string.deobf_slow_initial_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  OutlinedButton(
                    onClick = onRetryFast,
                    modifier = Modifier
                      .fillMaxWidth()
                      .heightIn(min = 48.dp)
                      .testTag("deobf_switch_fast_btn"),
                  ) {
                    Text(stringResource(R.string.deobf_btn_try_fast_mode))
                  }
                }
              }
            }

            if (uiState.awaitingContinueDecision) {
              Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("deobf_checkin_card"),
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    text = stringResource(R.string.deobf_checkin_title, elapsedText),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                  Text(
                    text = stringResource(R.string.deobf_checkin_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                }
              }
            }
          }
        }
        is DeobfuscationUiState.Error -> {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = MaterialTheme.shapes.small,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Default.Error,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text = uiState.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
            if (!uiState.usedFastModel) {
              OutlinedButton(
                onClick = onRetryFast,
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .testTag("deobf_retry_fast_btn"),
              ) {
                Text(stringResource(R.string.deobf_btn_try_fast_mode))
              }
            }
          }
        }
        is DeobfuscationUiState.Ready -> {
          val selectedCount = uiState.suggestions.count { it.selected }
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            if (uiState.summary.isNotBlank()) {
              Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(modifier = Modifier.padding(10.dp)) {
                  Text(
                    text = stringResource(R.string.deobf_context_summary_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = uiState.summary,
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              }
            }

            if (uiState.suggestions.isEmpty()) {
              Text(
                text = stringResource(R.string.deobf_no_suggestions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = stringResource(R.string.deobf_selected_count, selectedCount, uiState.suggestions.size),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  TextButton(
                    onClick = { onSetAllSelected(true) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  ) {
                    Text(stringResource(R.string.deobf_select_all), style = MaterialTheme.typography.labelSmall)
                  }
                  TextButton(
                    onClick = { onSetAllSelected(false) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                  ) {
                    Text(stringResource(R.string.deobf_deselect_all), style = MaterialTheme.typography.labelSmall)
                  }
                }
              }

              HorizontalDivider()

              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 310.dp)
                  .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                uiState.suggestions.forEachIndexed { idx, item ->
                  DeobfuscationSuggestionRow(
                    item = item,
                    isTopLevelClass = item.kind == DeobfuscationSymbolKind.CLASS &&
                      topLevelClassName != null &&
                      item.originalName == topLevelClassName,
                    onToggle = { onToggleSuggestion(idx) },
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      when (uiState) {
        DeobfuscationUiState.Idle -> Unit
        is DeobfuscationUiState.Loading -> {
          if (uiState.awaitingContinueDecision) {
            Button(
              onClick = onContinueWaiting,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("deobf_continue_btn"),
            ) {
              Text(stringResource(R.string.deobf_btn_continue_waiting))
            }
          }
        }
        is DeobfuscationUiState.Error -> {
          Button(
            onClick = { onRetry(uiState.usedFastModel) },
            modifier = Modifier.heightIn(min = 48.dp),
          ) {
            Text(stringResource(R.string.btn_retry))
          }
        }
        is DeobfuscationUiState.Ready -> {
          val selectedCount = uiState.suggestions.count { it.selected }
          if (uiState.suggestions.isNotEmpty()) {
            Button(
              onClick = onApply,
              enabled = selectedCount > 0,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("deobf_apply_btn"),
            ) {
              Text(stringResource(R.string.btn_apply_renames, selectedCount))
            }
          }
        }
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("deobf_stop_btn"),
      ) {
        Text(
          stringResource(
            if (uiState is DeobfuscationUiState.Loading) {
              R.string.deobf_btn_stop_and_close
            } else {
              R.string.action_close
            }
          )
        )
      }
    },
  )
}

@Composable
private fun DeobfuscationSuggestionRow(
  item: DeobfuscationSuggestion,
  isTopLevelClass: Boolean,
  onToggle: () -> Unit,
) {
  val kindLabel = when (item.kind) {
    DeobfuscationSymbolKind.CLASS -> stringResource(R.string.deobf_kind_class)
    DeobfuscationSymbolKind.METHOD -> stringResource(R.string.deobf_kind_method)
    DeobfuscationSymbolKind.FIELD -> stringResource(R.string.deobf_kind_field)
    DeobfuscationSymbolKind.PARAM_OR_VAR -> stringResource(R.string.deobf_kind_var)
  }

  Surface(
    shape = MaterialTheme.shapes.small,
    color = if (item.selected) {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    },
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Checkbox(
        checked = item.selected,
        onCheckedChange = { onToggle() },
      )
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = kindLabel,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
          }
          Text(
            text = "${item.originalName} → ${item.suggestedName}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (item.reason.isNotBlank()) {
          Text(
            text = item.reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (isTopLevelClass) {
          Text(
            text = stringResource(R.string.deobf_top_class_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
          )
        }
      }
    }
  }
}


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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import com.example.model.CompilePatchResult
import com.example.model.DecompilerMode
import com.example.model.DexClassInfo
import com.example.model.DexEditorState
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SyntaxColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavaCodeEditorScreen(
  state: DexEditorState,
  onSelectClass: (DexClassInfo) -> Unit,
  onSwitchDexEntry: (String) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onModeChange: (DecompilerMode) -> Unit,
  onCodeChange: (String) -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onCompileToDex: () -> Unit,
  onSaveAndSign: (autoSign: Boolean, overwriteOriginal: Boolean, createBackup: Boolean) -> Unit,
  onExportSavedFile: (java.io.File) -> Unit,
  onShareSavedFile: (java.io.File) -> Unit,
  onDismissSavedBanner: () -> Unit,
  onClose: () -> Unit,
) {
  BackHandler(onBack = onClose)

  var showClassSheet by remember { mutableStateOf(false) }
  var showFindReplace by remember { mutableStateOf(false) }
  var findText by remember { mutableStateOf("") }
  var replaceText by remember { mutableStateOf("") }
  var showBuildLogs by remember { mutableStateOf(false) }
  var showConfirmSaveDialog by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .imePadding(),
    color = SyntaxColors.EditorBackground,
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val isWide = maxWidth >= 720.dp

      Column(modifier = Modifier.fillMaxSize()) {
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
                onClick = onClose,
                modifier = Modifier.size(48.dp).testTag("dex_editor_back_btn"),
              ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Salir del editor")
              }

              if (!isWide) {
                IconButton(
                  onClick = { showClassSheet = true },
                  modifier = Modifier.size(48.dp).testTag("dex_editor_classes_btn"),
                ) {
                  Icon(Icons.Default.List, contentDescription = "Lista de clases")
                }
              }

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = buildString {
                    append(state.selectedClass?.simpleName ?: state.targetDisplayName)
                    if (state.isDirty) append(" *")
                  },
                  style = MaterialTheme.typography.titleSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = state.selectedClass?.packageName ?: "${state.classes.size} clases",
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
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Deshacer")
              }
              IconButton(
                onClick = onRedo,
                enabled = state.redoStack.isNotEmpty(),
                modifier = Modifier.size(48.dp),
              ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Rehacer")
              }
              IconButton(
                onClick = { showFindReplace = !showFindReplace },
                modifier = Modifier.size(48.dp),
              ) {
                Icon(Icons.Default.FindReplace, contentDescription = "Buscar y reemplazar")
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
                label = { Text("Java") },
                leadingIcon = { Icon(Icons.Default.Code, null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.height(36.dp).testTag("mode_java_chip"),
              )
              FilterChip(
                selected = state.decompilerMode == DecompilerMode.SMALI_INFO,
                onClick = { onModeChange(DecompilerMode.SMALI_INFO) },
                label = { Text("Bytecode") },
                modifier = Modifier.height(36.dp).testTag("mode_bytecode_chip"),
              )

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
                Text("Compilar → DEX", style = MaterialTheme.typography.labelLarge)
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
                  if (state.targetApkPath != null) "Confirmar y Firmar APK" else "Guardar DEX",
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
                  placeholder = { Text("Buscar") },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                  value = replaceText,
                  onValueChange = { replaceText = it },
                  placeholder = { Text("Reemplazar") },
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
                  Text("Todo")
                }
              }
            }
          }
        }

        HorizontalDivider()

        // Main Workspace Row (Sidebar on Wide + Code Editor)
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
          if (isWide) {
            Surface(
              color = MaterialTheme.colorScheme.surface,
              modifier = Modifier.width(270.dp).fillMaxHeight(),
            ) {
              ClassBrowserContent(
                classes = state.classes,
                selectedClass = state.selectedClass,
                searchQuery = state.searchQuery,
                onSearchChange = onSearchQueryChange,
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
                  Text("Descompilando índice DEX con Jadx...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
              state.isDecompiling -> {
                Column(
                  modifier = Modifier.align(Alignment.Center),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  CircularProgressIndicator()
                  Text("Generando código Java...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
              state.selectedClass == null -> {
                Column(
                  modifier = Modifier.align(Alignment.Center).padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                  Text("Selecciona una clase para editar en Java", style = MaterialTheme.typography.titleMedium)
                  if (!isWide) {
                    Button(onClick = { showClassSheet = true }) {
                      Text("Explorar ${state.classes.size} clases")
                    }
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

        // Ready-to-use Saved & Signed APK Banner
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
                  text = "Listo: ${savedFile.name}",
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = "Cambios compilados en DEX y APK firmado",
                  style = MaterialTheme.typography.labelSmall,
                )
              }
              TextButton(onClick = { onExportSavedFile(savedFile) }) {
                Text("Exportar")
              }
              TextButton(onClick = { onShareSavedFile(savedFile) }) {
                Text("Compartir")
              }
              IconButton(onClick = onDismissSavedBanner, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar aviso")
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

      if (showClassSheet && !isWide) {
        ModalBottomSheet(
          onDismissRequest = { showClassSheet = false },
        ) {
          Box(modifier = Modifier.fillMaxWidth().height(520.dp)) {
            ClassBrowserContent(
              classes = state.classes,
              selectedClass = state.selectedClass,
              searchQuery = state.searchQuery,
              onSearchChange = onSearchQueryChange,
              onSelectClass = { cls ->
                showClassSheet = false
                onSelectClass(cls)
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ClassBrowserContent(
  classes: List<DexClassInfo>,
  selectedClass: DexClassInfo?,
  searchQuery: String,
  onSearchChange: (String) -> Unit,
  onSelectClass: (DexClassInfo) -> Unit,
) {
  val filtered = remember(classes, searchQuery) {
    val q = searchQuery.trim()
    if (q.isEmpty()) classes
    else classes.filter {
      it.fullName.contains(q, ignoreCase = true)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    OutlinedTextField(
      value = searchQuery,
      onValueChange = onSearchChange,
      placeholder = { Text("Buscar clase o paquete...") },
      leadingIcon = { Icon(Icons.Default.Search, null) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { onSearchChange("") }) {
            Icon(Icons.Default.Close, "Limpiar")
          }
        }
      },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
    )

    Text(
      text = "${filtered.size} clases encontradas",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
    )

    LazyColumn(modifier = Modifier.fillMaxSize()) {
      items(filtered, key = { it.fullName }) { cls ->
        val selected = cls.fullName == selectedClass?.fullName
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
              else MaterialTheme.colorScheme.surface
            )
            .clickable { onSelectClass(cls) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (cls.isInterface) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(26.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = when {
                  cls.isInterface -> "I"
                  cls.isEnum -> "E"
                  else -> "C"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
              )
            }
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = cls.simpleName,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${cls.packageName} • ${cls.methodCount} mét.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
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
            "Compilado OK (${result.compiledClassesCount} clase(s) en DEX)"
          } else {
            val first = result.diagnostics.firstOrNull()
            if (first != null) "Línea ${first.line}: ${first.message}" else "Error de compilación"
          },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = if (expanded) "Ocultar log" else "Ver log",
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
              text = "[Línea ${diag.line}] ${diag.message}",
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
        text = if (isApk) "Confirmar cambios y firmar APK" else "Confirmar y guardar DEX",
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = buildString {
            if (hasUncompiledEdits) {
              append("Se compilará automáticamente tu código Java modificado a bytecode .dex")
              if (isApk) append(", se aplicará dentro de \"$apkName\" y se firmará listo para usar.")
              else append(" y se guardará el archivo.")
            } else {
              if (isApk) append("Se empaquetará el DEX parcheado en \"$apkName\" y se aplicará la firma digital.")
              else append("Se guardará el archivo DEX modificado.")
            }
          },
          style = MaterialTheme.typography.bodyMedium,
        )

        if (isApk) {
          HorizontalDivider()
          Text(
            text = "Destino de guardado",
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
              Text("Guardar en el APK original", style = MaterialTheme.typography.bodyMedium)
              Text(
                "Actualiza directamente $apkName",
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
                "Crear copia de respaldo (.bak)",
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
              Text("Guardar copia en Exportados/", style = MaterialTheme.typography.bodyMedium)
              Text(
                "Conserva el original intacto y crea un APK nuevo",
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
              Text("Firmar automáticamente (v1 / v2 / v3)", style = MaterialTheme.typography.bodyMedium)
              Text(
                "Necesario para poder instalar el APK en Android",
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
          when {
            !isApk -> "Guardar DEX"
            autoSign -> "Aplicar y Firmar"
            else -> "Aplicar sin firmar"
          }
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancelar")
      }
    },
  )
}


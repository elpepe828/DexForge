package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.ApkInspectorDialog
import com.example.ui.components.FilePaneCard
import com.example.ui.components.JavaCodeEditorScreen
import com.example.ui.components.SignerStudioScreen
import com.example.ui.components.TextFileEditorScreen
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexForgeApp(viewModel: MainViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }

  var pendingKeystoreUri by remember { mutableStateOf<Uri?>(null) }

  LaunchedEffect(state.statusMessage) {
    state.statusMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.clearStatusMessage()
    }
  }

  val importFileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      viewModel.importUriToPane(state.activePaneId, uri, openIfApk = true)
    }
  }

  val importSafTreeLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree()
  ) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      viewModel.mountSafTreeInPane(state.activePaneId, uri)
    }
  }

  val exportFileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
  ) { uri ->
    viewModel.completeExportToUri(uri)
  }

  val importKeystoreLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      pendingKeystoreUri = uri
    }
  }

  LaunchedEffect(state.pendingExportFile) {
    state.pendingExportFile?.let { file ->
      exportFileLauncher.launch(file.name)
    }
  }

  val shareFile: (File) -> Unit = { file ->
    val intent = viewModel.buildShareIntent(file)
    context.startActivity(Intent.createChooser(intent, "Compartir ${file.name}"))
  }

  // Fullscreen DEX -> Java Editor overlay
  state.dexEditor?.let { dexEd ->
    JavaCodeEditorScreen(
      state = dexEd,
      onSelectClass = viewModel::selectDexClass,
      onOpenPackageFolder = viewModel::openDexPackageFolder,
      onNavigateUpFolder = viewModel::navigateUpDexPackageFolder,
      onOpenClassBrowser = viewModel::openClassBrowser,
      onReturnToCodeEditor = viewModel::returnToCodeEditorFromBrowser,
      onCompileAndReturnToBrowser = { viewModel.compileEditedJavaToDex(returnToBrowserOnSuccess = true) },
      onDiscardAndReturnToBrowser = viewModel::discardEditsAndReturnToClassBrowser,
      onSwitchDexEntry = viewModel::switchActiveDexEntry,
      onSearchQueryChange = viewModel::setDexSearchQuery,
      onModeChange = viewModel::setDecompilerMode,
      onCodeChange = viewModel::updateJavaEditorCode,
      onUndo = viewModel::undoJavaEdit,
      onRedo = viewModel::redoJavaEdit,
      onCompileToDex = { viewModel.compileEditedJavaToDex(returnToBrowserOnSuccess = false) },
      onSaveAndSign = { autoSign, overwriteOriginal, createBackup ->
        viewModel.saveAndSignPatchedTarget(autoSign, overwriteOriginal, createBackup)
      },
      onExportSavedFile = viewModel::requestExportToDevice,
      onShareSavedFile = shareFile,
      onDismissSavedBanner = viewModel::clearSavedApkBanner,
      onClose = viewModel::closeDexEditor,
    )
    return
  }

  // Fullscreen Text / Decoded XML Editor overlay
  state.textEditor?.let { textEd ->
    TextFileEditorScreen(
      state = textEd,
      onContentChange = viewModel::updateTextEditorContent,
      onSave = viewModel::saveTextEditor,
      onClose = viewModel::closeTextEditor,
    )
    return
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isExpanded = maxWidth >= 700.dp

    Row(modifier = Modifier.fillMaxSize()) {
      if (isExpanded) {
        NavigationRail(
          containerColor = MaterialTheme.colorScheme.surface,
          modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
          Spacer(Modifier.height(12.dp))
          NavigationRailItem(
            selected = state.destination == MainNavDestination.DUAL_FILES,
            onClick = { viewModel.setDestination(MainNavDestination.DUAL_FILES) },
            icon = { Icon(Icons.Default.FolderCopy, contentDescription = "Gestor Dual") },
            label = { Text("Gestor") },
          )
          NavigationRailItem(
            selected = state.destination == MainNavDestination.SIGNER_STUDIO,
            onClick = { viewModel.setDestination(MainNavDestination.SIGNER_STUDIO) },
            icon = { Icon(Icons.Default.VerifiedUser, contentDescription = "Firmador APK") },
            label = { Text("Firmador") },
          )
        }
      }

      Scaffold(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
          TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
            ),
            title = {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = "DexForge",
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                )
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                  Text(
                    text = "DEX → JAVA",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            },
            actions = {
              IconButton(
                onClick = { viewModel.refreshBothPanes() },
                modifier = Modifier.size(48.dp).testTag("refresh_panes_btn"),
              ) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar paneles")
              }
              var topMenuOpen by remember { mutableStateOf(false) }
              Box {
                IconButton(
                  onClick = { topMenuOpen = true },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(Icons.Default.MoreVert, contentDescription = "Menú principal")
                }
                DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                  DropdownMenuItem(
                    text = { Text("Importar APK / DEX / archivo") },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = {
                      topMenuOpen = false
                      importFileLauncher.launch(arrayOf("*/*"))
                    },
                  )
                  DropdownMenuItem(
                    text = { Text("Generar APK de muestra (Java→DEX)") },
                    leadingIcon = { Icon(Icons.Default.BuildCircle, null) },
                    onClick = {
                      topMenuOpen = false
                      viewModel.createSampleApkInActivePane()
                    },
                  )
                }
              }
            },
          )
        },
        bottomBar = {
          if (!isExpanded) {
            NavigationBar(
              windowInsets = WindowInsets.navigationBars,
              containerColor = MaterialTheme.colorScheme.surface,
            ) {
              NavigationBarItem(
                selected = state.destination == MainNavDestination.DUAL_FILES,
                onClick = { viewModel.setDestination(MainNavDestination.DUAL_FILES) },
                icon = { Icon(Icons.Default.FolderCopy, contentDescription = null) },
                label = { Text("Gestor Dual") },
                modifier = Modifier.testTag("nav_dual_files"),
              )
              NavigationBarItem(
                selected = state.destination == MainNavDestination.SIGNER_STUDIO,
                onClick = { viewModel.setDestination(MainNavDestination.SIGNER_STUDIO) },
                icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                label = { Text("Firmador APK") },
                modifier = Modifier.testTag("nav_signer_studio"),
              )
            }
          }
        },
        floatingActionButton = {
          if (state.destination == MainNavDestination.DUAL_FILES) {
            ExtendedFloatingActionButton(
              onClick = { importFileLauncher.launch(arrayOf("*/*")) },
              icon = { Icon(Icons.Default.Add, contentDescription = null) },
              text = { Text("Abrir APK / DEX") },
              modifier = Modifier.testTag("fab_import_apk"),
            )
          }
        },
      ) { innerPadding ->
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MaterialTheme.colorScheme.background),
        ) {
          when (state.destination) {
            MainNavDestination.DUAL_FILES -> {
              if (isExpanded) {
                Row(
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  PaneWrapper(
                    viewModel = viewModel,
                    state = state,
                    paneId = 0,
                    onImportFile = { importFileLauncher.launch(arrayOf("*/*")) },
                    onMountSaf = { importSafTreeLauncher.launch(null) },
                    onShareFile = shareFile,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                  )
                  PaneWrapper(
                    viewModel = viewModel,
                    state = state,
                    paneId = 1,
                    onImportFile = { importFileLauncher.launch(arrayOf("*/*")) },
                    onMountSaf = { importSafTreeLauncher.launch(null) },
                    onShareFile = shareFile,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                  )
                }
              } else {
                Column(
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                      selected = state.activePaneId == 0,
                      onClick = { viewModel.setActivePane(0) },
                      shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                      modifier = Modifier.testTag("tab_pane_0"),
                    ) {
                      Text(
                        text = "Izq: ${state.leftPane.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    SegmentedButton(
                      selected = state.activePaneId == 1,
                      onClick = { viewModel.setActivePane(1) },
                      shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                      modifier = Modifier.testTag("tab_pane_1"),
                    ) {
                      Text(
                        text = "Der: ${state.rightPane.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                  }

                  PaneWrapper(
                    viewModel = viewModel,
                    state = state,
                    paneId = state.activePaneId,
                    onImportFile = { importFileLauncher.launch(arrayOf("*/*")) },
                    onMountSaf = { importSafTreeLauncher.launch(null) },
                    onShareFile = shareFile,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                  )
                }
              }
            }

            MainNavDestination.SIGNER_STUDIO -> {
              SignerStudioScreen(
                selectedApkPath = state.signerApkPath,
                verification = state.signerVerification,
                keystoreProfiles = state.keystoreProfiles,
                selectedKeystoreId = state.selectedKeystoreId,
                signV1 = state.signV1,
                signV2 = state.signV2,
                signV3 = state.signV3,
                isSigning = state.isSigning,
                workspaceApks = remember(state.leftPane.entries, state.rightPane.entries) {
                  viewModel.listWorkspaceApks()
                },
                onPickApkFile = viewModel::pickApkForSigner,
                onImportExternalApk = { importFileLauncher.launch(arrayOf("*/*")) },
                onSelectKeystore = viewModel::selectKeystore,
                onToggleSchemes = viewModel::setSignSchemes,
                onSignApk = viewModel::signCurrentTargetApk,
                onCreateKeystore = viewModel::createKeystore,
                onRequestImportKeystore = { importKeystoreLauncher.launch(arrayOf("*/*")) },
                onExportSignedApk = viewModel::requestExportToDevice,
              )
            }
          }

          if (state.isBusy) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.65f)),
              contentAlignment = Alignment.Center,
            ) {
              Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Row(
                  modifier = Modifier.padding(20.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  CircularProgressIndicator(modifier = Modifier.size(24.dp))
                  Text("Procesando APK/DEX...", style = MaterialTheme.typography.titleSmall)
                }
              }
            }
          }
        }
      }
    }
  }

  // APK Inspector Modal
  state.apkInspector?.let { summary ->
    ApkInspectorDialog(
      summary = summary,
      onDismiss = viewModel::dismissApkInspector,
      onOpenDexEditor = { viewModel.openApkInDexEditor(summary.apkFilePath) },
      onExploreEntries = { viewModel.openApkInsidePane(summary.apkFilePath) },
      onOpenSigner = { viewModel.sendApkToSigner(summary.apkFilePath) },
      onExportApk = viewModel::requestExportToDevice,
      onShareApk = shareFile,
    )
  }

  // Imported Keystore Credentials Dialog
  pendingKeystoreUri?.let { uri ->
    ImportKeystoreCredentialsDialog(
      onDismiss = { pendingKeystoreUri = null },
      onConfirm = { name, type, alias, sPass, kPass ->
        pendingKeystoreUri = null
        viewModel.importKeystoreFromUri(uri, name, type, alias, sPass, kPass)
      },
    )
  }
}

@Composable
private fun PaneWrapper(
  viewModel: MainViewModel,
  state: MainUiState,
  paneId: Int,
  onImportFile: () -> Unit,
  onMountSaf: () -> Unit,
  onShareFile: (File) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pane = if (paneId == 0) state.leftPane else state.rightPane
  val otherTitle = if (paneId == 0) state.rightPane.title else state.leftPane.title

  FilePaneCard(
    pane = pane,
    isActive = state.activePaneId == paneId,
    otherPaneTitle = otherTitle,
    onActivatePane = { viewModel.setActivePane(paneId) },
    onNavigateUp = { viewModel.navigateUp(paneId) },
    onNavigateInto = { viewModel.navigateInto(paneId, it) },
    onSwitchQuickFolder = { viewModel.switchPaneToWorkspaceQuickFolder(paneId, it) },
    onToggleSelect = { viewModel.toggleEntrySelection(paneId, it) },
    onClearSelection = { viewModel.clearSelection(paneId) },
    onSearchChange = { viewModel.updatePaneSearch(paneId, it) },
    onCopyToOther = { viewModel.transferSelectedToOtherPane(paneId, move = false) },
    onMoveToOther = { viewModel.transferSelectedToOtherPane(paneId, move = true) },
    onDeleteSelected = { viewModel.deleteSelected(paneId) },
    onZipSelected = { viewModel.zipSelectedInPane(paneId) },
    onCreateItem = { name, isDir -> viewModel.createNewItem(paneId, name, isDir) },
    onRenameItem = { entry, newName -> viewModel.renameEntry(paneId, entry, newName) },
    onOpenApkInDexEditor = { viewModel.openApkInDexEditor(it.absolutePath) },
    onOpenArchiveAsFolder = { viewModel.openArchiveInPane(paneId, it) },
    onSignApk = { viewModel.sendApkToSigner(it.absolutePath) },
    onExportFile = viewModel::requestExportToDevice,
    onShareFile = onShareFile,
    onMountSafFolder = onMountSaf,
    onImportFile = onImportFile,
    modifier = modifier,
  )
}

@Composable
private fun ImportKeystoreCredentialsDialog(
  onDismiss: () -> Unit,
  onConfirm: (String, String, String, String, String) -> Unit,
) {
  var name by remember { mutableStateOf("Mi Keystore") }
  var storeType by remember { mutableStateOf("PKCS12") }
  var alias by remember { mutableStateOf("") }
  var storePass by remember { mutableStateOf("") }
  var keyPass by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Credenciales del Keystore") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre") }, singleLine = true)
        OutlinedTextField(value = storeType, onValueChange = { storeType = it }, label = { Text("Tipo (PKCS12 o BKS)") }, singleLine = true)
        OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Alias (opcional)") }, singleLine = true)
        OutlinedTextField(value = storePass, onValueChange = { storePass = it }, label = { Text("Contraseña Store") }, singleLine = true)
        OutlinedTextField(value = keyPass, onValueChange = { keyPass = it }, label = { Text("Contraseña Clave") }, singleLine = true)
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name, storeType, alias, storePass, keyPass) }) {
        Text("Importar")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancelar") }
    },
  )
}

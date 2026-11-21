package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.components.ApkInspectorDialog
import com.example.ui.components.FilePaneCard
import com.example.ui.components.JavaCodeEditorScreen
import com.example.ui.components.SignerStudioScreen
import com.example.ui.components.TextFileEditorScreen
import com.example.util.LocaleManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexForgeApp(viewModel: MainViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val activityContext = LocalContext.current
  val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
  val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
  val localizedContext = remember(activityContext, state.appLanguageTag) {
    LocaleManager.wrapActivityContextForLocale(activityContext, state.appLanguageTag)
  }

  val providedValues = buildList {
    add(LocalContext provides localizedContext)
    add(LocalConfiguration provides localizedContext.resources.configuration)
    activityResultRegistryOwner?.let { add(LocalActivityResultRegistryOwner provides it) }
    onBackPressedDispatcherOwner?.let { add(LocalOnBackPressedDispatcherOwner provides it) }
  }.toTypedArray()

  CompositionLocalProvider(*providedValues) {
    DexForgeAppBody(
      viewModel = viewModel,
      state = state,
      activityContext = activityContext,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexForgeAppBody(
  viewModel: MainViewModel,
  state: MainUiState,
  activityContext: android.content.Context,
) {
  val snackbarHostState = remember { SnackbarHostState() }

  var pendingKeystoreUri by remember { mutableStateOf<Uri?>(null) }
  var showLanguageDialog by remember { mutableStateOf(false) }

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
        activityContext.contentResolver.takePersistableUriPermission(
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

  val shareContext = LocalContext.current
  val shareFile: (File) -> Unit = { file ->
    val intent = viewModel.buildShareIntent(file)
    activityContext.startActivity(
      Intent.createChooser(intent, shareContext.getString(R.string.share_chooser_title, file.name))
    )
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
            icon = { Icon(Icons.Default.FolderCopy, contentDescription = stringResource(R.string.nav_dual_manager)) },
            label = { Text(stringResource(R.string.nav_manager_short)) },
          )
          NavigationRailItem(
            selected = state.destination == MainNavDestination.SIGNER_STUDIO,
            onClick = { viewModel.setDestination(MainNavDestination.SIGNER_STUDIO) },
            icon = { Icon(Icons.Default.VerifiedUser, contentDescription = stringResource(R.string.nav_apk_signer)) },
            label = { Text(stringResource(R.string.nav_signer_short)) },
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
                  text = stringResource(R.string.app_name),
                  style = MaterialTheme.typography.titleLarge,
                  fontWeight = FontWeight.Bold,
                )
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                  Text(
                    text = stringResource(R.string.badge_dex_to_java),
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
                onClick = { showLanguageDialog = true },
                modifier = Modifier.size(48.dp).testTag("language_picker_btn"),
              ) {
                Icon(Icons.Default.Language, contentDescription = stringResource(R.string.menu_language))
              }
              IconButton(
                onClick = { viewModel.refreshBothPanes() },
                modifier = Modifier.size(48.dp).testTag("refresh_panes_btn"),
              ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.desc_refresh_panes))
              }
              var topMenuOpen by remember { mutableStateOf(false) }
              Box {
                IconButton(
                  onClick = { topMenuOpen = true },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.desc_main_menu))
                }
                DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                  DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_import_file)) },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = {
                      topMenuOpen = false
                      importFileLauncher.launch(arrayOf("*/*"))
                    },
                  )
                  DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_create_sample_apk)) },
                    leadingIcon = { Icon(Icons.Default.BuildCircle, null) },
                    onClick = {
                      topMenuOpen = false
                      viewModel.createSampleApkInActivePane()
                    },
                  )
                  DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_language)) },
                    leadingIcon = { Icon(Icons.Default.Language, null) },
                    onClick = {
                      topMenuOpen = false
                      showLanguageDialog = true
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
                label = { Text(stringResource(R.string.nav_dual_manager)) },
                modifier = Modifier.testTag("nav_dual_files"),
              )
              NavigationBarItem(
                selected = state.destination == MainNavDestination.SIGNER_STUDIO,
                onClick = { viewModel.setDestination(MainNavDestination.SIGNER_STUDIO) },
                icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                label = { Text(stringResource(R.string.nav_apk_signer)) },
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
              text = { Text(stringResource(R.string.fab_open_apk_dex)) },
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
                        text = stringResource(R.string.tab_left_prefix, state.leftPane.title),
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
                        text = stringResource(R.string.tab_right_prefix, state.rightPane.title),
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
                  Text(stringResource(R.string.busy_processing_apk_dex), style = MaterialTheme.typography.titleSmall)
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

  // App Language Selector Dialog
  if (showLanguageDialog) {
    LanguagePickerDialog(
      currentTag = state.appLanguageTag,
      onSelect = { tag ->
        showLanguageDialog = false
        viewModel.setAppLanguage(tag)
      },
      onDismiss = { showLanguageDialog = false },
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
  val defaultName = stringResource(R.string.default_keystore_name)
  var name by remember { mutableStateOf(defaultName) }
  var storeType by remember { mutableStateOf("PKCS12") }
  var alias by remember { mutableStateOf("") }
  var storePass by remember { mutableStateOf("") }
  var keyPass by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.dialog_keystore_creds_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.label_name)) }, singleLine = true)
        OutlinedTextField(value = storeType, onValueChange = { storeType = it }, label = { Text(stringResource(R.string.label_keystore_type)) }, singleLine = true)
        OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text(stringResource(R.string.label_alias_optional)) }, singleLine = true)
        OutlinedTextField(value = storePass, onValueChange = { storePass = it }, label = { Text(stringResource(R.string.label_store_password)) }, singleLine = true)
        OutlinedTextField(value = keyPass, onValueChange = { keyPass = it }, label = { Text(stringResource(R.string.label_key_password)) }, singleLine = true)
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name, storeType, alias, storePass, keyPass) }) {
        Text(stringResource(R.string.action_import))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
private fun LanguagePickerDialog(
  currentTag: String,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Default.Language, contentDescription = null) },
    title = { Text(stringResource(R.string.dialog_language_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = stringResource(R.string.dialog_language_subtitle),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 6.dp),
        )
        LocaleManager.supportedLanguages.forEach { option ->
          val label = if (option.tag.isEmpty()) {
            stringResource(R.string.language_system_default)
          } else {
            option.nativeName
          }
          val selected = option.tag == currentTag
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelect(option.tag) }
              .padding(vertical = 4.dp)
              .testTag("language_option_${option.tag.ifEmpty { "auto" }}"),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = selected,
              onClick = { onSelect(option.tag) },
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = label,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_close))
      }
    },
  )
}

package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.FileEntry
import com.example.model.PaneSourceType
import com.example.model.PaneState
import com.example.ui.formatSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilePaneCard(
  pane: PaneState,
  isActive: Boolean,
  otherPaneTitle: String,
  onActivatePane: () -> Unit,
  onNavigateUp: () -> Unit,
  onNavigateInto: (FileEntry) -> Unit,
  onSwitchQuickFolder: (String) -> Unit,
  onToggleSelect: (String) -> Unit,
  onClearSelection: () -> Unit,
  onSearchChange: (String) -> Unit,
  onCopyToOther: () -> Unit,
  onMoveToOther: () -> Unit,
  onDeleteSelected: () -> Unit,
  onZipSelected: () -> Unit,
  onCreateItem: (String, Boolean) -> Unit,
  onRenameItem: (FileEntry, String) -> Unit,
  onOpenApkInDexEditor: (File) -> Unit,
  onOpenArchiveAsFolder: (File) -> Unit,
  onSignApk: (File) -> Unit,
  onExportFile: (File) -> Unit,
  onShareFile: (File) -> Unit,
  onMountSafFolder: () -> Unit,
  onImportFile: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showSearch by remember { mutableStateOf(false) }
  var showCreateDialog by remember { mutableStateOf(false) }
  var createIsDir by remember { mutableStateOf(false) }
  var renameTarget by remember { mutableStateOf<FileEntry?>(null) }

  val filteredEntries = remember(pane.entries, pane.searchQuery) {
    val q = pane.searchQuery.trim()
    if (q.isEmpty()) pane.entries
    else pane.entries.filter { it.name.contains(q, ignoreCase = true) }
  }

  val borderColor = if (isActive) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.outlineVariant
  }

  Surface(
    modifier = modifier
      .fillMaxSize()
      .testTag("file_pane_${pane.paneId}"),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
    tonalElevation = if (isActive) 3.dp else 1.dp,
    onClick = onActivatePane,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Header bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
          .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (pane.canNavigateUp) {
          IconButton(
            onClick = {
              onActivatePane()
              onNavigateUp()
            },
            modifier = Modifier
              .size(48.dp)
              .testTag("pane_${pane.paneId}_up_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.desc_navigate_up),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        } else {
          Box(
            modifier = Modifier
              .padding(start = 4.dp, end = 8.dp)
              .size(32.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = when (pane.sourceType) {
                PaneSourceType.ARCHIVE_APK -> Icons.Default.Archive
                PaneSourceType.SAF_TREE -> Icons.Default.FolderOpen
                PaneSourceType.LOCAL_WORKSPACE -> Icons.Default.Folder
              },
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = pane.title.ifBlank { "Workspace" },
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            SourceBadge(pane.sourceType)
          }
          Text(
            text = when (pane.sourceType) {
              PaneSourceType.ARCHIVE_APK -> stringResource(R.string.subtitle_inside_archive, pane.archiveInternalDir)
              PaneSourceType.SAF_TREE -> stringResource(R.string.subtitle_saf_storage)
              PaneSourceType.LOCAL_WORKSPACE -> stringResource(R.string.subtitle_items_count, filteredEntries.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        IconButton(
          onClick = {
            onActivatePane()
            showSearch = !showSearch
          },
          modifier = Modifier.size(48.dp).testTag("pane_${pane.paneId}_search_btn"),
        ) {
          Icon(
            imageVector = if (showSearch) Icons.Default.Close else Icons.Default.Search,
            contentDescription = stringResource(R.string.desc_search_files),
          )
        }

        var paneMenuExpanded by remember { mutableStateOf(false) }
        Box {
          IconButton(
            onClick = {
              onActivatePane()
              paneMenuExpanded = true
            },
            modifier = Modifier.size(48.dp).testTag("pane_${pane.paneId}_menu_btn"),
          ) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.desc_pane_options))
          }
          DropdownMenu(
            expanded = paneMenuExpanded,
            onDismissRequest = { paneMenuExpanded = false },
          ) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.menu_import_apk_file)) },
              leadingIcon = { Icon(Icons.Default.FileOpen, null) },
              onClick = {
                paneMenuExpanded = false
                onImportFile()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.menu_mount_saf)) },
              leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
              onClick = {
                paneMenuExpanded = false
                onMountSafFolder()
              },
            )
            if (pane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_new_folder)) },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick = {
                  paneMenuExpanded = false
                  createIsDir = true
                  showCreateDialog = true
                },
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_new_text_java)) },
                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                onClick = {
                  paneMenuExpanded = false
                  createIsDir = false
                  showCreateDialog = true
                },
              )
            }
          }
        }
      }

      // Quick workspace chips
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        listOf(
          "APKs" to R.string.quick_folder_apks,
          "Proyectos" to R.string.quick_folder_projects,
          "Firmas" to R.string.quick_folder_keystores,
          "Exportados" to R.string.quick_folder_exports,
        ).forEach { (folderKey, labelRes) ->
          AssistChip(
            onClick = {
              onActivatePane()
              onSwitchQuickFolder(folderKey)
            },
            label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.height(32.dp),
          )
        }
      }

      AnimatedVisibility(visible = showSearch) {
        OutlinedTextField(
          value = pane.searchQuery,
          onValueChange = onSearchChange,
          placeholder = { Text(stringResource(R.string.hint_filter_files)) },
          singleLine = true,
          leadingIcon = { Icon(Icons.Default.Search, null) },
          trailingIcon = {
            if (pane.searchQuery.isNotEmpty()) {
              IconButton(onClick = { onSearchChange("") }) {
                Icon(Icons.Default.Close, stringResource(R.string.desc_clear_filter))
              }
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }

      // Selection bar
      AnimatedVisibility(visible = pane.selectedPaths.isNotEmpty()) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = stringResource(R.string.selected_count, pane.selectedPaths.size),
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 6.dp),
            )
            TextButton(onClick = onCopyToOther, modifier = Modifier.testTag("pane_${pane.paneId}_copy_btn")) {
              Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(
                if (pane.sourceType == PaneSourceType.ARCHIVE_APK) {
                  stringResource(R.string.action_extract_to, otherPaneTitle)
                } else {
                  stringResource(R.string.action_copy_to, otherPaneTitle)
                }
              )
            }
            if (pane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
              TextButton(onClick = onMoveToOther) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_move))
              }
              TextButton(onClick = onZipSelected) {
                Icon(Icons.Default.Archive, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.action_zip))
              }
              if (pane.selectedPaths.size == 1) {
                val targetEntry = pane.entries.firstOrNull { it.path == pane.selectedPaths.first() }
                if (targetEntry != null) {
                  TextButton(onClick = { renameTarget = targetEntry }) {
                    Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_rename))
                  }
                }
              }
            }
            TextButton(onClick = onDeleteSelected) {
              Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
              Spacer(Modifier.width(4.dp))
              Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onClearSelection, modifier = Modifier.size(40.dp)) {
              Icon(Icons.Default.Close, stringResource(R.string.desc_deselect))
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

      // File list
      if (pane.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else if (filteredEntries.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
              imageVector = Icons.Default.FolderOpen,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
              modifier = Modifier.size(44.dp),
            )
            Text(
              text = stringResource(R.string.empty_folder_title),
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = stringResource(R.string.empty_folder_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(vertical = 4.dp),
        ) {
          items(filteredEntries, key = { it.id }) { entry ->
            val isSelected = entry.path in pane.selectedPaths
            FileEntryRow(
              entry = entry,
              isSelected = isSelected,
              onClick = {
                onActivatePane()
                if (pane.selectedPaths.isNotEmpty()) {
                  onToggleSelect(entry.path)
                } else {
                  onNavigateInto(entry)
                }
              },
              onLongClick = {
                onActivatePane()
                onToggleSelect(entry.path)
              },
              onToggleSelect = {
                onActivatePane()
                onToggleSelect(entry.path)
              },
              onOpenApkInDexEditor = { onOpenApkInDexEditor(File(entry.path)) },
              onOpenArchiveAsFolder = { onOpenArchiveAsFolder(File(entry.path)) },
              onSignApk = { onSignApk(File(entry.path)) },
              onExportFile = { onExportFile(File(entry.path)) },
              onShareFile = { onShareFile(File(entry.path)) },
            )
          }
        }
      }
    }
  }

  if (showCreateDialog) {
    CreateItemDialog(
      isDirectory = createIsDir,
      onDismiss = { showCreateDialog = false },
      onConfirm = { name ->
        showCreateDialog = false
        onCreateItem(name, createIsDir)
      },
    )
  }

  renameTarget?.let { entry ->
    RenameItemDialog(
      currentName = entry.name,
      onDismiss = { renameTarget = null },
      onConfirm = { newName ->
        renameTarget = null
        onRenameItem(entry, newName)
      },
    )
  }
}

@Composable
private fun SourceBadge(sourceType: PaneSourceType) {
  val (label, container, content) = when (sourceType) {
    PaneSourceType.LOCAL_WORKSPACE -> Triple(
      "LOCAL",
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
    )
    PaneSourceType.ARCHIVE_APK -> Triple(
      "APK/ZIP",
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
    )
    PaneSourceType.SAF_TREE -> Triple(
      "SAF",
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
    )
  }
  Surface(
    shape = RoundedCornerShape(4.dp),
    color = container,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = content,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
  entry: FileEntry,
  isSelected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onToggleSelect: () -> Unit,
  onOpenApkInDexEditor: () -> Unit,
  onOpenArchiveAsFolder: () -> Unit,
  onSignApk: () -> Unit,
  onExportFile: () -> Unit,
  onShareFile: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val dateStr = remember(entry.lastModifiedMillis) {
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(entry.lastModifiedMillis))
  }

  val iconTint = when {
    entry.isDirectory -> MaterialTheme.colorScheme.secondary
    entry.isApk -> MaterialTheme.colorScheme.tertiary
    entry.isDex -> MaterialTheme.colorScheme.secondary
    entry.isEditableText -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  val iconVector = when {
    entry.isDirectory -> Icons.Default.Folder
    entry.isApk -> Icons.Default.Android
    entry.isDex || entry.extension == "java" || entry.extension == "kt" -> Icons.Default.Code
    entry.isZipLike -> Icons.Default.Archive
    else -> Icons.Default.Description
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surface
      )
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onToggleSelect, modifier = Modifier.size(48.dp)) {
      Icon(
        imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
        contentDescription = stringResource(R.string.desc_select_item, entry.name),
        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.size(20.dp),
      )
    }

    Box(
      modifier = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(iconTint.copy(alpha = 0.15f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = iconVector,
        contentDescription = null,
        tint = iconTint,
        modifier = Modifier.size(20.dp),
      )
    }

    Spacer(Modifier.width(10.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (entry.isDirectory || entry.isApk) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!entry.isDirectory) {
          Text(
            text = formatSize(entry.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            text = stringResource(R.string.label_folder),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = dateStr,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
      }
    }

    if (!entry.isDirectory && !entry.isArchiveEntry && !entry.isSafEntry) {
      Box {
        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
          Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.desc_item_actions, entry.name),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          if (entry.isApk) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_editor_dex_java)) },
              leadingIcon = { Icon(Icons.Default.Code, null) },
              onClick = {
                menuExpanded = false
                onOpenApkInDexEditor()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_explore_apk_content)) },
              leadingIcon = { Icon(Icons.Default.Archive, null) },
              onClick = {
                menuExpanded = false
                onOpenArchiveAsFolder()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.action_sign_apk)) },
              leadingIcon = { Icon(Icons.Default.VerifiedUser, null) },
              onClick = {
                menuExpanded = false
                onSignApk()
              },
            )
          }
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_save_to_device)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null) },
            onClick = {
              menuExpanded = false
              onExportFile()
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            onClick = {
              menuExpanded = false
              onShareFile()
            },
          )
        }
      }
    }
  }
}

@Composable
private fun CreateItemDialog(
  isDirectory: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  val defaultName = stringResource(if (isDirectory) R.string.default_new_folder_name else R.string.default_new_file_name)
  var name by remember { mutableStateOf(defaultName) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(if (isDirectory) R.string.dialog_new_folder_title else R.string.dialog_new_file_title)) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.label_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.action_create)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
private fun RenameItemDialog(
  currentName: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var name by remember { mutableStateOf(currentName) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.dialog_rename_title)) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.label_new_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.action_save)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

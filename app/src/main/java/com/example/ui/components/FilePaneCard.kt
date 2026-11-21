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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
              contentDescription = "Subir directorio",
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
              PaneSourceType.ARCHIVE_APK -> "Dentro de APK/ZIP: /${pane.archiveInternalDir}"
              PaneSourceType.SAF_TREE -> "Almacenamiento del dispositivo (SAF)"
              PaneSourceType.LOCAL_WORKSPACE -> "${filteredEntries.size} elementos"
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
            contentDescription = "Buscar archivos",
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
            Icon(Icons.Default.MoreVert, contentDescription = "Opciones del panel")
          }
          DropdownMenu(
            expanded = paneMenuExpanded,
            onDismissRequest = { paneMenuExpanded = false },
          ) {
            DropdownMenuItem(
              text = { Text("Importar APK / archivo...") },
              leadingIcon = { Icon(Icons.Default.FileOpen, null) },
              onClick = {
                paneMenuExpanded = false
                onImportFile()
              },
            )
            DropdownMenuItem(
              text = { Text("Montar carpeta SAF...") },
              leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
              onClick = {
                paneMenuExpanded = false
                onMountSafFolder()
              },
            )
            if (pane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
              DropdownMenuItem(
                text = { Text("Nueva carpeta") },
                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                onClick = {
                  paneMenuExpanded = false
                  createIsDir = true
                  showCreateDialog = true
                },
              )
              DropdownMenuItem(
                text = { Text("Nuevo archivo de texto/Java") },
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
        listOf("APKs", "Proyectos", "Firmas", "Exportados").forEach { folder ->
          AssistChip(
            onClick = {
              onActivatePane()
              onSwitchQuickFolder(folder)
            },
            label = { Text(folder, style = MaterialTheme.typography.labelMedium) },
            modifier = Modifier.height(32.dp),
          )
        }
      }

      AnimatedVisibility(visible = showSearch) {
        OutlinedTextField(
          value = pane.searchQuery,
          onValueChange = onSearchChange,
          placeholder = { Text("Filtrar archivos...") },
          singleLine = true,
          leadingIcon = { Icon(Icons.Default.Search, null) },
          trailingIcon = {
            if (pane.searchQuery.isNotEmpty()) {
              IconButton(onClick = { onSearchChange("") }) {
                Icon(Icons.Default.Close, "Limpiar filtro")
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
              text = "${pane.selectedPaths.size} sel.",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 6.dp),
            )
            TextButton(onClick = onCopyToOther, modifier = Modifier.testTag("pane_${pane.paneId}_copy_btn")) {
              Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(if (pane.sourceType == PaneSourceType.ARCHIVE_APK) "Extraer → $otherPaneTitle" else "Copiar → $otherPaneTitle")
            }
            if (pane.sourceType == PaneSourceType.LOCAL_WORKSPACE) {
              TextButton(onClick = onMoveToOther) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Mover")
              }
              TextButton(onClick = onZipSelected) {
                Icon(Icons.Default.Archive, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("ZIP")
              }
              if (pane.selectedPaths.size == 1) {
                val targetEntry = pane.entries.firstOrNull { it.path == pane.selectedPaths.first() }
                if (targetEntry != null) {
                  TextButton(onClick = { renameTarget = targetEntry }) {
                    Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Renombrar")
                  }
                }
              }
            }
            TextButton(onClick = onDeleteSelected) {
              Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
              Spacer(Modifier.width(4.dp))
              Text("Eliminar", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onClearSelection, modifier = Modifier.size(40.dp)) {
              Icon(Icons.Default.Close, "Deseleccionar")
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
              text = "Carpeta vacía",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "Importa un APK con '+' o cambia de carpeta arriba.",
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
        contentDescription = "Seleccionar ${entry.name}",
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
            text = "Carpeta",
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
            contentDescription = "Acciones de ${entry.name}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          if (entry.isApk) {
            DropdownMenuItem(
              text = { Text("Editor DEX → Java") },
              leadingIcon = { Icon(Icons.Default.Code, null) },
              onClick = {
                menuExpanded = false
                onOpenApkInDexEditor()
              },
            )
            DropdownMenuItem(
              text = { Text("Explorar contenido APK") },
              leadingIcon = { Icon(Icons.Default.Archive, null) },
              onClick = {
                menuExpanded = false
                onOpenArchiveAsFolder()
              },
            )
            DropdownMenuItem(
              text = { Text("Firmar APK") },
              leadingIcon = { Icon(Icons.Default.VerifiedUser, null) },
              onClick = {
                menuExpanded = false
                onSignApk()
              },
            )
          }
          DropdownMenuItem(
            text = { Text("Guardar en dispositivo...") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null) },
            onClick = {
              menuExpanded = false
              onExportFile()
            },
          )
          DropdownMenuItem(
            text = { Text("Compartir") },
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
  var name by remember { mutableStateOf(if (isDirectory) "nueva_carpeta" else "ClaseNueva.java") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (isDirectory) "Nueva carpeta" else "Nuevo archivo") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Nombre") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name) }) { Text("Crear") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancelar") }
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
    title = { Text("Renombrar") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Nuevo nombre") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name) }) { Text("Guardar") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancelar") }
    },
  )
}

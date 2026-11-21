package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.DexClassInfo
import com.example.model.DexEditorState
import com.example.ui.theme.JetBrainsMonoFamily

data class DexPackageFolderItem(
  val name: String,
  val fullPackagePath: String,
  val totalClasses: Int,
  val patchedCount: Int,
)

data class DexFolderListing(
  val folders: List<DexPackageFolderItem>,
  val classes: List<DexClassInfo>,
)

fun buildDexFolderListing(
  allClasses: List<DexClassInfo>,
  currentPackagePath: String,
  patchedClassNames: Set<String>,
): DexFolderListing {
  val prefix = if (currentPackagePath.isEmpty()) "" else "$currentPackagePath."
  val folderBuckets = linkedMapOf<String, MutableList<DexClassInfo>>()
  val directClasses = mutableListOf<DexClassInfo>()

  for (cls in allClasses) {
    val pkg = cls.packageName
    if (pkg == currentPackagePath) {
      directClasses += cls
    } else if (currentPackagePath.isEmpty() && pkg.isNotEmpty()) {
      val topSeg = pkg.substringBefore('.')
      folderBuckets.getOrPut(topSeg) { mutableListOf() }.add(cls)
    } else if (prefix.isNotEmpty() && pkg.startsWith(prefix)) {
      val remainder = pkg.removePrefix(prefix)
      if (remainder.isNotEmpty()) {
        val nextSeg = remainder.substringBefore('.')
        folderBuckets.getOrPut(nextSeg) { mutableListOf() }.add(cls)
      }
    }
  }

  val folders = folderBuckets.entries
    .map { (segName, bucket) ->
      val fullPkg = if (currentPackagePath.isEmpty()) segName else "$currentPackagePath.$segName"
      DexPackageFolderItem(
        name = segName,
        fullPackagePath = fullPkg,
        totalClasses = bucket.size,
        patchedCount = bucket.count { it.fullName in patchedClassNames },
      )
    }
    .sortedBy { it.name.lowercase() }

  return DexFolderListing(
    folders = folders,
    classes = directClasses.sortedBy { it.simpleName.lowercase() },
  )
}

@Composable
fun DexClassExplorerView(
  state: DexEditorState,
  onSelectClass: (DexClassInfo) -> Unit,
  onOpenPackageFolder: (String) -> Unit,
  onNavigateUpFolder: () -> Unit,
  onSwitchDexEntry: (String) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onReturnToOpenClass: () -> Unit,
  onRequestSaveAndSign: () -> Unit,
  onCloseEditor: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showExitConfirm by remember { mutableStateOf(false) }

  val handleSystemBack = {
    if (state.currentPackagePath.isNotEmpty()) {
      onNavigateUpFolder()
    } else if (state.hasUncommittedDexChanges) {
      showExitConfirm = true
    } else {
      onCloseEditor()
    }
  }
  BackHandler(onBack = handleSystemBack)

  Column(modifier = modifier.fillMaxSize()) {
    // Top Header
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
      Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = {
              if (state.hasUncommittedDexChanges) showExitConfirm = true
              else onCloseEditor()
            },
            modifier = Modifier.size(48.dp).testTag("dex_explorer_close_btn"),
          ) {
            Icon(Icons.Default.Close, contentDescription = "Cerrar explorador DEX")
          }

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Clases DEX → Java",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = buildString {
                append(state.targetDisplayName)
                append(" • ")
                append(state.activeDexEntry)
                if (state.patchedClassNames.isNotEmpty()) {
                  append(" • ${state.patchedClassNames.size} modif.")
                }
              },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          Button(
            onClick = onRequestSaveAndSign,
            enabled = !state.isCompiling && !state.isLoadingIndex,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.tertiary,
              contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(38.dp).testTag("explorer_save_and_sign_btn"),
          ) {
            Icon(
              imageVector = if (state.targetApkPath != null) Icons.Default.VerifiedUser else Icons.Default.Save,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = if (state.targetApkPath != null) "Confirmar y Firmar" else "Guardar DEX",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }

        // Multi-DEX switcher + quick return to last opened Java class
        if (state.dexEntries.size > 1 || state.selectedClass != null) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            state.dexEntries.forEach { entry ->
              FilterChip(
                selected = entry == state.activeDexEntry,
                onClick = { if (entry != state.activeDexEntry) onSwitchDexEntry(entry) },
                label = { Text(entry) },
                modifier = Modifier.height(34.dp),
              )
            }

            state.selectedClass?.let { openCls ->
              OutlinedButton(
                onClick = onReturnToOpenClass,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(34.dp).testTag("explorer_resume_class_btn"),
              ) {
                Icon(Icons.Default.Code, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                  text = "Volver a ${openCls.simpleName}.java",
                  style = MaterialTheme.typography.labelMedium,
                  maxLines = 1,
                )
              }
            }
          }
        }
      }
    }

    HorizontalDivider()

    if (state.isLoadingIndex) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator()
          Text(
            text = "Cargando paquetes y clases de ${state.activeDexEntry}...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      DexPackageBrowserContent(
        classes = state.classes,
        currentPackagePath = state.currentPackagePath,
        selectedClass = state.selectedClass,
        patchedClassNames = state.patchedClassNames,
        searchQuery = state.searchQuery,
        activeDexEntry = state.activeDexEntry,
        onSearchChange = onSearchQueryChange,
        onOpenPackageFolder = onOpenPackageFolder,
        onNavigateUpFolder = onNavigateUpFolder,
        onSelectClass = onSelectClass,
        modifier = Modifier.weight(1f),
      )
    }
  }

  if (showExitConfirm) {
    AlertDialog(
      onDismissRequest = { showExitConfirm = false },
      title = { Text("Cambios sin aplicar al APK") },
      text = {
        Text("Has compilado ${state.patchedClassNames.size} clase(s) en DEX pero aún no has guardado ni firmado el resultado final.")
      },
      confirmButton = {
        Button(
          onClick = {
            showExitConfirm = false
            onRequestSaveAndSign()
          }
        ) {
          Text(if (state.targetApkPath != null) "Confirmar y Firmar" else "Guardar DEX")
        }
      },
      dismissButton = {
        Row {
          TextButton(
            onClick = {
              showExitConfirm = false
              onCloseEditor()
            }
          ) {
            Text("Salir sin guardar")
          }
          TextButton(onClick = { showExitConfirm = false }) {
            Text("Cancelar")
          }
        }
      },
    )
  }
}

@Composable
fun DexPackageBrowserContent(
  classes: List<DexClassInfo>,
  currentPackagePath: String,
  selectedClass: DexClassInfo?,
  patchedClassNames: Set<String>,
  searchQuery: String,
  activeDexEntry: String,
  onSearchChange: (String) -> Unit,
  onOpenPackageFolder: (String) -> Unit,
  onNavigateUpFolder: () -> Unit,
  onSelectClass: (DexClassInfo) -> Unit,
  modifier: Modifier = Modifier,
) {
  val trimmedQuery = searchQuery.trim()
  val isSearching = trimmedQuery.isNotEmpty()

  val searchMatches = remember(classes, trimmedQuery) {
    if (trimmedQuery.isEmpty()) emptyList()
    else classes.filter {
      it.fullName.contains(trimmedQuery, ignoreCase = true)
    }
  }

  val listing = remember(classes, currentPackagePath, patchedClassNames) {
    buildDexFolderListing(classes, currentPackagePath, patchedClassNames)
  }

  Column(modifier = modifier.fillMaxSize()) {
    // Search Bar
    OutlinedTextField(
      value = searchQuery,
      onValueChange = onSearchChange,
      placeholder = { Text("Buscar clase o paquete...") },
      leadingIcon = { Icon(Icons.Default.Search, null) },
      trailingIcon = {
        if (searchQuery.isNotEmpty()) {
          IconButton(onClick = { onSearchChange("") }) {
            Icon(Icons.Default.Close, "Limpiar búsqueda")
          }
        }
      },
      singleLine = true,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .testTag("dex_class_search_input"),
    )

    if (!isSearching) {
      // Breadcrumb path bar (like MT Manager path navigation)
      val segments = remember(currentPackagePath) {
        if (currentPackagePath.isEmpty()) emptyList()
        else currentPackagePath.split('.')
      }

      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          BreadcrumbChip(
            label = activeDexEntry,
            isCurrent = currentPackagePath.isEmpty(),
            onClick = { onOpenPackageFolder("") },
          )

          var cumulative = ""
          segments.forEachIndexed { idx, seg ->
            cumulative = if (cumulative.isEmpty()) seg else "$cumulative.$seg"
            val targetPath = cumulative
            Icon(
              imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp),
            )
            BreadcrumbChip(
              label = seg,
              isCurrent = idx == segments.lastIndex,
              onClick = { onOpenPackageFolder(targetPath) },
            )
          }
        }
      }
    } else {
      Text(
        text = "${searchMatches.size} coincidencia(s) en todo $activeDexEntry",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
      )
    }

    HorizontalDivider()

    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("dex_package_browser_list"),
    ) {
      if (isSearching) {
        if (searchMatches.isEmpty()) {
          item {
            EmptyBrowserNotice("No se encontraron clases para \"$trimmedQuery\"")
          }
        } else {
          items(searchMatches, key = { it.fullName }) { cls ->
            DexClassRow(
              cls = cls,
              showFullPackage = true,
              isSelected = cls.fullName == selectedClass?.fullName,
              isPatched = cls.fullName in patchedClassNames,
              onClick = { onSelectClass(cls) },
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
          }
        }
      } else {
        // Parent ".." row
        if (currentPackagePath.isNotEmpty()) {
          item(key = "__parent_up__") {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateUpFolder)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("dex_folder_up_row"),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(36.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Subir carpeta",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "..",
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.Bold,
                  fontFamily = JetBrainsMonoFamily,
                )
                Text(
                  text = "Subir a la carpeta anterior",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
          }
        }

        // Package Folders
        items(listing.folders, key = { "dir:${it.fullPackagePath}" }) { folder ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onOpenPackageFolder(folder.fullPackagePath) }
              .padding(horizontal = 14.dp, vertical = 11.dp)
              .testTag("dex_folder_${folder.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
              modifier = Modifier.size(36.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.Folder,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(22.dp),
                )
              }
            }

            Column(modifier = Modifier.weight(1f)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = folder.name,
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                if (folder.patchedCount > 0) {
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                  ) {
                    Text(
                      text = "${folder.patchedCount} modif.",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onTertiaryContainer,
                      modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                  }
                }
              }
              Text(
                text = "${folder.totalClasses} clase(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            Icon(
              imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        // Classes directly in current package
        items(listing.classes, key = { "cls:${it.fullName}" }) { cls ->
          DexClassRow(
            cls = cls,
            showFullPackage = false,
            isSelected = cls.fullName == selectedClass?.fullName,
            isPatched = cls.fullName in patchedClassNames,
            onClick = { onSelectClass(cls) },
          )
          HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        if (listing.folders.isEmpty() && listing.classes.isEmpty()) {
          item {
            EmptyBrowserNotice("Esta carpeta no contiene clases DEX")
          }
        }
      }
    }
  }
}

@Composable
private fun BreadcrumbChip(
  label: String,
  isCurrent: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    modifier = Modifier.clickable(onClick = onClick),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
      color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun DexClassRow(
  cls: DexClassInfo,
  showFullPackage: Boolean,
  isSelected: Boolean,
  isPatched: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surface
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 11.dp)
      .testTag("dex_class_row_${cls.simpleName}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      shape = RoundedCornerShape(8.dp),
      color = when {
        cls.isInterface -> MaterialTheme.colorScheme.secondaryContainer
        cls.isEnum -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
      },
      modifier = Modifier.size(36.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = when {
            cls.isInterface -> "I"
            cls.isEnum -> "E"
            else -> "C"
          },
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = when {
            cls.isInterface -> MaterialTheme.colorScheme.onSecondaryContainer
            cls.isEnum -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
          },
        )
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = "${cls.simpleName}.java",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          fontFamily = JetBrainsMonoFamily,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (isPatched) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(11.dp),
              )
              Text(
                text = "Modificada",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }
        }
      }

      Text(
        text = buildString {
          if (showFullPackage && cls.packageName.isNotEmpty()) {
            append(cls.packageName)
            append(" • ")
          }
          append("${cls.methodCount} mét. • ${cls.fieldCount} camp.")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Surface(
      shape = RoundedCornerShape(6.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Text(
        text = "Java",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
      )
    }
  }
}

@Composable
private fun EmptyBrowserNotice(message: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

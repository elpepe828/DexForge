package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.KeystoreProfile
import com.example.model.SignatureReport
import com.example.ui.theme.JetBrainsMonoFamily
import java.io.File

@Composable
fun SignerStudioScreen(
  selectedApkPath: String?,
  verification: SignatureReport?,
  keystoreProfiles: List<KeystoreProfile>,
  selectedKeystoreId: String?,
  signV1: Boolean,
  signV2: Boolean,
  signV3: Boolean,
  isSigning: Boolean,
  workspaceApks: List<File>,
  onPickApkFile: (File) -> Unit,
  onImportExternalApk: () -> Unit,
  onSelectKeystore: (String) -> Unit,
  onToggleSchemes: (Boolean, Boolean, Boolean) -> Unit,
  onSignApk: () -> Unit,
  onCreateKeystore: (String, String, String, String, String, String) -> Unit,
  onRequestImportKeystore: () -> Unit,
  onExportSignedApk: (File) -> Unit,
) {
  var showApkPickerDialog by remember { mutableStateOf(false) }
  var showCreateKeystoreDialog by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Target APK Card
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.tertiary)
          Text(stringResource(R.string.signer_step_1), style = MaterialTheme.typography.titleMedium)
        }

        if (selectedApkPath != null) {
          val file = File(selectedApkPath)
          Text(
            text = file.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = file.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            text = stringResource(R.string.no_apk_selected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { showApkPickerDialog = true },
            modifier = Modifier.weight(1f).height(48.dp),
          ) {
            Text(stringResource(R.string.btn_choose_workspace))
          }
          OutlinedButton(
            onClick = onImportExternalApk,
            modifier = Modifier.weight(1f).height(48.dp),
          ) {
            Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.btn_import_apk))
          }
        }

        // Verification Report
        verification?.let { rep ->
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (rep.isVerified) {
              MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            } else {
              MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                  imageVector = if (rep.isVerified) Icons.Default.CheckCircle else Icons.Default.Warning,
                  contentDescription = null,
                  tint = if (rep.isVerified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
                Text(
                  text = stringResource(if (rep.isVerified) R.string.sig_valid_apksig else R.string.sig_invalid_absent),
                  style = MaterialTheme.typography.titleSmall,
                )
              }
              val okLabel = stringResource(R.string.sig_scheme_ok)
              val noLabel = stringResource(R.string.sig_scheme_no)
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("v1: ${if (rep.v1Verified) okLabel else noLabel}", style = MaterialTheme.typography.labelMedium)
                Text("v2: ${if (rep.v2Verified) okLabel else noLabel}", style = MaterialTheme.typography.labelMedium)
                Text("v3: ${if (rep.v3Verified) okLabel else noLabel}", style = MaterialTheme.typography.labelMedium)
              }
              rep.signerSubject?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
              }
              rep.sha256Fingerprint?.let {
                Text("SHA-256: $it", style = MaterialTheme.typography.labelSmall, fontFamily = JetBrainsMonoFamily)
              }
            }
          }
        }
      }
    }

    // Keystore Selection Card
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.secondary)
            Text(stringResource(R.string.signer_step_2), style = MaterialTheme.typography.titleMedium)
          }
          Row {
            TextButton(onClick = onRequestImportKeystore) {
              Text(stringResource(R.string.action_import))
            }
            TextButton(onClick = { showCreateKeystoreDialog = true }) {
              Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text(stringResource(R.string.action_create_short))
            }
          }
        }

        keystoreProfiles.forEach { profile ->
          val isSelected = profile.id == selectedKeystoreId
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelectKeystore(profile.id) },
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = isSelected, onClick = { onSelectKeystore(profile.id) })
              Column(modifier = Modifier.weight(1f)) {
                Text(profile.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                  text = stringResource(R.string.keystore_alias_type, profile.alias, profile.storeType),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = profile.subjectDn,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }

    // Schemes & Sign CTA
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.signer_step_3), style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = signV1,
            onClick = { onToggleSchemes(!signV1, signV2, signV3) },
            label = { Text(stringResource(R.string.scheme_v1)) },
          )
          FilterChip(
            selected = signV2,
            onClick = { onToggleSchemes(signV1, !signV2, signV3) },
            label = { Text(stringResource(R.string.scheme_v2)) },
          )
          FilterChip(
            selected = signV3,
            onClick = { onToggleSchemes(signV1, signV2, !signV3) },
            label = { Text(stringResource(R.string.scheme_v3)) },
          )
        }

        Button(
          onClick = onSignApk,
          enabled = selectedApkPath != null && !isSigning && (signV1 || signV2 || signV3),
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("signer_execute_btn"),
        ) {
          if (isSigning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          } else {
            Icon(Icons.Default.VerifiedUser, null)
          }
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.btn_sign_apk_now))
        }

        if (selectedApkPath != null) {
          OutlinedButton(
            onClick = { onExportSignedApk(File(selectedApkPath)) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
          ) {
            Text(stringResource(R.string.btn_export_current_apk))
          }
        }
      }
    }
  }

  if (showApkPickerDialog) {
    AlertDialog(
      onDismissRequest = { showApkPickerDialog = false },
      title = { Text(stringResource(R.string.dialog_select_apk)) },
      text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (workspaceApks.isEmpty()) {
            Text(stringResource(R.string.no_workspace_apks))
          } else {
            workspaceApks.forEach { file ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    showApkPickerDialog = false
                    onPickApkFile(file)
                  },
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Text(file.name, style = MaterialTheme.typography.titleSmall)
                  Text(file.parentFile?.name.orEmpty(), style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showApkPickerDialog = false }) { Text(stringResource(R.string.action_close)) }
      },
    )
  }

  if (showCreateKeystoreDialog) {
    CreateKeystoreDialog(
      onDismiss = { showCreateKeystoreDialog = false },
      onCreate = { name, alias, sPass, kPass, cn, org ->
        showCreateKeystoreDialog = false
        onCreateKeystore(name, alias, sPass, kPass, cn, org)
      },
    )
  }
}

@Composable
private fun CreateKeystoreDialog(
  onDismiss: () -> Unit,
  onCreate: (String, String, String, String, String, String) -> Unit,
) {
  var name by remember { mutableStateOf("ReleaseKeystore") }
  var alias by remember { mutableStateOf("upload") }
  var storePass by remember { mutableStateOf("123456") }
  var keyPass by remember { mutableStateOf("123456") }
  var commonName by remember { mutableStateOf("Developer") }
  var org by remember { mutableStateOf("DexForge") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.dialog_create_keystore)) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.label_profile_name)) }, singleLine = true)
        OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text(stringResource(R.string.label_alias)) }, singleLine = true)
        OutlinedTextField(value = storePass, onValueChange = { storePass = it }, label = { Text(stringResource(R.string.label_keystore_password)) }, singleLine = true)
        OutlinedTextField(value = keyPass, onValueChange = { keyPass = it }, label = { Text(stringResource(R.string.label_key_password)) }, singleLine = true)
        OutlinedTextField(value = commonName, onValueChange = { commonName = it }, label = { Text(stringResource(R.string.label_common_name)) }, singleLine = true)
        OutlinedTextField(value = org, onValueChange = { org = it }, label = { Text(stringResource(R.string.label_organization)) }, singleLine = true)
      }
    },
    confirmButton = {
      TextButton(onClick = { onCreate(name, alias, storePass, keyPass, commonName, org) }) {
        Text(stringResource(R.string.btn_generate))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

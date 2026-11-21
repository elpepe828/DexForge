package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.ApkMetadataSummary
import com.example.ui.formatSize
import com.example.ui.theme.JetBrainsMonoFamily
import java.io.File

@Composable
fun ApkInspectorDialog(
  summary: ApkMetadataSummary,
  onDismiss: () -> Unit,
  onOpenDexEditor: () -> Unit,
  onExploreEntries: () -> Unit,
  onOpenSigner: () -> Unit,
  onExportApk: (File) -> Unit,
  onShareApk: (File) -> Unit,
) {
  BackHandler(onBack = onDismiss)
  var showManifest by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(
      modifier = Modifier
        .fillMaxWidth(0.94f)
        .heightIn(max = 680.dp),
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(
        modifier = Modifier
          .padding(20.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Android,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.padding(10.dp),
            )
          }
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = summary.apkName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text = summary.packageName ?: stringResource(R.string.unknown_package),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_close_inspector))
          }
        }

        // Metadata pills
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          InfoChip(stringResource(R.string.chip_size, formatSize(summary.fileSizeBytes)))
          summary.versionName?.let { InfoChip("v$it (${summary.versionCode ?: "?"})") }
          InfoChip(stringResource(R.string.chip_dex_and_files, summary.dexFiles.size, summary.entryCount))
        }

        // Signature Status Card
        val sig = summary.signatureReport
        Card(
          colors = CardDefaults.cardColors(
            containerColor = if (sig.isVerified) {
              MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
            } else {
              MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            }
          ),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(
                imageVector = if (sig.isVerified) Icons.Default.VerifiedUser else Icons.Default.Warning,
                contentDescription = null,
                tint = if (sig.isVerified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
              )
              Text(
                text = if (sig.isVerified) {
                  val schemes = buildList {
                    if (sig.v1Verified) add("v1")
                    if (sig.v2Verified) add("v2")
                    if (sig.v3Verified) add("v3")
                  }.joinToString("+")
                  stringResource(R.string.sig_verified_schemes, schemes)
                } else {
                  stringResource(R.string.sig_invalid_or_missing)
                },
                style = MaterialTheme.typography.titleSmall,
              )
            }
            sig.signerSubject?.let {
              Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            sig.sha256Fingerprint?.let {
              Text(
                text = "SHA-256: $it",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        // Primary CTA: DEX -> Java Editor
        Button(
          onClick = onOpenDexEditor,
          enabled = summary.dexFiles.isNotEmpty(),
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("inspector_open_dex_java_btn"),
        ) {
          Icon(Icons.Default.Code, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.cta_editor_dex_java, summary.dexFiles.joinToString(", ")))
        }

        // Secondary Actions
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilledTonalButton(
            onClick = onExploreEntries,
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("inspector_explore_zip_btn"),
          ) {
            Icon(Icons.Default.Archive, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.btn_explore_apk))
          }
          FilledTonalButton(
            onClick = onOpenSigner,
            modifier = Modifier
              .weight(1f)
              .height(48.dp)
              .testTag("inspector_sign_btn"),
          ) {
            Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_sign_apk))
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = { onExportApk(File(summary.apkFilePath)) },
            modifier = Modifier.weight(1f).height(48.dp),
          ) {
            Text(stringResource(R.string.btn_export_dots))
          }
          OutlinedButton(
            onClick = { onShareApk(File(summary.apkFilePath)) },
            modifier = Modifier.weight(1f).height(48.dp),
          ) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_share))
          }
        }

        HorizontalDivider()

        TextButton(
          onClick = { showManifest = !showManifest },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(if (showManifest) R.string.btn_hide_manifest else R.string.btn_view_manifest))
        }

        if (showManifest) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 140.dp, max = 260.dp),
          ) {
            Text(
              text = summary.decodedManifestXml,
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun InfoChip(text: String) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
    )
  }
}

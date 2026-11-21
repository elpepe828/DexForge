package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.engine.ApkArchiveEngine
import com.example.engine.JavaToDexCompiler
import com.example.engine.X509CertGenerator
import com.example.model.FileEntry
import com.example.model.KeystoreProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceRepository(private val context: Context) {

  val workspaceRoot: File = File(context.filesDir, "workspace").apply { mkdirs() }
  val apksDir: File = File(workspaceRoot, "APKs").apply { mkdirs() }
  val projectsDir: File = File(workspaceRoot, "Proyectos").apply { mkdirs() }
  val keystoresDir: File = File(workspaceRoot, "Firmas").apply { mkdirs() }
  val exportsDir: File = File(workspaceRoot, "Exportados").apply { mkdirs() }
  val cacheDir: File = File(context.cacheDir, "dexforge_work").apply { mkdirs() }
  private val profilesFile = File(context.filesDir, "keystore_profiles.json")

  fun ensureInitialized(): List<KeystoreProfile> {
    val defaultKeystoreFile = File(keystoresDir, "dexforge_default.p12")
    if (!defaultKeystoreFile.exists()) {
      X509CertGenerator.createPkcs12Keystore(
        outputFile = defaultKeystoreFile,
        alias = "dexforge",
        storePassword = "android",
        keyPassword = "android",
        commonName = "DexForge Signer",
        organization = "DexForge Studio",
      )
    }

    val profiles = loadKeystoreProfiles().toMutableList()
    if (profiles.none { it.filePath == defaultKeystoreFile.absolutePath }) {
      profiles.add(
        0,
        KeystoreProfile(
          id = "builtin_default",
          displayName = "DexForge Debug Key (RSA 2048)",
          filePath = defaultKeystoreFile.absolutePath,
          storeType = "PKCS12",
          alias = "dexforge",
          storePassword = "android",
          keyPassword = "android",
          subjectDn = "CN=DexForge Signer, O=DexForge Studio, C=US",
          isBuiltIn = true,
        )
      )
      saveKeystoreProfiles(profiles)
    }

    val demoApk = File(apksDir, "DemoSandbox.apk")
    if (!demoApk.exists() && apksDir.listFiles().isNullOrEmpty()) {
      runCatching {
        JavaToDexCompiler.createSampleApk(
          workRoot = cacheDir,
          outputApkFile = demoApk,
          keystoreProfile = profiles.first(),
        )
      }
    }

    return profiles
  }

  fun listLocalDirectory(dir: File): List<FileEntry> {
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    val children = dir.listFiles() ?: return emptyList()
    return children
      .filter { !it.name.startsWith(".tmp_") && !it.name.startsWith(".staged_") }
      .map { file ->
        FileEntry(
          id = file.absolutePath,
          name = file.name,
          path = file.absolutePath,
          isDirectory = file.isDirectory,
          sizeBytes = if (file.isDirectory) 0L else file.length(),
          lastModifiedMillis = file.lastModified(),
        )
      }
      .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
  }

  fun listSafDirectory(treeUri: Uri): List<FileEntry> {
    val doc = DocumentFile.fromTreeUri(context, treeUri)
      ?: DocumentFile.fromSingleUri(context, treeUri)
      ?: return emptyList()
    return doc.listFiles()
      .mapNotNull { child ->
        val name = child.name ?: return@mapNotNull null
        FileEntry(
          id = child.uri.toString(),
          name = name,
          path = child.uri.toString(),
          isDirectory = child.isDirectory,
          sizeBytes = if (child.isDirectory) 0L else child.length(),
          lastModifiedMillis = child.lastModified(),
          isSafEntry = true,
          safUri = child.uri,
        )
      }
      .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
  }

  fun importDocumentUri(uri: Uri, targetDir: File): File {
    val displayName = resolveDisplayName(uri) ?: "imported_${System.currentTimeMillis()}"
    val sanitized = displayName.replace(Regex("""[/\\:*?"<>|]"""), "_")
    var dest = File(targetDir, sanitized)
    if (dest.exists()) {
      val base = dest.nameWithoutExtension
      val ext = dest.extension.let { if (it.isEmpty()) "" else ".$it" }
      dest = File(targetDir, "${base}_${System.currentTimeMillis() % 10000}$ext")
    }
    targetDir.mkdirs()
    context.contentResolver.openInputStream(uri)?.use { input ->
      FileOutputStream(dest).use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado")
    return dest
  }

  fun exportFileToUri(sourceFile: File, destUri: Uri) {
    context.contentResolver.openOutputStream(destUri)?.use { output ->
      sourceFile.inputStream().use { input -> input.copyTo(output) }
    } ?: throw IllegalStateException("No se pudo escribir en la ubicación seleccionada")
  }

  fun createShareIntent(file: File, mimeType: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
      type = mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  fun copyLocalItem(source: File, targetDir: File, move: Boolean): File {
    targetDir.mkdirs()
    var dest = File(targetDir, source.name)
    if (dest.absolutePath == source.absolutePath) {
      val base = source.nameWithoutExtension
      val ext = source.extension.let { if (it.isEmpty()) "" else ".$it" }
      dest = File(targetDir, "${base}_copia$ext")
    }
    if (source.isDirectory) {
      source.copyRecursively(dest, overwrite = true)
      if (move) source.deleteRecursively()
    } else {
      source.copyTo(dest, overwrite = true)
      if (move) source.delete()
    }
    return dest
  }

  fun zipFiles(sources: List<File>, destZip: File) {
    destZip.parentFile?.mkdirs()
    ZipOutputStream(FileOutputStream(destZip)).use { zos ->
      for (src in sources) {
        if (src.isDirectory) {
          val baseLen = src.parentFile?.absolutePath?.length?.plus(1) ?: 0
          src.walkTopDown().forEach { f ->
            val rel = f.absolutePath.drop(baseLen).replace('\\', '/')
            if (f.isDirectory) {
              zos.putNextEntry(ZipEntry("$rel/"))
              zos.closeEntry()
            } else {
              zos.putNextEntry(ZipEntry(rel))
              f.inputStream().use { it.copyTo(zos) }
              zos.closeEntry()
            }
          }
        } else {
          zos.putNextEntry(ZipEntry(src.name))
          src.inputStream().use { it.copyTo(zos) }
          zos.closeEntry()
        }
      }
    }
  }

  fun loadKeystoreProfiles(): List<KeystoreProfile> {
    if (!profilesFile.exists()) return emptyList()
    return runCatching {
      val arr = JSONArray(profilesFile.readText())
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          val filePath = obj.getString("filePath")
          if (File(filePath).exists()) {
            add(
              KeystoreProfile(
                id = obj.getString("id"),
                displayName = obj.getString("displayName"),
                filePath = filePath,
                storeType = obj.optString("storeType", "PKCS12"),
                alias = obj.getString("alias"),
                storePassword = obj.getString("storePassword"),
                keyPassword = obj.getString("keyPassword"),
                subjectDn = obj.optString("subjectDn", ""),
                isBuiltIn = obj.optBoolean("isBuiltIn", false),
              )
            )
          }
        }
      }
    }.getOrDefault(emptyList())
  }

  fun saveKeystoreProfiles(profiles: List<KeystoreProfile>) {
    val arr = JSONArray()
    for (p in profiles) {
      arr.put(
        JSONObject().apply {
          put("id", p.id)
          put("displayName", p.displayName)
          put("filePath", p.filePath)
          put("storeType", p.storeType)
          put("alias", p.alias)
          put("storePassword", p.storePassword)
          put("keyPassword", p.keyPassword)
          put("subjectDn", p.subjectDn)
          put("isBuiltIn", p.isBuiltIn)
        }
      )
    }
    profilesFile.writeText(arr.toString(2))
  }

  fun createNewKeystoreProfile(
    displayName: String,
    alias: String,
    storePassword: String,
    keyPassword: String,
    commonName: String,
    organization: String,
  ): KeystoreProfile {
    val safeName = displayName.replace(Regex("""[^a-zA-Z0-9_-]"""), "_").ifBlank { "keystore" }
    val outFile = File(keystoresDir, "${safeName}_${System.currentTimeMillis() % 10000}.p12")
    val material = X509CertGenerator.createPkcs12Keystore(
      outputFile = outFile,
      alias = alias,
      storePassword = storePassword,
      keyPassword = keyPassword,
      commonName = commonName,
      organization = organization,
    )
    val profile = KeystoreProfile(
      id = "ks_${System.currentTimeMillis()}",
      displayName = displayName.ifBlank { outFile.nameWithoutExtension },
      filePath = outFile.absolutePath,
      storeType = "PKCS12",
      alias = material.alias,
      storePassword = storePassword,
      keyPassword = keyPassword,
      subjectDn = material.certificate.subjectX500Principal.name,
      isBuiltIn = false,
    )
    val updated = loadKeystoreProfiles() + profile
    saveKeystoreProfiles(updated)
    return profile
  }

  fun registerImportedKeystore(
    keystoreFile: File,
    displayName: String,
    storeType: String,
    alias: String,
    storePassword: String,
    keyPassword: String,
  ): KeystoreProfile {
    val material = X509CertGenerator.loadKeystore(
      keystoreFile = keystoreFile,
      storeType = storeType,
      alias = alias.ifBlank { null },
      storePassword = storePassword,
      keyPassword = keyPassword,
    )
    val profile = KeystoreProfile(
      id = "ks_${System.currentTimeMillis()}",
      displayName = displayName.ifBlank { keystoreFile.nameWithoutExtension },
      filePath = keystoreFile.absolutePath,
      storeType = storeType.ifBlank { "PKCS12" },
      alias = material.alias,
      storePassword = storePassword,
      keyPassword = keyPassword,
      subjectDn = material.certificate.subjectX500Principal.name,
      isBuiltIn = false,
    )
    val updated = loadKeystoreProfiles() + profile
    saveKeystoreProfiles(updated)
    return profile
  }

  private fun resolveDisplayName(uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) return cursor.getString(idx)
        }
      }
    return uri.lastPathSegment?.substringAfterLast('/')
  }
}

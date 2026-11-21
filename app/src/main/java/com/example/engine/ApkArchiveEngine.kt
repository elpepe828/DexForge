package com.example.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.example.model.ApkMetadataSummary
import com.example.model.FileEntry
import com.example.model.KeystoreProfile
import com.example.model.SignatureReport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkArchiveEngine {

  fun listArchiveDirectory(archiveFile: File, internalDir: String): List<FileEntry> {
    val normalizedDir = internalDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    val directories = linkedMapOf<String, FileEntry>()
    val files = mutableListOf<FileEntry>()

    ZipFile(archiveFile).use { zip ->
      val enumEntries = zip.entries()
      while (enumEntries.hasMoreElements()) {
        val entry = enumEntries.nextElement()
        val name = entry.name.trimStart('/')
        if (!name.startsWith(normalizedDir)) continue
        val remainder = name.removePrefix(normalizedDir)
        if (remainder.isEmpty()) continue

        val slashIndex = remainder.indexOf('/')
        if (slashIndex >= 0) {
          val dirName = remainder.substring(0, slashIndex)
          val dirPath = "$normalizedDir$dirName"
          if (!directories.containsKey(dirPath)) {
            directories[dirPath] = FileEntry(
              id = "zip:$dirPath",
              name = dirName,
              path = dirPath,
              isDirectory = true,
              sizeBytes = 0L,
              lastModifiedMillis = entry.time,
              isArchiveEntry = true,
            )
          }
        } else if (!entry.isDirectory) {
          files += FileEntry(
            id = "zip:$name",
            name = remainder,
            path = name,
            isDirectory = false,
            sizeBytes = entry.size.coerceAtLeast(0L),
            lastModifiedMillis = entry.time,
            isArchiveEntry = true,
            compressedSize = entry.compressedSize.coerceAtLeast(0L),
          )
        }
      }
    }

    return directories.values.sortedBy { it.name.lowercase() } +
      files.sortedBy { it.name.lowercase() }
  }

  fun readArchiveEntryBytes(archiveFile: File, entryPath: String): ByteArray {
    ZipFile(archiveFile).use { zip ->
      val entry = zip.getEntry(entryPath)
        ?: throw IllegalArgumentException("No se encontró '$entryPath' en ${archiveFile.name}")
      zip.getInputStream(entry).use { input ->
        return input.readBytes()
      }
    }
  }

  fun extractArchiveEntry(archiveFile: File, entryPath: String, destinationFile: File) {
    destinationFile.parentFile?.mkdirs()
    val bytes = readArchiveEntryBytes(archiveFile, entryPath)
    destinationFile.writeBytes(bytes)
  }

  fun extractArchiveDirectory(archiveFile: File, internalDir: String, destRoot: File): Int {
    val prefix = internalDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    var count = 0
    ZipFile(archiveFile).use { zip ->
      val enumEntries = zip.entries()
      while (enumEntries.hasMoreElements()) {
        val entry = enumEntries.nextElement()
        val entryName = entry.name.trimStart('/')
        if (!entryName.startsWith(prefix) || entry.isDirectory) continue
        val relPath = entryName.removePrefix(prefix).ifEmpty { entryName }
        val target = File(destRoot, relPath)
        // Guard against Zip Slip
        if (!target.canonicalPath.startsWith(destRoot.canonicalPath)) continue
        target.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
          FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        count++
      }
    }
    return count
  }

  /**
   * Replaces or adds entries inside an APK/ZIP, stripping stale META-INF signature files
   * when modifying an APK so it can be cleanly re-signed with apksig.
   */
  fun replaceOrAddEntries(
    sourceArchive: File,
    outputArchive: File,
    replacements: Map<String, ByteArray>,
    deletions: Set<String> = emptySet(),
    stripSignatures: Boolean = true,
  ) {
    val tempOut = File(outputArchive.parentFile, ".tmp_${outputArchive.name}_${System.nanoTime()}")
    val written = hashSetOf<String>()

    ZipFile(sourceArchive).use { zipIn ->
      ZipOutputStream(FileOutputStream(tempOut)).use { zos ->
        val entries = zipIn.entries()
        while (entries.hasMoreElements()) {
          val entry = entries.nextElement()
          val name = entry.name
          if (name in deletions) continue
          if (stripSignatures && isSignatureArtifact(name)) continue

          val data = replacements[name] ?: zipIn.getInputStream(entry).use { it.readBytes() }
          written += name
          writeZipEntry(zos, name, data, shouldStoreUncompressed(name))
        }

        for ((name, data) in replacements) {
          if (name !in written && name !in deletions) {
            writeZipEntry(zos, name, data, shouldStoreUncompressed(name))
          }
        }
      }
    }

    if (outputArchive.exists()) outputArchive.delete()
    if (!tempOut.renameTo(outputArchive)) {
      tempOut.copyTo(outputArchive, overwrite = true)
      tempOut.delete()
    }
  }

  private fun writeZipEntry(
    zos: ZipOutputStream,
    name: String,
    data: ByteArray,
    stored: Boolean,
  ) {
    val newEntry = ZipEntry(name)
    if (stored) {
      newEntry.method = ZipEntry.STORED
      newEntry.size = data.size.toLong()
      newEntry.compressedSize = data.size.toLong()
      val crc = CRC32().apply { update(data) }.value
      newEntry.crc = crc
    } else {
      newEntry.method = ZipEntry.DEFLATED
    }
    zos.putNextEntry(newEntry)
    zos.write(data)
    zos.closeEntry()
  }

  private fun shouldStoreUncompressed(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "resources.arsc" || lower.endsWith(".so")
  }

  private fun isSignatureArtifact(name: String): Boolean {
    val upper = name.uppercase()
    if (!upper.startsWith("META-INF/")) return false
    val fileName = upper.removePrefix("META-INF/")
    if ('/' in fileName) return false
    return fileName == "MANIFEST.MF" ||
      fileName.endsWith(".SF") ||
      fileName.endsWith(".RSA") ||
      fileName.endsWith(".DSA") ||
      fileName.endsWith(".EC")
  }

  fun signApk(
    inputApk: File,
    outputApk: File,
    keystoreProfile: KeystoreProfile,
    enableV1: Boolean,
    enableV2: Boolean,
    enableV3: Boolean,
  ) {
    val keyMaterial = X509CertGenerator.loadKeystore(
      keystoreFile = File(keystoreProfile.filePath),
      storeType = keystoreProfile.storeType,
      alias = keystoreProfile.alias,
      storePassword = keystoreProfile.storePassword,
      keyPassword = keystoreProfile.keyPassword,
    )

    // Strip any prior JAR signatures into a staging APK so ApkSigner starts cleanly
    val stagedUnsigned = File(outputApk.parentFile, ".staged_${System.nanoTime()}.apk")
    val targetOut = if (inputApk.absolutePath == outputApk.absolutePath) {
      File(outputApk.parentFile, ".signed_${System.nanoTime()}.apk")
    } else {
      outputApk
    }

    try {
      replaceOrAddEntries(
        sourceArchive = inputApk,
        outputArchive = stagedUnsigned,
        replacements = emptyMap(),
        stripSignatures = true,
      )

      val signerConfig = ApkSigner.SignerConfig.Builder(
        keyMaterial.alias,
        keyMaterial.privateKey,
        listOf(keyMaterial.certificate),
      ).build()

      val builder = ApkSigner.Builder(listOf(signerConfig))
        .setInputApk(stagedUnsigned)
        .setOutputApk(targetOut)
        .setV1SigningEnabled(enableV1)
        .setV2SigningEnabled(enableV2)
        .setV3SigningEnabled(enableV3)
        .setCreatedBy("DexForge")

      builder.build().sign()

      if (targetOut != outputApk) {
        if (outputApk.exists()) outputApk.delete()
        if (!targetOut.renameTo(outputApk)) {
          targetOut.copyTo(outputApk, overwrite = true)
          targetOut.delete()
        }
      }
    } finally {
      stagedUnsigned.delete()
      if (targetOut != outputApk && targetOut.exists()) targetOut.delete()
    }
  }

  fun verifyApkSignature(apkFile: File): SignatureReport {
    return try {
      val result = ApkVerifier.Builder(apkFile).build().verify()
      val cert = result.signerCertificates.firstOrNull()
      val encoded = cert?.encoded
      SignatureReport(
        isVerified = result.isVerified,
        v1Verified = result.isVerifiedUsingV1Scheme,
        v2Verified = result.isVerifiedUsingV2Scheme,
        v3Verified = result.isVerifiedUsingV3Scheme,
        signerSubject = cert?.subjectX500Principal?.name,
        sha256Fingerprint = encoded?.let { digestHex(it, "SHA-256") },
        sha1Fingerprint = encoded?.let { digestHex(it, "SHA-1") },
        errors = result.errors.map { it.toString() },
        warnings = result.warnings.map { it.toString() },
      )
    } catch (e: Exception) {
      SignatureReport(
        isVerified = false,
        v1Verified = false,
        v2Verified = false,
        v3Verified = false,
        signerSubject = null,
        sha256Fingerprint = null,
        sha1Fingerprint = null,
        errors = listOf(e.message ?: "Error al verificar APK"),
        warnings = emptyList(),
      )
    }
  }

  fun inspectApk(apkFile: File): ApkMetadataSummary {
    val dexFiles = mutableListOf<String>()
    var entryCount = 0
    var manifestBytes: ByteArray? = null

    ZipFile(apkFile).use { zip ->
      val enumEntries = zip.entries()
      while (enumEntries.hasMoreElements()) {
        val entry = enumEntries.nextElement()
        if (!entry.isDirectory) entryCount++
        if (entry.name.matches(Regex("""^classes\d*\.dex$"""))) {
          dexFiles += entry.name
        } else if (entry.name == "AndroidManifest.xml") {
          manifestBytes = zip.getInputStream(entry).use { it.readBytes() }
        }
      }
    }

    dexFiles.sortWith(compareBy<String> {
      val num = it.removePrefix("classes").removeSuffix(".dex")
      if (num.isEmpty()) 1 else num.toIntOrNull() ?: 999
    })

    val decodedManifest = manifestBytes?.let { AxmlDecoder.decode(it) }
      ?: "<!-- AndroidManifest.xml no encontrado -->"

    val pkg = AxmlDecoder.extractManifestField(decodedManifest, "package")
    val vName = AxmlDecoder.extractManifestField(decodedManifest, "versionName")
    val vCode = AxmlDecoder.extractManifestField(decodedManifest, "versionCode")
    val signature = verifyApkSignature(apkFile)

    return ApkMetadataSummary(
      apkFilePath = apkFile.absolutePath,
      apkName = apkFile.name,
      fileSizeBytes = apkFile.length(),
      packageName = pkg,
      versionName = vName,
      versionCode = vCode,
      dexFiles = dexFiles,
      entryCount = entryCount,
      decodedManifestXml = decodedManifest,
      signatureReport = signature,
    )
  }

  private fun digestHex(bytes: ByteArray, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm).digest(bytes)
    return digest.joinToString(":") { "%02X".format(it) }
  }
}

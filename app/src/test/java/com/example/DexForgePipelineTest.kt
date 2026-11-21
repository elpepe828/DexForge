package com.example

import com.example.engine.ApkArchiveEngine
import com.example.engine.JavaDecompilerEngine
import com.example.engine.JavaToDexCompiler
import com.example.engine.X509CertGenerator
import com.example.model.KeystoreProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DexForgePipelineTest {

  @Test
  fun fullEndToEnd_sampleApk_decompileToJava_edit_recompileToDex_and_sign() {
    val root = createTempDirectory("dexforge_test").toFile()
    try {
      val ksFile = File(root, "test.p12")
      val mat = X509CertGenerator.createPkcs12Keystore(
        outputFile = ksFile,
        alias = "dexforge",
        storePassword = "android",
        keyPassword = "android",
        commonName = "DexForge Test",
        organization = "DexForge",
      )
      val profile = KeystoreProfile(
        id = "test",
        displayName = "Test",
        filePath = ksFile.absolutePath,
        storeType = "PKCS12",
        alias = mat.alias,
        storePassword = "android",
        keyPassword = "android",
        subjectDn = mat.certificate.subjectX500Principal.name,
      )

      // 1. Create sample APK
      val sampleApk = File(root, "sample.apk")
      JavaToDexCompiler.createSampleApk(root, sampleApk, profile)
      assertTrue(sampleApk.exists())

      // 2. Inspect APK + decoded AXML + verify signature
      val summary = ApkArchiveEngine.inspectApk(sampleApk)
      assertEquals("com.dexforge.demo", summary.packageName)
      assertTrue(summary.dexFiles.contains("classes.dex"))
      assertTrue(summary.signatureReport.isVerified)
      assertTrue(summary.signatureReport.v2Verified)
      assertTrue(summary.decodedManifestXml.contains("DemoSandbox"))

      // 3. Extract classes.dex and decompile to Java via Jadx
      val extractedDex = File(root, "classes.dex")
      ApkArchiveEngine.extractArchiveEntry(sampleApk, "classes.dex", extractedDex)
      val javaSource = JavaDecompilerEngine.openDexSession(root, extractedDex, "classes.dex").use { session ->
        val classes = session.listClasses()
        assertTrue(classes.any { it.fullName == "com.dexforge.demo.LicenseCalculator" })
        session.decompileClassToJava("com.dexforge.demo.LicenseCalculator")
      }
      assertTrue(javaSource.contains("isFeatureUnlocked"))

      // 4. Edit Java code and recompile -> DEX patch -> merge into classes.dex
      val editedJava = javaSource.replace("tierLevel >= 2", "tierLevel >= 1")
      val compileResult = JavaToDexCompiler.compileJavaAndPatchDex(
        workRoot = root,
        targetDexFile = extractedDex,
        siblingDexFiles = emptyList(),
        fullClassName = "com.dexforge.demo.LicenseCalculator",
        javaSource = editedJava,
      )
      assertTrue("Compile failed: ${compileResult.logs} / ${compileResult.diagnostics}", compileResult.success)

      // 5. Repackage APK and re-sign with apksig
      val patchedUnsigned = File(root, "patched_unsigned.apk")
      ApkArchiveEngine.replaceOrAddEntries(
        sourceArchive = sampleApk,
        outputArchive = patchedUnsigned,
        replacements = mapOf("classes.dex" to extractedDex.readBytes()),
        stripSignatures = true,
      )
      val patchedSigned = File(root, "patched_signed.apk")
      ApkArchiveEngine.signApk(
        inputApk = patchedUnsigned,
        outputApk = patchedSigned,
        keystoreProfile = profile,
        enableV1 = true,
        enableV2 = true,
        enableV3 = true,
      )

      val verifyAfter = ApkArchiveEngine.verifyApkSignature(patchedSigned)
      assertTrue(verifyAfter.isVerified)
      assertTrue(verifyAfter.v1Verified)
      assertTrue(verifyAfter.v2Verified)
      assertTrue(verifyAfter.v3Verified)

      // 6. Re-decompile patched APK to confirm the Java edit persisted in DEX
      val recheckDex = File(root, "recheck.dex")
      ApkArchiveEngine.extractArchiveEntry(patchedSigned, "classes.dex", recheckDex)
      val recompiledJava = JavaDecompilerEngine.openDexSession(root, recheckDex, "classes.dex").use { session ->
        session.decompileClassToJava("com.dexforge.demo.LicenseCalculator")
      }
      assertTrue(
        "Unexpected recompiled Java:\n$recompiledJava",
        recompiledJava.contains(">= 1") || recompiledJava.contains("> 0"),
      )

      // 7. Compile a second edit with an anonymous inner class (tests ECJ Core + dalvik-dx multi-class + DexPool)
      val withInnerClass = editedJava.replace(
        "return tierLevel >= 1;",
        """
        Runnable r = new Runnable() {
            @Override
            public void run() {
                System.out.println("checked");
            }
        };
        r.run();
        return tierLevel >= 1;
        """.trimIndent(),
      )
      val secondResult = JavaToDexCompiler.compileJavaAndPatchDex(
        workRoot = root,
        targetDexFile = extractedDex,
        siblingDexFiles = emptyList(),
        fullClassName = "com.dexforge.demo.LicenseCalculator",
        javaSource = withInnerClass,
      )
      assertTrue("Second compile failed: ${secondResult.logs} / ${secondResult.diagnostics}", secondResult.success)
      assertTrue(secondResult.compiledClassesCount >= 2)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun checkCoreToolchainHasNoMissingAndroidClasses() {
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
    val androidJar = sdkRoot?.let { File(it, "platforms/android-36/android.jar") } ?: return
    if (!androidJar.exists()) return

    val androidClasses = java.util.zip.ZipFile(androidJar).use { zip ->
      zip.entries().asSequence().map { it.name }.toSet()
    }
    val jars = listOf(
      org.eclipse.jdt.internal.compiler.Compiler::class.java,
      com.android.dx.command.dexer.Main::class.java,
      com.android.tools.smali.dexlib2.DexFileFactory::class.java,
      com.android.apksig.ApkSigner::class.java,
      org.objectweb.asm.ClassWriter::class.java,
    ).map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct()

    val missing = mutableSetOf<String>()
    for (jar in jars) {
      java.util.zip.ZipFile(jar).use { zip ->
        for (entry in zip.entries()) {
          if (!entry.name.endsWith(".class")) continue
          if (entry.name.contains("/apt/") || entry.name.contains("/tool/") || entry.name.contains("/batch/")) continue
          val bytes = zip.getInputStream(entry).readBytes()
          val cr = org.objectweb.asm.ClassReader(bytes)
          cr.accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            private fun checkInternal(internal: String?) {
              if (internal == null) return
              val clean = internal.trimStart('[')
              if (clean.startsWith("L") && clean.endsWith(";")) {
                checkInternal(clean.substring(1, clean.length - 1))
                return
              }
              if ((clean.startsWith("java/") || clean.startsWith("javax/") || clean.startsWith("sun/")) &&
                "$clean.class" !in androidClasses
              ) {
                missing += "${jar.name}:${entry.name} -> $clean"
              }
            }
            override fun visit(v: Int, a: Int, n: String?, s: String?, sup: String?, ints: Array<out String>?) {
              checkInternal(sup)
              ints?.forEach { checkInternal(it) }
            }
            override fun visitMethod(a: Int, n: String?, d: String?, s: String?, ex: Array<out String>?): org.objectweb.asm.MethodVisitor {
              return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitTypeInsn(op: Int, type: String?) = checkInternal(type)
                override fun visitMethodInsn(op: Int, owner: String?, name: String?, desc: String?, itf: Boolean) = checkInternal(owner)
                override fun visitFieldInsn(op: Int, owner: String?, name: String?, desc: String?) = checkInternal(owner)
              }
            }
          }, 0)
        }
      }
    }
    assertTrue("Missing classes in Android SDK: $missing", missing.isEmpty())
  }
}

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
        val calcClass = classes.firstOrNull { it.fullName == "com.dexforge.demo.LicenseCalculator" }
        assertTrue(calcClass != null)
        assertTrue(
          "Expected indexed methodNames to contain isFeatureUnlocked, got ${calcClass?.methodNames}",
          calcClass?.methodNames?.contains("isFeatureUnlocked") == true,
        )
        assertTrue(calcClass?.methodNames?.none { it == "<init>" || it == "<clinit>" } == true)
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

  @Test
  fun packageFolderTree_navigatesFoldersAndDirectClasses() {
    val classes = listOf(
      com.example.model.DexClassInfo("com.example.app.MainActivity", "com.example.app", "MainActivity", "classes.dex", 4, 2, false, false),
      com.example.model.DexClassInfo("com.example.app.BuildConfig", "com.example.app", "BuildConfig", "classes.dex", 1, 3, false, false),
      com.example.model.DexClassInfo("com.example.ui.Theme", "com.example.ui", "Theme", "classes.dex", 2, 1, false, false),
      com.example.model.DexClassInfo("RootHelper", "", "RootHelper", "classes.dex", 1, 0, false, false),
    )
    val patched = setOf("com.example.app.MainActivity")

    val root = com.example.ui.components.buildDexFolderListing(classes, "", patched)
    assertEquals(listOf("com"), root.folders.map { it.name })
    assertEquals(3, root.folders.first().totalClasses)
    assertEquals(1, root.folders.first().patchedCount)
    assertEquals(listOf("RootHelper"), root.classes.map { it.simpleName })

    val comLevel = com.example.ui.components.buildDexFolderListing(classes, "com", patched)
    assertEquals(listOf("example"), comLevel.folders.map { it.name })
    assertTrue(comLevel.classes.isEmpty())

    val exampleLevel = com.example.ui.components.buildDexFolderListing(classes, "com.example", patched)
    assertEquals(listOf("app", "ui"), exampleLevel.folders.map { it.name })
    assertEquals(2, exampleLevel.folders[0].totalClasses)
    assertEquals(1, exampleLevel.folders[0].patchedCount)

    val appLevel = com.example.ui.components.buildDexFolderListing(classes, "com.example.app", patched)
    assertTrue(appLevel.folders.isEmpty())
    assertEquals(listOf("BuildConfig", "MainActivity"), appLevel.classes.map { it.simpleName })
  }

  @Test
  fun searchDexClasses_supportsAllClassesMethodsAndFuzzySimilarity() {
    val classes = listOf(
      com.example.model.DexClassInfo(
        fullName = "com.app.BillingManager",
        packageName = "com.app",
        simpleName = "BillingManager",
        dexEntryName = "classes.dex",
        methodCount = 3,
        fieldCount = 1,
        isInterface = false,
        isEnum = false,
        methodNames = listOf("checkLicense", "checkPremiumStatus", "refreshPurchase"),
      ),
      com.example.model.DexClassInfo(
        fullName = "com.app.AuthService",
        packageName = "com.app",
        simpleName = "AuthService",
        dexEntryName = "classes.dex",
        methodCount = 2,
        fieldCount = 0,
        isInterface = false,
        isEnum = false,
        methodNames = listOf("loginUser", "validateLicenseToken"),
      ),
      com.example.model.DexClassInfo(
        fullName = "com.app.LicenseCheckActivity",
        packageName = "com.app",
        simpleName = "LicenseCheckActivity",
        dexEntryName = "classes.dex",
        methodCount = 1,
        fieldCount = 0,
        isInterface = false,
        isEnum = false,
        methodNames = listOf("onCreate"),
      ),
    )

    // 1. ALL scope finds both class-name match and method-name matches
    val allResults = com.example.ui.components.searchDexClasses(
      classes,
      "License",
      com.example.model.DexSearchScope.ALL,
    )
    assertEquals(
      setOf("BillingManager", "AuthService", "LicenseCheckActivity"),
      allResults.map { it.cls.simpleName }.toSet(),
    )
    val billingAll = allResults.first { it.cls.simpleName == "BillingManager" }
    assertEquals(false, billingAll.matchedClass)
    assertEquals(listOf("checkLicense"), billingAll.matchedMethods)

    // 2. CLASSES scope excludes method-only hits
    val classOnly = com.example.ui.components.searchDexClasses(
      classes,
      "License",
      com.example.model.DexSearchScope.CLASSES,
    )
    assertEquals(listOf("LicenseCheckActivity"), classOnly.map { it.cls.simpleName })
    assertTrue(classOnly.first().matchedClass)
    assertTrue(classOnly.first().matchedMethods.isEmpty())

    // 3. METHODS scope excludes class-only hits and ranks exact match first
    val methodOnly = com.example.ui.components.searchDexClasses(
      classes,
      "checkLicense",
      com.example.model.DexSearchScope.METHODS,
    )
    assertEquals(listOf("BillingManager"), methodOnly.map { it.cls.simpleName })
    assertEquals(false, methodOnly.first().matchedClass)
    assertEquals(listOf("checkLicense"), methodOnly.first().matchedMethods)

    // 4. Fuzzy / similar method name search (e.g. slight typo "chckLicens" -> "checkLicense")
    val fuzzyMethod = com.example.ui.components.searchDexClasses(
      classes,
      "chckLicens",
      com.example.model.DexSearchScope.METHODS,
    )
    assertEquals(listOf("BillingManager"), fuzzyMethod.map { it.cls.simpleName })
    assertEquals(listOf("checkLicense"), fuzzyMethod.first().matchedMethods)

    // 5. Fuzzy / similar class name search (e.g. "BilingManagr" -> "BillingManager")
    val fuzzyClass = com.example.ui.components.searchDexClasses(
      classes,
      "BilingManagr",
      com.example.model.DexSearchScope.CLASSES,
    )
    assertEquals(listOf("BillingManager"), fuzzyClass.map { it.cls.simpleName })
  }

  @Test
  fun javaSymbolRenamer_parsesStructuredJsonAndSafelyRenamesObfuscatedCode() {
    val obfuscatedJava = """
      package p000a.b;

      import java.util.List;

      // Keep comment a f12a m3b untouched
      public class C0012a {
          private String f12a = "f12a";

          public boolean m3b(String a, List<String> b) {
              String str = this.f12a + "a";
              if (a == null || b.isEmpty()) {
                  return false;
              }
              return a.equals(str) && other.a(b);
          }

          public boolean onCreate() {
              return m3b("ok", java.util.Collections.emptyList());
          }
      }
    """.trimIndent()

    val aiJson = """
      {
        "summary": "Validates an input token against a stored key.",
        "renames": [
          {"originalName": "C0012a", "suggestedName": "TokenValidator", "kind": "CLASS", "reason": "Class role"},
          {"originalName": "f12a", "suggestedName": "expectedToken", "kind": "FIELD", "reason": "Compared against input"},
          {"originalName": "m3b", "suggestedName": "verifyToken", "kind": "METHOD", "reason": "Returns boolean check"},
          {"originalName": "a", "suggestedName": "inputToken", "kind": "PARAM_OR_VAR", "reason": "Checked for null and equality"},
          {"originalName": "b", "suggestedName": "allowedList", "kind": "PARAM_OR_VAR", "reason": "Checked with isEmpty()"},
          {"originalName": "str", "suggestedName": "expectedToken", "kind": "PARAM_OR_VAR", "reason": "Collision test"},
          {"originalName": "onCreate", "suggestedName": "initScreen", "kind": "METHOD", "reason": "Protected override"},
          {"originalName": "nonExistent", "suggestedName": "ignored", "kind": "FIELD", "reason": "Not in code"}
        ]
      }
    """.trimIndent()

    val plan = com.example.engine.JavaSymbolRenamer.parseDeobfuscationJson(
      rawJson = aiJson,
      javaSource = obfuscatedJava,
      topLevelClassSimpleName = "C0012a",
    )

    assertEquals("Validates an input token against a stored key.", plan.summary)
    // Protected override 'onCreate' and missing 'nonExistent' must be filtered out.
    assertTrue(plan.suggestions.none { it.originalName == "onCreate" || it.originalName == "nonExistent" })

    // Top-level class rename is unchecked by default so DEX references stay safe.
    val classSug = plan.suggestions.first { it.originalName == "C0012a" }
    assertEquals(false, classSug.selected)

    // Collision on 'expectedToken' should auto-disambiguate for 'str'.
    val strSug = plan.suggestions.first { it.originalName == "str" }
    assertEquals("expectedToken2", strSug.suggestedName)

    val renamed = com.example.engine.JavaSymbolRenamer.applyRenames(
      javaSource = obfuscatedJava,
      suggestions = plan.suggestions,
      enclosingClassSimpleName = "C0012a",
    )

    // Package, imports, comments, strings, top-level class name, and external receiver `other.a(...)` stay intact
    assertTrue(renamed.contains("package p000a.b;"))
    assertTrue(renamed.contains("// Keep comment a f12a m3b untouched"))
    assertTrue(renamed.contains("public class C0012a"))
    assertTrue(renamed.contains("= \"f12a\";"))
    assertTrue(renamed.contains("+ \"a\";"))
    assertTrue(renamed.contains("other.a(allowedList)"))
    // Obfuscated symbols are renamed safely everywhere they belong
    assertTrue(renamed.contains("private String expectedToken = \"f12a\";"))
    assertTrue(renamed.contains("public boolean verifyToken(String inputToken, List<String> allowedList)"))
    assertTrue(renamed.contains("String expectedToken2 = this.expectedToken + \"a\";"))
    assertTrue(renamed.contains("return verifyToken(\"ok\""))
  }
}

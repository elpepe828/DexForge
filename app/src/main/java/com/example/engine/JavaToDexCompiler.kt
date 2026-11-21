package com.example.engine

import com.android.dx.command.dexer.Main as DxMain
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.example.model.CompilePatchResult
import com.example.model.DiagnosticSeverity
import com.example.model.EditorDiagnostic
import com.example.model.KeystoreProfile
import org.eclipse.jdt.internal.compiler.CompilationResult
import org.eclipse.jdt.internal.compiler.Compiler
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies
import org.eclipse.jdt.internal.compiler.ICompilerRequestor
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes as AsmOpcodes
import org.objectweb.asm.Type as AsmType
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object JavaToDexCompiler {

  private val CORE_RUNTIME_CLASSES = listOf(
    "java.lang.Object",
    "java.lang.String",
    "java.lang.CharSequence",
    "java.lang.Comparable",
    "java.lang.Cloneable",
    "java.lang.Runnable",
    "java.lang.Thread",
    "java.lang.Throwable",
    "java.lang.Exception",
    "java.lang.RuntimeException",
    "java.lang.Error",
    "java.lang.Class",
    "java.lang.Enum",
    "java.lang.Void",
    "java.lang.Boolean",
    "java.lang.Byte",
    "java.lang.Short",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.Character",
    "java.lang.Number",
    "java.lang.Math",
    "java.lang.System",
    "java.lang.StringBuilder",
    "java.lang.StringBuffer",
    "java.lang.Iterable",
    "java.lang.Override",
    "java.lang.Deprecated",
    "java.lang.SuppressWarnings",
    "java.lang.annotation.Annotation",
    "java.io.Serializable",
    "java.io.PrintStream",
    "java.io.File",
    "java.io.InputStream",
    "java.io.OutputStream",
    "java.io.IOException",
    "java.util.List",
    "java.util.ArrayList",
    "java.util.Map",
    "java.util.HashMap",
    "java.util.Set",
    "java.util.HashSet",
    "java.util.Collection",
    "java.util.Collections",
    "java.util.Arrays",
    "java.util.Iterator",
    "java.util.Objects",
    "android.app.Activity",
    "android.app.Application",
    "android.app.Service",
    "android.content.Context",
    "android.content.Intent",
    "android.content.SharedPreferences",
    "android.os.Bundle",
    "android.os.Handler",
    "android.os.Looper",
    "android.util.Log",
    "android.view.View",
    "android.view.ViewGroup",
    "android.widget.TextView",
    "android.widget.Button",
    "android.widget.Toast",
  )

  fun compileJavaAndPatchDex(
    workRoot: File,
    targetDexFile: File,
    siblingDexFiles: List<File>,
    fullClassName: String,
    javaSource: String,
  ): CompilePatchResult {
    val logs = mutableListOf<String>()
    val workDir = File(workRoot, "compile_${System.nanoTime()}").apply { mkdirs() }
    val classesOutDir = File(workDir, "classes").apply { mkdirs() }
    val patchDexFile = File(workDir, "patch.dex")
    val mergedDexFile = File(workDir, "merged.dex")

    return try {
      logs += "1/4 Indexando clases del DEX y runtime Android..."
      val targetDescriptor = "L${fullClassName.replace('.', '/')};"
      val innerPrefix = targetDescriptor.removeSuffix(";") + "$"

      val dexClassMap = LinkedHashMap<String, ClassDef>()
      val allDexInputs = (listOf(targetDexFile) + siblingDexFiles).distinctBy { it.absolutePath }
      for (dex in allDexInputs) {
        if (!dex.exists()) continue
        val loaded = DexFileFactory.loadDexFile(dex, null)
        for (cls in loaded.classes) {
          if (cls.type == targetDescriptor || cls.type.startsWith(innerPrefix)) continue
          if (cls.type.startsWith("L") && cls.type.endsWith(";")) {
            val internal = cls.type.substring(1, cls.type.length - 1)
            dexClassMap.putIfAbsent(internal, cls)
          }
        }
      }
      logs += "   Índice cargado: ${dexClassMap.size} clases DEX + stubs Android"

      logs += "2/4 Compilando Java en memoria con Eclipse JDT (-1.8)..."
      val sourceUnits = listOf(SourceUnitSpec(fullClassName, javaSource))
      val extraPackages = parseImportedPackages(javaSource) +
        listOfNotNull(fullClassName.substringBeforeLast('.', "").takeIf { it.isNotEmpty() })

      val compileOutcome = compileWithEcjCore(
        sources = sourceUnits,
        dexClassMap = dexClassMap,
        extraPackages = extraPackages,
        classesOutDir = classesOutDir,
      )

      if (!compileOutcome.success) {
        compileOutcome.diagnostics.forEach { d ->
          logs += "[Línea ${d.line}] ${d.message}"
        }
        return CompilePatchResult(
          success = false,
          diagnostics = compileOutcome.diagnostics.ifEmpty {
            listOf(EditorDiagnostic(1, 1, DiagnosticSeverity.ERROR, "Error de compilación Java"))
          },
          logs = logs,
        )
      }

      val compiledClasses = classesOutDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
      if (compiledClasses.isEmpty()) {
        return CompilePatchResult(
          success = false,
          diagnostics = listOf(EditorDiagnostic(1, 1, DiagnosticSeverity.ERROR, "El compilador no produjo archivos .class")),
          logs = logs,
        )
      }
      logs += "   Compiladas ${compiledClasses.size} clase(s) .class"

      logs += "3/4 Convirtiendo bytecode .class -> .dex con dalvik-dx..."
      val dxArgs = DxMain.Arguments().apply {
        outName = patchDexFile.absolutePath
        fileNames = arrayOf(classesOutDir.absolutePath)
        strictNameCheck = false
        jarOutput = false
      }
      val dxExit = DxMain.run(dxArgs)
      if (dxExit != 0 || !patchDexFile.exists()) {
        logs += "Error en dalvik-dx (código $dxExit)"
        return CompilePatchResult(
          success = false,
          diagnostics = listOf(EditorDiagnostic(1, 1, DiagnosticSeverity.ERROR, "Fallo al convertir .class a .dex (código $dxExit)")),
          logs = logs,
        )
      }

      logs += "4/4 Fusionando clases compiladas en ${targetDexFile.name}..."
      val originalDex = DexFileFactory.loadDexFile(targetDexFile, null)
      val patchDex = DexFileFactory.loadDexFile(patchDexFile, null)
      val patchTypes = patchDex.classes.map { it.type }.toSet()

      val dexPool = DexPool(originalDex.opcodes)
      for (origCls in originalDex.classes) {
        if (origCls.type in patchTypes || origCls.type == targetDescriptor || origCls.type.startsWith(innerPrefix)) {
          continue
        }
        dexPool.internClass(origCls)
      }
      for (patchedCls in patchDex.classes) {
        dexPool.internClass(patchedCls)
      }
      dexPool.writeTo(FileDataStore(mergedDexFile))
      mergedDexFile.copyTo(targetDexFile, overwrite = true)

      logs += "¡Parche aplicado con éxito en ${targetDexFile.name}!"
      CompilePatchResult(
        success = true,
        diagnostics = compileOutcome.diagnostics,
        logs = logs,
        updatedDexFilePath = targetDexFile.absolutePath,
        compiledClassesCount = patchDex.classes.size,
      )
    } catch (t: Throwable) {
      val detail = "${t::class.java.simpleName}: ${t.message ?: "Error interno"}"
      logs += "Excepción: $detail"
      CompilePatchResult(
        success = false,
        diagnostics = listOf(EditorDiagnostic(1, 1, DiagnosticSeverity.ERROR, detail)),
        logs = logs,
      )
    } finally {
      runCatching { workDir.deleteRecursively() }
    }
  }

  /**
   * Builds a genuine, valid, signed sample APK right inside the workspace using ECJ Core + dalvik-dx + apksig
   * so the user immediately has a real APK to inspect, decompile to Java, edit, recompile, and re-sign.
   */
  fun createSampleApk(
    workRoot: File,
    outputApkFile: File,
    keystoreProfile: KeystoreProfile,
  ) {
    val buildDir = File(workRoot, "sample_build_${System.nanoTime()}").apply { mkdirs() }
    try {
      val classesDir = File(buildDir, "classes").apply { mkdirs() }
      val dexFile = File(buildDir, "classes.dex")
      val unsignedApk = File(buildDir, "unsigned.apk")

      val greeterSource = """
        package com.dexforge.demo;

        public class DemoGreeter {
            private final String appTitle;
            private int launchCount;

            public DemoGreeter(String appTitle) {
                this.appTitle = appTitle;
                this.launchCount = 1;
            }

            public String buildWelcomeMessage(String developerName) {
                StringBuilder sb = new StringBuilder();
                sb.append("Hola ").append(developerName);
                sb.append(" desde ").append(this.appTitle);
                sb.append(" #").append(this.launchCount);
                return sb.toString();
            }

            public int incrementCounter(int delta) {
                this.launchCount += delta;
                return this.launchCount;
            }
        }
      """.trimIndent()

      val calcSource = """
        package com.dexforge.demo;

        public class LicenseCalculator {
            public static boolean isFeatureUnlocked(int tierLevel) {
                return tierLevel >= 2;
            }

            public static int computeChecksum(String input) {
                if (input == null) {
                    return 0;
                }
                int sum = 0;
                for (int i = 0; i < input.length(); i++) {
                    sum = (sum * 31) + input.charAt(i);
                }
                return Math.abs(sum);
            }
        }
      """.trimIndent()

      val outcome = compileWithEcjCore(
        sources = listOf(
          SourceUnitSpec("com.dexforge.demo.DemoGreeter", greeterSource),
          SourceUnitSpec("com.dexforge.demo.LicenseCalculator", calcSource),
        ),
        dexClassMap = emptyMap(),
        extraPackages = listOf("com.dexforge.demo"),
        classesOutDir = classesDir,
      )
      if (!outcome.success) {
        val msg = outcome.diagnostics.joinToString("; ") { "L${it.line}: ${it.message}" }
        throw IllegalStateException("Error compilando APK de muestra: $msg")
      }

      val dxArgs = DxMain.Arguments().apply {
        outName = dexFile.absolutePath
        fileNames = arrayOf(classesDir.absolutePath)
        strictNameCheck = false
        jarOutput = false
      }
      val dxCode = DxMain.run(dxArgs)
      if (dxCode != 0 || !dexFile.exists()) {
        throw IllegalStateException("Error convirtiendo clases de muestra a DEX")
      }

      val manifestBytes = buildMinimalBinaryManifest(
        packageName = "com.dexforge.demo",
        versionCode = 1,
        versionName = "1.0.0",
        appLabel = "DemoSandbox",
      )

      ZipOutputStream(FileOutputStream(unsignedApk)).use { zos ->
        zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
        zos.write(manifestBytes)
        zos.closeEntry()

        zos.putNextEntry(ZipEntry("classes.dex"))
        zos.write(dexFile.readBytes())
        zos.closeEntry()
      }

      outputApkFile.parentFile?.mkdirs()
      ApkArchiveEngine.signApk(
        inputApk = unsignedApk,
        outputApk = outputApkFile,
        keystoreProfile = keystoreProfile,
        enableV1 = true,
        enableV2 = true,
        enableV3 = true,
      )
    } finally {
      runCatching { buildDir.deleteRecursively() }
    }
  }

  private data class SourceUnitSpec(
    val fullClassName: String,
    val source: String,
  )

  private data class EcjOutcome(
    val success: Boolean,
    val diagnostics: List<EditorDiagnostic>,
  )

  private fun compileWithEcjCore(
    sources: List<SourceUnitSpec>,
    dexClassMap: Map<String, ClassDef>,
    extraPackages: List<String>,
    classesOutDir: File,
  ): EcjOutcome {
    val diagnostics = mutableListOf<EditorDiagnostic>()
    var hasErrors = false

    val nameEnvironment = DexAwareNameEnvironment(
      sources = sources,
      dexClassMap = dexClassMap,
      extraPackages = extraPackages,
    )

    val settings = hashMapOf<String, String>(
      CompilerOptions.OPTION_Compliance to CompilerOptions.VERSION_1_8,
      CompilerOptions.OPTION_Source to CompilerOptions.VERSION_1_8,
      CompilerOptions.OPTION_TargetPlatform to CompilerOptions.VERSION_1_8,
      CompilerOptions.OPTION_LocalVariableAttribute to CompilerOptions.GENERATE,
      CompilerOptions.OPTION_LineNumberAttribute to CompilerOptions.GENERATE,
      CompilerOptions.OPTION_SourceFileAttribute to CompilerOptions.GENERATE,
      CompilerOptions.OPTION_PreserveUnusedLocal to CompilerOptions.PRESERVE,
    )
    val options = CompilerOptions(settings)

    val requestor = ICompilerRequestor { result: CompilationResult ->
      result.allProblems?.forEach { problem ->
        if (problem.isError) {
          hasErrors = true
          diagnostics += EditorDiagnostic(
            line = problem.sourceLineNumber.coerceAtLeast(1),
            column = 1,
            severity = DiagnosticSeverity.ERROR,
            message = problem.message ?: "Error de compilación",
          )
        }
      }
      if (!result.hasErrors()) {
        result.classFiles?.forEach { cf ->
          val rel = String(cf.fileName()) + ".class"
          val outFile = File(classesOutDir, rel)
          outFile.parentFile?.mkdirs()
          outFile.writeBytes(cf.bytes)
        }
      }
    }

    val compiler = Compiler(
      nameEnvironment,
      DefaultErrorHandlingPolicies.proceedWithAllProblems(),
      options,
      requestor,
      DefaultProblemFactory(Locale.getDefault()),
    )

    val units: Array<ICompilationUnit> = sources.map { spec ->
      val pkgParts = spec.fullClassName.substringBeforeLast('.', "")
        .split('.')
        .filter { it.isNotEmpty() }
        .map { it.toCharArray() }
        .toTypedArray()
      val simpleName = spec.fullClassName.substringAfterLast('.').toCharArray()
      val fileName = (spec.fullClassName.replace('.', '/') + ".java").toCharArray()
      val contents = spec.source.toCharArray()

      object : ICompilationUnit {
        override fun getContents(): CharArray = contents
        override fun getMainTypeName(): CharArray = simpleName
        override fun getPackageName(): Array<CharArray> = pkgParts
        override fun getFileName(): CharArray = fileName
        override fun ignoreOptionalProblems(): Boolean = false
      }
    }.toTypedArray()

    try {
      compiler.compile(units)
    } finally {
      nameEnvironment.cleanup()
    }

    return EcjOutcome(
      success = !hasErrors,
      diagnostics = diagnostics,
    )
  }

  private class DexAwareNameEnvironment(
    sources: List<SourceUnitSpec>,
    private val dexClassMap: Map<String, ClassDef>,
    extraPackages: List<String>,
  ) : INameEnvironment {

    private val sourceUnitMap: Map<String, ICompilationUnit>
    private val knownPackages = hashSetOf<String>()
    private val classBytesCache = hashMapOf<String, ByteArray?>()
    private val classLoader = JavaToDexCompiler::class.java.classLoader

    init {
      val map = LinkedHashMap<String, ICompilationUnit>()
      for (spec in sources) {
        val internal = spec.fullClassName.replace('.', '/')
        val pkgParts = spec.fullClassName.substringBeforeLast('.', "")
          .split('.')
          .filter { it.isNotEmpty() }
          .map { it.toCharArray() }
          .toTypedArray()
        val simpleName = spec.fullClassName.substringAfterLast('.').toCharArray()
        val fileName = "$internal.java".toCharArray()
        val contents = spec.source.toCharArray()
        map[internal] = object : ICompilationUnit {
          override fun getContents(): CharArray = contents
          override fun getMainTypeName(): CharArray = simpleName
          override fun getPackageName(): Array<CharArray> = pkgParts
          override fun getFileName(): CharArray = fileName
          override fun ignoreOptionalProblems(): Boolean = false
        }
        addPackageHierarchy(spec.fullClassName.substringBeforeLast('.', ""))
      }
      sourceUnitMap = map

      for (internal in dexClassMap.keys) {
        val pkg = internal.substringBeforeLast('/', "").replace('/', '.')
        if (pkg.isNotEmpty()) addPackageHierarchy(pkg)
      }
      for (cls in CORE_RUNTIME_CLASSES) {
        val pkg = cls.substringBeforeLast('.', "")
        if (pkg.isNotEmpty()) addPackageHierarchy(pkg)
      }
      for (pkg in extraPackages) {
        if (pkg.isNotEmpty()) addPackageHierarchy(pkg)
      }
    }

    private fun addPackageHierarchy(dotPackage: String) {
      var curr = dotPackage
      while (curr.isNotEmpty()) {
        if (!knownPackages.add(curr)) break
        curr = curr.substringBeforeLast('.', "")
      }
    }

    override fun findType(compoundTypeName: Array<out CharArray>?): NameEnvironmentAnswer? {
      if (compoundTypeName == null || compoundTypeName.isEmpty()) return null
      val internal = compoundTypeName.joinToString("/") { String(it) }
      return findTypeByInternalName(internal)
    }

    override fun findType(typeName: CharArray?, packageName: Array<out CharArray>?): NameEnvironmentAnswer? {
      if (typeName == null) return null
      val typeStr = String(typeName)
      val pkgStr = packageName?.takeIf { it.isNotEmpty() }?.joinToString("/") { String(it) }
      val internal = if (pkgStr.isNullOrEmpty()) typeStr else "$pkgStr/$typeStr"
      return findTypeByInternalName(internal)
    }

    private fun findTypeByInternalName(internalName: String): NameEnvironmentAnswer? {
      sourceUnitMap[internalName]?.let { unit ->
        return NameEnvironmentAnswer(unit, null)
      }

      val resolvedBytes = lookupClassBytesWithInnerFallback(internalName) ?: return null
      return runCatching {
        val reader = ClassFileReader(resolvedBytes, "$internalName.class".toCharArray(), true)
        NameEnvironmentAnswer(reader, null)
      }.getOrNull()
    }

    private fun lookupClassBytesWithInnerFallback(internalName: String): ByteArray? {
      val direct = classBytesCache.getOrPut(internalName) {
        dexClassMap[internalName]?.let { emitDexClassStubBytes(it) }
          ?: loadRuntimeClassStubBytes(internalName)
      }
      if (direct != null) return direct

      // Try converting trailing '/' separators into '$' for nested/inner classes
      var candidate = internalName
      while ('/' in candidate) {
        val lastSlash = candidate.lastIndexOf('/')
        candidate = candidate.substring(0, lastSlash) + "$" + candidate.substring(lastSlash + 1)
        val found = classBytesCache.getOrPut(candidate) {
          dexClassMap[candidate]?.let { emitDexClassStubBytes(it) }
            ?: loadRuntimeClassStubBytes(candidate)
        }
        if (found != null) return found
      }
      return null
    }

    override fun isPackage(parentPackageName: Array<out CharArray>?, packageName: CharArray?): Boolean {
      if (packageName == null) return false
      val name = String(packageName)
      val parent = parentPackageName?.takeIf { it.isNotEmpty() }?.joinToString(".") { String(it) }
      val dotName = if (parent.isNullOrEmpty()) name else "$parent.$name"
      val internal = dotName.replace('.', '/')

      // If it's a known source unit, DEX class, or loadable runtime class, it's NOT a package
      if (internal in sourceUnitMap || internal in dexClassMap) return false
      if (canLoadRuntimeClass(dotName)) return false
      if (parent != null && canLoadRuntimeClass("$parent$$name")) return false

      if (dotName in knownPackages) return true
      val isStandardRoot = dotName == "java" ||
        dotName == "javax" ||
        dotName == "android" ||
        dotName == "androidx" ||
        dotName == "kotlin" ||
        dotName == "dalvik" ||
        dotName == "org" ||
        dotName == "com"
      if (isStandardRoot) return true

      // Dynamically allow lowercase subpackages under standard Android/JDK namespaces
      val isUnderStandardPrefix = dotName.startsWith("java.") ||
        dotName.startsWith("javax.") ||
        dotName.startsWith("android.") ||
        dotName.startsWith("androidx.") ||
        dotName.startsWith("dalvik.") ||
        dotName.startsWith("org.json.") ||
        dotName.startsWith("org.w3c.") ||
        dotName.startsWith("org.xml.")
      return isUnderStandardPrefix && name.isNotEmpty() && name[0].isLowerCase()
    }

    private fun canLoadRuntimeClass(dotName: String): Boolean {
      return try {
        Class.forName(dotName, false, classLoader)
        true
      } catch (_: Throwable) {
        false
      }
    }

    override fun cleanup() {
      classBytesCache.clear()
    }
  }

  private fun parseImportedPackages(source: String): List<String> {
    val pkgs = mutableListOf<String>()
    val regex = Regex("""^\s*import\s+(?:static\s+)?([a-zA-Z_][\w.]*)\s*;""", RegexOption.MULTILINE)
    for (m in regex.findAll(source)) {
      val name = m.groupValues[1]
      val pkg = if (name.endsWith(".*")) {
        name.removeSuffix(".*")
      } else {
        name.substringBeforeLast('.', "")
      }
      if (pkg.isNotEmpty()) pkgs += pkg
    }
    return pkgs
  }

  private fun loadRuntimeClassStubBytes(internalName: String): ByteArray? {
    val dotName = internalName.replace('/', '.')
    val clazz = try {
      Class.forName(dotName, false, JavaToDexCompiler::class.java.classLoader)
    } catch (_: Throwable) {
      return null
    }

    return runCatching {
      val cw = ClassWriter(0)
      val superInternal = runCatching { clazz.superclass?.name?.replace('.', '/') }.getOrNull()
        ?: if (clazz.isInterface) "java/lang/Object" else null
      val interfaces = runCatching {
        clazz.interfaces.map { it.name.replace('.', '/') }.toTypedArray()
      }.getOrDefault(emptyArray())

      var access = clazz.modifiers and (
        Modifier.PUBLIC or Modifier.FINAL or Modifier.INTERFACE or Modifier.ABSTRACT
      )
      if (!clazz.isInterface) access = access or AsmOpcodes.ACC_SUPER
      if (clazz.isEnum) access = access or AsmOpcodes.ACC_ENUM
      if (clazz.isAnnotation) access = access or AsmOpcodes.ACC_ANNOTATION

      cw.visit(AsmOpcodes.V1_8, access, internalName, null, superInternal, interfaces)

      runCatching {
        for (f in clazz.declaredFields) {
          if (f.isSynthetic) continue
          runCatching {
            val fAcc = f.modifiers and (
              Modifier.PUBLIC or Modifier.PROTECTED or Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL
            )
            cw.visitField(fAcc, f.name, AsmType.getDescriptor(f.type), null, null)?.visitEnd()
          }
        }
      }

      runCatching {
        for (c in clazz.declaredConstructors) {
          if (c.isSynthetic) continue
          runCatching {
            val cAcc = c.modifiers and (Modifier.PUBLIC or Modifier.PROTECTED or Modifier.PRIVATE)
            val desc = AsmType.getConstructorDescriptor(c)
            cw.visitMethod(cAcc, "<init>", desc, null, null)?.visitEnd()
          }
        }
      }

      runCatching {
        for (m in clazz.declaredMethods) {
          if (m.isSynthetic || m.isBridge) continue
          runCatching {
            val mAcc = m.modifiers and (
              Modifier.PUBLIC or Modifier.PROTECTED or Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL or Modifier.ABSTRACT
            )
            val desc = AsmType.getMethodDescriptor(m)
            cw.visitMethod(mAcc, m.name, desc, null, null)?.visitEnd()
          }
        }
      }

      cw.visitEnd()
      cw.toByteArray()
    }.getOrNull()
  }

  private fun emitDexClassStubBytes(cls: ClassDef): ByteArray? {
    val rawType = cls.type
    if (!rawType.startsWith("L") || !rawType.endsWith(";")) return null
    val internalName = rawType.substring(1, rawType.length - 1)

    return runCatching {
      val superInternal = cls.superclass?.let {
        if (it.startsWith("L") && it.endsWith(";")) it.substring(1, it.length - 1) else "java/lang/Object"
      } ?: "java/lang/Object"

      val interfaces = cls.interfaces.mapNotNull {
        if (it.startsWith("L") && it.endsWith(";")) it.substring(1, it.length - 1) else null
      }.toTypedArray()

      val cw = ClassWriter(0)
      var access = cls.accessFlags and (
        AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_FINAL or AsmOpcodes.ACC_INTERFACE or
          AsmOpcodes.ACC_ABSTRACT or AsmOpcodes.ACC_ENUM or AsmOpcodes.ACC_ANNOTATION
      )
      if ((access and AsmOpcodes.ACC_INTERFACE) == 0) access = access or AsmOpcodes.ACC_SUPER
      cw.visit(AsmOpcodes.V1_8, access, internalName, null, superInternal, interfaces)

      for (f in cls.fields) {
        val fAcc = f.accessFlags and (
          AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_PROTECTED or AsmOpcodes.ACC_PRIVATE or
            AsmOpcodes.ACC_STATIC or AsmOpcodes.ACC_FINAL
        )
        cw.visitField(fAcc, f.name, f.type, null, null)?.visitEnd()
      }

      for (m in cls.methods) {
        if (m.name == "<clinit>") continue
        val mAcc = m.accessFlags and (
          AsmOpcodes.ACC_PUBLIC or AsmOpcodes.ACC_PROTECTED or AsmOpcodes.ACC_PRIVATE or
            AsmOpcodes.ACC_STATIC or AsmOpcodes.ACC_FINAL or AsmOpcodes.ACC_ABSTRACT
        )
        val desc = "(${m.parameterTypes.joinToString("")})${m.returnType}"
        cw.visitMethod(mAcc, m.name, desc, null, null)?.visitEnd()
      }

      cw.visitEnd()
      cw.toByteArray()
    }.getOrNull()
  }

  private fun buildMinimalBinaryManifest(
    packageName: String,
    versionCode: Int,
    versionName: String,
    appLabel: String,
  ): ByteArray {
    val strings = listOf(
      "http://schemas.android.com/apk/res/android",
      "android",
      "versionCode",
      "versionName",
      "label",
      "manifest",
      "package",
      packageName,
      versionName,
      "application",
      appLabel,
    )
    val resIds = intArrayOf(0, 0, 0x0101021B, 0x0101021C, 0x01010001)

    val stringPoolBytes = encodeStringPool(strings)
    val resIdChunk = encodeResIdChunk(resIds)

    val body = java.io.ByteArrayOutputStream()
    writeIntLE(body, 0x00100100)
    writeIntLE(body, 24)
    writeIntLE(body, 1)
    writeIntLE(body, -1)
    writeIntLE(body, 1)
    writeIntLE(body, 0)

    writeStartTag(
      body,
      line = 2,
      nsIdx = -1,
      nameIdx = 5,
      attrs = listOf(
        AttrSpec(-1, 6, 7, 0x03, 7),
        AttrSpec(0, 2, -1, 0x10, versionCode),
        AttrSpec(0, 3, 8, 0x03, 8),
      ),
    )

    writeStartTag(
      body,
      line = 3,
      nsIdx = -1,
      nameIdx = 9,
      attrs = listOf(
        AttrSpec(0, 4, 10, 0x03, 10),
      ),
    )

    writeEndTag(body, line = 4, nsIdx = -1, nameIdx = 9)
    writeEndTag(body, line = 5, nsIdx = -1, nameIdx = 5)

    writeIntLE(body, 0x00100101)
    writeIntLE(body, 24)
    writeIntLE(body, 5)
    writeIntLE(body, -1)
    writeIntLE(body, 1)
    writeIntLE(body, 0)

    val content = body.toByteArray()
    val totalSize = 8 + stringPoolBytes.size + resIdChunk.size + content.size
    val out = java.io.ByteArrayOutputStream(totalSize)
    writeIntLE(out, 0x00080003)
    writeIntLE(out, totalSize)
    out.write(stringPoolBytes)
    out.write(resIdChunk)
    out.write(content)
    return out.toByteArray()
  }

  private data class AttrSpec(val ns: Int, val name: Int, val rawVal: Int, val type: Int, val data: Int)

  private fun writeStartTag(out: java.io.ByteArrayOutputStream, line: Int, nsIdx: Int, nameIdx: Int, attrs: List<AttrSpec>) {
    val chunkSize = 36 + attrs.size * 20
    writeIntLE(out, 0x00100102)
    writeIntLE(out, chunkSize)
    writeIntLE(out, line)
    writeIntLE(out, -1)
    writeIntLE(out, nsIdx)
    writeIntLE(out, nameIdx)
    writeShortLE(out, 0x14)
    writeShortLE(out, 0x14)
    writeShortLE(out, attrs.size)
    writeShortLE(out, 0)
    writeShortLE(out, 0)
    writeShortLE(out, 0)
    for (a in attrs) {
      writeIntLE(out, a.ns)
      writeIntLE(out, a.name)
      writeIntLE(out, a.rawVal)
      writeShortLE(out, 8)
      out.write(0)
      out.write(a.type)
      writeIntLE(out, a.data)
    }
  }

  private fun writeEndTag(out: java.io.ByteArrayOutputStream, line: Int, nsIdx: Int, nameIdx: Int) {
    writeIntLE(out, 0x00100103)
    writeIntLE(out, 24)
    writeIntLE(out, line)
    writeIntLE(out, -1)
    writeIntLE(out, nsIdx)
    writeIntLE(out, nameIdx)
  }

  private fun encodeResIdChunk(ids: IntArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    writeIntLE(out, 0x00080180)
    writeIntLE(out, 8 + ids.size * 4)
    ids.forEach { writeIntLE(out, it) }
    return out.toByteArray()
  }

  private fun encodeStringPool(strings: List<String>): ByteArray {
    val stringData = java.io.ByteArrayOutputStream()
    val offsets = IntArray(strings.size)
    for ((i, s) in strings.withIndex()) {
      offsets[i] = stringData.size()
      val chars = s.toCharArray()
      writeShortLE(stringData, chars.size)
      for (ch in chars) writeShortLE(stringData, ch.code)
      writeShortLE(stringData, 0)
    }
    while (stringData.size() % 4 != 0) stringData.write(0)

    val stringsOffset = 28 + strings.size * 4
    val totalSize = stringsOffset + stringData.size()
    val out = java.io.ByteArrayOutputStream(totalSize)
    writeIntLE(out, 0x001C0001)
    writeIntLE(out, totalSize)
    writeIntLE(out, strings.size)
    writeIntLE(out, 0)
    writeIntLE(out, 0)
    writeIntLE(out, stringsOffset)
    writeIntLE(out, 0)
    offsets.forEach { writeIntLE(out, it) }
    out.write(stringData.toByteArray())
    return out.toByteArray()
  }

  private fun writeIntLE(out: java.io.ByteArrayOutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v ushr 8) and 0xFF)
    out.write((v ushr 16) and 0xFF)
    out.write((v ushr 24) and 0xFF)
  }

  private fun writeShortLE(out: java.io.ByteArrayOutputStream, v: Int) {
    out.write(v and 0xFF)
    out.write((v ushr 8) and 0xFF)
  }
}

package com.example.engine

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.example.model.DexClassInfo
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.impl.InMemoryCodeCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class JavaDecompilerSession(
  private val cacheRoot: File,
  private val dexFile: File,
  private val dexEntryName: String,
) : AutoCloseable {

  private val jadxExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "dexforge-jadx-warmup").apply { isDaemon = true }
  }

  private val lock = Any()
  @Volatile
  private var closed = false

  @Volatile
  private var cachedDexFile: DexFile = DexFileFactory.loadDexFile(dexFile, null)

  @Volatile
  private var cachedClasses: List<DexClassInfo> = buildClassIndex(cachedDexFile)

  @Volatile
  private var jadxSessionDir: File? = null

  @Volatile
  private var jadxFuture: Future<JadxHandle> = startJadxWarmupAsync()

  private val javaCache = ConcurrentHashMap<String, String>()
  private val smaliCache = ConcurrentHashMap<String, String>()

  private class JadxHandle(
    val outDir: File,
    val decompiler: JadxDecompiler,
    val classMap: Map<String, JavaClass>,
  ) : AutoCloseable {
    override fun close() {
      runCatching { decompiler.close() }
      runCatching { outDir.deleteRecursively() }
    }
  }

  private fun startJadxWarmupAsync(): Future<JadxHandle> {
    val snapshotOutDir = File(cacheRoot, "jadx_out_${System.nanoTime()}").apply { mkdirs() }
    jadxSessionDir = snapshotOutDir
    return jadxExecutor.submit<JadxHandle> {
      val args = JadxArgs().apply {
        setInputFile(dexFile)
        outDir = snapshotOutDir
        isSkipResources = true
        isShowInconsistentCode = true
        isDeobfuscationOn = false
        isUseImports = true
        codeCache = InMemoryCodeCache()
        threadsCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
      }
      val decompiler = JadxDecompiler(args)
      decompiler.load()
      val map = decompiler.classes.associateBy { it.fullName }
      JadxHandle(snapshotOutDir, decompiler, map)
    }
  }

  private fun buildClassIndex(loadedDex: DexFile): List<DexClassInfo> {
    val allTypes = HashSet<String>(loadedDex.classes.size)
    for (cls in loadedDex.classes) {
      allTypes += cls.type
    }

    // Collect method names keyed by the top-level outer descriptor so inner/anonymous class
    // methods are also searchable under the outer .java file they belong to.
    val methodsByOuterType = HashMap<String, LinkedHashSet<String>>(loadedDex.classes.size)
    for (cls in loadedDex.classes) {
      val rawType = cls.type
      if (!rawType.startsWith("L") || !rawType.endsWith(";")) continue
      val internal = rawType.substring(1, rawType.length - 1)
      val dollarIdx = internal.indexOf('$')
      val targetType = if (dollarIdx > 0) {
        val outerDescriptor = "L${internal.substring(0, dollarIdx)};"
        if (outerDescriptor in allTypes) outerDescriptor else rawType
      } else {
        rawType
      }
      val bucket = methodsByOuterType.getOrPut(targetType) { LinkedHashSet() }
      for (m in cls.methods) {
        val name = m.name
        if (name != "<init>" && name != "<clinit>") {
          bucket += name
        }
      }
    }

    val result = ArrayList<DexClassInfo>(loadedDex.classes.size)
    for (cls in loadedDex.classes) {
      val rawType = cls.type
      if (!rawType.startsWith("L") || !rawType.endsWith(";")) continue
      val internal = rawType.substring(1, rawType.length - 1)

      // Match Jadx's top-level compilation units: inner/anonymous classes live inside their outer .java
      val dollarIdx = internal.indexOf('$')
      if (dollarIdx > 0) {
        val outerDescriptor = "L${internal.substring(0, dollarIdx)};"
        if (outerDescriptor in allTypes) continue
      }

      val fullName = internal.replace('/', '.')
      val lastDot = fullName.lastIndexOf('.')
      val pkg = if (lastDot > 0) fullName.substring(0, lastDot) else "(default)"
      val simple = if (lastDot > 0) fullName.substring(lastDot + 1) else fullName
      val flags = cls.accessFlags
      val collectedMethods = methodsByOuterType[rawType]?.toList().orEmpty()

      result += DexClassInfo(
        fullName = fullName,
        packageName = pkg,
        simpleName = simple,
        dexEntryName = dexEntryName,
        methodCount = cls.methods.count(),
        fieldCount = cls.fields.count(),
        isInterface = (flags and 0x0200) != 0,
        isEnum = (flags and 0x4000) != 0,
        methodNames = collectedMethods,
      )
    }

    result.sortWith(compareBy({ it.packageName }, { it.simpleName }))
    return result
  }

  fun listClasses(): List<DexClassInfo> = cachedClasses

  fun isClassCached(fullClassName: String): Boolean = javaCache.containsKey(fullClassName)

  fun decompileClassToJava(fullClassName: String): String {
    javaCache[fullClassName]?.let { return it }

    val handle = jadxFuture.get()
    val cls = handle.classMap[fullClassName]
      ?: run {
        val outer = fullClassName.substringBefore('$')
        if (outer != fullClassName) handle.classMap[outer] else null
      }
      ?: throw IllegalArgumentException("Clase '$fullClassName' no encontrada en $dexEntryName")

    cls.decompile()
    val code = cls.code.orEmpty().ifBlank {
      "// Jadx no pudo generar código Java para $fullClassName"
    }
    javaCache[fullClassName] = code
    return code
  }

  fun disassembleClassSummary(fullClassName: String): String {
    smaliCache[fullClassName]?.let { return it }

    val descriptor = "L${fullClassName.replace('.', '/')};"
    val classDef = cachedDexFile.classes.firstOrNull { it.type == descriptor }
      ?: return "# Clase no encontrada en DEX: $descriptor"

    val formatted = formatDexlibClass(classDef)
    smaliCache[fullClassName] = formatted
    return formatted
  }

  /**
   * Reloads the dexlib2 index immediately (~30ms) and restarts Jadx warmup asynchronously
   * after the DEX file has been patched on disk.
   */
  fun reloadAfterPatch(patchedClassName: String? = null) {
    synchronized(lock) {
      if (closed) return
      if (patchedClassName != null) {
        javaCache.remove(patchedClassName)
        smaliCache.remove(patchedClassName)
      } else {
        javaCache.clear()
        smaliCache.clear()
      }

      val oldFuture = jadxFuture
      if (!oldFuture.isDone) {
        oldFuture.cancel(true)
      }
      cachedDexFile = DexFileFactory.loadDexFile(dexFile, null)
      cachedClasses = buildClassIndex(cachedDexFile)
      jadxFuture = startJadxWarmupAsync()

      if (oldFuture.isDone && !oldFuture.isCancelled) {
        jadxExecutor.execute {
          runCatching { oldFuture.get().close() }
        }
      }
    }
  }

  private fun formatDexlibClass(cls: ClassDef): String {
    val sb = StringBuilder(2048)
    sb.append(".class ").append(formatAccess(cls.accessFlags)).append(cls.type).append('\n')
    cls.superclass?.let { sb.append(".super ").append(it).append('\n') }
    cls.sourceFile?.let { sb.append(".source \"").append(it).append("\"\n") }
    cls.interfaces.forEach { sb.append(".implements ").append(it).append('\n') }
    sb.append('\n')

    if (cls.staticFields.iterator().hasNext() || cls.instanceFields.iterator().hasNext()) {
      sb.append("# Campos\n")
      for (f in cls.staticFields) {
        sb.append(".field ").append(formatAccess(f.accessFlags))
          .append(f.name).append(':').append(f.type).append('\n')
      }
      for (f in cls.instanceFields) {
        sb.append(".field ").append(formatAccess(f.accessFlags))
          .append(f.name).append(':').append(f.type).append('\n')
      }
      sb.append('\n')
    }

    sb.append("# Métodos\n")
    for (m in cls.methods) {
      appendMethod(sb, m)
    }
    return sb.toString()
  }

  private fun appendMethod(sb: StringBuilder, m: Method) {
    sb.append(".method ").append(formatAccess(m.accessFlags))
      .append(m.name)
      .append('(').append(m.parameterTypes.joinToString("")).append(')')
      .append(m.returnType)
      .append('\n')

    val impl = m.implementation
    if (impl != null) {
      sb.append("    .registers ").append(impl.registerCount).append('\n')
      for (ins in impl.instructions) {
        sb.append("    ").append(ins.opcode.name)
        sb.append('\n')
      }
    }
    sb.append(".end method\n\n")
  }

  private fun formatAccess(flags: Int): String {
    val parts = mutableListOf<String>()
    if ((flags and 0x0001) != 0) parts += "public"
    if ((flags and 0x0002) != 0) parts += "private"
    if ((flags and 0x0004) != 0) parts += "protected"
    if ((flags and 0x0008) != 0) parts += "static"
    if ((flags and 0x0010) != 0) parts += "final"
    if ((flags and 0x0200) != 0) parts += "interface"
    if ((flags and 0x0400) != 0) parts += "abstract"
    if ((flags and 0x10000) != 0) parts += "constructor"
    return if (parts.isEmpty()) "" else parts.joinToString(" ") + " "
  }

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      val future = jadxFuture
      val dir = jadxSessionDir
      runCatching {
        if (future.isDone) {
          future.get().close()
        } else {
          future.cancel(true)
        }
      }
      runCatching { jadxExecutor.shutdownNow() }
      runCatching { dir?.deleteRecursively() }
    }
  }
}

object JavaDecompilerEngine {

  fun openDexSession(
    cacheRoot: File,
    sourceDexFile: File,
    dexEntryName: String,
  ): JavaDecompilerSession {
    return JavaDecompilerSession(
      cacheRoot = cacheRoot,
      dexFile = sourceDexFile,
      dexEntryName = dexEntryName,
    )
  }
}

package com.example.engine

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.example.model.DexClassInfo
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import java.io.File

class JavaDecompilerSession(
  private val sessionDir: File,
  private val decompiler: JadxDecompiler,
  private val dexFile: File,
  private val dexEntryName: String,
) : AutoCloseable {

  private val classMap: Map<String, JavaClass> by lazy {
    decompiler.classes.associateBy { it.fullName }
  }

  fun listClasses(): List<DexClassInfo> {
    return decompiler.classes
      .map { cls ->
        val rawName = cls.fullName
        val pkg = cls.`package`.orEmpty()
        val simple = cls.name
        val acc = cls.classNode.accessFlags
        DexClassInfo(
          fullName = rawName,
          packageName = pkg.ifEmpty { "(default)" },
          simpleName = simple,
          dexEntryName = dexEntryName,
          methodCount = cls.methods.size,
          fieldCount = cls.fields.size,
          isInterface = acc.isInterface,
          isEnum = acc.isEnum,
        )
      }
      .sortedWith(compareBy({ it.packageName }, { it.simpleName }))
  }

  fun decompileClassToJava(fullClassName: String): String {
    val cls = classMap[fullClassName]
      ?: throw IllegalArgumentException("Clase '$fullClassName' no encontrada en $dexEntryName")
    cls.decompile()
    return cls.code.orEmpty().ifBlank {
      "// Jadx no pudo generar código Java para $fullClassName"
    }
  }

  fun disassembleClassSummary(fullClassName: String): String {
    val descriptor = "L${fullClassName.replace('.', '/')};"
    val loadedDex = DexFileFactory.loadDexFile(dexFile, null)
    val classDef = loadedDex.classes.firstOrNull { it.type == descriptor }
      ?: return "# Clase no encontrada en DEX: $descriptor"

    return formatDexlibClass(classDef)
  }

  private fun formatDexlibClass(cls: ClassDef): String {
    val sb = StringBuilder()
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
    runCatching { decompiler.close() }
    runCatching { sessionDir.deleteRecursively() }
  }
}

object JavaDecompilerEngine {

  fun openDexSession(
    cacheRoot: File,
    sourceDexFile: File,
    dexEntryName: String,
  ): JavaDecompilerSession {
    val sessionDir = File(cacheRoot, "jadx_${System.nanoTime()}").apply { mkdirs() }
    val workingDex = File(sessionDir, dexEntryName)
    sourceDexFile.copyTo(workingDex, overwrite = true)

    val args = JadxArgs().apply {
      setInputFile(workingDex)
      outDir = File(sessionDir, "out")
      isSkipResources = true
      isShowInconsistentCode = true
      isDeobfuscationOn = false
      threadsCount = 2
    }

    val decompiler = JadxDecompiler(args)
    decompiler.load()
    return JavaDecompilerSession(
      sessionDir = sessionDir,
      decompiler = decompiler,
      dexFile = workingDex,
      dexEntryName = dexEntryName,
    )
  }
}

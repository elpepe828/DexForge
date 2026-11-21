package com.example.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Decodes Android Binary XML (AXML) used in compiled AndroidManifest.xml and res/ XML files.
 */
object AxmlDecoder {

  private const val CHUNK_AXML_FILE = 0x00080003
  private const val CHUNK_STRING_POOL = 0x001C0001
  private const val CHUNK_RESOURCE_IDS = 0x00080180
  private const val CHUNK_XML_START_NAMESPACE = 0x00100100
  private const val CHUNK_XML_END_NAMESPACE = 0x00100101
  private const val CHUNK_XML_START_TAG = 0x00100102
  private const val CHUNK_XML_END_TAG = 0x00100103
  private const val CHUNK_XML_TEXT = 0x00100104

  fun isBinaryXml(bytes: ByteArray): Boolean {
    if (bytes.size < 8) return false
    val buf = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
    val type = buf.int
    return (type and 0xFFFF) == 0x0003
  }

  fun decode(bytes: ByteArray): String {
    if (!isBinaryXml(bytes)) {
      return String(bytes, StandardCharsets.UTF_8)
    }
    return try {
      decodeInternal(bytes)
    } catch (e: Exception) {
      "<!-- Error decodificando XML binario: ${e.message} -->"
    }
  }

  private fun decodeInternal(bytes: ByteArray): String {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val fileType = buf.int
    if ((fileType and 0xFFFF) != 0x0003) {
      return String(bytes, StandardCharsets.UTF_8)
    }
    buf.int // file size

    var strings: List<String> = emptyList()
    val namespaces = mutableMapOf<String, String>() // uri -> prefix
    val pendingNs = mutableListOf<Pair<String, String>>()
    val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
    var indent = 0

    while (buf.remaining() >= 8) {
      val chunkStart = buf.position()
      val chunkHeader = buf.int
      val chunkType = chunkHeader and 0xFFFF
      val headerSize = (chunkHeader ushr 16) and 0xFFFF
      val chunkSize = buf.int

      if (chunkSize < 8 || chunkStart + chunkSize > bytes.size) break

      when (chunkHeader) {
        CHUNK_STRING_POOL -> {
          strings = parseStringPool(bytes, chunkStart, chunkSize)
        }
        CHUNK_RESOURCE_IDS -> {
          // Skip resource ID table
        }
        CHUNK_XML_START_NAMESPACE -> {
          buf.int // lineNumber
          buf.int // comment
          val prefixIdx = buf.int
          val uriIdx = buf.int
          val prefix = strings.getOrNull(prefixIdx).orEmpty()
          val uri = strings.getOrNull(uriIdx).orEmpty()
          if (uri.isNotEmpty()) {
            namespaces[uri] = prefix
            pendingNs += prefix to uri
          }
        }
        CHUNK_XML_END_NAMESPACE -> {
          // Handled via tag scoping
        }
        CHUNK_XML_START_TAG -> {
          buf.int // lineNumber
          buf.int // comment
          val nsIdx = buf.int
          val nameIdx = buf.int
          val attrStart = buf.short.toInt() and 0xFFFF
          val attrSize = buf.short.toInt() and 0xFFFF
          val attrCount = buf.short.toInt() and 0xFFFF
          buf.short // idIndex
          buf.short // classIndex
          buf.short // styleIndex

          val tagName = strings.getOrNull(nameIdx).orEmpty()
          val nsPrefix = prefixFor(strings, namespaces, nsIdx)
          val fullTag = if (nsPrefix.isNotEmpty()) "$nsPrefix:$tagName" else tagName

          out.append("  ".repeat(indent)).append('<').append(fullTag)

          if (pendingNs.isNotEmpty()) {
            for ((p, u) in pendingNs) {
              if (p.isNotEmpty()) {
                out.append(" xmlns:").append(p).append("=\"").append(escapeXml(u)).append('"')
              } else {
                out.append(" xmlns=\"").append(escapeXml(u)).append('"')
              }
            }
            pendingNs.clear()
          }

          val attrsOffset = chunkStart + headerSize + attrStart
          if (attrsOffset >= chunkStart && attrsOffset <= chunkStart + chunkSize) {
            buf.position(attrsOffset)
          }

          for (i in 0 until attrCount) {
            if (buf.remaining() < 20) break
            val attrNsIdx = buf.int
            val attrNameIdx = buf.int
            val attrRawIdx = buf.int
            val valueSize = buf.short.toInt() and 0xFFFF
            buf.get() // res0
            val valueType = buf.get().toInt() and 0xFF
            val valueData = buf.int

            val attrName = strings.getOrNull(attrNameIdx) ?: "attr_$i"
            val attrPrefix = prefixFor(strings, namespaces, attrNsIdx)
            val fullAttr = if (attrPrefix.isNotEmpty()) "$attrPrefix:$attrName" else attrName

            val attrVal = formatAttrValue(strings, attrRawIdx, valueType, valueData)
            if (attrCount > 1) {
              out.append('\n').append("  ".repeat(indent + 1))
            } else {
              out.append(' ')
            }
            out.append(fullAttr).append("=\"").append(escapeXml(attrVal)).append('"')
            if (attrSize > 20) {
              buf.position((buf.position() + (attrSize - 20)).coerceAtMost(chunkStart + chunkSize))
            }
          }

          out.append(">\n")
          indent++
        }
        CHUNK_XML_END_TAG -> {
          buf.int // lineNumber
          buf.int // comment
          val nsIdx = buf.int
          val nameIdx = buf.int
          indent = (indent - 1).coerceAtLeast(0)
          val tagName = strings.getOrNull(nameIdx).orEmpty()
          val nsPrefix = prefixFor(strings, namespaces, nsIdx)
          val fullTag = if (nsPrefix.isNotEmpty()) "$nsPrefix:$tagName" else tagName
          out.append("  ".repeat(indent)).append("</").append(fullTag).append(">\n")
        }
        CHUNK_XML_TEXT -> {
          buf.int // lineNumber
          buf.int // comment
          val textIdx = buf.int
          val text = strings.getOrNull(textIdx).orEmpty().trim()
          if (text.isNotEmpty()) {
            out.append("  ".repeat(indent)).append(escapeXml(text)).append('\n')
          }
        }
        else -> {
          if (chunkType == 0x0001) {
            strings = parseStringPool(bytes, chunkStart, chunkSize)
          }
        }
      }

      buf.position(chunkStart + chunkSize)
    }

    return out.toString()
  }

  private fun parseStringPool(bytes: ByteArray, start: Int, size: Int): List<String> {
    val buf = ByteBuffer.wrap(bytes, start, size).order(ByteOrder.LITTLE_ENDIAN)
    buf.position(start + 8)
    val stringCount = buf.int
    val styleCount = buf.int
    val flags = buf.int
    val stringsOffset = buf.int
    buf.int // stylesOffset

    val isUtf8 = (flags and (1 shl 8)) != 0
    val offsets = IntArray(stringCount) { buf.int }
    repeat(styleCount) { if (buf.remaining() >= 4) buf.int }

    val poolDataStart = start + stringsOffset
    val result = ArrayList<String>(stringCount)
    for (i in 0 until stringCount) {
      val pos = poolDataStart + offsets[i]
      if (pos < start || pos >= start + size) {
        result += ""
        continue
      }
      result += if (isUtf8) readUtf8String(bytes, pos, start + size) else readUtf16String(bytes, pos, start + size)
    }
    return result
  }

  private fun readUtf8String(bytes: ByteArray, offset: Int, limit: Int): String {
    var p = offset
    // UTF-16 length (1 or 2 bytes)
    if (p >= limit) return ""
    if ((bytes[p].toInt() and 0x80) != 0) p += 2 else p += 1
    if (p >= limit) return ""
    var byteLen = bytes[p].toInt() and 0x7F
    if ((bytes[p].toInt() and 0x80) != 0 && p + 1 < limit) {
      byteLen = ((bytes[p].toInt() and 0x7F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
      p += 2
    } else {
      p += 1
    }
    val safeLen = byteLen.coerceIn(0, (limit - p).coerceAtLeast(0))
    return String(bytes, p, safeLen, StandardCharsets.UTF_8)
  }

  private fun readUtf16String(bytes: ByteArray, offset: Int, limit: Int): String {
    if (offset + 2 > limit) return ""
    var p = offset
    var charLen = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
    p += 2
    if ((charLen and 0x8000) != 0 && p + 2 <= limit) {
      val low = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)
      charLen = ((charLen and 0x7FFF) shl 16) or low
      p += 2
    }
    val byteLen = (charLen * 2).coerceIn(0, (limit - p).coerceAtLeast(0))
    return String(bytes, p, byteLen, StandardCharsets.UTF_16LE)
  }

  private fun prefixFor(strings: List<String>, namespaces: Map<String, String>, nsIdx: Int): String {
    if (nsIdx < 0) return ""
    val uri = strings.getOrNull(nsIdx) ?: return ""
    return namespaces[uri] ?: if (uri.endsWith("/android")) "android" else ""
  }

  private fun formatAttrValue(strings: List<String>, rawIdx: Int, type: Int, data: Int): String {
    if (rawIdx >= 0) {
      strings.getOrNull(rawIdx)?.let { return it }
    }
    return when (type) {
      0x03 -> strings.getOrNull(data) ?: ""
      0x01 -> "@0x${data.toUInt().toString(16).padStart(8, '0')}"
      0x02 -> "?0x${data.toUInt().toString(16).padStart(8, '0')}"
      0x04 -> java.lang.Float.intBitsToFloat(data).toString()
      0x10 -> data.toString()
      0x11 -> "0x${data.toUInt().toString(16)}"
      0x12 -> (data != 0).toString()
      in 0x1C..0x1F -> "#${data.toUInt().toString(16).padStart(8, '0')}"
      else -> data.toString()
    }
  }

  private fun escapeXml(value: String): String =
    value.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")

  fun extractManifestField(decodedXml: String, attributeName: String): String? {
    val regex = Regex("""\b$attributeName\s*=\s*"([^"]*)"""")
    return regex.find(decodedXml)?.groupValues?.getOrNull(1)
  }
}

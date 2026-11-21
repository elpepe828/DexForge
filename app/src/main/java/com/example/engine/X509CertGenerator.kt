package com.example.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LoadedKeyMaterial(
  val alias: String,
  val privateKey: PrivateKey,
  val certificate: X509Certificate,
)

object X509CertGenerator {

  fun createPkcs12Keystore(
    outputFile: File,
    alias: String,
    storePassword: String,
    keyPassword: String,
    commonName: String,
    organization: String,
    country: String = "US",
    validityYears: Int = 25,
  ): LoadedKeyMaterial {
    val keyPairGen = KeyPairGenerator.getInstance("RSA")
    keyPairGen.initialize(2048, SecureRandom())
    val keyPair = keyPairGen.generateKeyPair()

    val cert = buildSelfSignedX509(
      keyPair = keyPair,
      commonName = commonName.ifBlank { "DexForge Developer" },
      organization = organization.ifBlank { "DexForge" },
      country = country.take(2).uppercase(Locale.US).ifBlank { "US" },
      validityYears = validityYears.coerceIn(1, 50),
    )

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(alias, keyPair.private, keyPassword.toCharArray(), arrayOf(cert))
    outputFile.parentFile?.mkdirs()
    FileOutputStream(outputFile).use { fos ->
      ks.store(fos, storePassword.toCharArray())
    }
    return LoadedKeyMaterial(alias, keyPair.private, cert)
  }

  fun loadKeystore(
    keystoreFile: File,
    storeType: String,
    alias: String?,
    storePassword: String,
    keyPassword: String,
  ): LoadedKeyMaterial {
    val resolvedType = storeType.ifBlank {
      if (keystoreFile.extension.equals("bks", ignoreCase = true)) "BKS" else "PKCS12"
    }
    val ks = try {
      KeyStore.getInstance(resolvedType).also { store ->
        FileInputStream(keystoreFile).use { fis ->
          store.load(fis, storePassword.toCharArray())
        }
      }
    } catch (e: Exception) {
      val fallbackType = if (resolvedType == "PKCS12") "BKS" else "PKCS12"
      KeyStore.getInstance(fallbackType).also { store ->
        FileInputStream(keystoreFile).use { fis ->
          store.load(fis, storePassword.toCharArray())
        }
      }
    }

    val targetAlias = if (!alias.isNullOrBlank() && ks.containsAlias(alias)) {
      alias
    } else {
      val aliases = ks.aliases().toList()
      aliases.firstOrNull { ks.isKeyEntry(it) }
        ?: throw IllegalArgumentException("No se encontró ningún alias de clave privada en el keystore")
    }

    val key = ks.getKey(targetAlias, keyPassword.toCharArray()) as? PrivateKey
      ?: throw IllegalArgumentException("No se pudo obtener la clave privada para el alias '$targetAlias'")
    val cert = ks.getCertificate(targetAlias) as? X509Certificate
      ?: throw IllegalArgumentException("No se encontró certificado X.509 para el alias '$targetAlias'")

    return LoadedKeyMaterial(targetAlias, key, cert)
  }

  private fun buildSelfSignedX509(
    keyPair: KeyPair,
    commonName: String,
    organization: String,
    country: String,
    validityYears: Int,
  ): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = Date(now - 60_000L)
    val notAfter = Date(now + validityYears.toLong() * 365L * 24L * 3600L * 1000L)
    val serial = BigInteger(64, SecureRandom()).abs().max(BigInteger.ONE)

    val sha256WithRsaAlgId = derSequence(
      derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
      byteArrayOf(0x05, 0x00),
    )

    val dnBytes = derSequence(
      derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x03)), derUtf8String(commonName))),
      derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x0A)), derUtf8String(organization))),
      derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x06)), derPrintableString(country))),
    )

    val validity = derSequence(
      derGeneralizedTime(notBefore),
      derGeneralizedTime(notAfter),
    )

    val subjectPublicKeyInfo = keyPair.public.encoded

    // v3 explicit tag [0]
    val version = derContextTag(0, derInteger(BigInteger.valueOf(2)))

    val tbsCert = derSequence(
      version,
      derInteger(serial),
      sha256WithRsaAlgId,
      dnBytes,
      validity,
      dnBytes,
      subjectPublicKeyInfo,
    )

    val sig = Signature.getInstance("SHA256withRSA")
    sig.initSign(keyPair.private)
    sig.update(tbsCert)
    val signatureBytes = sig.sign()

    val fullCert = derSequence(
      tbsCert,
      sha256WithRsaAlgId,
      derBitString(signatureBytes),
    )

    val factory = CertificateFactory.getInstance("X.509")
    return factory.generateCertificate(ByteArrayInputStream(fullCert)) as X509Certificate
  }

  private fun derTag(tag: Int, content: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(tag)
    val len = content.size
    when {
      len < 128 -> out.write(len)
      len <= 0xFF -> {
        out.write(0x81)
        out.write(len)
      }
      len <= 0xFFFF -> {
        out.write(0x82)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
      }
      else -> {
        out.write(0x83)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
      }
    }
    out.write(content)
    return out.toByteArray()
  }

  private fun derSequence(vararg items: ByteArray): ByteArray {
    val body = ByteArrayOutputStream()
    items.forEach { body.write(it) }
    return derTag(0x30, body.toByteArray())
  }

  private fun derSet(vararg items: ByteArray): ByteArray {
    val body = ByteArrayOutputStream()
    items.forEach { body.write(it) }
    return derTag(0x31, body.toByteArray())
  }

  private fun derContextTag(tagNumber: Int, content: ByteArray): ByteArray =
    derTag(0xA0 or tagNumber, content)

  private fun derInteger(value: BigInteger): ByteArray =
    derTag(0x02, value.toByteArray())

  private fun derOid(encodedOidBody: ByteArray): ByteArray =
    derTag(0x06, encodedOidBody)

  private fun derUtf8String(text: String): ByteArray =
    derTag(0x0C, text.toByteArray(StandardCharsets.UTF_8))

  private fun derPrintableString(text: String): ByteArray =
    derTag(0x13, text.toByteArray(StandardCharsets.US_ASCII))

  private fun derGeneralizedTime(date: Date): ByteArray {
    val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return derTag(0x18, fmt.format(date).toByteArray(StandardCharsets.US_ASCII))
  }

  private fun derBitString(bytes: ByteArray): ByteArray {
    val body = ByteArrayOutputStream()
    body.write(0x00) // unused bits
    body.write(bytes)
    return derTag(0x03, body.toByteArray())
  }
}

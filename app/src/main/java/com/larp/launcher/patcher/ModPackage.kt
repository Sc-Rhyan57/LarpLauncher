package com.larp.launcher.patcher

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ModPackage {

    private const val HEADER = 20
    private const val TAG = 32
    private const val MAGIC_R = 82
    private const val MAGIC_G = 71
    private const val MAGIC_M = 77
    private const val MAGIC_1 = 49

    fun extract(pkg: File, key: ByteArray, outputDir: File) {
        val encKey = hmac(key, "enc".toByteArray(Charsets.US_ASCII))
        val macKey = hmac(key, "mac".toByteArray(Charsets.US_ASCII))
        val cipherTextLength = pkg.length() - TAG
        if (cipherTextLength < HEADER + 16) throw IOException("Pacote curto demais")

        val iv = ByteArray(16)
        val mac: Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }

        BufferedInputStream(FileInputStream(pkg), 65536).use { input ->
            val header = readFully(input, HEADER)
            if (header[0] != MAGIC_R || header[1] != MAGIC_G || header[2] != MAGIC_M || header[3] != MAGIC_1) {
                throw IOException("Não é um pacote RGM1")
            }
            System.arraycopy(header, 4, iv, 0, 16)
            mac.update(header)
            val buffer = ByteArray(65536)
            var remaining = cipherTextLength - HEADER
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(65536, remaining.toInt()))
                if (read < 0) throw IOException("Pacote truncado")
                mac.update(buffer, 0, read)
                remaining -= read
            }
            val expectedTag = readFully(input, TAG)
            if (!MessageDigest.isEqual(mac.doFinal(), expectedTag)) {
                throw GeneralSecurityException("Pacote adulterado ou chave errada")
            }
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        }

        val root = outputDir.canonicalPath + File.separator
        BufferedInputStream(FileInputStream(pkg), 65536).use { input ->
            readFully(input, HEADER)
            val limited = LimitedInputStream(input, cipherTextLength - HEADER)
            ZipInputStream(CipherInputStream(limited, cipher)).use { zip ->
                val buf = ByteArray(65536)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(outputDir, entry.name.replace('\\', '/'))
                    val targetCanonical = target.canonicalPath + File.separator
                    if (!targetCanonical.startsWith(root)) {
                        throw IOException("Entrada inválida no pacote: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(65536)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(data)
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = input.read(out, filled, count - filled)
            if (n < 0) throw IOException("Pacote truncado")
            filled += n
        }
        return out
    }

    private class LimitedInputStream(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val v = source.read()
            if (v >= 0) left--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val toRead = minOf(len.toLong(), left).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) left -= n
            return n
        }
    }
}

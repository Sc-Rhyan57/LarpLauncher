package com.larp.launcher.patcher

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HashDoInterop @Inject constructor() {

    fun calcular(libil2cpp: File, unityLibs: File): String {
        val md = MessageDigest.getInstance("MD5")
        somar(md, libil2cpp)
        unityLibs.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".dll")) {
                md.update(f.name.toByteArray(Charsets.UTF_8))
                somar(md, f)
            }
        }
        md.update(VERSAO_DO_GERADOR.toByteArray(Charsets.UTF_8))
        md.update(VERSAO_DO_CPP2IL.toByteArray(Charsets.UTF_8))
        return hex(md.digest())
    }

    fun md5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        somar(md, file)
        return hex(md.digest())
    }

    private fun somar(md: MessageDigest, file: File) {
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSAO_DO_CPP2IL = "2022.1.0.0"
        const val VERSAO_DO_GERADOR = "1.5.2.0"
    }
}

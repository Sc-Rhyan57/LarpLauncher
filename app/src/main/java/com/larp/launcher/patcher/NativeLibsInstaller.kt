package com.larp.launcher.patcher

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

object NativeLibsInstaller {

    private const val TAG = "LarpLauncher"
    private const val DIR_NAME = "native"
    private const val VERSION_FILE = "version.txt"
    private const val CURRENT_VERSION = 2

    fun ensureInstalled(context: Context): File {
        val target = File(context.filesDir, DIR_NAME)
        target.mkdirs()
        val versionFile = File(target, VERSION_FILE)
        val launcherLibDir = File(context.applicationInfo.nativeLibraryDir)
        val bundled = listBundledNativeLibs(launcherLibDir)
        if (bundled.isEmpty()) {
            Log.w(TAG, "Nenhuma .so bundled encontrada em $launcherLibDir; usando dir do jogo")
            return launcherLibDir
        }
        val fingerprint = computeFingerprint(bundled) + ":" + launcherUpdateTime(context)
        val cached = versionFile.takeIf { it.isFile }?.readText().orEmpty()
        if (cached == "$CURRENT_VERSION:$fingerprint") {
            Log.i(TAG, "Native libs já instaladas em $target")
            return target
        }
        Log.i(TAG, "Instalando native libs em $target (${bundled.size} arquivos)")
        target.listFiles()?.forEach { if (it.name != VERSION_FILE) it.delete() }
        bundled.forEach { src ->
            val dest = File(target, src.name)
            src.copyTo(dest, overwrite = true)
            dest.setReadOnly()
        }
        versionFile.writeText("$CURRENT_VERSION:$fingerprint")
        return target
    }

    fun installFridaGadget(context: Context, gadget: File, agent: File, config: File? = null): File {
        val target = ensureInstalled(context)
        val destGadget = File(target, "libfrida-gadget.so")
        val destAgent = File(target, "libfrida-gadget.script.so")
        val destConfig = File(target, "libfrida-gadget.config.so")
        destGadget.delete()
        destAgent.delete()
        destConfig.delete()
        gadget.copyTo(destGadget, overwrite = true)
        agent.copyTo(destAgent, overwrite = true)
        destGadget.setReadOnly()
        destAgent.setReadOnly()
        if (config != null) {
            config.copyTo(destConfig, overwrite = true)
            destConfig.setReadOnly()
        } else {
            destConfig.writeText(DEFAULT_GADGET_CONFIG)
            destConfig.setReadOnly()
        }
        Log.i(TAG, "Frida gadget instalado em $target")
        return target
    }

    fun isFridaGadgetInstalled(context: Context): Boolean {
        val target = File(context.filesDir, DIR_NAME)
        return File(target, "libfrida-gadget.so").isFile && File(target, "libfrida-gadget.script.so").isFile
    }

    fun removeFridaGadget(context: Context) {
        val target = File(context.filesDir, DIR_NAME)
        listOf("libfrida-gadget.so", "libfrida-gadget.script.so", "libfrida-gadget.config.so").forEach {
            File(target, it).delete()
        }
    }

    fun listBundledNativeLibs(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".so") }?.toList() ?: emptyList()
    }

    // nome+tamanho não bastam: um libfusion.so recompilado com o mesmo tamanho ficava preso na
    // cópia velha de filesDir/native (que vem primeiro no search path do jogo).
    private fun launcherUpdateTime(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    private fun computeFingerprint(files: List<File>): String {
        val md = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.name }.forEach { f ->
            md.update(f.name.toByteArray())
            md.update(f.length().toString().toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    const val DEFAULT_GADGET_CONFIG = """{"interaction":{"type":"script","path":"libfrida-gadget.script.so","on_load":"resume"}}"""
}

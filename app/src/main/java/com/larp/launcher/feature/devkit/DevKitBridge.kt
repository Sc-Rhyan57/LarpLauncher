package com.larp.launcher.feature.devkit

import android.util.Log
import com.larp.launcher.domain.model.DevKitSnapshot
import com.larp.launcher.domain.model.DevKitSnapshotValue
import com.larp.launcher.domain.model.DevKitVariable
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevKitBridge @Inject constructor() {

    fun findModuleBase(moduleName: String): Long? {
        return try {
            RandomAccessFile("/proc/self/maps", "r").use { raf ->
                val line = StringBuilder()
                while (true) {
                    val b = raf.read()
                    if (b == -1) break
                    if (b == '\n'.code) {
                        val s = line.toString()
                        if (s.contains(moduleName) && s.contains("r-xp")) {
                            val first = s.substringBefore('-').trim()
                            val parsed = first.toLongOrNull(16) ?: return@use null
                            return@use parsed
                        }
                        line.setLength(0)
                    } else {
                        line.append(b.toChar())
                    }
                }
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao ler /proc/self/maps: ${t.message}")
            null
        }
    }

    fun readBytesAt(absoluteAddress: Long, size: Int): ByteArray? {
        return try {
            val bytes = ByteArray(size)
            RandomAccessFile("/proc/self/mem", "r").use { raf ->
                raf.seek(absoluteAddress)
                val read = raf.read(bytes)
                if (read != size) {
                    Log.w(TAG, "Leitura parcial em $absoluteAddress: $read/$size")
                    return null
                }
            }
            bytes
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao ler /proc/self/mem em $absoluteAddress: ${t.message}")
            null
        }
    }

    fun readVariable(variable: DevKitVariable): DevKitSnapshotValue {
        val offset = parseAddress(variable.address) ?: return failure(variable, "Endereço inválido: ${variable.address}")
        val base = findModuleBase(variable.module) ?: return failure(variable, "Módulo não mapeado: ${variable.module}")
        val absolute = base + offset
        val raw = readBytesAt(absolute, variable.size) ?: return failure(variable, "Falha ao ler ${variable.size} bytes em 0x${absolute.toString(16)}")
        val hex = raw.toHex()
        val decoded = decode(raw, variable.format)
        return DevKitSnapshotValue(
            variableId = variable.id,
            name = variable.name,
            address = variable.address,
            module = variable.module,
            rawHex = hex,
            decoded = decoded,
            absoluteAddress = "0x${absolute.toString(16)}"
        )
    }

    fun snapshot(variables: List<DevKitVariable>, id: String): DevKitSnapshot {
        val values = variables.filter { it.enabled }.map { readVariable(it) }
        return DevKitSnapshot(
            id = id,
            timestamp = System.currentTimeMillis(),
            values = values
        )
    }

    private fun failure(variable: DevKitVariable, message: String): DevKitSnapshotValue =
        DevKitSnapshotValue(
            variableId = variable.id,
            name = variable.name,
            address = variable.address,
            module = variable.module,
            rawHex = "",
            decoded = "",
            absoluteAddress = "",
            error = message
        )

    private fun parseAddress(text: String): Long? {
        val trimmed = text.trim().removePrefix("0x").removePrefix("0X")
        return trimmed.toLongOrNull(16)
    }

    private fun decode(bytes: ByteArray, format: String): String = when (format) {
        "hex" -> bytes.toHex()
        "int32" -> {
            if (bytes.size < 4) "?"
            else java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toString()
        }
        "int64" -> {
            if (bytes.size < 8) "?"
            else java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).long.toString()
        }
        "float" -> {
            if (bytes.size < 4) "?"
            else java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toString()
        }
        "double" -> {
            if (bytes.size < 8) "?"
            else java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).double.toString()
        }
        "ascii" -> String(bytes, Charsets.US_ASCII).filter { it.isLetterOrDigit() || it.isWhitespace() || it in "()[]{}._-" }
        else -> bytes.toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "LarpDevKit"
    }
}

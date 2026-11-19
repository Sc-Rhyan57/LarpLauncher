package com.larp.launcher.diagnostics

import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class Diario private constructor(private val context: Context) {

    private val startTime = System.currentTimeMillis()
    private val steps = mutableListOf<Pair<String, Long>>()
    private val data: JsonObject = buildJsonObject {
        put("app", "larp-launcher")
        put("os", Build.VERSION.RELEASE)
        put("device", "${Build.MANUFACTURER}/${Build.MODEL}")
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        put("startedAt", startTime)
    }

    suspend fun step(name: String) = withContext(Dispatchers.IO) {
        steps.add(name to System.currentTimeMillis())
        save()
    }

    suspend fun finish(reason: String = "ok"): String = withContext(Dispatchers.IO) {
        val endTime = System.currentTimeMillis()
        val payload = buildJsonObject {
            data.forEach { (k, v) -> put(k, v) }
            put("endedAt", endTime)
            put("durationMs", endTime - startTime)
            put("exitReason", reason)
            put("steps", buildJsonArray {
                steps.forEach { (name, ts) -> add(buildJsonObject {
                    put("name", name)
                    put("at", ts)
                    put("deltaFromStart", ts - startTime)
                }) }
            })
            put("exitReasons", buildJsonArray {
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { info: ApplicationExitInfo ->
                            add(buildJsonObject {
                                put("reason", info.reason)
                                put("timestamp", info.timestamp)
                                put("description", info.description?.toString() ?: "")
                            })
                        }
                    }
                }
            })
        }
        val file = File(context.filesDir, "diag/atual.json")
        file.parentFile?.mkdirs()
        file.writeText(payload.toString())
        payload.toString()
    }

    private suspend fun save() {
        val file = File(context.filesDir, "diag/parcial.json")
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject {
            data.forEach { (k, v) -> put(k, v) }
            put("lastStep", steps.lastOrNull()?.first ?: "")
            put("stepCount", steps.size)
        }.toString())
    }

    companion object {
        fun begin(context: Context): Diario = Diario(context)
    }
}

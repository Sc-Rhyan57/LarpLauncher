package com.larp.launcher.lua

import java.net.HttpURLConnection
import java.net.URL

object LarpHttp {
    @JvmStatic
    fun request(url: String, method: String, body: ByteArray?, headers: String): ByteArray? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "LarpLua/1.0")
            for (line in headers.split('\n')) {
                val i = line.indexOf(':')
                if (i > 0) c.setRequestProperty(line.substring(0, i).trim(), line.substring(i + 1).trim())
            }
            if (body != null && body.isNotEmpty()) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            val head = "$code\n".toByteArray()
            head + data
        } catch (t: Throwable) {
            null
        }
    }
}

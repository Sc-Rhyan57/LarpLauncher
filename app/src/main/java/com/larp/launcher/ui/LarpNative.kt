package com.larp.launcher.ui

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

object LarpNative {

    private const val TAG = "LarpUi"
    private const val BASE_PORT = 38417
    private const val PORT_SPAN = 10

    @Volatile private var started = false
    @Volatile private var out: OutputStream? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "larpui-write").apply { isDaemon = true } }

    @Synchronized
    fun load(): Boolean {
        if (started) return true
        val loopback = InetAddress.getByName("127.0.0.1")
        var server: ServerSocket? = null
        for (i in 0 until PORT_SPAN) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(loopback, BASE_PORT + i), 4)
                server = s
                break
            } catch (t: Throwable) {
                Log.w(TAG, "porta ${BASE_PORT + i} indisponivel: ${t.message}")
            }
        }
        if (server == null) return false
        started = true
        val bound = server
        Thread({ acceptLoop(bound) }, "larpui-accept").apply { isDaemon = true; start() }
        Log.i(TAG, "ponte larpui ouvindo em ${bound.localPort}")
        return true
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            try {
                val client = server.accept()
                client.tcpNoDelay = true
                out = client.getOutputStream()
                Log.i(TAG, "mod conectado")
                readLoop(client)
            } catch (t: Throwable) {
                Log.w(TAG, "ponte: ${t.message}")
            } finally {
                out = null
            }
        }
    }

    private fun readLoop(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            try {
                when (line[0]) {
                    'S' -> LarpOverlay.send(line.substring(1))
                    'D' -> {
                        val parts = line.substring(1).split('\t')
                        if (parts.size >= 4) {
                            val raw = Base64.decode(parts[2], Base64.NO_WRAP)
                            val ints = IntArray(raw.size / 4)
                            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
                            val texts = String(Base64.decode(parts[3], Base64.NO_WRAP), Charsets.UTF_8)
                            LarpOverlay.draw(parts[0], parts[1], ints, texts)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "comando invalido: ${t.message}")
            }
        }
    }

    fun emit(layer: String, node: String, event: String, value: String) {
        val stream = out ?: return
        val encoded = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bytes = "E$layer\t$node\t$event\t$encoded\n".toByteArray(Charsets.UTF_8)
        writer.execute {
            try {
                synchronized(this) {
                    stream.write(bytes)
                    stream.flush()
                }
            } catch (t: Throwable) {
                if (out === stream) out = null
            }
        }
    }
}

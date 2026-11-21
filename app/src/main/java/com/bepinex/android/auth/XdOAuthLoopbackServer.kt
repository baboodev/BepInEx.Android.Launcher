package com.bepinex.android.auth

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PC-style OAuth callback: [System.Net.HttpListener] equivalent.
 * `WebAuth.StartListener` binds loopback and waits for
 * `http://127.0.0.1:<port>/?token=…&grantType=…&state=…`.
 */
class XdOAuthLoopbackServer(
    private val onResult: (Uri) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    @Volatile var port: Int = -1
        private set

    val redirectUri: String
        get() = "http://127.0.0.1:$port/"

    fun start(): Boolean {
        return try {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            ss.soTimeout = ACCEPT_TIMEOUT_MS
            serverSocket = ss
            port = ss.localPort
            Thread({ acceptLoop(ss) }, "XdOAuth-loopback").apply { isDaemon = true }.start()
            Log.i(TAG, "listening on $redirectUri")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to bind loopback", t)
            false
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        Log.i(TAG, "stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopped.get()) {
            try {
                val socket = ss.accept()
                handleClient(socket)
                // One successful OAuth callback is enough.
                stop()
                return
            } catch (_: SocketTimeoutException) {
                // keep waiting until stop()
            } catch (t: Throwable) {
                if (!stopped.get()) {
                    Log.w(TAG, "accept failed: ${t.message}")
                }
                return
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = input.readLine() ?: return
            // GET /?token=… HTTP/1.1
            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) {
                writeResponse(socket, 405, "Method Not Allowed", "Only GET")
                return
            }
            val pathAndQuery = parts[1]
            // Drain headers
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }

            // Keep raw query (with '+') — Uri.getQueryParameter would corrupt tokens.
            val uri = Uri.parse("http://127.0.0.1$pathAndQuery")
            Log.i(TAG, "callback $uri")
            writeResponse(
                socket,
                200,
                "OK",
                "<html><body><h2>Login complete</h2><p>You can close this tab and return to the game.</p></body></html>",
            )
            mainHandler.post { onResult(uri) }
        } catch (t: Throwable) {
            Log.e(TAG, "handleClient failed", t)
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun writeResponse(socket: Socket, code: Int, status: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val out = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        out.write("HTTP/1.1 $code $status\r\n")
        out.write("Content-Type: text/html; charset=utf-8\r\n")
        out.write("Content-Length: ${bytes.size}\r\n")
        out.write("Connection: close\r\n")
        out.write("\r\n")
        out.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    companion object {
        private const val TAG = "BugtopiaXdOAuth"
        private const val ACCEPT_TIMEOUT_MS = 1_000
    }
}

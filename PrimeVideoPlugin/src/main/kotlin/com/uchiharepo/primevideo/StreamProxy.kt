package com.uchiharepo.primevideo

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

object StreamProxy {
    private var port: Int = 0
    @Volatile private var running: Boolean = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var streamHeaders: Map<String, String> = emptyMap()
    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    @Synchronized
    fun start(headers: Map<String, String>): Int {
        streamHeaders = headers
        if (running && port > 0) return port

        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        port = ss.localPort
        running = true

        executor.execute {
            while (running) {
                try {
                    val sock = ss.accept()
                    executor.execute { handle(sock) }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return port
    }

    fun localUrl(url: String): String {
        val enc = b64Enc.encodeToString(url.toByteArray(Charsets.UTF_8))
        return "http://127.0.0.1:$port/p/$enc"
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 60000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val reqLine = readLine(input) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeResponse(output, 400, "text/plain", "Bad Request".toByteArray())
                return
            }

            val path = parts[1]
            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            if (!path.startsWith("/p/")) {
                writeResponse(output, 404, "text/plain", "Not Found".toByteArray())
                return
            }

            val upstreamUrl = String(b64Dec.decode(path.removePrefix("/p/")), Charsets.UTF_8)
            val u = URL(upstreamUrl)

            // সেগমেন্ট রিকোয়েস্ট হলে সাথে সাথে সার্ভ করে return করবে (ডাবল ফেচ ঠেকাবে)
            if (!u.path.endsWith(".m3u8") && !u.path.endsWith(".mpd")) {
                relayStream(upstreamUrl, rangeHeader, output)
                output.flush()
                return // ফিক্স: return না দিলে ডাবল ফেচ হয়ে Abuse error দিত
            }

            // প্লেলিস্ট হলে লিংকের ডোমেইন লোকাল প্রক্সিতে রূপান্তর করবে
            val (code, _, bytes) = fetchBytes(upstreamUrl, null)
            if (code != 200) {
                writeResponse(output, code, "text/plain", "Upstream error".toByteArray())
                return
            }

            val body = String(bytes, Charsets.UTF_8)
            val rewritten = rewritePlaylist(body, upstreamUrl)
            val mime = if (u.path.endsWith(".mpd")) "application/dash+xml" else "application/vnd.apple.mpegurl"
            writeResponse(output, 200, mime, rewritten.toByteArray(Charsets.UTF_8))
        }
    }

    private fun relayStream(upstream: String, range: String?, output: BufferedOutputStream) {
        val b = Request.Builder().url(upstream).get()
        streamHeaders.forEach { (k, v) -> b.header(k, v) }
        if (!range.isNullOrBlank()) b.header("Range", range)

        val response = client.newCall(b.build()).execute()
        response.use { resp ->
            val code = resp.code
            val body = resp.body ?: return
            val length = body.contentLength()
            val ctype = resp.header("Content-Type") ?: "video/mp2t"

            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(code).append(" OK\r\n")
            sb.append("Content-Type: ").append(ctype).append("\r\n")
            if (length > 0) sb.append("Content-Length: ").append(length).append("\r\n")
            resp.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
            sb.append("Connection: close\r\n\r\n")

            output.write(sb.toString().toByteArray(Charsets.UTF_8))
            body.byteStream().use { stream -> stream.copyTo(output) }
        }
    }

    private fun fetchBytes(url: String, range: String?): Triple<Int, String, ByteArray> {
        val b = Request.Builder().url(url).get()
        streamHeaders.forEach { (k, v) -> b.header(k, v) }
        if (!range.isNullOrBlank()) b.header("Range", range)

        val response = client.newCall(b.build()).execute()
        response.use { resp ->
            val code = resp.code
            val ctype = resp.header("Content-Type") ?: ""
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return Triple(code, ctype, bytes)
        }
    }

    private fun rewritePlaylist(body: String, baseUrl: String): String {
        val base = URL(baseUrl)
        val out = StringBuilder()
        for (line in body.lines()) {
            val t = line.trim()
            if (t.startsWith("#EXTM3U") || t.startsWith("#EXT-X-") || t.isEmpty()) {
                out.append(line).append("\n")
            } else {
                val resolved = URL(base, t).toString()
                out.append(localUrl(resolved)).append("\n")
            }
        }
        return out.toString()
    }

    private fun writeResponse(out: BufferedOutputStream, code: Int, mime: String, data: ByteArray) {
        val header = "HTTP/1.1 $code OK\r\nContent-Type: $mime\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) out.append(c.toChar())
        }
        return out.toString()
    }
}

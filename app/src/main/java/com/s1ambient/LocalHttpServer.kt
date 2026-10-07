package com.s1ambient

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
internal data class HttpResponse(val status: Int, val type: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, body: String) = HttpResponse(status, "application/json; charset=utf-8", body.toByteArray())
    }
}

/** Small HTTP/1.x server: bounded headers/body, no uploads, no chunking or keep-alive. */
internal class LocalHttpServer(
    private val address: InetAddress,
    private val port: Int,
    private val assets: Map<String, HttpResponse>,
    private val handle: (HttpRequest) -> HttpResponse
) {
    private val server = ServerSocket()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    @Volatile private var closed = false
    fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, port), 8)
        Thread({
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients.add(socket)
                try { workers.execute { serve(socket) } }
                catch (_: Exception) { clients.remove(socket); runCatching { socket.close() } }
            }
        }, "s1-http-accept").start()
    }
    fun close() {
        closed = true
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 3000
                val input = BufferedInputStream(socket.getInputStream())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var headerBytes = 0
                fun readByte(): Int {
                    require(System.nanoTime() <= deadline) { "Request timed out" }
                    return input.read()
                }
                fun line(): String {
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val b = readByte()
                        require(b != -1 && ++headerBytes <= 8192) { "Invalid headers" }
                        if (b == 10) break
                        out.write(b)
                    }
                    val bytes = out.toByteArray()
                    require(bytes.isNotEmpty() && bytes.last() == 13.toByte()) { "CRLF required" }
                    return String(bytes, 0, bytes.size - 1, StandardCharsets.US_ASCII)
                }
                val first = line().split(' ')
                require(first.size == 3 && first[2] in listOf("HTTP/1.0", "HTTP/1.1"))
                val method = first[0]
                val path = first[1]
                require(path.startsWith('/') && !path.contains('#') && path.length <= 512)
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val text = line()
                    if (text.isEmpty()) break
                    val colon = text.indexOf(':')
                    require(colon > 0)
                    val name = text.substring(0, colon).lowercase()
                    require(name.matches(Regex("[a-z0-9-]+")) && name !in headers)
                    headers[name] = text.substring(colon + 1).trim()
                }
                val host = "${address.hostAddress}:$port"
                if (headers["host"] != host || headers["origin"]?.let { it != "http://$host" } == true ||
                    headers["sec-fetch-site"] == "cross-site") {
                    send(socket, HttpResponse.json(403, "{\"error\":\"Origin or host rejected\"}")); return
                }
                require("transfer-encoding" !in headers && "expect" !in headers)
                val length = headers["content-length"]?.let {
                    require(it.matches(Regex("[0-9]{1,5}"))); it.toInt()
                } ?: 0
                if (length > 16384) { send(socket, HttpResponse.json(413, "{\"error\":\"Request too large\"}")); return }
                if (method in listOf("POST", "PUT") &&
                    headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
                    send(socket, HttpResponse.json(415, "{\"error\":\"JSON required\"}")); return
                }
                val body = ByteArray(length)
                for (i in body.indices) { val byte = readByte(); require(byte >= 0); body[i] = byte.toByte() }
                val response = if (path.startsWith("/api/")) handle(HttpRequest(method, path, headers, String(body, Charsets.UTF_8)))
                    else if (method == "GET") assets[path] ?: HttpResponse.json(404, "{\"error\":\"Not found\"}")
                    else HttpResponse.json(405, "{\"error\":\"Method not allowed\"}")
                send(socket, response)
            } catch (_: Exception) {
                runCatching { send(socket, HttpResponse.json(400, "{\"error\":\"Invalid request\"}")) }
            } finally { clients.remove(socket) }
        }
    }
    private fun send(socket: Socket, response: HttpResponse) {
        val reason = when (response.status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 429 -> "Too Many Requests"
            else -> "Service Unavailable"
        }
        val header = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.type}\r\nContent-Length: ${response.body.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(response.body); flush() }
    }
}

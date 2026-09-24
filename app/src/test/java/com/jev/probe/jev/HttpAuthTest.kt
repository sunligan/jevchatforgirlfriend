package com.jev.probe.jev

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HttpAuthTest {
    @Test(timeout = 10000) fun realHttpTransportUsesAnthropicHeadersNotBearer() {
        verifyAuth(HttpJson.AuthStyle.ANTHROPIC_API_KEY)
    }
    @Test(timeout = 10000) fun realHttpTransportUsesOpenAiBearerNotApiKey() {
        verifyAuth(HttpJson.AuthStyle.BEARER)
    }
    private fun verifyAuth(style: HttpJson.AuthStyle) {
        val seen = AtomicReference<Map<String, String?>>()
        val server = RawHttpServer { request, out ->
            seen.set(mapOf("path" to request.path, "bearer" to request.headers["authorization"],
                "key" to request.headers["x-api-key"], "version" to request.headers["anthropic-version"]))
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}".toByteArray())
            out.flush()
        }
        server.start()
        try {
            val headers = if (style == HttpJson.AuthStyle.ANTHROPIC_API_KEY) mapOf("anthropic-version" to ChatWire.ANTHROPIC_VERSION) else emptyMap()
            val result = HttpJson.post(server.url("/test"), "local-test-token", JSONObject().put("message", "中文"), "test", headers, style)
            assertTrue(result.getBoolean("ok"))
            assertEquals("/test", seen.get()["path"])
            if (style == HttpJson.AuthStyle.ANTHROPIC_API_KEY) {
                assertNull(seen.get()["bearer"])
                assertEquals("local-test-token", seen.get()["key"])
                assertEquals("2023-06-01", seen.get()["version"])
            } else {
                assertEquals("Bearer local-test-token", seen.get()["bearer"])
                assertNull(seen.get()["key"])
                assertNull(seen.get()["version"])
            }
        } finally { server.close() }
    }
    @Test(timeout = 10000) fun redirectDoesNotForwardCredentials() {
        val seenPath = AtomicReference<String?>(null)
        val server = RawHttpServer { request, out ->
            seenPath.set(request.path)
            if (request.path == "/redirect") {
                out.write("HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
            }
            out.flush()
        }
        server.start()
        try {
            val e = assertThrows(ApiException::class.java) {
                HttpJson.post(server.url("/redirect"), "local-test-token", JSONObject(), "test")
            }
            assertEquals(302, e.status)
            assertEquals("/redirect", seenPath.get())
        } finally { server.close() }
    }
}

private data class RawRequest(val path: String, val headers: Map<String, String>)
private class RawHttpServer(private val handler: (RawRequest, java.io.OutputStream) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0)
    private var thread: Thread? = null
    fun start() {
        thread = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                    val first = reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val split = line.indexOf(':')
                        if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                    }
                    val path = first.split(' ').getOrNull(1) ?: "/"
                    handler(RawRequest(path, headers), socket.getOutputStream())
                }
            } catch (_: Exception) { }
        }.also { it.start() }
    }
    fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
    override fun close() { server.close(); thread?.join(2000) }
}

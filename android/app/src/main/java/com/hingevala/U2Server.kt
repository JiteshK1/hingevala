package com.hingevala

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The uiautomator2 server (u2.jar, bundled), started through the phone's adb exactly as
 * the Python bot starts it from a computer. The app then talks to it over
 * http://127.0.0.1:9008 on the phone itself.
 */
class U2Server(private val ctx: Context, private val adb: Adb) {
    private val port = 9008
    private var process: AdbStream? = null

    /** Push u2.jar if it changed and start the server unless it's already answering. */
    fun ensureRunning() {
        if (alive()) return
        pushJar()
        process?.let { runCatching { it.close() } }
        // The server lives as long as this stream stays open.
        process = adb.shellStream("CLASSPATH=$JAR app_process / com.wetest.uia2.Main -p $port")
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (alive()) return
            if (process?.isClosed == true) throw RuntimeException("uiautomator server quit while starting")
            Thread.sleep(500)
        }
        throw RuntimeException("uiautomator server didn't start within 30s")
    }

    fun stop() {
        process?.let { runCatching { it.close() } }
        process = null
    }

    private fun pushJar() {
        val bytes = ctx.assets.open("u2.jar").use { it.readBytes() }
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        if (md5 in adb.shell("md5sum $JAR 2>/dev/null")) return
        // The shell user can read this app's folder on shared storage, not its private one.
        val dir = ctx.getExternalFilesDir(null) ?: throw RuntimeException("Shared storage isn't available")
        val staged = File(dir, "u2.jar").apply { writeBytes(bytes) }
        adb.shell("cp '${staged.absolutePath}' $JAR")
        if (md5 !in adb.shell("md5sum $JAR 2>/dev/null")) throw RuntimeException("Couldn't copy u2.jar to the phone")
    }

    fun alive(): Boolean = try {
        request("GET", "/ping", null, 3_000) == "pong"
    } catch (_: Exception) {
        false
    }

    /** A JSON-RPC call to the server, e.g. rpc("click", 100, 200). */
    fun rpc(method: String, vararg params: Any?, timeoutMs: Int = 20_000): Any? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", JSONArray(params.map { it ?: JSONObject.NULL }))
        val reply = JSONObject(request("POST", "/jsonrpc/0", payload.toString(), timeoutMs))
        reply.optJSONObject("error")?.let { throw RuntimeException("uiautomator $method failed: ${it.optString("message")}") }
        return reply.opt("result")
    }

    private fun request(method: String, path: String, body: String?, timeoutMs: Int): String {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 3_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept-Encoding", "") // the server leaks with gzip
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode != 200) throw RuntimeException("uiautomator HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val JAR = "/data/local/tmp/u2.jar"
    }
}

package com.ontheway.app.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 실시간 DB 클라이언트 (Firebase Realtime Database REST + 스트리밍 방식).
 *
 * 데모에서는 PC의 중계 서버(relay/server.js)에, 실제 서비스에서는 Firebase 주소에 붙는다.
 * 둘 다 같은 규칙이라 주소만 바꾸면 된다. 구글 Play 서비스가 없어도 동작한다 (차량용 안드로이드 대비).
 *
 *  - subscribe(path): 그 경로 아래 값이 바뀔 때마다 [data]가 새 값으로 바뀜
 *  - put / patch / post / delete: 쓰기
 */
class RtdbClient(private val scope: CoroutineScope) {
    private val tag = "OnTheWay/Sync"

    @Volatile var baseUrl: String = ""
        private set

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 구독 중인 경로의 현재 값 (JSONObject 사본) */
    private val _data = MutableStateFlow<JSONObject?>(null)
    val data: StateFlow<JSONObject?> = _data.asStateFlow()

    private var tree: Any? = null
    private var streamJob: Job? = null
    private var conn: HttpURLConnection? = null

    fun connect(baseUrl: String, path: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        streamJob?.cancel()
        runCatching { conn?.disconnect() }
        streamJob = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive) {
                try {
                    stream(path)
                    backoff = 1000L
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(tag, "stream error: ${e.message}")
                }
                _connected.value = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun disconnect() {
        streamJob?.cancel(); streamJob = null
        runCatching { conn?.disconnect() }
        _connected.value = false
    }

    private fun stream(path: String) {
        val c = (URL(url(path)).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 5_000
            readTimeout = 60_000 // 서버가 25초마다 keep-alive를 보냄
            instanceFollowRedirects = true
        }
        conn = c
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                var event: String? = null
                val data = StringBuilder()
                while (true) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                        line.isEmpty() -> {
                            handle(event, data.toString())
                            event = null; data.setLength(0)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun handle(event: String?, payload: String) {
        when (event) {
            "put", "patch" -> {
                val msg = JSONObject(payload)
                val p = msg.optString("path", "/")
                val d = msg.opt("data")
                synchronized(this) {
                    if (event == "put") applyAt(p, d)
                    else (d as? JSONObject)?.let { obj -> obj.keys().forEach { k -> applyAt(p.trimEnd('/') + "/" + k, obj.opt(k)) } }
                    _data.value = (tree as? JSONObject)?.let { JSONObject(it.toString()) }
                }
                _connected.value = true
            }
            "keep-alive" -> _connected.value = true
            "cancel", "auth_revoked" -> throw IllegalStateException("stream $event")
        }
    }

    private fun applyAt(path: String, value: Any?) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val v = if (value == JSONObject.NULL) null else value
        if (parts.isEmpty()) { tree = v; return }
        if (tree !is JSONObject) tree = JSONObject()
        var n = tree as JSONObject
        for (k in parts.dropLast(1)) {
            val next = n.optJSONObject(k) ?: JSONObject().also { n.put(k, it) }
            n = next
        }
        if (v == null) n.remove(parts.last()) else n.put(parts.last(), v)
    }

    // ---------- 쓰기 ----------

    suspend fun put(path: String, value: Any?) = send("PUT", path, value)
    suspend fun patch(path: String, values: Map<String, Any?>) = send("PATCH", path, JSONObject().apply { values.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
    suspend fun delete(path: String) = send("DELETE", path, null)
    /** 새 항목 추가. 서버가 만든 id를 돌려준다. */
    suspend fun post(path: String, value: Any?): String? =
        (send("POST", path, value) as? JSONObject)?.optString("name")

    private suspend fun send(method: String, path: String, body: Any?): Any? = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) return@withContext null
        try {
            val c = (URL(url(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = if (method == "PATCH") "POST" else method
                if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connectTimeout = 5_000; readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (method != "DELETE" && method != "GET") {
                c.doOutput = true
                c.outputStream.use { it.write(toJson(body).toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            if (code !in 200..299) { Log.w(tag, "$method $path → $code $text"); return@withContext null }
            text?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
        } catch (e: Exception) {
            Log.w(tag, "$method $path failed: ${e.message}")
            null
        }
    }

    private fun url(path: String) = baseUrl + "/" + path.trim('/') + ".json"

    private fun toJson(v: Any?): String = when (v) {
        null -> "null"
        is JSONObject, is JSONArray -> v.toString()
        is Map<*, *> -> JSONObject(v).toString()
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }
}

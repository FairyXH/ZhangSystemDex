package io.github.fairyxh.zhangsystemdex.core

import io.github.fairyxh.zhangsystemdex.core.rubbish.JsonBuilder
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishCleaner
import io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishGuard
import io.github.fairyxh.zhangsystemdex.core.rubbish.UserGuardRules
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Local HTTP backend for the module WebUI.
 *
 * Design goal (user requirement): Main.dex hosts ALL data reads/writes and the
 * WebUI only renders. The daemon binds a TCP server to 127.0.0.1 ONLY (never on
 * an external interface), on a FIXED port declared in config.conf (`http_port`).
 *
 * Every endpoint returns JSON (UTF-8) and adds permissive CORS headers so the
 * page also works when opened as a local file (file:// origin) inside any
 * WebView, independent of the host JS bridge (KSU.exec / window.$), which is
 * what previously made the UI hang forever on "加载中…".
 *
 * This server is intentionally tiny and dependency-free: a single accept loop
 * on its own thread, one request per connection, no keep-alive. It is a control
 * plane for the module's own config, not a general purpose web server.
 */
class HttpBackend(
    ctx: DexContext,
    private val port: Int,
) : DaemonLoop(ctx, 1000L, pauseAware = false) {

    override val name: String = "HttpBackend"

    private var server: ServerSocket? = null

    @Volatile
    private var boundPort: Int = port

    /** The port actually bound (useful when `port` is 0 -> ephemeral). */
    fun activePort(): Int = boundPort

    override fun onStart() {
        try {
            // Bind to loopback only: never reachable from Wi-Fi/mobile network.
            val ss = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
            server = ss
            boundPort = ss.localPort
            Logger.i(name, "HTTP 后端已监听 http://127.0.0.1:$boundPort （仅回环）")
        } catch (t: Throwable) {
            Logger.e(name, "HTTP 后端监听失败（端口 $port）", t)
        }
    }

    override fun onStop() {
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        Logger.i(name, "HTTP 后端已停止")
    }

    override fun tick() {
        val ss = server ?: run {
            // Bind failed earlier (port in use). Retry lazily every tick.
            onStart()
            return
        }
        try {
            val sock = ss.accept()
            try {
                handle(sock)
            } catch (t: Throwable) {
                Logger.w(name, "处理请求失败: ${t.message}")
            } finally {
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            // accept() throws when the socket is closed on shutdown: ignore.
            if (server != null) Logger.w(name, "accept 失败: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // Request handling
    // ------------------------------------------------------------------

    private fun handle(sock: Socket) {
        sock.soTimeout = 10000
        val input: InputStream = BufferedInputStream(sock.getInputStream())
        val out: OutputStream = sock.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        // Read headers, tracking Content-Length for POST bodies.
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val hk = line.substring(0, idx).trim().lowercase()
                val hv = line.substring(idx + 1).trim()
                if (hk == "content-length") contentLength = hv.toIntOrNull() ?: 0
            }
        }

        val body = if (method == "POST" && contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, StandardCharsets.UTF_8)
        } else ""

        // Split path and query string.
        val qIdx = rawTarget.indexOf('?')
        val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
        val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()

        val result: String = try {
            route(method, path, query, body)
        } catch (t: Throwable) {
            Logger.e(name, "路由异常 $path", t)
            jsonError("内部错误: ${t.message}")
        }
        respond(out, 200, result)
    }

    private fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String,
    ): String {
        return when (path) {
            "/", "/index.html" -> jsonOk("\"zhangsystemdex-http\"")
            "/api/ping" -> jsonOk("\"pong\"")
            "/api/paths" -> apiPaths()
            "/api/read" -> apiRead(query)
            "/api/write" -> apiWrite(method, body)
            "/api/bglist/read" -> apiReadFile(File(ctx.config.rootDir, "power_bg_stop_list.conf"))
            "/api/bglist/write" -> apiWriteFile(method, body, File(ctx.config.rootDir, "power_bg_stop_list.conf"))
            "/api/powerstatus" -> apiPowerStatus()
            "/api/switch/set" -> apiSwitchSet(method, body)
            "/api/reload" -> apiReload()
            // ===== 垃圾清理 =====
            "/api/rubbish/rules" -> apiRubbishRules()
            "/api/rubbish/scan" -> apiRubbishScan(method, body, dryRun = true)
            "/api/rubbish/clean" -> apiRubbishScan(method, body, dryRun = false)
            "/api/rubbish/status" -> apiRubbishStatus()
            "/api/rubbish/history" -> apiRubbishHistory(query)
            "/api/rubbish/guard/read" -> apiRubbishGuardRead()
            "/api/rubbish/guard/write" -> apiRubbishGuardWrite(method, body)
            else -> jsonError("未知接口: $path")
        }
    }

    // ------------------------------------------------------------------
    // Endpoints
    // ------------------------------------------------------------------

    /** Page bootstrap: absolute paths + fixed port, so the UI need not shell out. */
    private fun apiPaths(): String {
        val root = ctx.config.rootDir
        val mod = ctx.modDir
        val body = StringBuilder()
        body.append("{")
        body.append("\"ok\":true,")
        body.append("\"rootDir\":").append(q(root)).append(',')
        body.append("\"modDir\":").append(q(mod)).append(',')
        body.append("\"switchesFile\":").append(q("$root/switches.conf")).append(',')
        body.append("\"configFile\":").append(q("$mod/config.conf")).append(',')
        body.append("\"bgListFile\":").append(q("$root/power_bg_stop_list.conf")).append(',')
        body.append("\"port\":").append(boundPort).append(',')
        body.append("\"pid\":").append(android.os.Process.myPid())
        body.append("}")
        return jsonRaw(body.toString())
    }

    /** Read switches.conf (or any file under an allow-listed base) as text. */
    private fun apiRead(query: Map<String, String>): String {
        val target = query["path"]?.takeIf { it.isNotBlank() }
            ?: return apiReadFile(File(ctx.config.rootDir, "switches.conf"))
        return apiReadFile(File(target))
    }

    private fun apiReadFile(f: File): String {
        val safe = guard(f)
        if (safe != null) return safe
        if (!f.exists() || !f.isFile) {
            return jsonRaw("{\"ok\":false,\"code\":1,\"content\":\"\",\"message\":\"文件不存在\"}")
        }
        val text = try {
            f.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return jsonError("读取失败: ${t.message}")
        }
        val body = StringBuilder()
        body.append("{\"ok\":true,\"code\":0,\"path\":").append(q(f.absolutePath))
        body.append(",\"size\":").append(f.length())
        body.append(",\"content\":").append(q(text)).append("}")
        return jsonRaw(body.toString())
    }

    /** Write switches.conf / bg list atomically (temp + rename), mirroring the page's old shell logic. */
    private fun apiWrite(method: String, body: String): String {
        if (method != "POST") return jsonError("需要 POST")
        val obj = MiniJson.parseObject(body) ?: return jsonError("请求体不是 JSON")
        val path = obj["path"]?.takeIf { it.isNotBlank() }
            ?: return jsonError("缺少 path")
        val content = obj["content"] ?: ""
        return apiWriteFile(method, body, File(path))
    }

    private fun apiWriteFile(method: String, body: String, f: File): String {
        if (method != "POST") return jsonError("需要 POST")
        val safe = guard(f)
        if (safe != null) return safe
        // Body may be either {"content": "..."} or a raw text payload.
        val content = if (body.trimStart().startsWith("{")) {
            MiniJson.parseObject(body)?.get("content") ?: ""
        } else body

        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".http.tmp")
            FileOutputStream(tmp).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            // Atomic replace.
            if (f.exists() && !f.delete()) {
                // fall through; renameTo below may still succeed on same fs
            }
            if (!tmp.renameTo(f)) {
                // Cross-device or failure: copy + delete.
                f.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
            Logger.i(name, "已写入 ${f.absolutePath} (${content.length} chars)")
            jsonRaw("{\"ok\":true,\"code\":0,\"message\":\"OK\"}")
        } catch (t: Throwable) {
            jsonError("写入失败: ${t.message}")
        }
    }

    /** Toggle a single switch by editing switches.conf in place (line-preserving, last-wins). */
    private fun apiSwitchSet(method: String, body: String): String {
        if (method != "POST") return jsonError("需要 POST")
        val obj = MiniJson.parseObject(body) ?: return jsonError("请求体不是 JSON")
        val key = obj["key"]?.takeIf { it.isNotBlank() } ?: return jsonError("缺少 key")
        val value = obj["value"] ?: ""
        val f = File(ctx.config.rootDir, "switches.conf")
        val safe = guard(f)
        if (safe != null) return safe
        if (!f.exists()) return jsonError("switches.conf 不存在")

        return try {
            val lines = f.readText(Charsets.UTF_8).replace("\r\n", "\n").split("\n").toMutableList()
            // Update the LAST occurrence (matches daemon last-wins semantics).
            var last = -1
            for (i in lines.indices) {
                val p = parseSwitchLine(lines[i]) ?: continue
                if (p.first == key) last = i
            }
            if (last >= 0) {
                val p = parseSwitchLine(lines[last])!!
                val cmt = if (p.second.isNotBlank()) " " + p.second else ""
                lines[last] = "$key=$value$cmt"
            } else {
                lines.add("$key=$value")
            }
            val text = lines.joinToString("\n")
            val tmp = File(f.parentFile, f.name + ".http.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
            jsonRaw("{\"ok\":true,\"code\":0,\"message\":\"OK\"}")
        } catch (t: Throwable) {
            jsonError("保存失败: ${t.message}")
        }
    }

    /** Battery + power facts sourced from the framework (no shell needed). */
    private fun apiPowerStatus(): String {
        val level = readBatteryInt("EXTRA_LEVEL", -1)
        val status = readBatteryInt("EXTRA_STATUS", -1)
        val plugged = readBatteryInt("EXTRA_PLUGGED", -1)
        val charging = (status == 2 || status == 5 || plugged > 0)
        val body = StringBuilder()
        body.append("{\"ok\":true,\"level\":").append(level)
        body.append(",\"status\":").append(status)
        body.append(",\"plugged\":").append(plugged)
        body.append(",\"charging\":").append(charging).append("}")
        return jsonRaw(body.toString())
    }

    private fun readBatteryInt(key: String, def: Int): Int {
        // Try framework first (StickyBroadcast intent via shell-free API is not
        // available in app_process), so fall back to `dumpsys battery` parsing.
        val out = ShellExecutor.run("dumpsys battery 2>/dev/null") ?: return def
        val map = mapOf(
            "EXTRA_LEVEL" to "level",
            "EXTRA_STATUS" to "status",
            "EXTRA_PLUGGED" to "plugged",
        )
        val field = map[key] ?: return def
        val m = Regex("$field:\\s*(\\d+)").find(out) ?: return def
        return m.groupValues[1].toIntOrNull() ?: def
    }

    /** Force an immediate switches.conf reload + module resync without waiting 60s. */
    private fun apiReload(): String {
        val changed = ctx.config.reloadSwitchesIfChanged()
        // Touch the file mtime comparison is done by daemon; we just report.
        return jsonRaw("{\"ok\":true,\"reloaded\":$changed}")
    }

    // ------------------------------------------------------------------
    // Rubbish cleaning endpoints
    //
    // SAFETY: the WebUI never passes filesystem paths. It only passes rule ids;
    // every path is derived from the server-side rule table (RubbishRuleSet)
    // and every deletion still goes through RubbishGuard's central audit.
    // ------------------------------------------------------------------

    @Volatile
    private var rubbishCleaner: RubbishCleaner? = null

    private fun cleaner(): RubbishCleaner =
        rubbishCleaner ?: synchronized(this) {
            rubbishCleaner ?: RubbishCleaner(ctx.config).also { rubbishCleaner = it }
        }

    /** Rule table + current switch state (for rendering the WebUI clean tab). */
    private fun apiRubbishRules(): String = cleaner().rulesToJson()

    /**
     * Scan (dryRun=true) or clean (dryRun=false).
     *
     * Gate: `rubbish_clean_enable` must be true for BOTH scan and clean so the
     * feature cannot even be triggered from the UI while disabled. Scan itself
     * is read-only, but requiring the master switch keeps behaviour predictable.
     */
    private fun apiRubbishScan(method: String, body: String, dryRun: Boolean): String {
        if (method != "POST") return jsonError("需要 POST")
        if (!ctx.config.switch("rubbish_clean_enable")) {
            return jsonError("垃圾清理总开关未开启（rubbish_clean_enable=false）")
        }
        val obj = if (body.trimStart().startsWith("{")) MiniJson.parseObject(body) else emptyMap()
        val ruleIds = parseRuleIds(obj?.get("rules"))
        return try {
            val c = cleaner()
            val summary = if (dryRun) c.scan(ruleIds) else c.clean(ruleIds)
            c.summaryToJson(summary)
        } catch (t: Throwable) {
            Logger.e(name, "垃圾清理执行失败", t)
            jsonError("执行失败: ${t.message}")
        }
    }

    /** Quick status: master switch + per-rule switches (cheap, no filesystem walk). */
    private fun apiRubbishStatus(): String {
        val sb = JsonBuilder.obj {
            key("ok"); value(true); comma()
            key("masterEnabled"); value(ctx.config.switch("rubbish_clean_enable")); comma()
            key("screenOffOnly"); value(ctx.config.switch("rubbish_clean_screen_off_only")); comma()
            key("forceWhenRunning"); value(ctx.config.switch("rubbish_force_when_running")); comma()
            key("bigFileMb"); value(ctx.config.getString("rubbish_big_file_mb", "100")); comma()
            key("wxChatMediaDays"); value(ctx.config.getString("rubbish_wx_chat_media_days", "30")); comma()
            key("auditLog"); value(RubbishGuard.auditLog().filePath())
        }
        return jsonRaw(sb)
    }

    /** Recent audit lines (default 100, max 500). */
    private fun apiRubbishHistory(query: Map<String, String>): String {
        val lines = (query["lines"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val tail = RubbishGuard.auditLog().tail(lines)
        val sb = JsonBuilder.obj {
            key("ok"); value(true); comma()
            key("path"); value(RubbishGuard.auditLog().filePath()); comma()
            key("lines"); raw(JsonBuilder.arr {
                tail.forEachIndexed { i, l ->
                    if (i > 0) comma()
                    value(l)
                }
            })
        }
        return jsonRaw(sb)
    }

    /** Read the user-editable guard config (deny_path / deny_word). */
    private fun apiRubbishGuardRead(): String {
        val f = File(ctx.config.rootDir, "rubbish_guard.conf")
        val content = try {
            if (f.exists()) f.readText(Charsets.UTF_8) else UserGuardRules.defaultContent()
        } catch (t: Throwable) {
            return jsonError("读取失败: ${t.message}")
        }
        val sb = JsonBuilder.obj {
            key("ok"); value(true); comma()
            key("path"); value(f.path); comma()
            key("content"); value(content)
        }
        return jsonRaw(sb)
    }

    /** Write the guard config (append-only semantics are the user's responsibility). */
    private fun apiRubbishGuardWrite(method: String, body: String): String {
        if (method != "POST") return jsonError("需要 POST")
        val obj = MiniJson.parseObject(body) ?: return jsonError("请求体不是 JSON")
        val content = obj["content"] ?: return jsonError("缺少 content")
        val f = File(ctx.config.rootDir, "rubbish_guard.conf")
        return try {
            val tmp = File(f.parentFile, f.name + ".http.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
            // Reload immediately so the new rules take effect without a restart.
            RubbishGuard.loadUserRules(f.path)
            jsonRaw("{\"ok\":true,\"code\":0,\"message\":\"OK\"}")
        } catch (t: Throwable) {
            jsonError("写入失败: ${t.message}")
        }
    }

    /** Parse the `rules` field: either ["id1","id2"] or "id1,id2". */
    private fun parseRuleIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val s = raw.trim()
        val parts = if (s.startsWith("[")) {
            s.trimStart('[').trimEnd(']')
                .split(',')
                .map { it.trim().trim('"') }
        } else {
            s.split(',')
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Reject paths outside the module/config roots to keep the surface safe. */
    private fun guard(f: File): String? {
        return try {
            val canon = f.canonicalPath
            val allowed = listOf(
                File(ctx.config.rootDir).canonicalPath,
                File(ctx.modDir).canonicalPath,
            )
            if (allowed.any { canon == it || canon.startsWith(it + File.separator) }) null
            else jsonError("拒绝访问根目录之外: $canon")
        } catch (t: Throwable) {
            jsonError("路径校验失败: ${t.message}")
        }
    }

    /** Parse "key=value  # comment" -> (key, comment). Null when not a config line. */
    private fun parseSwitchLine(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val i = line.indexOf('=')
        if (i <= 0) return null
        val key = line.substring(0, i).trim()
        if (key.isEmpty()) return null
        val rest = line.substring(i + 1)
        val h = rest.indexOf('#')
        val comment = if (h >= 0) rest.substring(h) else ""
        return key to comment
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(" OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (prev == '\r'.code) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            prev = c
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                map[urlDecode(pair)] = ""
            } else {
                map[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return map
    }

    private fun urlDecode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Throwable) {
            s
        }

    // JSON string escaping.
    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonOk(inner: String): String = jsonRaw("{\"ok\":true,\"code\":0,\"result\":$inner}")

    private fun jsonError(msg: String): String =
        jsonRaw("{\"ok\":false,\"code\":1,\"message\":${q(msg)}}")

    private fun jsonRaw(raw: String): String = raw

    /**
     * Minimal JSON object parser for flat string maps. Only what the WebUI sends
     * ({"path":"...","content":"..."}) is supported; nested structures are not
     * needed and are intentionally rejected to keep the surface tiny.
     */
    private object MiniJson {
        fun parseObject(s: String): Map<String, String>? {
            val t = s.trim()
            if (!t.startsWith("{") || !t.endsWith("}")) return null
            val map = HashMap<String, String>()
            var i = 1
            val n = t.length
            while (i < n - 1) {
                // skip whitespace and commas
                while (i < n && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t' || t[i] == ',')) i++
                if (i >= n - 1) break
                if (t[i] != '"') return null
                val keyRes = readString(t, i) ?: return null
                val key = keyRes.first
                i = keyRes.second
                while (i < n && t[i] != ':') i++
                if (i >= n) return null
                i++ // skip ':'
                while (i < n && t[i] == ' ') i++
                if (i >= n) return null
                if (t[i] == '"') {
                    val valRes = readString(t, i) ?: return null
                    map[key] = valRes.first
                    i = valRes.second
                } else {
                    // bare token (number/bool/null) up to , or }
                    val start = i
                    while (i < n - 1 && t[i] != ',' && t[i] != '}') i++
                    map[key] = t.substring(start, i).trim()
                }
            }
            return map
        }

        private fun readString(t: String, start: Int): Pair<String, Int>? {
            if (t[start] != '"') return null
            val sb = StringBuilder()
            var i = start + 1
            while (i < t.length) {
                val c = t[i]
                when {
                    c == '\\' && i + 1 < t.length -> {
                        when (val e = t[i + 1]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 5 < t.length) {
                                    val hex = t.substring(i + 2, i + 6)
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        i += 2
                    }
                    c == '"' -> return sb.toString() to (i + 1)
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return null
        }
    }
}

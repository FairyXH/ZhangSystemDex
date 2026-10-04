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

    /** Dedicated accept thread: the periodic DaemonLoop tick is far too slow for
     *  HTTP (1 request/second). We run our own blocking accept loop so the WebUI
     *  is snappy, and hand each connection to a short-lived worker thread so one
     *  slow request (e.g. a big rubbish scan) never blocks the others. */
    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var boundPort: Int = port

    /** The port actually bound (useful when `port` is 0 -> ephemeral). */
    fun activePort(): Int = boundPort

    override fun onStart() {
        try {
            // Bind to loopback only: never reachable from Wi-Fi/mobile network.
            val ss = ServerSocket(port, 128, InetAddress.getByName("127.0.0.1"))
            ss.soTimeout = 1000
            server = ss
            boundPort = ss.localPort
            Logger.i(name, "HTTP 后端已监听 http://127.0.0.1:$boundPort （仅回环）")
            startAcceptLoop(ss)
        } catch (t: Throwable) {
            Logger.e(name, "HTTP 后端监听失败（端口 $port）", t)
        }
    }

    private fun startAcceptLoop(ss: ServerSocket) {
        if (acceptThread != null) return
        val t = Thread({
            while (server === ss && !ss.isClosed) {
                val sock = try {
                    ss.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    break
                }
                // One short-lived thread per connection: concurrent + non-blocking.
                Thread({
                    try {
                        handle(sock)
                    } catch (e: Throwable) {
                        Logger.w(name, "处理请求失败: ${e.message}")
                    } finally {
                        try { sock.close() } catch (_: Throwable) {}
                    }
                }, "HttpConn").apply { isDaemon = true }.start()
            }
        }, "HttpAccept")
        t.isDaemon = true
        acceptThread = t
        t.start()
    }

    override fun onStop() {
        try { server?.close() } catch (_: Throwable) {}
        server = null
        acceptThread = null
        Logger.i(name, "HTTP 后端已停止")
    }

    override fun tick() {
        // No-op: accept is handled by the dedicated accept thread started in
        // onStart(). The DaemonLoop tick only exists because the base class
        // requires it. (Previously this single accept() per 1000ms tick made the
        // WebUI take ~1s per request -> "保存很慢很卡".)
        if (server == null) onStart()
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
        Logger.i(name, "请求 $method $rawTarget")

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
            // CORS preflight: Chromium sends OPTIONS before a POST that carries
            // Content-Type: application/json. Answer it directly with 2xx and the
            // full CORS header set (no JSON error body) so the follow-up POST is
            // allowed. Previously OPTIONS fell through to route() -> jsonError,
            // which still returned HTTP 200 and looked fine in curl, but some
            // WebView builds reject a preflight whose body/headers look wrong.
            if (method == "OPTIONS") "" else route(method, path, query, body)
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
            // ===== 实时总览（概览页数据源，聚合模块/清理/省电/系统）=====
            "/api/overview" -> apiOverview()
            "/api/switch/set" -> apiSwitchSet(method, body)
            "/api/reload" -> apiReload()
            "/api/diag" -> apiDiag(method, body)
            // ===== 免重启在线更新（OTA）=====
            "/api/ota/status" -> apiOtaStatus()
            "/api/ota/update" -> apiOtaUpdate(method)
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
            // Main save path writes switches.conf: force an immediate in-memory
            // reload so the daemon picks the change up without waiting for the
            // 60s mtime-gated cycle (which can be skipped due to 1s mtime granularity).
            if (f.name == "switches.conf") {
                try { ctx.config.reloadSwitches() } catch (_: Throwable) {}
            }
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
            // Force a synchronous in-memory reload so /api/rubbish/status etc.
            // reflect the change immediately (mtime has 1s granularity on /data/adb).
            ctx.config.reloadSwitches()
            jsonRaw("{\"ok\":true,\"code\":0,\"message\":\"OK\"}")
        } catch (t: Throwable) {
            jsonError("保存失败: ${t.message}")
        }
    }

    /**
     * Battery + power subsystem facts. Two data sources are merged:
     *
     *  1. `dumpsys battery` — the authoritative kernel/HAL battery readings
     *     (level, status, plugged, health, temperature, voltage, current).
     *  2. [io.github.fairyxh.zhangsystemdex.core.power.PowerOptimizer] live
     *     snapshot — whether the optimization subsystem is running, its current
     *     policy level, applied action and fail-open counter.
     *
     * All fields are always present (never omitted) so the WebUI can render a
     * stable grid; unavailable readings are reported as null and the UI shows
     * a placeholder. `ok=false` is only returned when nothing at all could be
     * read.
     */
    private fun apiPowerStatus(): String {
        val bat = readBatteryFacts()
        val opt = io.github.fairyxh.zhangsystemdex.core.power.PowerOptimizer.live()
        val snap = opt?.snapshot()
        val subsystemEnabled = ctx.config.switch("power_optimize_enable")

        val body = StringBuilder()
        body.append("{\"ok\":true")

        // ---- Raw battery facts -------------------------------------------
        body.append(",\"level\":").append(bat.level)
        body.append(",\"scale\":").append(bat.scale)
        body.append(",\"status\":").append(bat.status)
        body.append(",\"plugged\":").append(bat.plugged)
        body.append(",\"health\":").append(bat.health)
        body.append(",\"present\":").append(bat.present)
        body.append(",\"temperature\":").append(bat.temperature)
        body.append(",\"voltage\":").append(bat.voltage)
        body.append(",\"currentNow\":").append(bat.currentNow)

        // Derived / human-meaningful fields.
        body.append(",\"charging\":").append(bat.charging)
        body.append(",\"statusText\":").append(q(bat.statusText))
        body.append(",\"pluggedText\":").append(q(bat.pluggedText))
        body.append(",\"healthText\":").append(q(bat.healthText))
        body.append(",\"levelPercent\":").append(if (bat.level >= 0) bat.level else -1)

        // ---- Subsystem facts ---------------------------------------------
        body.append(",\"subsystemEnabled\":").append(subsystemEnabled)
        body.append(",\"running\":").append(opt != null)
        body.append(",\"eventDriven\":").append(snap?.get("eventDriven") ?: false)
        body.append(",\"screenOn\":").append(snap?.get("screenOn") ?: bat.screenOn)
        body.append(",\"policyLevel\":").append(snap?.get("level") ?: 0)
        body.append(",\"policyLevelText\":").append(q(policyLevelText(snap?.get("level"))))
        body.append(",\"lastAction\":").append(q(snap?.get("lastAction")?.toString() ?: ""))
        body.append(",\"kernelApplied\":").append(snap?.get("kernelApplied") ?: false)
        body.append(",\"hasBgTargets\":").append(snap?.get("hasBgTargets") ?: false)
        body.append(",\"policyAppliedCount\":").append(snap?.get("policyAppliedCount") ?: 0)
        body.append(",\"policyRevertCount\":").append(snap?.get("policyRevertCount") ?: 0)
        body.append(",\"backgroundRestrictCount\":").append(snap?.get("backgroundRestrictCount") ?: 0)
        body.append(",\"lowBatteryEnterCount\":").append(snap?.get("lowBatteryEnterCount") ?: 0)
        body.append(",\"failOpenCount\":").append(snap?.get("failOpenCount") ?: 0)
        body.append(",\"screenOffCount\":").append(snap?.get("screenOffCount") ?: 0)
        body.append(",\"screenOnCount\":").append(snap?.get("screenOnCount") ?: 0)
        body.append(",\"lastTransitionMs\":").append(snap?.get("lastTransitionMs") ?: 0L)

        // Thresholds the policy actually uses (so the UI can explain the level).
        body.append(",\"lowBatteryThreshold\":")
            .append(ctx.config.getString("power_low_battery_threshold", "20").trim().toIntOrNull() ?: 20)
        body.append(",\"timestamp\":").append(System.currentTimeMillis())
        body.append("}")
        return jsonRaw(body.toString())
    }

    // ======================================================================
    // 实时总览（/api/overview）——概览页的单一数据源
    //
    // 目标（用户需求）：概览页要"信息越全面越好、要实时"。本接口把后端
    // 能看到的一切聚合为一份 JSON，供前端每 2 秒轮询一次：
    //   - modules  各功能模块的真实运行状态（线程级 ground-truth）+ 心跳
    //   - power    省电子系统实时快照（策略/计数/最近动作）
    //   - clean    垃圾清理：主开关/规则数/最近一次会话统计/历史累计
    //   - system   电池/屏幕/内存/存储/负载/开机时长/温度/进程数
    //   - backend  daemon 进程、端口、dex 校验、运行时长
    //   - config   配置文件路径与开关总数
    // ======================================================================
    private fun apiOverview(): String {
        val now = System.currentTimeMillis()
        val bat = readBatteryFacts()
        val sb = StringBuilder()
        sb.append("{\"ok\":true")
        sb.append(",\"timestamp\":").append(now)

        // ---------- backend ----------
        val dexFile = File(ctx.config.rootDir, "Main.dex")
        val started = RuntimeRegistry.daemonStartedMs
        sb.append(",\"backend\":{")
        sb.append("\"pid\":").append(ProcessUtils.selfPid())
        sb.append(",\"port\":").append(ctx.config.httpPort)
        sb.append(",\"uptimeMs\":").append(if (started > 0) now - started else 0L)
        sb.append(",\"startedMs\":").append(started)
        sb.append(",\"dexPath\":").append(q(dexFile.path))
        sb.append(",\"dexSize\":").append(if (dexFile.exists()) dexFile.length() else 0L)
        sb.append(",\"dexMd5\":").append(q(md5Of(dexFile)))
        sb.append(",\"configPath\":").append(q(File(ctx.config.rootDir, "switches.conf").path))
        sb.append(",\"moduleDir\":").append(q(RuntimeRegistry.moduleDir.ifEmpty { ctx.modDir }))
        sb.append(",\"configRoot\":").append(q(ctx.config.rootDir))
        sb.append("}")

        // ---------- config ----------
        sb.append(",\"config\":{")
        sb.append("\"switchCount\":").append(ctx.config.allSwitchKeys().size)
        sb.append(",\"logEnabled\":").append(ctx.config.logEnabled)
        sb.append(",\"powersave\":").append(ctx.config.switch("powersave_enable"))
        sb.append("}")

        // ---------- modules ----------
        sb.append(",\"modules\":[")
        var firstM = true
        for ((key, st) in RuntimeRegistry.snapshot()) {
            if (!firstM) sb.append(',')
            firstM = false
            sb.append("{\"key\":").append(q(key))
            sb.append(",\"label\":").append(q(st.label))
            sb.append(",\"desc\":").append(q(st.desc))
            sb.append(",\"enabled\":").append(st.enabled)
            sb.append(",\"running\":").append(st.running)
            sb.append(",\"tickCount\":").append(st.tickCount)
            sb.append(",\"lastTickMs\":").append(st.lastTickMs)
            sb.append(",\"lastTickAgoMs\":").append(if (st.lastTickMs > 0) now - st.lastTickMs else -1L)
            sb.append(",\"lastAction\":").append(q(st.lastAction))
            // counters
            sb.append(",\"counters\":{")
            var firstC = true
            for ((ck, cv) in st.counters.entries.sortedBy { it.key }) {
                if (!firstC) sb.append(',')
                firstC = false
                sb.append(q(ck)).append(':').append(cv)
            }
            sb.append("}")
            // extras (numbers/bools/strings)
            sb.append(",\"extras\":{")
            var firstE = true
            for ((ek, ev) in st.extras.entries.sortedBy { it.key }) {
                if (!firstE) sb.append(',')
                firstE = false
                sb.append(q(ek)).append(':')
                when (ev) {
                    is Boolean -> sb.append(ev)
                    is Number -> sb.append(ev)
                    else -> sb.append(q(ev.toString()))
                }
            }
            sb.append("}")
            sb.append("}")
        }
        sb.append("]")

        // ---------- power ----------
        val opt = io.github.fairyxh.zhangsystemdex.core.power.PowerOptimizer.live()
        val snap = opt?.snapshot()
        sb.append(",\"power\":{")
        sb.append("\"subsystemEnabled\":").append(ctx.config.switch("power_optimize_enable"))
        sb.append(",\"running\":").append(opt != null)
        sb.append(",\"eventDriven\":").append(snap?.get("eventDriven") ?: false)
        sb.append(",\"screenOn\":").append(snap?.get("screenOn") ?: bat.screenOn)
        sb.append(",\"policyLevel\":").append(snap?.get("level") ?: 0)
        sb.append(",\"policyLevelText\":").append(q(policyLevelText(snap?.get("level"))))
        sb.append(",\"kernelApplied\":").append(snap?.get("kernelApplied") ?: false)
        sb.append(",\"hasBgTargets\":").append(snap?.get("hasBgTargets") ?: false)
        sb.append(",\"policyAppliedCount\":").append(snap?.get("policyAppliedCount") ?: 0)
        sb.append(",\"policyRevertCount\":").append(snap?.get("policyRevertCount") ?: 0)
        sb.append(",\"backgroundRestrictCount\":").append(snap?.get("backgroundRestrictCount") ?: 0)
        sb.append(",\"lowBatteryEnterCount\":").append(snap?.get("lowBatteryEnterCount") ?: 0)
        sb.append(",\"failOpenCount\":").append(snap?.get("failOpenCount") ?: 0)
        sb.append(",\"screenOffCount\":").append(snap?.get("screenOffCount") ?: 0)
        sb.append(",\"screenOnCount\":").append(snap?.get("screenOnCount") ?: 0)
        sb.append(",\"lastAction\":").append(q(snap?.get("lastAction")?.toString() ?: ""))
        sb.append(",\"lowBatteryThreshold\":")
            .append(ctx.config.getString("power_low_battery_threshold", "20").trim().toIntOrNull() ?: 20)
        sb.append("}")

        // ---------- clean ----------
        sb.append(",\"clean\":")
        sb.append(cleanOverviewJson(now))

        // ---------- system ----------
        sb.append(",\"system\":")
        sb.append(systemOverviewJson(bat, now))

        sb.append("}")
        return jsonRaw(sb.toString())
    }

    /** Aggregate of everything the clean subsystem can report right now. */
    private fun cleanOverviewJson(now: Long): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"masterEnabled\":").append(ctx.config.switch("rubbish_clean_enable"))
        sb.append(",\"screenOffOnly\":").append(ctx.config.switch("rubbish_clean_screen_off_only"))
        sb.append(",\"forceWhenRunning\":").append(ctx.config.switch("rubbish_force_when_running"))
        sb.append(",\"bigFileMb\":").append(ctx.config.getString("rubbish_big_file_mb", "100"))
        sb.append(",\"auditLog\":").append(q(RubbishGuard.auditLog().filePath()))
        // Enabled rule count from switches.conf (rubbish_rule_*).
        val ruleKeys = ctx.config.allSwitchKeys().filter { it.startsWith("rubbish_rule_") }
        val enabledRules = ruleKeys.count { ctx.config.switch(it) }
        sb.append(",\"ruleTotal\":").append(ruleKeys.size)
        sb.append(",\"ruleEnabled\":").append(enabledRules)
        // Last session stats recorded by the cleaner (via audit log tail).
        val last = lastCleanSession()
        sb.append(",\"lastSession\":{")
        sb.append("\"files\":").append(last?.first ?: 0)
        sb.append(",\"bytes\":").append(last?.second ?: 0L)
        sb.append(",\"atMs\":").append(last?.third ?: 0L)
        sb.append(",\"agoMs\":").append(if (last != null && last.third > 0) now - last.third else -1L)
        sb.append("}")
        // Live counters pushed by SystemTuningModule (the clean executor).
        val mc = RuntimeRegistry.get("system_tuning")
        sb.append(",\"cleanedFiles\":").append(mc?.counters?.get("cleanFiles") ?: 0L)
        sb.append(",\"cleanedBytes\":").append(mc?.counters?.get("cleanBytes") ?: 0L)
        sb.append(",\"cleanRuns\":").append(mc?.counters?.get("cleanRuns") ?: 0L)
        sb.append(",\"lastCleanAgoMs\":").append(
            (mc?.extras?.get("lastCleanMs") as? Long)?.let { if (it > 0) now - it else -1L } ?: -1L
        )
        sb.append(",\"lastCleanFiles\":").append((mc?.extras?.get("lastCleanFiles") as? Long) ?: 0L)
        sb.append(",\"lastCleanBytes\":").append((mc?.extras?.get("lastCleanBytes") as? Long) ?: 0L)
        // Lifetime totals from the audit log (survive daemon restarts).
        val cum = RubbishGuard.auditLog().cumulative()
        sb.append(",\"totalFiles\":").append(cum.first)
        sb.append(",\"totalBytes\":").append(cum.second)
        sb.append(",\"totalSessions\":").append(cum.third)
        // Recent audit lines (last 8, most recent first).
        sb.append(",\"recent\":[")
        val tail = RubbishGuard.auditLog().tail(8).reversed()
        tail.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append(q(l))
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    /** Parse the audit log tail for the last "session" total (files/bytes/time). */
    private fun lastCleanSession(): Triple<Int, Long, Long>? {
        return try {
            val lines = RubbishGuard.auditLog().tail(200)
            for (l in lines.reversed()) {
                // matches:  "[...] 会话结束 ... 文件=N 字节=M" or "cleaned N files, M bytes"
                val f = Regex("(?i)(?:已清理|清理|cleaned|文件|files)[^0-9]{0,8}(\\d+)").find(l)
                val b = Regex("(?i)(?:字节|bytes|B)[^0-9]{0,8}(\\d+)").find(l)
                val t = parseAuditTime(l)
                if (f != null && b != null) {
                    return Triple(f.groupValues[1].toIntOrNull() ?: 0, b.groupValues[1].toLongOrNull() ?: 0L, t)
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseAuditTime(line: String): Long = try {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})").find(line)
        if (m != null) {
            val cal = java.util.Calendar.getInstance()
            cal.set(
                m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt()
            )
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } else 0L
    } catch (_: Throwable) {
        0L
    }

    /** System-wide live facts: battery, memory, storage, load, thermal, uptime. */
    private fun systemOverviewJson(bat: BatteryFacts, now: Long): String {
        val sb = StringBuilder()
        sb.append("{")
        // battery
        sb.append("\"battery\":{")
        sb.append("\"level\":").append(bat.level)
        sb.append(",\"charging\":").append(bat.charging)
        sb.append(",\"statusText\":").append(q(bat.statusText))
        sb.append(",\"pluggedText\":").append(q(bat.pluggedText))
        sb.append(",\"temperature\":").append(bat.temperature)
        sb.append(",\"voltage\":").append(bat.voltage)
        sb.append(",\"currentNow\":").append(bat.currentNow)
        sb.append(",\"healthText\":").append(q(bat.healthText))
        sb.append("}")
        // screen
        sb.append(",\"screenOn\":").append(bat.screenOn)
        // memory (MemTotal/MemAvailable in kB)
        val mem = readMemInfo()
        sb.append(",\"mem\":{")
        sb.append("\"totalKb\":").append(mem.first)
        sb.append(",\"availKb\":").append(mem.second)
        sb.append(",\"usedKb\":").append(if (mem.first > 0) mem.first - mem.second else 0L)
        sb.append(",\"usedPercent\":").append(
            if (mem.first > 0) ((mem.first - mem.second) * 100 / mem.first).toInt() else -1
        )
        sb.append("}")
        // storage of /data
        val data = File("/data")
        sb.append(",\"storage\":{")
        sb.append("\"dataTotal\":").append(data.totalSpace)
        sb.append(",\"dataFree\":").append(data.usableSpace)
        sb.append("}")
        // load / uptime / processes
        sb.append(",\"load1\":").append(readLoad1())
        sb.append(",\"uptimeMs\":").append(readUptimeMs())
        sb.append(",\"procCount\":").append(ProcessUtils.processCount())
        // thermal (max of all thermal zones, in 0.001 °C)
        sb.append(",\"thermalMaxMilliC\":").append(readThermalMaxMilliC())
        sb.append("}")
        return sb.toString()
    }

    /** Read MemTotal/MemAvailable (kB) from /proc/meminfo. */
    private fun readMemInfo(): Pair<Long, Long> {
        return try {
            var total = -1L
            var avail = -1L
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull() ?: -1L
                    line.startsWith("MemAvailable:") -> avail = line.filter { it.isDigit() }.toLongOrNull() ?: -1L
                }
            }
            total to (if (avail >= 0) avail else 0L)
        } catch (_: Throwable) {
            -1L to 0L
        }
    }

    private fun readLoad1(): Double = try {
        File("/proc/loadavg").readText().trim().split(" ").firstOrNull()?.toDoubleOrNull() ?: -1.0
    } catch (_: Throwable) {
        -1.0
    }

    private fun readUptimeMs(): Long = try {
        val secs = File("/proc/uptime").readText().trim().split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0
        (secs * 1000).toLong()
    } catch (_: Throwable) {
        0L
    }

    /** Max thermal zone temperature in 0.001 °C (or Int.MIN_VALUE if none). */
    private fun readThermalMaxMilliC(): Int {
        return try {
            var max = Int.MIN_VALUE
            val roots = listOf(File("/sys/class/thermal"), File("/sys/devices/virtual/thermal"))
            for (root in roots) {
                val children = root.listFiles() ?: continue
                for (c in children) {
                    if (!c.name.startsWith("thermal_zone")) continue
                    val tf = File(c, "temp")
                    if (!tf.exists()) continue
                    val v = tf.readText().trim().toIntOrNull() ?: continue
                    if (v > max) max = v
                }
            }
            max
        } catch (_: Throwable) {
            Int.MIN_VALUE
        }
    }

    /** Parsed subset of `dumpsys battery` plus the screen state. */
    private class BatteryFacts {
        var level: Int = -1
        var scale: Int = -1
        var status: Int = -1
        var plugged: Int = -1
        var health: Int = -1
        var present: Boolean = false
        var temperature: Int = Int.MIN_VALUE   // tenths of a degree C
        var voltage: Int = -1                  // mV
        var currentNow: Int = Int.MIN_VALUE    // µA (signed)
        var charging: Boolean = false
        var screenOn: Boolean = false
        var statusText: String = "未知"
        var pluggedText: String = "未连接"
        var healthText: String = "未知"
    }

    /**
     * Read the battery facts from `dumpsys battery`. The framework sticky
     * intent is not reachable from app_process, so `dumpsys` is the portable
     * source. Every field is best-effort: a missing line leaves the sentinel.
     */
    private fun readBatteryFacts(): BatteryFacts {
        val f = BatteryFacts()
        val out = ShellExecutor.run("dumpsys battery 2>/dev/null")
        if (out != null) {
            f.level = intField(out, "level", -1)
            f.scale = intField(out, "scale", -1)
            f.status = intField(out, "status", -1)
            f.health = intField(out, "health", -1)
            f.present = boolField(out, "present", false)
            f.voltage = intField(out, "voltage", -1)
            // Temperature: standard AOSP uses `temperature:` (tenths of °C);
            // some OEM builds (OPLUS) expose `PhoneTemp:` as a fallback.
            f.temperature = intField(out, "temperature", Int.MIN_VALUE)
                .let { if (it == Int.MIN_VALUE) intField(out, "PhoneTemp", Int.MIN_VALUE) else it }
            // Current: standard is `current now:`; OPLUS uses `Battery current:`.
            f.currentNow = intField(out, "current now", Int.MIN_VALUE)
                .let { if (it == Int.MIN_VALUE) intField(out, "current_now", Int.MIN_VALUE) else it }
                .let { if (it == Int.MIN_VALUE) intField(out, "Battery current", Int.MIN_VALUE) else it }
            // `plugged:` is absent on some OEM builds; derive it from the
            // per-source power flags instead.
            f.plugged = intField(out, "plugged", Int.MIN_VALUE)
                .let { if (it == Int.MIN_VALUE) derivePlugged(out) else it }
        }
        f.charging = f.status == 2 || f.status == 5 || f.plugged > 0
        f.statusText = statusText(f.status)
        f.pluggedText = pluggedText(f.plugged)
        f.healthText = healthText(f.health)
        f.screenOn = screenOnNow()
        return f
    }

    /**
     * Derive the `plugged` bitmask from OEM `* powered` flags when the standard
     * `plugged:` line is missing. Mirrors BatteryManager's BATTERY_PLUGGED_*.
     */
    private fun derivePlugged(out: String): Int {
        var mask = 0
        if (boolField(out, "AC powered", false)) mask = mask or 0x1
        if (boolField(out, "USB powered", false)) mask = mask or 0x2
        if (boolField(out, "Wireless powered", false)) mask = mask or 0x4
        if (boolField(out, "Dock powered", false)) mask = mask or 0x8
        return mask
    }

    private fun intField(text: String, field: String, def: Int): Int {
        // Allow optional whitespace before the colon (`Battery current : -31`).
        val m = Regex("(?:^|\\n)\\s*" + Regex.escape(field) + "\\s*:\\s*(-?\\d+)").find(text) ?: return def
        return m.groupValues[1].toIntOrNull() ?: def
    }

    private fun boolField(text: String, field: String, def: Boolean): Boolean {
        val m = Regex("(?:^|\\n)\\s*" + Regex.escape(field) + "\\s*:\\s*(true|false)").find(text) ?: return def
        return m.groupValues[1].equals("true", ignoreCase = true)
    }

    private fun screenOnNow(): Boolean =
        try {
            val c = SystemContext.get() ?: return false
            val pm = c.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return false
            pm.isInteractive
        } catch (_: Throwable) {
            false
        }

    private fun statusText(s: Int): String = when (s) {
        1 -> "未知"
        2 -> "充电中"
        3 -> "放电中"
        4 -> "未充电"
        5 -> "已充满"
        else -> "未知"
    }

    private fun pluggedText(p: Int): String = when (p) {
        0 -> "未连接"
        1 -> "交流电源"
        2 -> "USB"
        4 -> "无线充电"
        8 -> "Dock"
        else -> if (p > 0) "已连接" else "未连接"
    }

    private fun healthText(h: Int): String = when (h) {
        1 -> "未知"
        2 -> "良好"
        3 -> "过热"
        4 -> "已损坏"
        5 -> "过压"
        6 -> "未指定故障"
        7 -> "过冷"
        else -> "未知"
    }

    /** Map the numeric policy level to a professional label matching PowerPolicyEngine. */
    private fun policyLevelText(level: Any?): String = when ((level as? Number)?.toInt() ?: 0) {
        0 -> "空闲（系统默认）"
        1 -> "息屏省电"
        2 -> "低电量省电"
        3 -> "充电恢复"
        else -> "空闲（系统默认）"
    }

    private fun readBatteryInt(key: String, def: Int): Int {
        // Retained for backwards compatibility with any older caller.
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
        // Force (not mtime-gated): WebUI writes then reloads within the same second,
        // and /data/adb mtime granularity would otherwise skip the reload.
        ctx.config.reloadSwitches()
        return jsonRaw("{\"ok\":true,\"reloaded\":true}")
    }
    /**
     * Diagnostic sink for the WebUI. The page POSTs small JSON blobs describing
     * client-side events (toggle, error, page-load) so we can see — from the
     * daemon side — what really happens inside the WebView, independent of the
     * `log_enabled` master switch: diagnostics are ALWAYS written to a dedicated
     * `webui_diag.log` so they survive even when normal logging is off.
     */
    private fun apiDiag(method: String, body: String): String {
        if (method != "POST") return jsonError("需要 POST")
        try {
            val line = timestampForDiag() + " " + body.replace("\n", " ")
            val dir = File(ctx.config.rootDir, "log")
            dir.mkdirs()
            File(dir, "webui_diag.log").appendText(line + "\n")
        } catch (_: Throwable) {
        }
        return jsonRaw("{\"ok\":true}")
    }
    private fun timestampForDiag(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())

    // ------------------------------------------------------------------
    // 免重启在线更新（OTA）
    //
    // 直接把最新 Main.dex / webroot/index.html 写进「已存在的模块目录」并重启
    // daemon 进程即可生效——无需刷 zip、无需重启设备（模块目录在运行时本就可写，
    // daemon 同一时刻只从 /data/adb/Zhang/Main.dex 加载）。
    // ------------------------------------------------------------------
    // 使用 github.com/.../raw/main/（302 跳转到带 cache-busting 的 CDN），
    // 比 raw.githubusercontent.com 更新及时：后者 CDN 缓存可能滞后数分钟~数小时，
    // 会把刚推送的新 dex 误当成旧版本下载回来。
    private val otaRepoRaw = "https://github.com/FairyXH/ZhangSystemDex/raw/main"
    private val otaDexUrl = "$otaRepoRaw/Main.dex"
    private val otaUiUrl = "$otaRepoRaw/app/src/main/assets/webroot/index.html"
    /** modDir 由 ConfigManager 暴露；兜底 /data/adb/modules/Zhang。 */
    private fun modDirFile(): File {
        val p = try { ctx.config.moduleDir } catch (_: Throwable) { null }
        val d = if (p != null) File(p) else null
        return if (d != null && d.exists()) d else File("/data/adb/modules/Zhang")
    }

    /** POSIX single-quote a string for safe use in a `/system/bin/sh -c` command. */
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun md5Of(f: File): String {
        return try {
            val dig = java.security.MessageDigest.getInstance("MD5")
            f.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    dig.update(buf, 0, n)
                }
            }
            dig.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { "" }
    }

    /** 当前版本状态：本地 dex / UI 的大小与 md5，以及仓库地址。 */
    private fun apiOtaStatus(): String {
        val mod = modDirFile()
        val dex = File(mod, "Main.dex")
        val ui = File(mod, "webroot/index.html")
        val sb = StringBuilder()
        sb.append("{\"ok\":true")
        sb.append(",\"modDir\":").append(quoteJson(mod.absolutePath))
        sb.append(",\"dexPath\":").append(quoteJson(dex.absolutePath))
        sb.append(",\"dexSize\":").append(if (dex.exists()) dex.length() else 0L)
        sb.append(",\"dexMd5\":").append(quoteJson(if (dex.exists()) md5Of(dex) else ""))
        sb.append(",\"uiSize\":").append(if (ui.exists()) ui.length() else 0L)
        sb.append(",\"uiMd5\":").append(quoteJson(if (ui.exists()) md5Of(ui) else ""))
        sb.append(",\"source\":").append(quoteJson(otaDexUrl))
        sb.append(",\"curl\":").append(ShellExecutor.fileExists("/system/bin/curl"))
        sb.append("}")
        return jsonRaw(sb.toString())
    }

    /**
     * 执行免重启更新：
     *  1. 用系统 curl 下载最新 dex + UI 到模块目录旁的临时文件；
     *  2. 校验 dex 魔数（dex\n0xx）与非空；
     *  3. 备份现有文件，原子替换进模块目录，并同步 dex 到 /data/adb/Zhang；
     *  4. 返回结果，随后在后台（分离进程）重启 daemon —— 新 dex/UI 立即生效，
     *     无需刷入 zip、无需重启设备。
     */
    private fun apiOtaUpdate(method: String): String {
        if (method != "POST") return jsonError("需要 POST")
        val mod = modDirFile()
        if (!mod.exists()) return jsonError("模块目录不存在: ${mod.absolutePath}")
        val dex = File(mod, "Main.dex")
        val ui = File(mod, "webroot/index.html")
        val tmpDex = File(mod, "Main.dex.ota.tmp")
        val tmpUi = File(mod, "webroot/index.html.ota.tmp")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val backupDir = File(ctx.config.rootDir, "_backup_ota_$stamp")
        val log = StringBuilder()

        fun dl(url: String, out: File): Boolean {
            out.parentFile?.mkdirs()
            // -f: HTTP 错误直接失败；-L 跟随跳转；--max-time 防卡死；重试 2 次。
            val cmd = "/system/bin/curl -fL --connect-timeout 10 --max-time 120 --retry 2 -s -o " +
                    shq(out.absolutePath) + " " + shq(url)
            val code = ShellExecutor.runExit(cmd, 150_000)
            return code == 0 && out.exists() && out.length() > 0
        }

        try {
            // 1. 下载
            if (!dl(otaDexUrl, tmpDex)) {
                tmpDex.delete()
                return jsonError("下载 Main.dex 失败（检查网络/源地址）")
            }
            log.append("dex 下载完成(${tmpDex.length()}B); ")
            val uiOk = dl(otaUiUrl, tmpUi)
            if (uiOk) log.append("UI 下载完成(${tmpUi.length()}B); ") else log.append("UI 下载失败(跳过); ")

            // 2. 校验 dex 魔数：前 4 字节 "dex\n"，第 7 字节 '0'（035/036/037...）
            val magic = ByteArray(8)
            tmpDex.inputStream().use { it.read(magic) }
            val isDex = magic[0] == 'd'.code.toByte() && magic[1] == 'e'.code.toByte() &&
                    magic[2] == 'x'.code.toByte() && magic[3] == '\n'.code.toByte()
            if (!isDex) {
                tmpDex.delete()
                return jsonError("下载内容不是合法 dex（魔数校验失败）")
            }

            // 3. 备份 + 替换
            backupDir.mkdirs()
            if (dex.exists()) dex.copyTo(File(backupDir, "Main.dex"), overwrite = true)
            if (ui.exists()) ui.copyTo(File(backupDir, "index.html"), overwrite = true)

            // dex：写入模块目录（service.sh 重启时会自动同步到配置根 /data/adb/Zhang，
            // 因此这里不再重复写配置根，避免与重启产生写竞态/半截文件）。
            if (!tmpDex.renameTo(dex)) {
                dex.writeBytes(tmpDex.readBytes()); tmpDex.delete()
            }
            runCatching { android.system.Os.chmod(dex.absolutePath, 493) } // 0755

            // UI：写入 webroot
            if (uiOk) {
                if (!tmpUi.renameTo(ui)) {
                    ui.writeBytes(tmpUi.readBytes()); tmpUi.delete()
                }
            } else {
                tmpUi.delete()
            }
            log.append("已写入模块目录并备份到 ${backupDir.name}; ")

            // 4. 后台重启 daemon（完全分离的进程，避免随本次响应/旧 daemon 一起被杀）
            //    关键：把重启逻辑写成临时脚本，用 setsid 在新的会话/进程组中运行，
            //    这样旧 daemon 被 kill（含 pkill）时不会连带杀掉重启脚本；
            //    脚本内 sleep 2 等旧进程彻底退出后再拉起，避免端口/pid 竞态。
            val svc = File(mod, "service.sh")
            val stop = File(mod, "停止Dex.sh")
            val rlog = File(ctx.config.rootDir, "log/ota_restart.log")
            val restartSh = File(ctx.config.rootDir, "ota_restart.sh")
            val restartBody = buildString {
                append("#!/system/bin/sh\n")
                append("exec >>").append(shq(rlog.absolutePath)).append(" 2>&1\n")
                append("echo \"[OTA] restart begin $(date)\"\n")
                append("sleep 2\n")
                if (stop.exists()) append("/system/bin/sh ").append(shq(stop.absolutePath)).append("\n")
                append("sleep 2\n")
                append("if [ -f ").append(shq(svc.absolutePath)).append(" ]; then\n")
                append("  /system/bin/sh ").append(shq(svc.absolutePath)).append("\n")
                append("else\n")
                append("  echo '[OTA] service.sh 不存在，无法自动重启'\n")
                append("fi\n")
                append("echo \"[OTA] restart done rc=$?\"\n")
            }
            restartSh.writeText(restartBody, Charsets.UTF_8)
            runCatching { android.system.Os.chmod(restartSh.absolutePath, 493) } // 0755
            // setsid 使其脱离当前会话；nohup 忽略挂断；末尾 & 立即返回。
            ShellExecutor.runBackground(
                "setsid /system/bin/sh " + shq(restartSh.absolutePath) + " </dev/null >/dev/null 2>&1 &"
            )
            log.append("已触发后台重启; ")

            val sb = StringBuilder()
            sb.append("{\"ok\":true,\"code\":0")
            sb.append(",\"dexMd5\":").append(quoteJson(md5Of(dex)))
            sb.append(",\"uiSize\":").append(if (ui.exists()) ui.length() else 0L)
            sb.append(",\"backup\":").append(quoteJson(backupDir.absolutePath))
            sb.append(",\"message\":").append(quoteJson(log.toString()))
            sb.append("}")
            return jsonRaw(sb.toString())
        } catch (t: Throwable) {
            return jsonError("更新失败: ${t.message}")
        }
    }

    private fun quoteJson(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c.code < 0x20) b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        return b.append("\"").toString()
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
            // Chrome Private Network Access: a page served from a public / opaque
            // origin (file://, webui-x://) that calls a loopback address must be
            // granted this header on BOTH the preflight and the real response,
            // otherwise Chromium silently blocks the request. This is the reason
            // GET /api/paths worked while POST /api/write never reached the daemon.
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Vary: Origin\r\n")
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

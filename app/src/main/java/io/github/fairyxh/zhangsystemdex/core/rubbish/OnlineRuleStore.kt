package io.github.fairyxh.zhangsystemdex.core.rubbish

import io.github.fairyxh.zhangsystemdex.core.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 在线规则源元数据（`online_rules/<id>/meta.json`）。
 *
 * 每个订阅源一个目录；[url] 为用户输入的**规则直链**，[intervalHours] 为拉取间隔。
 */
data class OnlineRuleSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val intervalHours: Int = 24,
    val lastFetchMs: Long = 0L,
    /** SUCCESS / FAILED / NEVER。 */
    val lastStatus: String = "NEVER",
    val lastError: String = "",
    val ruleCount: Int = 0,
    val sha256: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("enabled", enabled)
        put("intervalHours", intervalHours)
        put("lastFetchMs", lastFetchMs)
        put("lastStatus", lastStatus)
        put("lastError", lastError)
        put("ruleCount", ruleCount)
        put("sha256", sha256)
    }

    companion object {
        fun fromJson(o: JSONObject): OnlineRuleSource = OnlineRuleSource(
            id = o.optString("id"),
            name = o.optString("name", "未命名源"),
            url = o.optString("url"),
            enabled = o.optBoolean("enabled", true),
            intervalHours = o.optInt("intervalHours", 24).coerceIn(1, 24 * 30),
            lastFetchMs = o.optLong("lastFetchMs", 0L),
            lastStatus = o.optString("lastStatus", "NEVER"),
            lastError = o.optString("lastError", ""),
            ruleCount = o.optInt("ruleCount", 0),
            sha256 = o.optString("sha256", ""),
        )
    }
}

/**
 * 在线规则存储 + 拉取 + 合并。
 *
 * 目录布局（`{rootDir}/online_rules/`）：
 * ```
 * online_rules/
 *   index.json          # 所有源元数据（数组）
 *   <id>/rules.json     # 该源最近一次成功拉取的规则缓存（原始 JSON）
 * ```
 * 用户本地规则单独存 `{rootDir}/user_rules.json`。
 *
 * 设计原则（借鉴 CZero 的「数据驱动 + 只增不删」）：
 *  - **多源**：任意数量、各自独立启用/间隔；
 *  - **只增不删**：重拉只更新该源自身缓存；合并到扫描时只「新增」规则，
 *    绝不覆盖/删除内建 [RubbishRuleSet]；
 *  - **安全第一**：所有在线/用户规则路径在执行期仍经 [RubbishGuard] 审查；
 *  - **失败保留**：拉取失败保留旧缓存并记录状态，不影响主清理流程。
 *
 * 该对象为无状态单例，所有方法线程安全（读写加锁）。
 */
object OnlineRuleStore {

    const val DIR_NAME = "online_rules"
    const val INDEX_NAME = "index.json"
    const val USER_RULES_NAME = "user_rules.json"

    /** 单次拉取响应体上限（2MB），防止超大响应拖垮内存。 */
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** 拉取超时（ms）。 */
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 12000

    private val lock = Any()

    fun dir(rootDir: File): File = File(rootDir, DIR_NAME)
    private fun indexFile(rootDir: File): File = File(dir(rootDir), INDEX_NAME)
    private fun sourceDir(rootDir: File, id: String): File = File(dir(rootDir), id)
    private fun sourceRulesFile(rootDir: File, id: String): File = File(sourceDir(rootDir, id), "rules.json")
    fun userRulesFile(rootDir: File): File = File(rootDir, USER_RULES_NAME)

    // ------------------------------------------------------------------
    // 索引读写
    // ------------------------------------------------------------------

    /** 读取全部源元数据。文件缺失/损坏时返回空表。 */
    fun listSources(rootDir: File): List<OnlineRuleSource> = synchronized(lock) {
        val f = indexFile(rootDir)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<OnlineRuleSource>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out += OnlineRuleSource.fromJson(it) }
            }
            out
        } catch (t: Throwable) {
            Logger.w("OnlineRuleStore", "index.json 解析失败: ${t.message}")
            emptyList()
        }
    }

    private fun writeSources(rootDir: File, sources: List<OnlineRuleSource>) {
        val arr = JSONArray()
        sources.forEach { arr.put(it.toJson()) }
        val f = indexFile(rootDir)
        f.parentFile?.mkdirs()
        writeAtomic(f, arr.toString(2))
    }

    fun findSource(rootDir: File, id: String): OnlineRuleSource? =
        listSources(rootDir).firstOrNull { it.id == id }

    // ------------------------------------------------------------------
    // 增删改
    // ------------------------------------------------------------------

    /** 新增一个订阅源（不拉取）。返回新源 id；URL 非法时返回 null。 */
    fun addSource(
        rootDir: File,
        name: String,
        url: String,
        intervalHours: Int = 24,
        enabled: Boolean = true,
    ): String? {
        val normUrl = url.trim()
        if (!isHttpUrl(normUrl)) return null
        synchronized(lock) {
            val sources = listSources(rootDir).toMutableList()
            val id = "src_" + randomHex(8)
            val src = OnlineRuleSource(
                id = id,
                name = name.trim().ifBlank { "规则源" },
                url = normUrl,
                enabled = enabled,
                intervalHours = intervalHours.coerceIn(1, 24 * 30),
            )
            sources += src
            writeSources(rootDir, sources)
            Logger.i("OnlineRuleStore", "新增在线规则源: ${src.name} -> $normUrl")
            return id
        }
    }

    /** 更新源字段（仅覆盖非 null 参数）。 */
    fun updateSource(
        rootDir: File,
        id: String,
        name: String? = null,
        url: String? = null,
        enabled: Boolean? = null,
        intervalHours: Int? = null,
    ): Boolean = synchronized(lock) {
        val sources = listSources(rootDir).toMutableList()
        val idx = sources.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = sources[idx]
        if (url != null && !isHttpUrl(url.trim())) return false
        sources[idx] = old.copy(
            name = name?.trim()?.ifBlank { old.name } ?: old.name,
            url = url?.trim() ?: old.url,
            enabled = enabled ?: old.enabled,
            intervalHours = (intervalHours ?: old.intervalHours).coerceIn(1, 24 * 30),
        )
        writeSources(rootDir, sources)
        true
    }

    /** 删除源及其缓存目录。 */
    fun removeSource(rootDir: File, id: String): Boolean = synchronized(lock) {
        val sources = listSources(rootDir).toMutableList()
        val removed = sources.removeAll { it.id == id }
        if (removed) {
            writeSources(rootDir, sources)
            try { sourceDir(rootDir, id).deleteRecursively() } catch (_: Throwable) {}
        }
        removed
    }

    // ------------------------------------------------------------------
    // 拉取
    // ------------------------------------------------------------------

    /**
     * 立即拉取一个源并缓存。返回 (成功?, 规则数, 错误信息)。
     *
     * 失败时**保留旧缓存**，仅更新 meta 的状态字段。
     */
    fun fetchSource(rootDir: File, id: String): Triple<Boolean, Int, String> {
        val src = findSource(rootDir, id) ?: return Triple(false, 0, "源不存在")
        return fetchSource(rootDir, src)
    }

    private fun fetchSource(rootDir: File, src: OnlineRuleSource): Triple<Boolean, Int, String> {
        val (ok, body, err) = httpGet(src.url)
        if (!ok || body == null) {
            markStatus(rootDir, src, "FAILED", err ?: "拉取失败", null)
            return Triple(false, 0, err ?: "拉取失败")
        }
        val parsed = RuleDocCodec.parse(body, strict = true)
        if (parsed is RuleDoc.Result.Err) {
            markStatus(rootDir, src, "FAILED", "规则校验失败: ${parsed.message}", null)
            return Triple(false, 0, "规则校验失败: ${parsed.message}")
        }
        val doc = (parsed as RuleDoc.Result.Ok).doc
        val dir = sourceDir(rootDir, src.id)
        dir.mkdirs()
        writeAtomic(sourceRulesFile(rootDir, src.id), body)
        markStatus(rootDir, src, "SUCCESS", "", doc.groups.size, sha256(body))
        Logger.i("OnlineRuleStore", "在线规则拉取成功: ${src.name} 分组=${doc.groups.size}")
        return Triple(true, doc.groups.size, "")
    }

    /** 拉取所有「启用且到期」的源。返回 (成功数, 失败数)。 */
    fun fetchDue(rootDir: File, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        for (src in listSources(rootDir)) {
            if (!src.enabled) continue
            val due = src.lastFetchMs <= 0L ||
                now - src.lastFetchMs >= src.intervalHours.toLong() * 3600_000L
            if (!due) continue
            val r = fetchSource(rootDir, src)
            if (r.first) ok++ else fail++
        }
        return ok to fail
    }

    /** 读取某源的缓存原文（供编辑器导入）。 */
    fun readSourceContent(rootDir: File, id: String): String? {
        val f = sourceRulesFile(rootDir, id)
        if (!f.exists()) return null
        return try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { null }
    }

    private fun markStatus(
        rootDir: File,
        src: OnlineRuleSource,
        status: String,
        error: String,
        ruleCount: Int?,
        sha: String? = null,
    ) = synchronized(lock) {
        val sources = listSources(rootDir).toMutableList()
        val idx = sources.indexOfFirst { it.id == src.id }
        if (idx >= 0) {
            val old = sources[idx]
            sources[idx] = old.copy(
                lastFetchMs = if (status == "SUCCESS") System.currentTimeMillis() else old.lastFetchMs,
                lastStatus = status,
                lastError = error,
                ruleCount = ruleCount ?: old.ruleCount,
                sha256 = sha ?: old.sha256,
            )
            writeSources(rootDir, sources)
        }
    }

    // ------------------------------------------------------------------
    // 合并为运行期规则
    // ------------------------------------------------------------------

    /**
     * 汇总所有「启用源」的规则 + 用户本地规则为 [CleanRule] 列表。
     *
     * id 前缀：在线 `ol_<srcId>_`，用户 `ur_`。switchKey 留空（随总开关），
     * 避免与 switches.conf 内建键冲突导致误判为「未启用」而不生效。
     *
     * @param includeDisabledSources 是否包含被禁用的源（编辑器展示用）。
     */
    fun allRules(rootDir: File, includeDisabledSources: Boolean = false): List<CleanRule> {
        val out = ArrayList<CleanRule>()
        for (src in listSources(rootDir)) {
            if (!src.enabled && !includeDisabledSources) continue
            val text = readSourceContent(rootDir, src.id) ?: continue
            val parsed = RuleDocCodec.parse(text, strict = false)
            if (parsed is RuleDoc.Result.Ok) {
                parsed.doc.groups.forEachIndexed { i, g ->
                    if (!g.enabled) return@forEachIndexed
                    out += RuleDocCodec.toCleanRule(g, "ol_${src.id}_", i)
                }
            }
        }
        // 用户本地规则
        val userText = readUserRules(rootDir)
        if (!userText.isNullOrBlank()) {
            val parsed = RuleDocCodec.parse(userText, strict = false)
            if (parsed is RuleDoc.Result.Ok) {
                parsed.doc.groups.forEachIndexed { i, g ->
                    if (!g.enabled) return@forEachIndexed
                    out += RuleDocCodec.toCleanRule(g, "ur_", i)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // 用户规则读写
    // ------------------------------------------------------------------

    fun readUserRules(rootDir: File): String? {
        val f = userRulesFile(rootDir)
        if (!f.exists()) return null
        return try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { null }
    }

    /**
     * 保存用户规则（先校验）。返回 null 表示成功，否则为错误原因。
     */
    fun saveUserRules(rootDir: File, json: String): String? {
        val parsed = RuleDocCodec.parse(json, strict = true)
        if (parsed is RuleDoc.Result.Err) return parsed.message
        val doc = (parsed as RuleDoc.Result.Ok).doc
        return try {
            writeAtomic(userRulesFile(rootDir), RuleDocCodec.encode(doc))
            Logger.i("OnlineRuleStore", "用户规则已保存: 分组=${doc.groups.size}")
            null
        } catch (t: Throwable) {
            "写入失败: ${t.message}"
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    fun isHttpUrl(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) && url.length in 8..2048

    private fun randomHex(n: Int): String {
        val bytes = ByteArray((n + 1) / 2)
        java.security.SecureRandom().nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString().substring(0, n)
    }

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** 简易 HTTP GET（仅直链，限制大小与超时）。返回 (ok, body, error)。 */
    private fun httpGet(url: String): Triple<Boolean, String?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ZhangSystemDex/1.0 (online-rules)")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return Triple(false, null, "HTTP $code")
            }
            val body = conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BODY_BYTES) {
                        return Triple(false, null, "响应体超过 ${MAX_BODY_BYTES / 1024}KB 上限")
                    }
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
            Triple(true, body, null)
        } catch (t: Throwable) {
            Triple(false, null, "网络错误: ${t.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }
}
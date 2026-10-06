package io.github.fairyxh.zhangsystemdex.core

/**
 * 无障碍服务授权核心（root）。
 *
 * 与 tools/a11y.sh 功能对等，走进程内 Settings + shell 降级。
 * 与 [AccessibilityGuardModule] 互补：那个模块负责「保活已启用的服务」，
 * 本类负责「免点击启用/关闭服务」。
 *
 * 覆盖：
 *   1. enabled_accessibility_services  启用/关闭无障碍服务（自动探测组件）
 *   2. accessibility_enabled           总开关
 *   3. 生效检测                        对比 dumpsys 的 Bound services
 *
 * 实现要点 / 踩坑记录（真机 Android 15 / ColorOS 实测）：
 *  - 无障碍**没有** `cmd accessibility enable` 之类的命令，只能写 Settings，
 *    与通知使用权的 `cmd notification allow_listener` 不同。
 *  - `enabled_accessibility_services` 是 `:` 分隔的组件列表，组件形如
 *    `pkg/pkg.Class`（内部类 `$` 保留、首点展开），与通知 listener 一致。
 *  - 部分应用故意用**别名组件**（如 gkd 声明
 *    `li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService`），
 *    因此探测必须按包名取 dumpsys 的 AccessibilityService 段，
 *    不能按类名前缀猜。
 *  - `accessibility_enabled` 必须为 1，否则列表写了也不生效。
 *  - 写入后系统通常**立即绑定**（无需重启应用）；但个别 ROM 需等待或重启应用。
 *    用 `dumpsys accessibility` 的 Bound services 段可确认是否真正生效。
 *  - clean 必须 fail-safe：枚举不到组件的条目保守保留（同通知权限）。
 */
object AccessibilityGrant {

    private const val TAG = "AccessibilityGrant"
    private const val KEY_SERVICES = "enabled_accessibility_services"
    private const val KEY_ENABLED = "accessibility_enabled"

    const val PERM_BIND = "android.permission.BIND_ACCESSIBILITY_SERVICE"

    // ======================= Settings 读写 =======================

    fun getRawServices(): String =
        SettingsUtils.getSecure(KEY_SERVICES)?.takeIf { it.isNotEmpty() && it != "null" }
            ?: ShellExecutor.run("settings get secure $KEY_SERVICES")?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
            ?: ""

    fun listComponents(): List<String> =
        getRawServices().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    fun listPackages(): List<String> =
        listComponents().map { it.substringBefore('/') }.distinct().sorted()

    fun writeServices(list: List<String>) {
        val joined = list.filter { it.isNotBlank() }.joinToString(":")
        SettingsUtils.putSecure(KEY_SERVICES, joined)
    }

    fun isServiceEnabled(pkg: String): Boolean =
        listComponents().any { it.startsWith("$pkg/") }

    fun servicesOf(pkg: String): List<String> =
        listComponents().filter { it.startsWith("$pkg/") }

    /** 总开关是否打开。 */
    fun isMasterEnabled(): Boolean =
        (SettingsUtils.getSecure(KEY_ENABLED) ?: "").trim() == "1"

    /** 打开总开关。 */
    fun ensureMasterSwitch(): Boolean {
        if (isMasterEnabled()) return true
        SettingsUtils.putSecure(KEY_ENABLED, "1")
        Logger.i(TAG, "[switch] accessibility_enabled=1")
        return true
    }

    // ======================= 生效检测 =======================

    /**
     * 系统**实际绑定**的无障碍服务组件列表。
     *
     * 优先用框架 API：`AccessibilityManager.getEnabledAccessibilityServiceList()`
     * 返回的是已解析并**处于绑定**状态的 [AccessibilityServiceInfo]，可拿到 id
     * 与 resolvedInfo（组件名）。dumpsys 的 `Bound services:` 段只有 label（无
     * 组件名），无法直接映射到包，故仅作为降级依据（只能数数量）。
     */
    fun boundComponents(): List<String> {
        // 1) 框架 API（最准）
        val viaFw = runCatching {
            val ctx = SystemContext.get() ?: return@runCatching emptyList<String>()
            val am = ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager
                ?: return@runCatching emptyList<String>()
            am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).mapNotNull { info ->
                runCatching { info.resolveInfo?.serviceInfo }
                    .getOrNull()
                    ?.let { si -> "${si.packageName}/${si.name}" }
            }
        }.getOrDefault(emptyList())
        if (viaFw.isNotEmpty()) return viaFw.distinct()

        // 2) 降级：解析 dumpsys 的 Enabled services（含组件名）。注意这只是
        //    「已启用」，不代表已绑定；在框架 API 不可用时作为近似值。
        val dump = ShellExecutor.run("dumpsys accessibility", 25000L) ?: return emptyList()
        val out = mutableListOf<String>()
        var inSection = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Enabled services:")) { inSection = true; continue }
            if (inSection) {
                if (line.startsWith("Binding services:")) break
                Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.${'$'}]+")
                    .findAll(line).forEach { out.add(normalizeComponent(it.value)) }
            }
        }
        return out.distinct()
    }

    /** Bound services 段里的服务数量（dumpsys，仅有 label，用于交叉验证）。 */
    fun boundServiceCount(): Int {
        val dump = ShellExecutor.run("dumpsys accessibility", 25000L) ?: return -1
        var inSection = false
        var count = 0
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("Bound services:")) { inSection = true; continue }
            if (inSection) {
                if (line.startsWith("Enabled services:")) break
                count += Regex("Service\\[label=").findAll(line).count()
            }
        }
        return count
    }

    fun isBound(pkg: String): Boolean = boundComponents().any { it.startsWith("$pkg/") }

    // ======================= 组件探测 =======================

    /** 探测某包的 AccessibilityService 组件（相对形式，如 `pkg/.Service`）。 */
    fun probeComponents(pkg: String): List<String> {
        // 结果缓存（含空结果）：保住「本包没有 AccessibilityService」这一负结论，
        // 避免保活 tick（15s）对 45 个内置应用**每轮**重新 dump。
        // 无缓存时没有无障碍组件的应用会被永久重试 → 每 15s 一条
        // `cmd package dump` → system_server Binder 池耗尽 → Watchdog 软重启。
        probeCache[pkg]?.let { return it }
        val dump = ShellExecutor.run("cmd package dump $pkg", 8_000L)
            ?: ShellExecutor.run("dumpsys package $pkg", 8_000L)
            ?: return emptyList() // IPC 失败：不缓存，允许下次重试
        val result = mutableListOf<String>()
        var inSection = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("android.accessibilityservice.AccessibilityService:")) {
                inSection = true
                continue
            }
            if (inSection) {
                if (line.endsWith(":") && !line.contains(' ') && !line.contains('/')) {
                    inSection = false
                    continue
                }
                val m = Regex("([A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.${'$'}]+)").find(line)
                if (m != null) {
                    val p = m.groupValues[1]
                    val c = m.groupValues[3]
                    if (p != "android") result.add("$p/$c")
                }
            }
        }
        val out = result.distinct()
        probeCache[pkg] = out
        return out
    }

    /** 组件探测结果缓存（含空结果，避免无无障碍组件的包被永久重复 dump）。 */
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** 探查失败（IPC 超时）时清空缓存，允许下一次重试。 */
    fun invalidateProbe(pkg: String) { probeCache.remove(pkg) }

    /** 把 `pkg/.Svc` 规范化为 `pkg/pkg.Svc`。 */
    fun normalizeComponent(comp: String): String {
        val pkg = comp.substringBefore('/')
        var cls = comp.substringAfter('/', "")
        if (cls.isEmpty()) return comp
        cls = when {
            cls.startsWith(".") -> pkg + cls
            cls.contains(".") -> cls
            else -> "$pkg.$cls"
        }
        return "$pkg/$cls"
    }

    // ======================= 授权 =======================

    /**
     * 启用某包的无障碍服务。
     *
     * @param pkg          目标包名
     * @param waitSettle   是否等待写入可见
     */
    fun enable(pkg: String, waitSettle: Boolean = true): Boolean {
        if (isServiceEnabled(pkg)) {
            Logger.i(TAG, "[service] $pkg 已启用，跳过")
            ensureMasterSwitch()
            return true
        }
        val comps = probeComponents(pkg)
        if (comps.isEmpty()) {
            Logger.w(TAG, "[service] $pkg 未找到无障碍服务组件")
            return false
        }
        var current = listComponents()
        var ok = false
        for (c in comps) {
            val norm = normalizeComponent(c)
            if (current.contains(norm)) { ok = true; continue }
            val updated = (current + norm).distinct()
            writeServices(updated)
            if (waitSettle) waitSettled(norm)
            if (isServiceEnabled(pkg)) {
                Logger.i(TAG, "[service] 已启用 $norm")
                current = listComponents()
                ok = true
            } else {
                Logger.w(TAG, "[service] 写入未生效: $norm")
            }
        }
        if (ok) ensureMasterSwitch()
        return ok
    }

    fun enableAll(pkgs: List<String>): Map<String, Boolean> =
        pkgs.associateWith { enable(it) }

    /** 关闭某包的无障碍服务（移除其全部条目）。 */
    fun disable(pkg: String): Boolean {
        val current = listComponents()
        val kept = current.filterNot { it.startsWith("$pkg/") }
        if (kept.size == current.size) {
            Logger.i(TAG, "disable: $pkg 未启用")
            return false
        }
        writeServices(kept)
        Logger.i(TAG, "disable: 已关闭 $pkg")
        return true
    }

    // ======================= 清理 =======================

    /**
     * 清理失效条目（fail-safe：枚举不到组件的保守保留）。
     */
    fun cleanInvalid(report: (String) -> Unit = {}): Int {
        val current = listComponents()
        if (current.isEmpty()) return 0
        val kept = mutableListOf<String>()
        var removed = 0
        for (line in current) {
            val pkg = line.substringBefore('/')
            val installed = ShellExecutor.run("cmd package list packages $pkg", 15000L)
                ?.lineSequence()?.any { it.trim() == "package:$pkg" } == true
            if (!installed) {
                report("移除（包未安装）: $line"); removed++; continue
            }
            val comps = probeComponents(pkg)
            if (comps.isEmpty()) {
                report("保留（无法枚举组件）: $line"); kept.add(line); continue
            }
            if (line in comps.map { normalizeComponent(it) }) kept.add(line)
            else { report("移除（组件不存在）: $line"); removed++ }
        }
        if (removed > 0) writeServices(kept)
        Logger.i(TAG, "clean: 移除 $removed 条，保留 ${kept.size} 条")
        return removed
    }

    // ======================= 自检输出 =======================

    fun inspect(pkg: String): String {
        val sb = StringBuilder()
        sb.appendLine("========== $pkg ==========")
        sb.appendLine("无障碍总开关: ${if (isMasterEnabled()) "开" else "关"}")
        if (isServiceEnabled(pkg)) {
            sb.appendLine("无障碍权限: 已启用")
            servicesOf(pkg).forEach { sb.appendLine("  已配置: $it") }
        } else {
            sb.appendLine("无障碍权限: 未启用")
        }
        val comps = probeComponents(pkg)
        if (comps.isEmpty()) sb.appendLine("可用组件: (无)")
        else { sb.appendLine("可用组件:"); comps.forEach { sb.appendLine("  $it") } }
        sb.appendLine("实际绑定: ${if (isBound(pkg)) "是" else "否"}")
        return sb.toString()
    }

    fun summary(): String {
        val pkgs = listPackages()
        return "已启用无障碍的应用: ${pkgs.size} 个（总开关: ${if (isMasterEnabled()) "开" else "关"}）\n" +
            pkgs.joinToString("\n") { "  $it" }
    }

    /** 已配置 vs 实际绑定（供排查）。 */
    fun boundDiff(): String {
        val configured = listComponents()
        val bound = boundComponents()
        val sb = StringBuilder()
        sb.appendLine("无障碍总开关: ${if (isMasterEnabled()) "开" else "关"}")
        sb.appendLine("-- 已配置（Settings）--")
        configured.forEach { sb.appendLine("  $it") }
        sb.appendLine("-- 实际绑定（dumpsys）--")
        bound.forEach { sb.appendLine("  $it") }
        val missing = configured.filter { it !in bound }
        if (missing.isNotEmpty()) {
            sb.appendLine("-- 配置了但未绑定 --")
            missing.forEach { sb.appendLine("  $it") }
        }
        return sb.toString()
    }

    private fun waitSettled(want: String, tries: Int = 20): Boolean {
        repeat(tries) {
            if (getRawServices().contains(want)) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        return false
    }
}
package io.github.fairyxh.zhangsystemdex.core

import android.os.Process

/**
 * 通知权限授予核心（root）。
 *
 * 与 tools/set.sh、tools/list.sh、tools/del.sh 等 shell 工具功能对等，
 * 但走进程内 API + shell 降级，适合被 daemon / HTTP 后端 / 调试菜单调用。
 *
 * 覆盖：
 *   1. 通知使用权   Settings.Secure.enabled_notification_listeners
 *   2. POST_NOTIFICATIONS（Android 13+ 运行时权限）
 *   3. POST_NOTIFICATION appop（op 名无 S，与权限名不同）
 *   4. ACCESS_NOTIFICATION_POLICY appop（若声明）
 *   5. 电池优化白名单（Doze whitelist）+ 后台运行放行
 *
 * 实现要点 / 踩坑记录（真机 Android 15 / ColorOS 实测）：
 *  - 组件名可能是内部类 `com.catchingnow.np/.E$V`。
 *    ComponentName.flattenToString() 输出 `pkg/pkg.E$V`（点号去掉，$ 保留）。
 *    写 Settings 时用后者；shell 里 `$` 必须单引号包住。
 *  - `cmd package list permissions <pkg>` 在 Android 15 返回空，
 *    判断「是否声明权限」必须解析 dumpsys 的 requested permissions 段。
 *  - `settings put secure` 是异步的，紧接着读可能拿到旧值，需要轮询。
 *  - `cmd notification allow_listener` 会把 Settings 里 `Has user set`
 *    的旧条目一并带回，授权后列表「变长」是正常现象。
 *  - `dumpsys package` 里没有 `userId=`，uid 要从 `appId=` 取。
 *  - ColorOS 的 `com.oplus.notificationmanager` 无法通过 dumpsys 枚举
 *    listener 组件，因此 clean 必须 fail-safe（枚举不到就保留），
 *    否则会误删系统 listener。
 *  - 应用界面报「Service has been disconnected」≠ 授权失败。若系统设置页
 *    显示「已允许」，那多半是应用自身误判或与 ROM 不兼容；rebind() 能做的
 *    只是重建绑定并重启应用进程，不能修复应用内部逻辑。
 */
object NotificationGrant {

    private const val TAG = "NotificationGrant"
    private const val KEY_LISTENERS = "enabled_notification_listeners"

    const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val PERM_ACCESS_NOTIFICATION_POLICY = "android.permission.ACCESS_NOTIFICATION_POLICY"

    // ======================= 数据结构 =======================

    /** 一次授权的结果明细。 */
    data class GrantResult(
        val pkg: String,
        /** 实际写入的 listener 组件，空表示未找到/未授权。 */
        val listeners: List<String> = emptyList(),
        /** 是否声明了 POST_NOTIFICATIONS。 */
        val declaredPostNotif: Boolean = false,
        /** POST_NOTIFICATIONS 授权是否成功（未声明时为 null）。 */
        val postNotifGranted: Boolean? = null,
        /** POST_NOTIFICATION appop 是否设置成功。 */
        val appopSet: Boolean = false,
        /** 是否已加入电池优化白名单。 */
        val dozeWhitelisted: Boolean = false,
        /** 触发的警告信息。 */
        val warnings: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = listeners.isNotEmpty() || postNotifGranted == true
    }

    // ======================= Settings 读写 =======================

    /** 读原始的 enabled_notification_listeners 字符串。 */
    fun getRawListeners(): String {
        val viaFw = runCatching {
            SystemContext.get()?.contentResolver?.let {
                android.provider.Settings.Secure.getString(it, KEY_LISTENERS)
            }
        }.getOrNull()
        if (!viaFw.isNullOrBlank() && viaFw != "null") return viaFw
        return ShellExecutor.run("settings get secure $KEY_LISTENERS")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
    }

    /** 已授权的 listener 组件列表。 */
    fun listListeners(): List<String> =
        getRawListeners().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** 已授权的包名列表（去重排序）。 */
    fun listPackages(): List<String> =
        listListeners().map { it.substringBefore('/') }.distinct().sorted()

    /** 覆盖式写入 listener 列表。 */
    fun writeListeners(list: List<String>) {
        val joined = list.filter { it.isNotBlank() }.joinToString(":")
        val cr = runCatching { SystemContext.get()?.contentResolver }.getOrNull()
        val viaFw = if (cr != null) {
            runCatching {
                android.provider.Settings.Secure.putString(cr, KEY_LISTENERS, joined)
                true
            }.getOrDefault(false)
        } else false
        if (!viaFw) {
            // 值里可能含 $，必须单引号包裹
            ShellExecutor.run("settings put secure $KEY_LISTENERS '$joined'")
        }
    }

    /** 某包是否已在 listener 列表中。 */
    fun isListenerEnabled(pkg: String): Boolean =
        listListeners().any { it.startsWith("$pkg/") }

    /** 某包已授权的 listener 组件（可能多个）。 */
    fun listenersOf(pkg: String): List<String> =
        listListeners().filter { it.startsWith("$pkg/") }

    // ======================= 存活检测（binder 是否真的连着） =======================

    /**
     * 当前真正处于 live（binder 已连接）状态的 listener 组件集合。
     *
     * 关键：`enabled_notification_listeners` 只表示「已被授权」，
     * 并不代表 binder 真的连着。当 NMS 认为某 listener「状态已知」时，
     * 它不会主动重绑，于是会出现「settings 里有、但 binder 是死的」的僵尸态
     * —— 应用侧表现就是「Service has been disconnected」。
     *
     * 判定依据：`dumpsys notification` 的 `Live notification listeners` 段。
     * 行形如 `ComponentInfo{pkg/cls} (user N): ...`，只取 user 0。
     * 解析失败时返回 null，调用方应保守处理（不误判为掉线）。
     */
    fun liveListeners(): Set<String>? {
        // 结果缓存 [LIVE_TTL_MS]：本方法会被遍历循环（保活 tick）反复调用，而
        // `dumpsys notification` 输出可达 1MB+、且最终在 system_server 内执行；
        // 无缓存时一轮遍历就会发起 N 条该命令 → system_server Binder 池耗尽 →
        // Watchdog 杀死 system_server（2026-10-06 软重启事故放大器）。
        val now = System.currentTimeMillis()
        liveCache?.let { if (now - liveAt < LIVE_TTL_MS) return it }
        // 超时从 20s 收紧到 5s：该命令若被 system_server 卡住，20s 足够触发
        // Watchdog（15s）级联；5s 让其尽早放弃，减少悬停 binder 线程占用。
        val dump = ShellExecutor.run("dumpsys notification", 5_000L)
        if (dump == null) {
            Logger.w(TAG, "liveListeners: dumpsys notification 执行失败/超时")
            return liveCache
        }
        val parsed = parseLiveListeners(dump)
        if (parsed == null) {
            Logger.w(TAG, "liveListeners: 输出中未找到 Live 段（长度=${dump.length}，" +
                "含 NotificationService=${dump.contains("NotificationService")}，" +
                "含 Live=${dump.contains("Live")}）")
        } else {
            liveCache = parsed
            liveAt = now
        }
        return parsed
    }

    @Volatile private var liveCache: Set<String>? = null
    @Volatile private var liveAt: Long = 0L
    /** `dumpsys notification` 结果缓存时长（毫秒）。 */
    private const val LIVE_TTL_MS = 10_000L

    /** 从 dumpsys notification 输出中解析 live listener 组件集合。 */
    fun parseLiveListeners(dump: String): Set<String>? {
        return try {
            val start = dump.indexOf("Live notification listeners")
            if (start < 0) return null
            val end = dump.indexOf("Snoozed notification listeners", start)
            // 防御：段尾必须严格晚于段头；否则用固定长度窗口，避免 substring 越界。
            val safeEnd = if (end > start) end else minOf(dump.length, start + 200_000)
            if (safeEnd <= start) return null
            val section = dump.substring(start, safeEnd)
            val out = LinkedHashSet<String>()
            // 注意：正则的字符类里若写 "$]" 会被 Kotlin 的字符串模板误解析，
            // 所以这里统一把 `$` 拆成 [$] 的等价写法（chr(36)），彻底绕开模板。
            val dollar = 36.toChar()
            val pattern = "ComponentInfo\\{([A-Za-z0-9_.]+/[A-Za-z0-9_.$dollar]+)[^\\n]*\\(user\\s+(\\d+)\\)"
            Logger.i(TAG, "parseLiveListeners pattern=${pattern.replace("\\", "\\\\")}")
            val re = try {
                Regex(pattern)
            } catch (t: Throwable) {
                Logger.w(TAG, "Regex 构建失败: ${t}")
                return null
            }
            for (m in re.findAll(section)) {
                // 只关心主用户；user -1 是系统内建监听器
                if (m.groupValues[2] == "0") out.add(m.groupValues[1])
            }
            out
        } catch (t: Throwable) {
            Logger.w(TAG, "parseLiveListeners 异常: ${t}")
            null
        }
    }

    /**
     * 某组件是否 live（binder 已连接）。
     * @return true=live，false=已授权但 binder 未连接（僵尸态），null=无法判定
     */
    fun isLive(comp: String): Boolean? {
        val live = liveListeners() ?: return null
        val norm = normalizeComponent(comp)
        return live.any { normalizeComponent(it) == norm }
    }

    /**
     * 某包是否有任一 listener 处于 live 状态。
     * @return true=live，false=已授权但全部为僵尸态，null=无法判定或未授权
     */
    fun isPackageLive(pkg: String): Boolean? {
        val live = liveListeners() ?: return null
        return live.any { it.startsWith("$pkg/") }
    }

    // ======================= 组件探测 =======================

    /**
     * 探测某包的 NotificationListenerService 组件（相对形式，如 `pkg/.E$V`）。
     */
    fun probeComponents(pkg: String): List<String> {
        // 结果缓存（含空结果）：保住「本包没有 NotificationListenerService」这一
        // 负结论，避免保活 tick（15s）对 45 个内置应用**每轮**重新 dump。
        // 不加缓存时，没有监听组件的应用会被永久重试 → 每 15s 一条
        // `dumpsys package`/`cmd package dump` → system_server Binder 池耗尽
        // → Watchdog 杀死 system_server（2026-10-06 软重启事故主力放大器）。
        probeCache[pkg]?.let { return it }
        val dump = ShellExecutor.run("dumpsys package $pkg", 8_000L)
            ?: ShellExecutor.run("cmd package dump $pkg", 8_000L)
            ?: return emptyList() // IPC 失败：**不缓存**，允许下次重试
        val result = mutableListOf<String>()
        var inSection = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("android.service.notification.NotificationListenerService:")) {
                inSection = true
                continue
            }
            if (inSection) {
                // 段落结束：遇到另一个顶级小节就退出
                if (line.endsWith(":") && !line.contains(' ') &&
                    !line.contains("NotificationListenerService") && !line.contains('/')
                ) {
                    inSection = false
                    continue
                }
                val m = Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.${'$'}]+)").find(line)
                if (m != null) {
                    val p = m.groupValues[1]
                    val c = m.groupValues[2]
                    if (p != "android") result.add("$p/$c")
                }
            }
        }
        val out = result.distinct()
        probeCache[pkg] = out
        return out
    }

    /** 组件探测结果缓存（含空结果，避免无监听组件的包被永久重复 dump）。 */
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** 探查失败（IPC 超时）时清空缓存，允许下一次重试。 */
    fun invalidateProbe(pkg: String) { probeCache.remove(pkg) }

/** 把 `pkg/.E$V` 规范化为 `pkg/pkg.E$V`（与 framework flattenToString 一致）。 */
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

    // ======================= 权限声明 / 授予检测 =======================

    /**
     * 应用是否声明了某权限。
     * 注意：不能用 `cmd package list permissions <pkg>`（Android 15 返回空）。
     */
    fun declaresPermission(pkg: String, perm: String): Boolean {
        val dump = ShellExecutor.run("dumpsys package $pkg", 20000L) ?: return false
        var inSec = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("requested permissions:")) { inSec = true; continue }
            if (inSec) {
                if (line.startsWith("install permissions:") || line.startsWith("runtime permissions:")) {
                    inSec = false
                    continue
                }
                if (line == perm) return true
            }
        }
        return false
    }

    /** 运行时权限是否已 granted=true。 */
    fun isPermissionGranted(pkg: String, perm: String): Boolean {
        val dump = ShellExecutor.run("dumpsys package $pkg", 20000L) ?: return false
        return dump.contains("$perm: granted=true")
    }

    /** 应用是否已安装。 */
    fun isInstalled(pkg: String): Boolean =
        ShellExecutor.run("cmd package list packages $pkg", 15000L)
            ?.lineSequence()?.any { it.trim() == "package:$pkg" } == true

    /** 取 uid（从 appId= 解析；Android 15 的 dumpsys 没有 userId=）。 */
    fun getUid(pkg: String): Int {
        val dump = ShellExecutor.run("dumpsys package $pkg", 20000L) ?: return -1
        return Regex("appId=(\\d+)").find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    // ======================= 授权 =======================

    /**
     * 为单个包授予通知相关全部权限。
     *
     * @param pkg          目标包名
     * @param waitSettle   是否等待 Settings 写入可见（建议 true）
     * @param background   是否加 Doze 白名单 + 放行后台（默认 true）
     */
    fun grant(pkg: String, waitSettle: Boolean = true, background: Boolean = true): GrantResult {
        val warnings = mutableListOf<String>()
        var listeners = emptyList<String>()
        var declaredPost = false
        var grantedPost: Boolean? = null
        var appopOk = false
        var dozeOk = false

        Logger.i(TAG, "开始授权: $pkg")

        // ---- 1) 通知使用权 ----
        if (isListenerEnabled(pkg)) {
            Logger.i(TAG, "[listener] $pkg 已授权，跳过")
            listeners = listenersOf(pkg)
        } else {
            val comps = probeComponents(pkg)
            if (comps.isEmpty()) {
                warnings.add("未找到 NotificationListenerService 组件")
                Logger.w(TAG, "[listener] $pkg 未找到 listener 组件，跳过")
                // 无监听组件 → 后续的 POST_NOTIFICATIONS / appop / Doze 白名单全部无意义，
                // **直接返回**，不再为该包发起 3~4 条 system_server IPC。
                // 保活 tick 会对全部内置应用（45 个）调用本方法，若继续执行，
                // 每轮就是 45×4 ≈ 180 条 IPC，是软重启的主力放大器之一。
                return GrantResult(pkg, listeners, false, null, false, false, warnings)
            } else {
                for (c in comps) {
                    val norm = normalizeComponent(c)
                    val rc = ShellExecutor.runExit("cmd notification allow_listener '$norm'", 20000L)
                    if (rc == 0) {
                        Logger.i(TAG, "[listener] 已授权 $norm")
                        listeners = listeners + norm
                    } else {
                        warnings.add("allow_listener 失败($rc): $norm")
                        Logger.w(TAG, "[listener] allow_listener 失败 rc=$rc: $norm")
                    }
                }
                if (waitSettle && listeners.isNotEmpty()) {
                    waitListenerSettled(listeners.first())
                }
            }
        }

        // ---- 2) POST_NOTIFICATIONS 权限 + appop ----
        if (declaresPermission(pkg, PERM_POST_NOTIFICATIONS)) {
            declaredPost = true
            grantedPost = when {
                isPermissionGranted(pkg, PERM_POST_NOTIFICATIONS) -> {
                    Logger.i(TAG, "[perm] POST_NOTIFICATIONS 已 granted")
                    true
                }
                ShellExecutor.runExit("pm grant $pkg $PERM_POST_NOTIFICATIONS", 20000L) == 0 -> {
                    Logger.i(TAG, "[perm] POST_NOTIFICATIONS 已授予")
                    true
                }
                else -> {
                    warnings.add("pm grant POST_NOTIFICATIONS 失败")
                    Logger.w(TAG, "[perm] POST_NOTIFICATIONS 授予失败")
                    false
                }
            }
            appopOk = ShellExecutor.runExit("cmd appops set $pkg POST_NOTIFICATION allow", 20000L) == 0
            if (appopOk) Logger.i(TAG, "[appop] POST_NOTIFICATION=allow")
        } else {
            Logger.i(TAG, "[perm] $pkg 未声明 POST_NOTIFICATIONS，跳过")
        }

        // ---- 3) ACCESS_NOTIFICATION_POLICY appop ----
        if (declaresPermission(pkg, PERM_ACCESS_NOTIFICATION_POLICY)) {
            if (ShellExecutor.runExit("cmd appops set $pkg ACCESS_NOTIFICATION_POLICY allow", 20000L) == 0) {
                Logger.i(TAG, "[appop] ACCESS_NOTIFICATION_POLICY=allow")
            }
        }

        // ---- 4) 后台存活 ----
        if (background) {
            dozeOk = addDozeWhitelist(pkg)
            allowBackground(pkg)
        }

        return GrantResult(pkg, listeners, declaredPost, grantedPost, appopOk, dozeOk, warnings)
    }

    /** 批量授权。 */
    fun grantAll(pkgs: List<String>, background: Boolean = true): List<GrantResult> =
        pkgs.map { grant(it, background = background) }

    /** 撤销某包的通知使用权（移除 Settings 条目 + disallow_listener）。 */
    fun revoke(pkg: String): Boolean {
        val current = listListeners()
        val kept = current.filterNot { it.startsWith("$pkg/") }
        if (kept.size == current.size) {
            Logger.i(TAG, "revoke: $pkg 不在列表中")
            return false
        }
        writeListeners(kept)
        ShellExecutor.run("cmd notification disallow_listener '$pkg'", 20000L)
        Logger.i(TAG, "revoke: 已移除 $pkg")
        return true
    }

    /** 撤销 POST_NOTIFICATIONS。 */
    fun revokePostNotif(pkg: String): Boolean {
        val rc = ShellExecutor.runExit("pm revoke $pkg $PERM_POST_NOTIFICATIONS", 20000L)
        if (rc == 0) {
            Logger.i(TAG, "revokePostNotif: 已撤销 $pkg")
            return true
        }
        ShellExecutor.runExit("cmd appops set $pkg POST_NOTIFICATION ignore", 20000L)
        Logger.w(TAG, "revokePostNotif: pm revoke 失败，降级 appop ignore")
        return false
    }

    // ======================= 后台存活 =======================

    /** 加入电池优化白名单。 */
    fun addDozeWhitelist(pkg: String): Boolean {
        val (code, _) = ShellExecutor.runWithCode("dumpsys deviceidle whitelist +$pkg", 15000L)
        val ok = code == 0
        if (ok) Logger.i(TAG, "[doze] $pkg 已加入白名单")
        return ok
    }

    /** 移出电池优化白名单。 */
    fun removeDozeWhitelist(pkg: String): Boolean {
        val (code, _) = ShellExecutor.runWithCode("dumpsys deviceidle whitelist -$pkg", 15000L)
        val ok = code == 0
        if (ok) Logger.i(TAG, "[doze] $pkg 已移出白名单")
        return ok
    }

    /** 是否在电池优化白名单。 */
    fun isDozeWhitelisted(pkg: String): Boolean =
        ShellExecutor.run("dumpsys deviceidle whitelist", 15000L)
            ?.contains(",$pkg,") == true

    /** 放行后台运行 appop。 */
    fun allowBackground(pkg: String) {
        ShellExecutor.run("cmd appops set $pkg RUN_IN_BACKGROUND allow", 15000L)
        ShellExecutor.run("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow", 15000L)
        Logger.i(TAG, "[appop] $pkg 后台运行已放行")
    }

    // ======================= 清理 / 重绑 =======================

    /**
     * 清理失效 listener 条目（fail-safe：判不准的保守保留）。
     *
     * 判定：包未安装 -> 删；能枚举组件且不含该条目 -> 删；
     *       枚举不到组件（如 ColorOS 的 oplus.notificationmanager）-> 保留。
     */
    fun cleanInvalid(report: (String) -> Unit = {}): Int {
        val current = listListeners()
        if (current.isEmpty()) return 0
        val kept = mutableListOf<String>()
        var removed = 0

        for (line in current) {
            val pkg = line.substringBefore('/')
            if (!isInstalled(pkg)) {
                report("移除（包未安装）: $line")
                removed++
                continue
            }
            val comps = probeComponents(pkg)
            if (comps.isEmpty()) {
                report("保留（无法枚举组件）: $line")
                kept.add(line)
                continue
            }
            val normComps = comps.map { normalizeComponent(it) }
            if (line in normComps) {
                kept.add(line)
            } else {
                report("移除（组件不存在）: $line")
                removed++
            }
        }

        if (removed > 0) writeListeners(kept)
        Logger.i(TAG, "clean: 移除 $removed 条，保留 ${kept.size} 条")
        return removed
    }
    /**
     * 轻量强制重绑：不重启应用进程，只做 NMS 层的「解绑 -> 重绑」。
     *
     * 这是修复「僵尸 listener」的核心手段。实测（Android 15 / ColorOS）：
     * 直接 `settings put secure enabled_notification_listeners` 无效 ——
     * NMS 不保证重建 binder；必须走 `cmd notification disallow_listener`
     * + `allow_listener` 这条正式流程，NMS 才会真正重新 bind。
     *
     * @param comp 已授权的 listener 组件（`pkg/pkg.E$V` 形式）
     * @return true=重绑后已 live
     */
    fun forceRebind(comp: String): Boolean {
        val norm = normalizeComponent(comp)
        Logger.i(TAG, "forceRebind: $norm")
        ShellExecutor.run("cmd notification disallow_listener '$norm'", 20_000L)
        // 给 NMS 一点时间完成解绑（解绑会触发 onListenerDisconnected）
        Thread.sleep(500)
        val rc = ShellExecutor.runExit("cmd notification allow_listener '$norm'", 20_000L)
        if (rc != 0) {
            Logger.w(TAG, "forceRebind: allow_listener 返回 $rc")
            return false
        }
        // 轮询等待 binder 真正连上（NMS 是异步 bind 的）
        repeat(20) {
            if (isLive(norm) == true) {
                Logger.i(TAG, "forceRebind: 已 live $norm")
                return true
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
        }
        Logger.w(TAG, "forceRebind: 重绑后仍未 live $norm")
        return false
    }

    /**
     * 轻量强制重绑（按包名，自动取已学习/已授权组件）。
     * @return true=重绑成功
     */
    fun forceRebindPackage(pkg: String): Boolean {
        val comp = listenersOf(pkg).firstOrNull()
            ?: probeComponents(pkg).firstOrNull()?.let { normalizeComponent(it) }
            ?: return false
        return forceRebind(comp)
    }

    /**
     * 强制刷新某包的通知使用权绑定。
     *
     * 流程：解绑 -> 重绑 -> 重启应用 -> 发测试通知触发 bind。
     *
     * 注意：这不能修复「应用自身误判」的情况。若系统设置页显示已允许
     * 但应用仍报断开，多半是应用与 ROM 的兼容问题。
     */
    fun rebind(pkg: String, softOnly: Boolean = false): Boolean {
        if (!softOnly) {
            val existing = listenersOf(pkg).firstOrNull()
            if (existing != null) {
                revoke(pkg)
                Thread.sleep(800)
                val rc = ShellExecutor.runExit("cmd notification allow_listener '$existing'", 20000L)
                if (rc == 0) {
                    waitListenerSettled(existing)
                    Logger.i(TAG, "rebind: 已重绑 $existing")
                } else {
                    Logger.w(TAG, "rebind: 重绑失败，改用探测组件")
                    grant(pkg, background = false)
                }
            } else {
                grant(pkg, background = false)
            }
        }
        // 重启应用进程
        ShellExecutor.run("am force-stop $pkg", 15000L)
        Thread.sleep(800)
        ShellExecutor.run("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 15000L)
        Thread.sleep(2000)
        // 发通知触发 bind
        ShellExecutor.run(
            "cmd notification post -S bigtext -t rebind-test rebind_${System.currentTimeMillis()} ok",
            15000L
        )
        val ok = listenersOf(pkg).isNotEmpty()
        Logger.i(TAG, "rebind: ${if (ok) "已重绑" else "未授权"} $pkg")
        return ok
    }

    // ======================= 自检输出 =======================

    /**
     * 生成某包的完整授权状态文本（供 HTTP 接口 / 日志 / 调试菜单使用）。
     */
    fun inspect(pkg: String): String {
        val sb = StringBuilder()
        sb.appendLine("========== $pkg ==========")
        sb.appendLine("uid(root)=${Process.myUid()}")
        sb.appendLine("安装: ${if (isInstalled(pkg)) "是 (uid=${getUid(pkg)})" else "否"}")
        sb.appendLine("-- 通知使用权 --")
        if (isListenerEnabled(pkg)) {
            listenersOf(pkg).forEach { sb.appendLine("  已授权: $it") }
        } else {
            sb.appendLine("  未开启")
        }
        sb.appendLine("-- 可用组件 --")
        val comps = probeComponents(pkg)
        if (comps.isEmpty()) sb.appendLine("  (无 / 无法枚举)") else comps.forEach { sb.appendLine("  $it") }
        sb.appendLine("-- POST_NOTIFICATIONS --")
        sb.appendLine(
            "  声明=${declaresPermission(pkg, PERM_POST_NOTIFICATIONS)} " +
                "已授予=${isPermissionGranted(pkg, PERM_POST_NOTIFICATIONS)}"
        )
        sb.appendLine("-- 后台 --")
        sb.appendLine("  Doze 白名单=${isDozeWhitelisted(pkg)}")
        return sb.toString()
    }

    /** 汇总统计（供概览页）。 */
    fun summary(): String {
        val pkgs = listPackages()
        return "已开启通知使用权的应用: ${pkgs.size} 个\n" + pkgs.joinToString("\n") { "  $it" }
    }

    // ======================= 内部 =======================

    /** 轮询等待 Settings 写入可见（settings put 是异步的）。 */
    private fun waitListenerSettled(want: String, tries: Int = 20): Boolean {
        repeat(tries) {
            if (getRawListeners().contains(want)) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        return false
    }
}
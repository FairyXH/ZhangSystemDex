package io.github.fairyxh.zhangsystemdex.core

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 通知权限授予核心（root）。
 *
 * 与 tools/notifgrant.sh 功能对等，但走进程内 API，无需 fork shell：
 *   1. 通知使用权   Settings.Secure.enabled_notification_listeners
 *   2. POST_NOTIFICATIONS（Android 13+ 运行时权限）
 *   3. POST_NOTIFICATION appop（op 名无 S，与权限名不同）
 *   4. ACCESS_NOTIFICATION_POLICY appop（若声明）
 *
 * 实现要点/踩坑记录（全部在真机 Android 15 / ColorOS 上验证）：
 *  - 组件名可能是内部类，形如 `com.catchingnow.np/.E$V`。
 *    ComponentName.flattenToString() 会输出 `pkg/pkg.E$V`（丢掉点号）。
 *    写 Settings 时用后者；`$` 在 shell 里要单引号包住。
 *  - `cmd package list permissions <pkg>` 在 Android 15 返回空，
 *    判断“是否声明权限”必须解析 dumpsys 的 requested permissions 段。
 *  - `settings put secure` 是异步的，紧接着 get 可能读到旧值，
 *    需要轮询等待（waitListenerSettled）。
 *  - `cmd notification allow_listener` 会把 Settings 里 `Has user set`
 *    集合的条目一并带回，因此授权后列表可能“变长”，这是正常现象。
 */
object NotificationGrant {

    private const val TAG = "NotificationGrant"
    private const val KEY_LISTENERS = "enabled_notification_listeners"

    const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val PERM_ACCESS_NOTIFICATION_POLICY = "android.permission.ACCESS_NOTIFICATION_POLICY"

    // ---------- 数据结构 ----------

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
        /** 触发的警告信息。 */
        val warnings: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = listeners.isNotEmpty() || postNotifGranted == true
    }

    // ---------- 读取/写入 Settings ----------

    /** 读原始的 enabled_notification_listeners 字符串。 */
    fun getRawListeners(): String {
        // 优先 framework，失败降级 shell
        val viaFw = runCatching {
            SystemContext.get()?.contentResolver?.let {
                android.provider.Settings.Secure.getString(it, KEY_LISTENERS)
            }
        }.getOrNull()
        if (!viaFw.isNullOrBlank() && viaFw != "null") return viaFw
        return ShellExecutor.run("settings get secure $KEY_LISTENERS")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
    }

    /** 已授权的 listener 列表（已拆分）。 */
    fun listListeners(): List<String> =
        getRawListeners().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** 直接写入 listener 列表（注意：覆盖式，需自行拼接完整串）。 */
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

    // ---------- 组件探测 ----------

    /**
     * 探测某包的 NotificationListenerService 组件（相对形式，如 `pkg/.E$V`）。
     * 解析 dumpsys package，兼容 `cmd package dump` 不可用的场景。
     */
    fun probeComponents(pkg: String): List<String> {
        val dump = ShellExecutor.run("dumpsys package $pkg", 20000L)
            ?: ShellExecutor.run("cmd package dump $pkg", 20000L)
            ?: return emptyList()

        val result = mutableListOf<String>()
        var inSection = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("android.service.notification.NotificationListenerService:")) {
                inSection = true
                continue
            }
            if (inSection) {
                // 段落结束：遇到另一个 "xxx:" 顶级小节（缩进更浅）就退出
                if (line.endsWith(":") && !line.contains(' ') &&
                    !line.contains("NotificationListenerService") && !line.contains('/')
                ) {
                    inSection = false
                    continue
                }
                // 组件行形如：<hash> com.pkg/.Cls filter <hash> permission android.permission.BIND_...
                val m = Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").find(line)
                if (m != null) {
                    val p = m.groupValues[1]
                    val c = m.groupValues[2]
                    if (p != "android") result.add("$p/$c")
                }
            }
        }
        return result.distinct()
    }

    /**
     * 把 `pkg/.E$V` 规范化为 `pkg/pkg.E$V`（与 framework flattenToString 一致）。
     */
    fun normalizeComponent(comp: String): String {
        val pkg = comp.substringBefore('/')
        var cls = comp.substringAfter('/', "")
        if (cls.isEmpty()) return comp
        cls = when {
            cls.startsWith(".") -> pkg + cls          // 前导点 -> 包名 + 类
            cls.contains(".") -> cls                   // 已是全限定名
            else -> "$pkg.$cls"                        // 裸类名
        }
        return "$pkg/$cls"
    }

    // ---------- 权限声明/授予检测 ----------

    /**
     * 应用是否声明了某权限。
     * 注意：不能用 `cmd package list permissions <pkg>`（Android 15 返回空），
     * 必须解析 dumpsys 的 requested permissions 段。
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

    // ---------- 授权 ----------

    /**
     * 为单个包授予通知相关全部权限。
     *
     * @param pkg         目标包名
     * @param waitSettle  是否等待 Settings 写入可见（建议 true）
     */
    fun grant(pkg: String, waitSettle: Boolean = true): GrantResult {
        val warnings = mutableListOf<String>()
        var listeners = emptyList<String>()
        var declaredPost = false
        var grantedPost: Boolean? = null
        var appopOk = false

        Logger.i(TAG, "开始授权: $pkg")

        // ---- 1) 通知使用权 ----
        if (isListenerEnabled(pkg)) {
            Logger.i(TAG, "[listener] $pkg 已授权，跳过")
            listeners = listListeners().filter { it.startsWith("$pkg/") }
        } else {
            val comps = probeComponents(pkg)
            if (comps.isEmpty()) {
                warnings.add("未找到 NotificationListenerService 组件")
                Logger.w(TAG, "[listener] $pkg 未找到 listener 组件，跳过")
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

        return GrantResult(pkg, listeners, declaredPost, grantedPost, appopOk, warnings)
    }

    /** 撤销某包的通知使用权（同时移除 Settings 中该包的所有条目）。 */
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

    /**
     * 清理失效 listener 条目（fail-safe：判不准的保守保留）。
     *
     * 判定：包未安装 -> 删；能枚举组件且不含该条目 -> 删；
     *       枚举不到组件（如 ColorOS 的 oplus.notificationmanager）
     *       -> 保留，避免误删系统 listener。
     */
    fun cleanInvalid(report: (String) -> Unit = {}): Int {
        val current = listListeners()
        if (current.isEmpty()) return 0
        val kept = mutableListOf<String>()
        var removed = 0

        for (line in current) {
            val pkg = line.substringBefore('/')
            // 1) 包必须已安装
            val installed = ShellExecutor.run("cmd package list packages $pkg", 15000L)
                ?.lineSequence()?.any { it.trim() == "package:$pkg" } == true
            if (!installed) {
                report("移除（包未安装）: $line")
                removed++
                continue
            }
            // 2) 组件枚举
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

    /** 轮询等待 Settings 写入可见（settings put 是异步的）。 */
    private fun waitListenerSettled(want: String, tries: Int = 20): Boolean {
        repeat(tries) {
            if (getRawListeners().contains(want)) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        return false
    }

    // ---------- 自检 ----------

    /**
     * 打印当前环境与目标包的完整授权状态，便于排查。
     * 返回多行文本，供 HTTP 接口/日志使用。
     */
    fun inspect(pkg: String): String {
        val sb = StringBuilder()
        sb.appendLine("===== $pkg =====")
        sb.appendLine("uid(root)=${Process.myUid()}")
        sb.appendLine("-- 通知使用权 --")
        if (isListenerEnabled(pkg)) {
            listListeners().filter { it.startsWith("$pkg/") }.forEach { sb.appendLine("  已授权: $it") }
        } else {
            sb.appendLine("  未授权")
        }
        sb.appendLine("-- 可用组件 --")
        val comps = probeComponents(pkg)
        if (comps.isEmpty()) sb.appendLine("  (无 / 无法枚举)") else comps.forEach { sb.appendLine("  $it") }
        sb.appendLine("-- POST_NOTIFICATIONS --")
        sb.appendLine("  声明=${declaresPermission(pkg, PERM_POST_NOTIFICATIONS)} " +
                "已授予=${isPermissionGranted(pkg, PERM_POST_NOTIFICATIONS)}")
        return sb.toString()
    }
}

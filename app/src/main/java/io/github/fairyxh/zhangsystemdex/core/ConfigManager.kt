package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * Configuration manager.
 *
 * The Magisk module directory keeps only config.conf (configuration root and
 * master logging switch). The configuration root holds switches.conf: every
 * independent feature has its own switch with a Chinese description, checked on
 * every startup. Missing switches default to false, except the six special
 * features that default to true. Disabled features are never loaded/started.
 */
class ConfigManager(private val modDir: String) {
    /** Public read-only view of the module directory (for OTA / self-update). */
    val moduleDir: String get() = modDir
    @Volatile
    var rootDir: String = "/data/adb/Zhang"
        private set

    @Volatile
    var logEnabled: Boolean = true
        private set

    /**
     * Fixed loopback port for the built-in WebUI HTTP backend (Main.dex side).
     * Declared in config.conf as `http_port=NNNN`. The server ALWAYS binds to
     * 127.0.0.1 only; this value never opens an external interface. Invalid or
     * missing values fall back to [DEFAULT_HTTP_PORT].
     */
    @Volatile
    var httpPort: Int = DEFAULT_HTTP_PORT
        private set

    val rootFile: File get() = File(rootDir)
    val logDir: File get() = File(rootDir, "log")
    val cacheDir: File get() = File(rootDir, "cache")

    private val configFile: File = File(modDir, "config.conf")
    private val switchesFile: File get() = File(rootDir, "switches.conf")
    private val switches = HashMap<String, String>()

    @Volatile
    private var switchesLastModified = 0L

    fun load() {
        var parsedRoot: String? = null
        var parsedLog: Boolean? = null
        var parsedPort: Int? = null
        if (configFile.exists()) {
            try {
                configFile.forEachLine { line ->
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#")) {
                        val idx = t.indexOf('=')
                        if (idx > 0) {
                            val k = t.substring(0, idx).trim()
                            val v = t.substring(idx + 1).trim()
                            when (k) {
                                "root_dir" -> parsedRoot = v
                                "log_enabled" -> parsedLog = v.equals("true", ignoreCase = true)
                                // Loopback HTTP port for the built-in WebUI backend.
                                // Only accept sane, non-privileged ports; otherwise keep default.
                                "http_port" -> v.toIntOrNull()?.let { p ->
                                    if (p in 1024..65535) parsedPort = p
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.w("ConfigManager", "解析 config.conf 失败，使用默认值: ${t.message}")
            }
        } else {
            writeDefaultConfigConf()
        }
        if (!parsedRoot.isNullOrBlank()) rootDir = parsedRoot!!
        logEnabled = parsedLog ?: true
        httpPort = parsedPort ?: DEFAULT_HTTP_PORT
        rootFile.mkdirs()
        logDir.mkdirs()
        cacheDir.mkdirs()
        initUserConfigs()
        syncSqliteLib()
        loadSwitches()
    }

    /** Feature switch; missing/empty values default to false unless the feature is special-default-true. */
    fun switch(key: String): Boolean {
        val raw = switches[key]
        if (raw.isNullOrEmpty()) {
            return SWITCH_DEFAULTS[key] ?: false
        }
        return raw.equals("true", ignoreCase = true) || raw == "1"
    }

    /** Non-boolean string setting read from switches.conf (e.g. external commands). */
    fun getString(key: String, default: String): String =
        switches[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default

    /** Re-read switches.conf when its file changed; returns true when reloaded. */
    fun reloadSwitchesIfChanged(): Boolean {
        val f = switchesFile
        val lm = if (f.exists()) f.lastModified() else 0L
        if (switchesLastModified != 0L && lm != switchesLastModified) {
            switchesLastModified = lm
            loadSwitches()
            return true
        }
        if (switchesLastModified == 0L) switchesLastModified = lm
        return false
    }

    /**
     * Force an immediate in-memory reload, bypassing the mtime check.
     *
     * Why: `/data/adb` is often tmpfs/overlayfs where `File.lastModified()` has
     * whole-second granularity. A WebUI toggle writes the file and reloads within
     * the same second as a previous reload, so `reloadSwitchesIfChanged()` sees an
     * unchanged mtime and SKIPS the reload -> `/api/rubbish/status` and friends
     * keep serving the stale value, which made the clean-tab switches look like
     * they "don't work". A control-plane write must be reflected immediately.
     */
    fun reloadSwitches(): Boolean {
        val lm = if (switchesFile.exists()) switchesFile.lastModified() else 0L
        switchesLastModified = lm
        loadSwitches()
        return true
    }

    private fun loadSwitches() {
        if (!switchesFile.exists()) {
            writeSwitches()
        }
        switches.clear()
        try {
            switchesFile.forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) {
                    val idx = t.indexOf('=')
                    if (idx > 0) {
                        val k = t.substring(0, idx).trim()
                        // Value may carry a trailing " # 中文注释"; strip it before storing.
                        val v = t.substring(idx + 1).substringBefore('#').trim()
                        if (k.isNotEmpty()) switches[k] = v
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "解析 switches.conf 失败: ${t.message}")
        }
        val packageConf = File(rootDir, MODULE_APPOPS_CONF)
        if (!packageConf.exists()) {
            packageConf.parentFile?.mkdirs()
            packageConf.writeText("# 模块 APK AppOps 历史授权目标\n")
        }
        ensureParamLines()
        Logger.i("ConfigManager", "已加载开关（${switches.size} 项）")
    }

    /**
     * Append the optional tuning parameter lines when switches.conf already
     * exists from an older version. Existing values are never overwritten; a
     * key is only appended when it is completely absent.
     */
    private fun ensureParamLines() {
        if (!switchesFile.exists()) return
        val missing = mutableListOf<String>()
        if (switches["tuning_interval_seconds"] == null) {
            missing.add("tuning_interval_seconds=\t# 主调优循环周期（秒），留空=600（服务器模式 300）")
        }
        if (switches["heavy_interval_cycles"] == null) {
            missing.add("heavy_interval_cycles=\t# 高占用任务间隔周期数，留空=6（服务器模式 24）")
        }
        if (switches["heavy_screen_off_only"] == null) {
            missing.add("heavy_screen_off_only=false\t# 高占用任务是否仅在息屏时执行（false=亮屏也允许执行）")
        }
        if (switches["hma_config_enable"] == null) {
            missing.add("hma_config_enable=true\t# HideMyAppList 模板列表自动写入（含 Xposed 模块扫描）")
        }
        if (switches["skip_mount_guard_enable"] == null) {
            missing.add("skip_mount_guard_enable=true\t# 模块目录防护：自动删除 skip_mount 等残留文件（防止系统挂载被跳过）")
        }
        if (switches["module_appops_auth_enable"] == null) {
            missing.add("module_appops_auth_enable=false\t# 为模块挂载 App 授权 AppOps（仅处理模块目录 APK）")
        }
        // ===== 电源与后台调度优化子系统（新增，仅追加缺失键，不覆盖已有值）=====
        if (switches["power_optimize_enable"] == null) {
            missing.add("power_optimize_enable=false\t# 电源与后台调度优化（事件驱动省电子系统，关闭时不影响其它功能与系统 Doze）")
        }
        if (switches["power_charging_release"] == null) {
            missing.add("power_charging_release=true\t# 充电时自动退出省电策略并还原临时调度状态")
        }
        if (switches["power_low_battery_threshold"] == null) {
            missing.add("power_low_battery_threshold=20\t# 低电量阈值（百分比，0-100，默认 20）")
        }
        if (switches["power_low_battery_cpu_cap"] == null) {
            missing.add("power_low_battery_cpu_cap=55\t# 低电量时 CPU 最高频率上限百分比（1-100，默认 55）")
        }
        if (switches["power_screen_off_cpu_cap_percent"] == null) {
            missing.add("power_screen_off_cpu_cap_percent=70\t# 灭屏时 CPU 最高频率上限百分比（1-100，默认 70）")
        }
        // 以下三个布尔键此前仅在 SWITCH_DESCRIPTIONS 中登记、未落盘，导致
        // WebUI 渲染的开关在 switches.conf 中不存在（默认 false 恰好一致，
        // 但“文件即唯一真源”的一致性被破坏）。此处补齐，默认 false 保持保守。
        if (switches["power_screen_off_cpu_cap"] == null) {
            missing.add("power_screen_off_cpu_cap=false\t# 灭屏时限制 CPU 最高频率（true/false，默认 false，退出即还原）")
        }
        if (switches["power_low_battery_restrict_bg"] == null) {
            missing.add("power_low_battery_restrict_bg=false\t# 低电量时限制后台（true/false，默认 false，仅作用于 power_bg_stop_list.conf 中的应用）")
        }
        if (switches["power_screen_off_restrict_bg"] == null) {
            missing.add("power_screen_off_restrict_bg=false\t# 灭屏时限制后台（true/false，默认 false，仅作用于 power_bg_stop_list.conf 中的应用）")
        }
        // ===== 垃圾清理（新增，缺失键按默认值追加；低风险规则默认 true）=====
        appendRubbishMissing(missing)
        if (missing.isEmpty()) return
        try {
            val sb = StringBuilder("\n# 主调优循环参数（可选项，留空使用默认值）\n")
            for (line in missing) {
                sb.append(line).append('\n')
                val k = line.substringBefore('=').trim()
                switches[k] = ""
            }
            switchesFile.appendText(sb.toString())
            Logger.i("ConfigManager", "switches.conf 已追加缺失参数: ${missing.map { it.substringBefore('=') }}")
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "追加 switches.conf 参数失败: ${t.message}")
        }
    }

    /**
     * 垃圾清理相关键的 migration：只追加完全缺失的键，绝不覆盖已有值。
     * 低风险规则默认 true（加入 SPECIAL_DEFAULT_TRUE），中高风险默认 false。
     */
    private fun appendRubbishMissing(missing: MutableList<String>) {
        fun add(key: String, def: String, desc: String) {
            if (switches[key] == null) missing.add("$key=$def\t# $desc")
        }
        // 总开关与参数
        add("rubbish_clean_enable", "false", "垃圾清理总开关")
        add("rubbish_clean_screen_off_only", "true", "定时清理仅在息屏时执行")
        add("rubbish_force_when_running", "false", "目标应用运行中仍强制清理")
        add("rubbish_big_file_mb", "100", "大文件扫描阈值（MB）")
        add("rubbish_wx_chat_media_days", "30", "微信聊天媒体保留天数")
        // 通用低风险（默认 true）
        add("rubbish_rule_app_cache", "true", "清理应用缓存目录内容")
        add("rubbish_rule_thumbnails", "true", "清理媒体缩略图缓存")
        add("rubbish_rule_temp_files", "true", "清理临时/未完成文件")
        add("rubbish_rule_empty_dirs", "true", "清理空目录与 0 字节文件")
        add("rubbish_rule_system_crash_logs", "true", "清理系统崩溃日志")
        add("rubbish_rule_app_logs", "true", "清理应用日志目录")
        // 通用中风险（默认 false）
        add("rubbish_rule_apk_leftover", "false", "清理 APK 安装包")
        add("rubbish_rule_ad_cache", "false", "清理广告缓存")
        add("rubbish_rule_uninstalled_leftover", "false", "清理卸载残留")
        add("rubbish_rule_big_files_list", "false", "扫描大文件（仅列出）")
        // 微信低风险（默认 true）
        add("rubbish_rule_wx_logs", "true", "微信专清：日志 xlog")
        add("rubbish_rule_wx_temp", "true", "微信专清：临时缓存 cache/temp")
        add("rubbish_rule_wx_webview_cache", "true", "微信专清：WebView/小程序缓存")
        add("rubbish_rule_wx_media_cache", "true", "微信专清：外置媒体缓存")
        // 微信中高风险（默认 false）
        add("rubbish_rule_wx_rebuildable", "false", "微信专清：模板与资源缓存（中风险）")
        add("rubbish_rule_wx_chat_media", "false", "微信专清：聊天媒体按时间（高风险）")
        // QQ 低风险（默认 true）
        add("rubbish_rule_qq_logs", "true", "QQ 专清：日志")
        add("rubbish_rule_qq_cache", "true", "QQ 专清：缓存与 XWalk/WebView")
        add("rubbish_rule_qq_media_cache", "true", "QQ 专清：外置媒体缓存")
        // QQ 中风险（默认 false）
        add("rubbish_rule_qq_miniapp", "false", "QQ 专清：小程序与 TBS 缓存（中风险）")
        add("rubbish_rule_qq_file_recv", "false", "QQ 专清：接收的文件（中风险）")
        add("rubbish_rule_qq_chatpic", "false", "QQ 专清：聊天图片临时文件（中风险）")
        // ===== 深度扫描（文件头识别 + 缓存增量） =====
        add("rubbish_rule_apk_scan_media", "false", "全盘 APK 深度扫描（按文件头识别改名/无后缀安装包）")
        add("rubbish_rule_apk_scan_private", "true", "私有目录 APK 深度扫描")
        add("rubbish_rule_big_files_private", "false", "私有目录大文件（仅列出）")
        add("rubbish_rule_dup_files_media", "false", "重复文件（media，按内容哈希）")
        add("rubbish_rule_dup_wechat_tpc", "true", "微信重复下载文件（TPCFile）")
        // ===== 泛化垃圾扫描（全部 App + 整个 /data）=====
        add("rubbish_rule_junk_all_apps", "false", "全部应用垃圾扫描（私人目录，按特征识别）")
        add("rubbish_rule_junk_media_apps", "true", "外部存储应用垃圾扫描（全部 App）")
        add("rubbish_rule_system_junk_data", "false", "系统级垃圾（/data 下缓存/日志/崩溃转储）")
        add("rubbish_rule_system_bcc_csv", "true", "内核追踪 CSV（/data 根下 *_bcc.csv）")
    }

    private fun writeSwitches() {
        try {
            switchesFile.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("# ZhangSystemDex 功能开关配置\n")
            sb.append("# 每次启动都会检查；配置缺失时按默认值处理（特殊项默认开启，其余一律默认关闭）\n")
            sb.append("# 关闭的功能不会创建线程，也不会执行任何逻辑\n\n")
            sb.append("# ===== 特殊默认开启 =====\n")
            for (key in SPECIAL_DEFAULT_TRUE) {
                val desc = SWITCH_DESCRIPTIONS[key] ?: continue
                sb.append("$key=true\t# ${desc}\n")
            }
            sb.append("\n# ===== 其余功能（一律默认关闭） =====\n")
            for ((key, desc) in SWITCH_DESCRIPTIONS) {
                if (key in SPECIAL_DEFAULT_TRUE) continue
                sb.append("$key=false\t# ${desc}\n")
            }
            sb.append("\n# 服务器模式外部命令（可选项）\n")
            sb.append("frpc_command=\t# 服务器模式下启动 frpc 的命令（留空跳过）\n")
            sb.append("automusic_command=\t# 服务器模式下启动音乐的命令（留空跳过）\n")
            sb.append("\n# 主调优循环参数（可选项）\n")
            sb.append("tuning_interval_seconds=\t# 主调优循环周期（秒），留空=600（服务器模式 300）\n")
            sb.append("heavy_interval_cycles=\t# 高占用任务间隔周期数，留空=6（服务器模式 24）\n")
            sb.append("heavy_screen_off_only=false\t# 高占用任务是否仅在息屏时执行（false=亮屏也允许执行）\n")
            sb.append("module_appops_auth_enable=false\t# 为模块挂载 App 授权 AppOps（仅处理模块目录 APK）\n")
            sb.append("\n# 电源与后台调度优化参数（可选项，总开关见上方 power_optimize_enable）\n")
            sb.append("power_low_battery_threshold=20\t# 低电量阈值（百分比，0-100，默认 20）\n")
            sb.append("power_low_battery_cpu_cap=55\t# 低电量时 CPU 最高频率上限百分比（1-100，默认 55）\n")
            sb.append("power_low_battery_restrict_bg=false\t# 低电量时限制后台（true/false，默认 false，仅作用于 power_bg_stop_list.conf 中的应用）\n")
            sb.append("power_screen_off_cpu_cap=false\t# 灭屏时限制 CPU 最高频率（true/false，默认 false，退出即还原）\n")
            sb.append("power_screen_off_cpu_cap_percent=70\t# 灭屏时 CPU 最高频率上限百分比（1-100，默认 70）\n")
            sb.append("power_screen_off_restrict_bg=false\t# 灭屏时限制后台（true/false，默认 false，仅作用于 power_bg_stop_list.conf 中的应用）\n")
            sb.append("\n# ===== 垃圾清理 =====\n")
            sb.append("rubbish_clean_enable=false\t# 垃圾清理总开关\n")
            sb.append("rubbish_clean_screen_off_only=true\t# 定时清理仅在息屏时执行\n")
            sb.append("rubbish_force_when_running=false\t# 目标应用运行中仍强制清理\n")
            sb.append("rubbish_big_file_mb=100\t# 大文件扫描阈值（MB）\n")
            sb.append("rubbish_wx_chat_media_days=30\t# 微信聊天媒体保留天数\n")
            sb.append("rubbish_rule_app_cache=true\t# 清理应用缓存目录内容\n")
            sb.append("rubbish_rule_thumbnails=true\t# 清理媒体缩略图缓存\n")
            sb.append("rubbish_rule_temp_files=true\t# 清理临时/未完成文件\n")
            sb.append("rubbish_rule_empty_dirs=true\t# 清理空目录与 0 字节文件\n")
            sb.append("rubbish_rule_system_crash_logs=true\t# 清理系统崩溃日志\n")
            sb.append("rubbish_rule_app_logs=true\t# 清理应用日志目录\n")
            sb.append("rubbish_rule_apk_leftover=false\t# 清理 APK 安装包（中风险）\n")
            sb.append("rubbish_rule_ad_cache=false\t# 清理广告缓存（中风险）\n")
            sb.append("rubbish_rule_uninstalled_leftover=false\t# 清理卸载残留（中风险）\n")
            sb.append("rubbish_rule_big_files_list=false\t# 扫描大文件（仅列出）\n")
            sb.append("rubbish_rule_wx_logs=true\t# 微信专清：日志 xlog\n")
            sb.append("rubbish_rule_wx_temp=true\t# 微信专清：临时缓存 cache/temp\n")
            sb.append("rubbish_rule_wx_webview_cache=true\t# 微信专清：WebView/小程序缓存\n")
            sb.append("rubbish_rule_wx_media_cache=true\t# 微信专清：外置媒体缓存\n")
            sb.append("rubbish_rule_wx_rebuildable=false\t# 微信专清：模板与资源缓存（中风险）\n")
            sb.append("rubbish_rule_wx_chat_media=false\t# 微信专清：聊天媒体按时间（高风险）\n")
            sb.append("rubbish_rule_qq_logs=true\t# QQ 专清：日志\n")
            sb.append("rubbish_rule_qq_cache=true\t# QQ 专清：缓存与 XWalk/WebView\n")
            sb.append("rubbish_rule_qq_media_cache=true\t# QQ 专清：外置媒体缓存\n")
            sb.append("rubbish_rule_qq_miniapp=false\t# QQ 专清：小程序与 TBS 缓存（中风险）\n")
            sb.append("rubbish_rule_qq_file_recv=false\t# QQ 专清：接收的文件（中风险）\n")
            sb.append("rubbish_rule_qq_chatpic=false\t# QQ 专清：聊天图片临时文件（中风险）\n")
            sb.append("rubbish_rule_apk_scan_media=false\t# 全盘 APK 深度扫描（文件头识别）\n")
            sb.append("rubbish_rule_apk_scan_private=true\t# 私有目录 APK 深度扫描\n")
            sb.append("rubbish_rule_big_files_private=false\t# 私有目录大文件（仅列出）\n")
            sb.append("rubbish_rule_dup_files_media=false\t# 重复文件（media，按内容）\n")
            sb.append("rubbish_rule_dup_wechat_tpc=true\t# 微信重复下载文件（TPCFile）\n")
            sb.append("rubbish_rule_junk_all_apps=false\t# 全部应用垃圾扫描\n")
            sb.append("rubbish_rule_junk_media_apps=true\t# 外部存储应用垃圾扫描\n")
            sb.append("rubbish_rule_system_junk_data=false\t# 系统级垃圾（/data）\n")
            sb.append("rubbish_rule_system_bcc_csv=true\t# 内核追踪 CSV\n")
            switchesFile.writeText(sb.toString())
            Logger.i("ConfigManager", "switches.conf 已初始化")
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "写入 switches.conf 失败: ${t.message}")
        }
    }

    private fun writeDefaultConfigConf() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(
                "# ZhangSystemDex config\n" +
                    "# root_dir: directory holding all feature configuration\n" +
                    "root_dir=/data/adb/Zhang\n" +
                    "# log_enabled: master logging switch (true/false)\n" +
                    "log_enabled=true\n" +
                    "# http_port: fixed loopback port of the built-in WebUI backend.\n" +
                    "# The server binds 127.0.0.1 ONLY and is never reachable from the network.\n" +
                    "# Range 1024-65535; change it only if the chosen port is already in use.\n" +
                    "http_port=$DEFAULT_HTTP_PORT\n"
            )
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "写入默认 config.conf 失败: ${t.message}")
        }
    }

    private fun initUserConfigs() {
        copyOrInit("doze.conf", DEFAULT_DOZE_CONF)
        copyOrInit("game_pause.conf", DEFAULT_GAME_PAUSE_CONF)
        copyOrInit("asguard.conf", DEFAULT_ASGUARD_CONF)
        copyOrInit("notification.conf", DEFAULT_NOTIFICATION_CONF)
        copyOrInit("autorun.conf", DEFAULT_AUTORUN_CONF)
        copyOrInit("HideMyAppList_MoreBlack.txt", DEFAULT_HMA_MORE_BLACK)
        copyOrInit("power_bg_stop_list.conf", DEFAULT_POWER_BG_STOP_LIST)
        File(rootDir, "app_manager").mkdirs()
        copyOrInit("app_manager/disable_app_list.conf", DEFAULT_DISABLE_APP_LIST)
        copyOrInit("app_manager/disable_app_list_onlydisable.conf", DEFAULT_DISABLE_APP_LIST_ONLY)
        File(rootDir, "CleanedRubbish").mkdirs()
        // 垃圾清理审查规则（用户可编辑：违禁词/违禁路径；只增拒绝）
        io.github.fairyxh.zhangsystemdex.core.rubbish.UserGuardRules
            .ensureFile(File(rootDir, "rubbish_guard.conf"))
        // 加载到中心化审查（RubbishGuard）
        io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishGuard
            .loadUserRules(File(rootDir, "rubbish_guard.conf").path)
        // 审计日志目录（复用统一日志目录）
        io.github.fairyxh.zhangsystemdex.core.rubbish.RubbishGuard.auditLog().setDir(logDir)
    }

    private fun copyOrInit(rel: String, defaultContent: String) {
        val target = File(rootDir, rel)
        if (target.exists()) return
        try {
            target.parentFile?.mkdirs()
            target.writeText(defaultContent)
            Logger.i("ConfigManager", "已初始化 $rel")
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "初始化 $rel 失败: ${t.message}")
        }
    }

    /** Ship the bundled sqlite3 CLI (module dir sqlite_lib/) to cache/sqlite_lib. */
    private fun syncSqliteLib() {
        val src = File(modDir, "sqlite_lib")
        if (!src.exists()) return
        val dst = File(cacheDir, "sqlite_lib")
        if (!dst.exists()) {
            try {
                dst.mkdirs()
                src.listFiles()?.forEach { f ->
                    if (f.isFile) FileUtils.copyFile(f, File(dst, f.name))
                }
            } catch (t: Throwable) {
                Logger.w("ConfigManager", "同步 sqlite_lib 失败: ${t.message}")
            }
        }
        // Fix permissions every start: a module update or manual copy can
        // leave sqlite3 without the exec bit (observed as 666).
        val bin = File(dst, "sqlite3")
        if (bin.exists()) FileUtils.chmod(bin.path, "0755")
        dst.listFiles()?.forEach { f ->
            if (f.name.startsWith("lib")) FileUtils.chmod(f.path, "0644")
        }
        Logger.i("ConfigManager", "sqlite_lib 就绪: ${dst.path}")
    }

    companion object {
        const val MODULE_APPOPS_CONF = "appops_packages.conf"

        /**
         * Default loopback port for the built-in WebUI HTTP backend. Chosen in
         * the unprivileged range and unlikely to collide with common services.
         */
        const val DEFAULT_HTTP_PORT = 26437

        /** Features that default to ON. */
        val SPECIAL_DEFAULT_TRUE: Set<String> = setOf(
            "doze_enable",
            "hma_config_enable",
            "game_pause_enable",
            "accessibility_guard_enable",
            "locked_apps_enable",
            "prop_tuning_enable",
            "heavy_task_enable",
            "target_list_enable",
            "disable_apps_enable",
            "service_guard_enable",
            "read_game_list_enable",
            "miui_tuning_enable",
            "module_appops_auth_enable",
            "skip_mount_guard_enable",
            "game_oom_protect_enable",
            "power_charging_release",
            // ===== 垃圾清理：低风险规则默认开启（用户要求） =====
            "rubbish_rule_app_cache",
            "rubbish_rule_thumbnails",
            "rubbish_rule_temp_files",
            "rubbish_rule_empty_dirs",
            "rubbish_rule_system_crash_logs",
            "rubbish_rule_app_logs",
            "rubbish_rule_wx_logs",
            "rubbish_rule_wx_temp",
            "rubbish_rule_wx_webview_cache",
            "rubbish_rule_wx_media_cache",
            "rubbish_rule_qq_logs",
            "rubbish_rule_qq_cache",
            "rubbish_rule_qq_media_cache",
            "rubbish_clean_screen_off_only",
            // 深度扫描低风险项
            "rubbish_rule_apk_scan_private",
            "rubbish_rule_dup_wechat_tpc",
            "rubbish_rule_junk_media_apps",
            "rubbish_rule_system_bcc_csv"
        )

        /** Ordered switch descriptions (key -> Chinese description). */
        val SWITCH_DESCRIPTIONS: Map<String, String> = linkedMapOf(
            "doze_enable" to "Doze 处理：电池优化白名单维护与夜间强制 Doze",
            "hma_config_enable" to "HideMyAppList 模板列表自动写入（含 Xposed 模块扫描）",
            "game_pause_enable" to "游戏在前台时暂停其他功能",
            "accessibility_guard_enable" to "无障碍服务守护",
            "locked_apps_enable" to "多任务锁定应用处理（MIUI/ColorOS）",
            "prop_tuning_enable" to "系统属性优化与防检测属性（boot/保修/调试等属性维护）",
            "heavy_task_enable" to "周期高占用任务（防错误弹窗/Doze 白名单刷新/HMA 全量生成/target 列表/应用遮蔽/温控/MIUI/Soter/垃圾清理等，默认亮屏也执行，是否仅息屏由 heavy_screen_off_only 控制，间隔周期数可配置）",
            "heavy_screen_off_only" to "高占用任务是否仅在息屏时执行（false=亮屏也允许执行，默认 false）",
            "system_tuning_enable" to "主调优循环（热控/调度/进程提升等每周期常规任务，周期秒数可配置）",
            "service_guard_enable" to "服务守护（Shizuku/Brevent/蓝牙/健康应用）",
            "extra_features_enable" to "附加功能（NFC 守护/通知监听守护/开机自启动）",
            "memory_clean_enable" to "内存清理与低内存后台杀进程",
            "server_mode_enable" to "服务器模式（保持 WiFi/蓝牙/常亮/性能调度）",
            "thermal_mask_enable" to "温控配置文件遮蔽",
            "appops_allow_enable" to "白名单应用 AppOps 全允许与权限组授权",
            "module_appops_auth_enable" to "为模块挂载 App 授权 AppOps（仅处理模块目录 APK）",
            "dexopt_everything_enable" to "开机执行 everything 编译",
            "selinux_disable_enable" to "关闭 SELinux",
            "powersave_enable" to "省电模式（开启后其余调优类功能全部无效）",
            "storage_isolation_enable" to "存储空间隔离配套（痕迹清理/垃圾隔离/配置生成）",
            "storage_isolate_all_enable" to "存储空间隔离作用于所有应用（false=仅第三方）",
            "storage_isolate_media_enable" to "允许隔离媒体选择器",
            "disable_apps_enable" to "反诈/快应用等应用停用与遮蔽挂载",
            "boost_process_enable" to "进程调度提升（renice/chrt/cpuset）",
            "boost_game_enable" to "游戏进程自动加速",
            "run_once_enable" to "高占用任务仅执行一次后退出",
            "max_cpu_enable" to "CPU/GPU 满频率与核心分配",
            "miui_tuning_enable" to "MIUI joyose/powerkeeper 数据库与属性调优",
            "target_list_enable" to "tricky_store/hmspush 目标列表增量更新",
            "dnt_accessibility_enable" to "DoNotTryAccessibility 规则 XML 生成",
            "network_ipv6_disable_enable" to "禁用 IPv6",
            "only_base_enable" to "Doze 白名单使用内置规则（false=读取 doze.conf）",
            "read_game_list_enable" to "自动读取 MIUI/欧加游戏列表",
            "skip_mount_guard_enable" to "模块目录防护：自动删除 skip_mount 等残留文件（防止系统挂载被跳过）",
            "game_oom_protect_enable" to "保护游戏进程Oom=-1000,不被系统杀死",
            "accelerometer_rotation_enable" to "加速计自动旋转：每周期强制禁用自动旋转",
            "bt_offload_guard_enable" to "蓝牙音频 offload 循环守护（周期性复位 A2DP/LE 音频硬件 offload 属性，修复卡顿/无声/断连）",

            // ===== 电源与后台调度优化子系统（新增，默认关闭） =====
            "power_optimize_enable" to "电源与后台调度优化：事件驱动的省电子系统（关闭后不创建监听、立即恢复临时调度状态，不影响其它功能与系统 Doze）",
            "power_low_battery_threshold" to "低电量阈值（百分比，0-100，默认 20）",
            "power_low_battery_cpu_cap" to "低电量时 CPU 最高频率上限百分比（1-100，默认 55，仅降不锁，退出即还原）",
            "power_low_battery_restrict_bg" to "低电量时限制后台（true/false，默认 false，仅作用于 power_bg_stop_list.conf 中的应用）",
            "power_screen_off_cpu_cap" to "灭屏时限制 CPU 最高频率（true/false，默认 false，退出即还原）",
            "power_screen_off_cpu_cap_percent" to "灭屏时 CPU 最高频率上限百分比（1-100，默认 70）",
            "power_screen_off_restrict_bg" to "灭屏时限制后台（true/false，默认 false，仅作用于 power_bg_stop_list.conf 中的应用）",
            "power_charging_release" to "充电时自动退出省电策略并还原临时调度状态（true/false，默认 true）",

            // ===== 垃圾清理（新增） =====
            "rubbish_clean_enable" to "垃圾清理总开关（开启后按下方各规则开关执行；关闭时完全不扫描不删除）",
            "rubbish_clean_screen_off_only" to "定时清理仅在息屏时执行（true/false，默认 true）",
            "rubbish_force_when_running" to "目标应用运行中仍强制清理（true/false，默认 false=跳过，避免微信/QQ 运行中删文件异常）",
            "rubbish_big_file_mb" to "大文件扫描阈值（MB，默认 100）",
            "rubbish_wx_chat_media_days" to "微信聊天媒体保留天数（超过该天数的才清理，默认 30）",
            // 通用低风险
            "rubbish_rule_app_cache" to "清理应用缓存目录内容（保留目录与配置）",
            "rubbish_rule_thumbnails" to "清理媒体缩略图缓存",
            "rubbish_rule_temp_files" to "清理下载目录中的临时/未完成文件",
            "rubbish_rule_empty_dirs" to "清理下载目录中的空目录与 0 字节文件",
            "rubbish_rule_system_crash_logs" to "清理系统崩溃日志（ANR/tombstone/dropbox）",
            "rubbish_rule_app_logs" to "清理应用外部目录中的日志目录",
            // 通用中风险
            "rubbish_rule_apk_leftover" to "清理下载目录中的 APK 安装包（中风险）",
            "rubbish_rule_ad_cache" to "清理应用缓存中的广告 SDK 缓存（中风险）",
            "rubbish_rule_uninstalled_leftover" to "清理已卸载应用的外部数据目录（中风险）",
            "rubbish_rule_big_files_list" to "扫描大文件（仅列出，不自动删除）",
            // 微信低风险
            "rubbish_rule_wx_logs" to "微信专清：日志 xlog（可安全清理，实测 1GB+）",
            "rubbish_rule_wx_temp" to "微信专清：临时缓存 cache/temp（可安全清理，实测 2.5GB）",
            "rubbish_rule_wx_webview_cache" to "微信专清：WebView/小程序缓存（自动重建）",
            "rubbish_rule_wx_media_cache" to "微信专清：外置媒体缓存（缩略图等，自动重建）",
            // 微信中高风险
            "rubbish_rule_wx_rebuildable" to "微信专清：模板与资源缓存（约 1GB，清理后需重新下载，中风险）",
            "rubbish_rule_wx_chat_media" to "微信专清：聊天图片/视频/语音（仅删超期，不可恢复，高风险）",
            // QQ 低风险
            "rubbish_rule_qq_logs" to "QQ 专清：日志（可安全清理）",
            "rubbish_rule_qq_cache" to "QQ 专清：缓存与 XWalk/WebView 缓存（自动重建）",
            "rubbish_rule_qq_media_cache" to "QQ 专清：外置媒体缓存（短视频/磁盘缓存）",
            // QQ 中风险
            "rubbish_rule_qq_miniapp" to "QQ 专清：小程序与 TBS 内核缓存（中风险，需重新下载）",
            "rubbish_rule_qq_file_recv" to "QQ 专清：接收的文件（中风险，不可恢复）",
            "rubbish_rule_qq_chatpic" to "QQ 专清：聊天图片临时文件（中风险）",
            // 深度扫描
            "rubbish_rule_apk_scan_media" to "全盘 APK 深度扫描：按文件头识别改名/无后缀的安装包（中风险）",
            "rubbish_rule_apk_scan_private" to "私有目录 APK 深度扫描：常见应用私有目录中的安装包",
            "rubbish_rule_big_files_private" to "私有目录大文件：列出超过 50MB 的文件（仅列出不删）",
            "rubbish_rule_dup_files_media" to "重复文件（media）：按尺寸+内容哈希找出完全相同文件，每组保留最新（高风险）",
            "rubbish_rule_dup_wechat_tpc" to "微信重复下载文件（TPCFile）：同名同尺寸重复下载，保留最新一份",
            // 泛化垃圾扫描
            "rubbish_rule_junk_all_apps" to "全部应用垃圾扫描：按特征识别所有 App 私有目录的缓存/日志/临时垃圾（中风险）",
            "rubbish_rule_junk_media_apps" to "外部存储应用垃圾：扫描 /data/media 各应用目录的缓存/日志（全部 App）",
            "rubbish_rule_system_junk_data" to "系统级垃圾：/data 下缓存/日志/崩溃转储（已排除 /data/adb 与 Python 解释器）",
            "rubbish_rule_system_bcc_csv" to "内核追踪 CSV：清理 /data 根下散落的 *_bcc.csv"
        )

        /** Defaults: false for everything except the six special features. */
        val SWITCH_DEFAULTS: Map<String, Boolean> = SWITCH_DESCRIPTIONS.keys.associateWith { it in SPECIAL_DEFAULT_TRUE }

        /** Original module doze.conf shipped with the module, used as the default white list. */
        const val DEFAULT_DOZE_CONF =
            "+bin.mt.plus\n" +
                "+meow.helper\n" +
                "+rikka.appops\n" +
                "+bin.mt.termex\n" +
                "+com.mi.health\n" +
                "+com.miui.home\n" +
                "+com.tencent.mm\n" +
                "+lzlnb.cnm.hook\n" +
                "+com.netease.x19\n" +
                "+com.suqi8.oshin\n" +
                "+com.tplink.tool\n" +
                "+io.github.qauxv\n" +
                "+top.hookvip.pro\n" +
                "+com.bitchat.droid\n" +
                "+com.zidongdianji\n" +
                "+com.zidongdianji\n" +
                "+com.gotokeep.keep\n" +
                "+com.heytap.health\n" +
                "+com.kugou.android\n" +
                "+com.omarea.vtools\n" +
                "+com.rosan.dhizuku\n" +
                "+com.baidu.netdisk\n" +
                "+com.rifsxd.ksunext\n" +
                "+leo.xposed.sesameX\n" +
                "+me.weishu.kernelsu\n" +
                "+com.netease.yyslscn\n" +
                "+com.reqable.android\n" +
                "+com.tencent.qqmusic\n" +
                "+com.tencent.tmgp.cf\n" +
                "+moe.fuqiuluo.portal\n" +
                "+org.lsposed.manager\n" +
                "+com.didjdk.adbhelper\n" +
                "+com.oasisfeng.island\n" +
                "+com.tencent.mobileqq\n" +
                "+com.topjohnwu.magisk\n" +
                "+com.vphonegaga.titan\n" +
                "+me.piebridge.brevent\n" +
                "+top.bogey.touch_tool\n" +
                "+com.lerist.fakelocation\n" +
                "+com.rosan.installer.x\n" +
                "+com.bintianqi.owndroid\n" +
                "+com.catchingnow.icebox\n" +
                "+com.github.kr328.clash\n" +
                "+com.github.kr328.clash\n" +
                "+com.kugou.android.lite\n" +
                "+com.luckyzyx.luckytool\n" +
                "+com.mojang.minecraftpe\n" +
                "+com.netease.cloudmusic\n" +
                "+com.tencent.tmgp.sgame\n" +
                "+com.tsng.hidemyapplist\n" +
                "+moe.fuqiuluo.portaldev\n" +
                "+org.telegram.messenger\n" +
                "+com.zhufucdev.cp_plugin\n" +
                "+com.zhufucdev.ws_plugin\n" +
                "+com.zmzx.college.search\n" +
                "+com.sy.fuck_miui_thermal\n" +
                "+com.tencent.tmgp.pubgmhd\n" +
                "+io.github.huskydg.magisk\n" +
                "+me.teble.xposed.autodaily\n" +
                "+moe.shizuku.privileged.api\n" +
                "+app.landrop.landrop_flutter\n" +
                "+com.eg.android.AlipayGphone\n" +
                "+moe.shizuku.redirectstorage\n" +
                "+com.rosan.dhizuku.api.xposed\n" +
                "+com.suda.yzune.wakeupschedule\n" +
                "+com.zhufucdev.motion_emulator\n" +
                "+github.tornaco.android.thanos\n" +
                "+com.github.metacubex.clash.meta\n" +
                "+io.github.vvb2060.keyattestation\n" +
                "+io.github.fairyxh.ZhangSystemHook\n" +
                "+com.softwarebakery.drivedroid.paid\n" +
                "+com.zhufucdev.mock_location_plugin\n" +
                "+com.github.tianma8023.xposed.smscode\n"

        /** Original AsGuard.conf package list. */
        const val DEFAULT_ASGUARD_CONF =
            "li.songe.gkd\n" +
                "com.zidongdianji\n" +
                "com.omarea.vtools\n" +
                "top.bogey.touch_tool\n"

        /** Original autorun.conf service list. */
        const val DEFAULT_AUTORUN_CONF =
            "com.pittvandewitt.viperfx/com.pittvandewitt.viperfx.service.ViPER4AndroidService\n" +
                "com.omarea.vtools/com.omarea.vtools.services.KeepAliveService\n" +
                "com.omarea.vtools/com.omarea.vtools.AccessibilitySceneMode\n" +
                "com.omarea.vtools/com.omarea.scene_mode.NotificationListenerService\n" +
                "com.mi.health/androidx.room.MultiInstanceInvalidationService\n" +
                "com.mi.health/com.xiaomi.fitness.keep_alive.KeepAliveService\n" +
                "com.mi.health/com.xiaomi.fitness.notify.NotifySyncService\n" +
                "com.mi.health/com.xiaomi.xms.wearable.WearableXmsService\n" +
                "com.heytap.health/com.heytap.sports.service.SportService\n" +
                "com.heytap.health/com.heytap.sports.service.NotifyService\n" +
                "com.heytap.health/com.heytap.sports.service.BgConnect\n" +
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService\n" +
                "com.heytap.health/com.heytap.health.oaf.OafHostService\n" +
                "com.heytap.health/com.heytap.health.devicemanagerimpl.processor.TryConnectService\n" +
                "com.heytap.health/com.heytap.device.service.SleepModelSyncServices\n" +
                "com.heytap.health/com.heytap.device.service.SleepModelSyncServices\n" +
                "com.heytap.health/com.heytap.databaseengineservice.SportHealthDataService\n" +
                "com.heytap.health/com.heytap.accessory.platform.services.FrameworkService\n" +
                "com.heytap.health/com.heytap.accessory.platform.services.FileService\n" +
                "com.heytap.health/com.amap.api.location.APSService\n" +
                "hello.litiaotiao.app/hello.litiaotiao.app.MyAccessibilityService\n"

        /** Original notification.conf listener list. */
        const val DEFAULT_NOTIFICATION_CONF =
            "com.omarea.vtools/com.omarea.scene_mode.NotificationListenerService:" +
                "com.hfhuaizhi.bird/com.hfhuaizhi.bird.service.BirdNotificationService:" +
                "com.mi.health/com.xiaomi.fitness.notify.NotifySyncService:" +
                "com.catchingnow.np/com.catchingnow.np.E\$V:" +
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService:" +
                "com.catchingnow.icebox/com.catchingnow.icebox.service.NotificationObserverService:" +
                "com.growing.topwidgets/com.growing.topwidgets.sprite.service.LocalNotificationService\n"

        /** Original disable_app_list.conf packages. */
        const val DEFAULT_DISABLE_APP_LIST =
            "com.miui.hybrid\n" +
                "com.android.updater\n" +
                "com.nearme.instant.platform\n" +
                "com.oplus.ota\n"

        /** Original disable_app_list_onlydisable.conf (empty in the module). */
        const val DEFAULT_DISABLE_APP_LIST_ONLY = ""

        /** Original pause_on_game_run_conf.txt game list. */
        const val DEFAULT_GAME_PAUSE_CONF =
            "com.netease.x19\n" +
                "com.netease.yyslscn\n" +
                "com.tencent.tmgp.cf\n" +
                "com.mojang.minecraftpe\n" +
                "com.tencent.tmgp.sgame\n" +
                "com.tencent.tmgp.pubgmhd\n"

        const val DEFAULT_HMA_MORE_BLACK =
            "# user blacklist: one package name per line, appended to the hidden list\n"

        /**
         * Packages the power subsystem may restrict in the background.
         * EMPTY by default: the subsystem never touches any app unless the user
         * explicitly lists it here. One package name per line, '#' starts a comment.
         */
        const val DEFAULT_POWER_BG_STOP_LIST =
            "# 电源优化后台限制列表：每行一个包名，默认空白（不限制任何应用）\n" +
                "# 受保护应用（systemui/settings/gms/桌面/模块自身/前台应用/Doze 白名单）永远不会被限制\n"
    }
}

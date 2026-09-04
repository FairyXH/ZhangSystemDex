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
    @Volatile
    var rootDir: String = "/data/adb/Zhang"
        private set

    @Volatile
    var logEnabled: Boolean = true
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
                    "log_enabled=true\n"
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
        File(rootDir, "app_manager").mkdirs()
        copyOrInit("app_manager/disable_app_list.conf", DEFAULT_DISABLE_APP_LIST)
        copyOrInit("app_manager/disable_app_list_onlydisable.conf", DEFAULT_DISABLE_APP_LIST_ONLY)
        File(rootDir, "CleanedRubbish").mkdirs()
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
            "game_oom_protect_enable"
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
            "game_oom_protect_enable" to "保护游戏进程Oom=-1000,不被系统杀死"
        )

        /** Defaults: false for everything except the six special features. */
        val SWITCH_DEFAULTS: Map<String, Boolean> = SWITCH_DESCRIPTIONS.keys.associateWith { it in SPECIAL_DEFAULT_TRUE }

        /** Original module doze.conf shipped with the module, used as the default white list. */
        const val DEFAULT_DOZE_CONF =
            "+com.remoteenv.collector\n" +
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
            "com.remoteenv.collector\n" +
                "li.songe.gkd\n" +
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
    }
}

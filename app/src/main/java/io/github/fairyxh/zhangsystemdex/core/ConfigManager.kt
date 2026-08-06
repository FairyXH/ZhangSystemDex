package io.github.fairyxh.zhangsystemdex.core

import org.json.JSONObject
import java.io.File

/**
 * Configuration manager.
 *
 * The Magisk module directory keeps only config.conf. config.conf points at the
 * configuration root (default /data/adb/Zhang) and carries the master logging
 * switch `log_enabled`. All feature configuration lives under the configuration
 * root and is initialized on first run.
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
    private val mainConfigFile: File get() = File(rootDir, "config.json")
    private val mainConfig: JSONObject = JSONObject()

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
                Logger.w("ConfigManager", "config.conf parse error, using defaults: ${t.message}")
            }
        } else {
            writeDefaultConfigConf()
        }
        if (!parsedRoot.isNullOrBlank()) rootDir = parsedRoot!!
        logEnabled = parsedLog ?: true
        rootFile.mkdirs()
        logDir.mkdirs()
        cacheDir.mkdirs()
        loadMainConfig()
        initUserConfigs()
    }

    fun getBool(key: String, default: Boolean): Boolean =
        mainConfig.optBoolean(key, default)

    fun getString(key: String, default: String): String =
        mainConfig.optString(key, default)

    fun getStringList(key: String, default: List<String>): List<String> {
        val arr = mainConfig.optJSONArray(key) ?: return default
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun setBool(key: String, value: Boolean) {
        mainConfig.put(key, value)
        saveMainConfig()
    }

    fun saveMainConfig() {
        try {
            mainConfigFile.parentFile?.mkdirs()
            mainConfigFile.writeText(mainConfig.toString(2))
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "failed to save config.json: ${t.message}")
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
            Logger.w("ConfigManager", "failed to write default config.conf: ${t.message}")
        }
    }

    private fun loadMainConfig() {
        try {
            if (mainConfigFile.exists()) {
                mainConfigFile.readText().let { raw ->
                    val parsed = JSONObject(raw)
                    mainConfig.keys().forEach { mainConfig.remove(it) }
                    parsed.keys().forEach { mainConfig.put(it, parsed.get(it)) }
                }
            } else {
                mainConfig.put("isserver", false)
                mainConfig.put("stdltm", false)
                mainConfig.put("appops", false)
                mainConfig.put("eve", false)
                mainConfig.put("closeselinux", false)
                mainConfig.put("addopen", false)
                mainConfig.put("powersave", false)
                mainConfig.put("redictallapps", false)
                mainConfig.put("boost_flag", false)
                mainConfig.put("run_once", false)
                mainConfig.put("max_cpu_flag", false)
                mainConfig.put("change_joyose", false)
                mainConfig.put("only_base", true)
                mainConfig.put("read_game_list", true)
                mainConfig.put("boost_game_flag", false)
                mainConfig.put("redire_providers_media_module", false)
                mainConfig.put("network_ipv6_disable", false)
                saveMainConfig()
            }
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "config.json corrupted, using defaults: ${t.message}")
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
            Logger.i("ConfigManager", "initialized $rel")
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "failed to init $rel: ${t.message}")
        }
    }

    companion object {
        /** Original module doze.conf shipped with the module, used as the default white list. */
        const val DEFAULT_DOZE_CONF =
            "+bin.mt.plus\n" +
                "+meow.helper\n" +
                "+rikka.appops\n" +
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
                "org.autojs.autojspro\n" +
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

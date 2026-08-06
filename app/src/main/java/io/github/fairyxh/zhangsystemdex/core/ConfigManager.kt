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
        copyOrInit("game_pause.conf", "")
        copyOrInit("asguard.conf", "")
        copyOrInit("notification.conf", "", copyFromModule = true)
        copyOrInit("autorun.conf", "", copyFromModule = true)
        copyOrInit("HideMyAppList_MoreBlack.txt",
            "# user blacklist: one package name per line, appended to the hidden list\n")
        File(rootDir, "app_manager").mkdirs()
        copyOrInit("app_manager/disable_app_list.conf",
            "com.miui.hybrid\ncom.android.updater\ncom.nearme.instant.platform\ncom.oplus.ota\n")
        copyOrInit("app_manager/disable_app_list_onlydisable.conf",
            "com.oplus.safecenter\ncom.oplus.securitypermission\n")
        File(rootDir, "CleanedRubbish").mkdirs()
    }

    private fun copyOrInit(rel: String, defaultContent: String, copyFromModule: Boolean = false) {
        val target = File(rootDir, rel)
        if (target.exists()) return
        try {
            if (copyFromModule) {
                val src = File(modDir, rel.substringAfterLast('/'))
                if (src.exists() && src.isFile) {
                    src.copyTo(target, overwrite = false)
                    Logger.i("ConfigManager", "initialized $rel from module template")
                    return
                }
            }
            target.parentFile?.mkdirs()
            target.writeText(defaultContent)
            Logger.i("ConfigManager", "initialized $rel")
        } catch (t: Throwable) {
            Logger.w("ConfigManager", "failed to init $rel: ${t.message}")
        }
    }

    companion object {
        const val DEFAULT_DOZE_CONF =
            "+li.songe.gkd\n" +
                "+com.box.app\n" +
                "+com.mi.home\n" +
                "+com.mi.health\n" +
                "+com.tencent.mm\n" +
                "+com.gotokeep.keep\n" +
                "+com.kugou.android\n" +
                "+com.tencent.mobileqq\n" +
                "+com.tencent.qqmusic\n" +
                "+com.heytap.health\n" +
                "+com.omarea.vtools\n" +
                "+com.zidongdianji\n" +
                "+com.baidu.netdisk\n" +
                "+me.piebridge.brevent\n" +
                "+moe.fuqiuluo.portal\n" +
                "+tornaco.apps.shortx\n" +
                "+top.bogey.touch_tool\n" +
                "+com.kugou.android.lite\n" +
                "+com.miui.home\n" +
                "+com.zmzx.college.search\n" +
                "+moe.fuqiuluo.portaldev\n" +
                "+moe.shizuku.privileged.api\n" +
                "+org.autojs.autojspro\n" +
                "+com.vphonegaga.titan\n" +
                "+com.lerist.fakelocation\n" +
                "+com.eg.android.AlipayGphone\n" +
                "+com.drdisagree.colorblendr\n" +
                "+com.wstxda.viper4android\n" +
                "+com.suda.yzune.wakeupschedule\n" +
                "+com.fvcorp.android.aijiasuclientw\n"
    }
}

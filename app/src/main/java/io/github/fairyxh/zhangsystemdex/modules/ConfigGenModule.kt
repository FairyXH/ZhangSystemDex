package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.GameListProvider
import io.github.fairyxh.zhangsystemdex.core.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Generated configuration: HideMyAppList config.json, DoNotTryAccessibility
 * rules XML and incremental tricky_store/hmspush target lists. All JSON is
 * built through org.json. Replaces HideMyApplistJsonUpdate.sh,
 * DoNotTryAccessibility_Main.sh, tricky_store_target_set.sh and
 * hmspush_target_set.sh.
 */
class ConfigGenModule(
    ctx: DexContext,
    private val scanner: LSPosedScannerModule,
) : DaemonLoop(ctx, 600_000L, pauseAware = false) {
    override val name: String get() = "ConfigGen"

    private val extBlacklistPattern = listOf(
        "com.miui", "com.xiaomi", "com.lbe.security.miui", "com.coolapk.market",
        "com.huawei.hwid", "com.tencent.mm", "com.tencent.mobileqq", "com.microsoft.emmx",
        "com.agc.gcam84", "com.miui.weather2", "luna.safe.luna", "com.xiaomi.market",
        "com.oplus", "com.coloros", "com.heytap", "com.finshell",
    )

    private val extraBlacklist = listOf(
        "org.lsposed.lspatch", "com.omarea.vtools", "io.github.vvb2060.mahoshojo",
        "icu.nullptr.applistdetector", "com.tsng.hidemyapplist", "com.byxiaorun.detector",
        "com.zhenxi.hunter", "luna.safe.luna", "icu.nullptr.nativetest",
        "io.github.huskydg.memorydetector", "me.garfieldhan.holmes","bin.mt.plus","bin.mt.termex"
    )

    private val moreWhiteList = listOf(
        "com.hicorenational.antifraud", "com.coloros.phonemanager", "com.oplus.safecenter",
        "com.oplus.pay", "com.oplus.appplatform",
    )

    override fun onStart() {
        Logger.i(name, "模块启动，首次全量生成（含 Xposed 扫描）")
        generateHma(forceScan = true)
    }

    override fun tick() {
        generateHma(forceScan = false)
    }

    fun generateHma(forceScan: Boolean) {
        try {
            val xposedPackages = scanner.scan(forceScan).map { it.packageName }.toSet()
            val thirdParty = AppListProvider.thirdPartyPackages().toSet()
            val ime = AppListProvider.inputMethods()
            val games = GameListProvider.refresh(ctx.config.switch("read_game_list_enable")).toSet()
            val userBlack = readUserBlacklist()

            val whiteSys = mutableSetOf<String>()
            for (pkg in AppListProvider.allPackages()) {
                if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) whiteSys.add(pkg)
            }
            whiteSys.addAll(ime)
            whiteSys.addAll(games)

            val blackPool = linkedSetOf<String>()
            blackPool.addAll(xposedPackages)
            blackPool.addAll(extraBlacklist)
            blackPool.add("icu.nullptr.applistdetector")
            blackPool.addAll(userBlack)

            val whiteMode = mutableListOf<String>()
            val blackMode = mutableListOf<String>()
            for (pkg in thirdParty) {
                if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.")) continue
                if (pkg in xposedPackages) continue
                when {
                    matchesAny(pkg, extBlacklistPattern) -> blackMode.add(pkg)
                    pkg in moreWhiteList -> whiteMode.add(pkg)
                    else -> whiteMode.add(pkg)
                }
            }
            whiteMode.addAll(moreWhiteList)

            val scope = JSONObject()
            for (pkg in whiteMode) scope.put(pkg, scopeEntry(useWhitelist = true))
            for (pkg in blackMode) scope.put(pkg, scopeEntry(useWhitelist = false))

            val whiteAppList = JSONArray()
            val baseWhite = listOf(
                "com.miui.securitycenter", "com.miui.guardprovider", "com.huawei.hwid",
                "com.tencent.mm", "com.tencent.mobileqq", "com.google.android.webview",
                "com.microsoft.emmx", "com.eg.android.AlipayGphone", "com.miui.mediaeditor",
                "com.miui.gallery", "com.android.camera", "com.oplus.appplatform",
                "com.coloros.gallery3d",
            )
            for (p in baseWhite) whiteAppList.put(p)
            for (p in whiteSys) whiteAppList.put(p)

            val blackAppList = JSONArray()
            for (p in blackPool) blackAppList.put(p)

            val templates = JSONObject()
            templates.put("Zhang-Protect-WhiteList", JSONObject()
                .put("appList", whiteAppList)
                .put("isWhitelist", true)
                .put("mapsRules", JSONArray())
                .put("queryParamRules", JSONArray()))
            templates.put("Zhang-Protect-BlackList", JSONObject()
                .put("appList", blackAppList)
                .put("isWhitelist", false)
                .put("mapsRules", JSONArray())
                .put("queryParamRules", JSONArray()))

            val root = JSONObject()
            root.put("configVersion", 90)
            root.put("detailLog", false)
            root.put("maxLogSize", 256)
            root.put("forceMountData", true)
            root.put("scope", scope)
            root.put("templates", templates)

            val jsonText = root.toString(2)
            val hmaData = File("/data/data/com.tsng.hidemyapplist/files/config.json")
            if (hmaData.parentFile?.exists() == true) {
                hmaData.writeText(jsonText)
                Logger.i(name, "HMA 配置已写入 ${hmaData.path}")
            }
            val moduleCopy = File(ctx.modDir, "ZhangSetting/隐藏应用列表全隐藏.json")
            moduleCopy.parentFile?.mkdirs()
            moduleCopy.writeText(jsonText)
            val downloadDir = File("/data/media/0/Download/ZhangSetting")
            downloadDir.mkdirs()
            FileUtils.copyFile(moduleCopy, File(downloadDir, moduleCopy.name))
            FileUtils.copyDirRecursive(File(ctx.modDir, "ZhangSetting"), downloadDir)
            Logger.i(name, "HMA 配置已生成（白名单模式=${whiteMode.size}，黑名单模式=${blackMode.size}，黑名单池=${blackPool.size}）")
        } catch (t: Throwable) {
            Logger.e(name, "生成 HMA 配置失败", t)
        }
    }

    fun generateDoNotTryAccessibility() {
        try {
            val packages = AppListProvider.thirdPartyPackages()
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n")
            sb.append("<map>\n")
            sb.append("    <set name=\"_block_apps\">\n")
            for (pkg in packages) {
                sb.append("        <string>").append(pkg).append("</string>\n")
            }
            sb.append("    </set>\n")
            sb.append("</map>\n")
            val xmlFile = File(ctx.modDir, "ZhangSetting/DoNotTryAccessibility规则.xml")
            xmlFile.parentFile?.mkdirs()
            xmlFile.writeText(sb.toString())
            val downloadDir = File("/data/media/0/Download/ZhangSetting")
            downloadDir.mkdirs()
            FileUtils.copyFile(xmlFile, File(downloadDir, xmlFile.name))
            FileUtils.copyDirRecursive(File(ctx.modDir, "ZhangSetting"), downloadDir)
            Logger.i(name, "已生成 DoNotTryAccessibility 规则（${packages.size} 个应用）")
        } catch (t: Throwable) {
            Logger.w(name, "生成 DoNotTryAccessibility 失败: ${t.message}")
        }
    }

    fun updateTargetList(kind: String) {
        try {
            when (kind) {
                "tricky" -> updateTrickyStore()
                "hmspush" -> updateHmspush()
            }
        } catch (t: Throwable) {
            Logger.w(name, "生成 $kind 目标列表失败: ${t.message}")
        }
    }

    private fun updateTrickyStore() {
        val target = File("/data/adb/tricky_store/target.txt")
        val known = File("/data/adb/tricky_store/target.known")
        if (!target.exists()) target.writeText("com.google.android.gms\n")
        FileUtils.touch(known.path)
        val newPackages = AppListProvider.allPackages().toSet() - knownPackages(known)
        if (newPackages.isNotEmpty()) {
            target.appendText(newPackages.sorted().joinToString("\n") + "\n")
            known.appendText(newPackages.sorted().joinToString("\n") + "\n")
            Logger.i(name, "tricky_store 目标新增 ${newPackages.size} 个包")
        }
    }

    private fun updateHmspush() {
        val target = File("/data/adb/hmspush/app.conf")
        val known = File("/data/adb/hmspush/app.known")
        FileUtils.touch(target.path)
        FileUtils.touch(known.path)
        val gamePause = GameListProvider.games().toSet()
        val exclude = Regex("com\\.tencent\\.mm|youqu\\.android\\.todesk|wallet|oplus|oppo|xiaomi|hyperos")
        val candidates = AppListProvider.thirdPartyPackages()
            .filter { !exclude.containsMatchIn(it) && it !in gamePause }
            .toSet()
        val newPackages = candidates - knownPackages(known)
        if (newPackages.isNotEmpty()) {
            target.appendText(newPackages.sorted().joinToString("\n") + "\n")
            known.appendText(newPackages.sorted().joinToString("\n") + "\n")
            Logger.i(name, "hmspush 目标新增 ${newPackages.size} 个包")
        }
    }

    private fun knownPackages(known: File): Set<String> {
        if (!known.exists()) return emptySet()
        return known.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun readUserBlacklist(): List<String> {
        val f = File(ctx.config.rootDir, "HideMyAppList_MoreBlack.txt")
        if (!f.exists()) return emptyList()
        return f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun scopeEntry(useWhitelist: Boolean): JSONObject {
        val hooks = JSONArray()
        for (h in listOf("Maps scan", "Intent queries", "ID detections", "API requests")) hooks.put(h)
        val templates = JSONArray()
        templates.put(if (useWhitelist) "Zhang-Protect-WhiteList" else "Zhang-Protect-BlackList")
        return JSONObject()
            .put("applyHooks", hooks)
            .put("applyTemplates", templates)
            .put("enableAllHooks", true)
            .put("excludeSystemApps", false)
            .put("aggressiveFilter", true)
            .put("extraAppList", JSONArray())
            .put("extraMapsRules", JSONArray())
            .put("extraQueryParamRules", JSONArray())
            .put("useWhitelist", useWhitelist)
    }

    private fun matchesAny(pkg: String, patterns: List<String>): Boolean =
        patterns.any { pkg.contains(it) }
}

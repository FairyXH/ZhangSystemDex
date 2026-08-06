package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Xposed/LSPosed module scanner with cache. Precedence:
 * 1. LSPosed's own configuration data
 * 2. PackageManager installed packages + ApplicationInfo.metaData markers
 * 3. (fallback removed) aapt is never invoked
 *
 * First scan is cached to /data/adb/Zhang/cache/xposed_modules.json; later
 * scans are skipped when the installed-package signature is unchanged.
 */
class LSPosedScannerModule(private val ctx: DexContext) {

    data class ModuleInfo(
        val packageName: String,
        val name: String,
        val version: String,
        val enabled: Boolean,
        val scopes: List<String>,
    )

    private val lock = Any()

    @Volatile
    private var modules: List<ModuleInfo> = emptyList()

    private val cacheFile: File get() = File(ctx.config.cacheDir, "xposed_modules.json")

    fun scan(force: Boolean = false): List<ModuleInfo> {
        synchronized(lock) {
            if (!force && modules.isNotEmpty()) return modules
            val signature = packageSignature()
            val cached = readCache()
            if (!force && cached != null && cached.first == signature) {
                modules = cached.second
                Logger.i("LSPosedScanner", "cache hit (${modules.size} modules)")
                return modules
            }
            Logger.i("LSPosedScanner", "full scan started (signature ${signature.take(12)}...)")
            val modulesResult = readLsposedConfig().distinctBy { it.packageName }
            modules = modulesResult
            writeCache(signature, modules)
            Logger.i("LSPosedScanner", "scan finished: ${modules.size} modules")
            return modules
        }
    }

    fun modules(): List<ModuleInfo> {
        if (modules.isEmpty()) scan(force = false)
        return modules
    }

    private fun readLsposedConfig(): List<ModuleInfo> {
        val candidates = listOf(
            File("/data/adb/lspd/config/modules.list"),
            File("/data/adb/modules/lsposed/config/modules.list"),
            File("/data/adb/lspd/modules.list"),
        )
        for (f in candidates) {
            if (!f.exists()) continue
            try {
                val result = ArrayList<ModuleInfo>()
                f.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val parts = line.split('|')
                    val pkg = parts[0].trim()
                    if (pkg.isEmpty()) return@forEach
                    val scopes = parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                    result.add(ModuleInfo(pkg, pkg, "", true, scopes))
                }
                if (result.isNotEmpty()) {
                    Logger.i("LSPosedScanner", "lsposed config: ${result.size} modules from ${f.path}")
                    return result
                }
            } catch (t: Throwable) {
                Logger.w("LSPosedScanner", "read ${f.path} failed: ${t.message}")
            }
        }
        // Fallback: newer LSPosed keeps module scope data in the manager database.
        // Enumerate likely directories so version-specific paths are covered.
        val dbDirs = listOf(
            File("/data/adb/lspd"),
            File("/data/user_de/0/org.lsposed.manager/databases"),
            File("/data/user/0/org.lsposed.manager/databases"),
            File("/data/adb/modules/lsposed"),
        )
        val targets = LinkedHashSet<String>()
        for (dir in dbDirs) {
            if (!dir.exists()) continue
            collectLsposedTargets(dir, targets, depth = 0)
        }
        // Prefer the LSPosed 2.x modules_config.db schema:
        //   modules(module_pkg_name, apk_path)
        //   modules_state(module_pkg_name, user_id, enabled, scope_request_blocked)
        //   scope(module_pkg_name, app_pkg_name, user_id)
        val lsposedDb = File("/data/adb/lspd/config/modules_config.db")
        if (lsposedDb.exists()) {
            try {
                // Only keep modules that are actually installed: this drops
                // uninstalled zombies and component-only entries such as
                // lyricon's *.cmprovider/*.kgprovider records.
                val installed = AppListProvider.allPackages().toSet()
                val allPkgs = SqliteUtils.queryFirst(lsposedDb.path, "SELECT module_pkg_name FROM modules")
                    .distinct()
                    .filter { installed.isEmpty() || it in installed }
                if (allPkgs.isNotEmpty()) {
                    val enabledSet = SqliteUtils.queryFirst(
                        lsposedDb.path,
                        "SELECT module_pkg_name FROM modules_state WHERE enabled = 1"
                    ).toSet()
                    val scopePairs = SqliteUtils.queryPairs(
                        lsposedDb.path,
                        "SELECT module_pkg_name, app_pkg_name FROM scope WHERE user_id = 0"
                    )
                    val scopeMap = scopePairs.groupBy({ it.first }, { it.second })
                    val result = allPkgs.map { pkg ->
                        ModuleInfo(pkg, pkg, "", pkg in enabledSet, scopeMap[pkg] ?: emptyList())
                    }
                    val enabledCount = result.count { it.enabled }
                    Logger.i(
                        "LSPosedScanner",
                        "lsposed modules_config.db: ${result.size} total, $enabledCount enabled"
                    )
                    return result
                }
            } catch (t: Throwable) {
                Logger.w("LSPosedScanner", "lsposed modules_config.db parse failed: ${t.message}")
            }
        }
        val queries = listOf(
            "SELECT module_pkg_name FROM scope WHERE user_id = 0",
            "SELECT module_pkg_name FROM scope",
            "SELECT module_pkg_name FROM modules",
            "SELECT package_name FROM modules",
        )
        for (target in targets) {
            if (target.endsWith("modules.list")) {
                try {
                    val result = ArrayList<ModuleInfo>()
                    File(target).readLines().forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val parts = line.split('|')
                        val pkg = parts[0].trim()
                        if (pkg.isEmpty()) return@forEach
                        val scopes = parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                        result.add(ModuleInfo(pkg, pkg, "", true, scopes))
                    }
                    if (result.isNotEmpty()) {
                        Logger.i("LSPosedScanner", "lsposed modules.list: ${result.size} modules from $target")
                        return result
                    }
                } catch (t: Throwable) {
                    Logger.w("LSPosedScanner", "read $target failed: ${t.message}")
                }
                continue
            }
            for (q in queries) {
                try {
                    val rows = SqliteUtils.queryFirst(target, q)
                    if (rows.isNotEmpty()) {
                        val result = rows.distinct().map { ModuleInfo(it, it, "", true, emptyList()) }
                        Logger.i("LSPosedScanner", "lsposed db: ${result.size} modules from $target (query: $q)")
                        return result
                    }
                } catch (t: Throwable) {
                    Logger.w("LSPosedScanner", "lsposed db query $target failed: ${t.message}")
                }
            }
        }
        return emptyList()
    }

    private fun collectLsposedTargets(dir: File, out: MutableSet<String>, depth: Int) {
        if (depth > 4) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isFile && (f.name.endsWith(".db") || f.name == "modules.list")) {
                out.add(f.path)
            } else if (f.isDirectory) {
                collectLsposedTargets(f, out, depth + 1)
            }
        }
    }

    private fun packageSignature(): String {
        val all = AppListProvider.allPackages().sorted()
        val sb = StringBuilder()
        for (pkg in all) sb.append(pkg).append('\n')
        return sb.toString().hashCode().toString()
    }

    private fun readCache(): Pair<String, List<ModuleInfo>>? {
        if (!cacheFile.exists()) return null
        return try {
            val obj = JSONObject(cacheFile.readText())
            val sig = obj.optString("signature", "")
            val arr = obj.optJSONArray("modules") ?: return null
            val list = ArrayList<ModuleInfo>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val scopesArr = m.optJSONArray("scopes")
                val scopes = ArrayList<String>()
                if (scopesArr != null) for (j in 0 until scopesArr.length()) scopes.add(scopesArr.optString(j))
                list.add(ModuleInfo(
                    m.optString("package_name"),
                    m.optString("name"),
                    m.optString("version"),
                    m.optBoolean("enabled"),
                    scopes,
                ))
            }
            Pair(sig, list)
        } catch (t: Throwable) {
            Logger.w("LSPosedScanner", "cache read failed: ${t.message}")
            null
        }
    }

    private fun writeCache(signature: String, list: List<ModuleInfo>) {
        try {
            ctx.config.cacheDir.mkdirs()
            val obj = JSONObject()
            obj.put("signature", signature)
            obj.put("last_scan", System.currentTimeMillis())
            val arr = JSONArray()
            for (m in list) {
                arr.put(JSONObject()
                    .put("package_name", m.packageName)
                    .put("name", m.name)
                    .put("version", m.version)
                    .put("enabled", m.enabled)
                    .put("scopes", JSONArray(m.scopes)))
            }
            obj.put("modules", arr)
            cacheFile.writeText(obj.toString(2))
        } catch (t: Throwable) {
            Logger.w("LSPosedScanner", "cache write failed: ${t.message}")
        }
    }
}

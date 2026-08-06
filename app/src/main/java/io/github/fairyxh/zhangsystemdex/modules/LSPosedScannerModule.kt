package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.AppListProvider
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
import io.github.fairyxh.zhangsystemdex.core.SystemContext
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
            val fromLsposed = readLsposedConfig()
            val result = LinkedHashMap<String, ModuleInfo>()
            val metaModules = scanByMetadata()
            for (m in metaModules) {
                result[m.packageName] = m
            }
            for (m in fromLsposed) {
                val prev = result[m.packageName]
                result[m.packageName] = if (prev != null) {
                    prev.copy(enabled = true, scopes = if (m.scopes.isNotEmpty()) m.scopes else prev.scopes)
                } else {
                    m
                }
            }
            modules = result.values.toList()
            writeCache(signature, modules)
            Logger.i("LSPosedScanner", "scan finished: ${modules.size} modules")
            return modules
        }
    }

    fun modules(): List<ModuleInfo> {
        if (modules.isEmpty()) scan(force = false)
        return modules
    }

    private fun scanByMetadata(): List<ModuleInfo> {
        val result = ArrayList<ModuleInfo>()
        val ctxRef = SystemContext.get()
        if (ctxRef == null) {
            Logger.w("LSPosedScanner", "no system context, metadata scan skipped")
            return result
        }
        try {
            val pm = ctxRef.packageManager
            val apps = pm.getInstalledApplications(0)
            for (app in apps) {
                try {
                    val meta = app.metaData ?: continue
                    if (!meta.containsKey("xposedmodule")) continue
                    val name = meta.getString("xposeddescription") ?: pm.getApplicationLabel(app).toString()
                    val version = try {
                        pm.getPackageInfo(app.packageName, 0).versionName ?: ""
                    } catch (_: Throwable) {
                        ""
                    }
                    val scopesRaw = meta.getString("xposedscope") ?: ""
                    val scopes = scopesRaw.split('|', ',').filter { it.isNotBlank() }
                    result.add(ModuleInfo(app.packageName, name, version, true, scopes))
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            Logger.w("LSPosedScanner", "metadata scan failed: ${t.message}")
        }
        return result
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
        val dbs = listOf(
            "/data/user_de/0/org.lsposed.manager/databases/lspd.db",
            "/data/user/0/org.lsposed.manager/databases/lspd.db",
            "/data/adb/lspd/db/lspd.db",
        )
        val queries = listOf(
            "SELECT module_pkg_name FROM scope WHERE user_id = 0",
            "SELECT module_pkg_name FROM scope",
            "SELECT module_pkg_name FROM modules",
            "SELECT package_name FROM modules",
        )
        for (db in dbs) {
            if (!File(db).exists()) continue
            for (q in queries) {
                try {
                    val rows = SqliteUtils.queryFirst(db, q)
                    if (rows.isNotEmpty()) {
                        val result = rows.distinct().map { ModuleInfo(it, it, "", true, emptyList()) }
                        Logger.i("LSPosedScanner", "lsposed db: ${result.size} modules from $db")
                        return result
                    }
                } catch (t: Throwable) {
                    Logger.w("LSPosedScanner", "lsposed db query $db failed: ${t.message}")
                }
            }
        }
        return emptyList()
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

package io.github.fairyxh.zhangsystemdex.core

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * SQLite access without a Context. Used for MIUI powerkeeper/joyose databases
 * and OnePlus game-list databases.
 *
 * On Android 15 an app_process systemMain Context cannot reach the settings
 * provider ("Unable to find app for caller ... getting content provider
 * settings"), which SQLiteDatabase may consult during initialization. We first
 * reflect SQLiteCompatibilityWalFlags.init(null) to skip that read; if the
 * framework path still fails, the failure is cached (one WARN) and we fall
 * back to a bundled/global sqlite3 CLI when present.
 */
object SqliteUtils {
    @Volatile
    private var compatInitAttempted = false

    @Volatile
    private var frameworkBroken = false

    @Volatile
    private var cliChecked = false

    @Volatile
    private var cliAvailable = false

    private fun ensureCompatInit() {
        if (compatInitAttempted) return
        compatInitAttempted = true
        try {
            val clazz = Class.forName("android.database.sqlite.SQLiteCompatibilityWalFlags")
            val m = clazz.getMethod("init", ContentResolver::class.java)
            m.invoke(null, null)
            Logger.i("SqliteUtils", "SQLiteCompatibilityWalFlags 已初始化（跳过设置读取）")
        } catch (t: Throwable) {
            Logger.w("SqliteUtils", "SQLiteCompatibilityWalFlags 不可用: ${t.message}")
        }
    }

    private fun frameworkUnavailable(op: String, t: Throwable): Boolean {
        if (!frameworkBroken) {
            frameworkBroken = true
            Logger.w("SqliteUtils", "$op 框架 SQLite 不可用（仅记录一次）: ${t.message}")
        }
        return cliAvailable()
    }

    /** True when a sqlite3 CLI can back the framework path. */
    private fun cliAvailable(): Boolean {
        if (!cliChecked) {
            cliChecked = true
            cliAvailable = File("/data/adb/Zhang/cache/sqlite3").exists() ||
                File("/data/adb/Zhang/cache/sqlite_lib/sqlite3").exists() ||
                ShellExecutor.runExit("command -v sqlite3") == 0
            if (!cliAvailable) {
                Logger.w("SqliteUtils", "未找到 sqlite3 CLI（仅记录一次）: 无框架 SQLite 时数据库操作不可用")
            }
        }
        return cliAvailable
    }

    private fun cliPath(): String {
        return when {
            File("/data/adb/Zhang/cache/sqlite_lib/sqlite3").exists() ->
                "/data/adb/Zhang/cache/sqlite_lib/sqlite3"
            File("/data/adb/Zhang/cache/sqlite3").exists() ->
                "/data/adb/Zhang/cache/sqlite3"
            else -> "sqlite3"
        }
    }

    /** Prefix needed for the bundled Termux sqlite3 (its libs live next to it). */
    private fun cliPrefix(): String {
        return if (File("/data/adb/Zhang/cache/sqlite_lib/sqlite3").exists()) {
            "LD_LIBRARY_PATH=/data/adb/Zhang/cache/sqlite_lib "
        } else {
            ""
        }
    }

    private fun cliCmd(path: String, sql: String): String = "${cliPrefix()}${cliPath()} '$path' \"$sql\""

    fun exec(path: String, sql: String): Boolean {
        if (!frameworkBroken) {
            ensureCompatInit()
            try {
                val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    db.execSQL(sql)
                } finally {
                    db.close()
                }
                return true
            } catch (t: Throwable) {
                frameworkUnavailable("exec", t)
            }
        }
        return fallbackCli(path, sql)
    }

    fun queryFirst(path: String, sql: String): List<String> {
        if (!frameworkBroken) {
            ensureCompatInit()
            try {
                val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    val result = ArrayList<String>()
                    db.rawQuery(sql, null).use { c ->
                        while (c.moveToNext()) {
                            result.add(if (c.columnCount > 0) c.getString(0) ?: "" else "")
                        }
                    }
                    return result
                } finally {
                    db.close()
                }
            } catch (t: Throwable) {
                frameworkUnavailable("query", t)
            }
        }
        return fallbackCliQuery(path, sql)
    }

    /** Query the first two columns as (a, b) pairs. */
    fun queryPairs(path: String, sql: String): List<Pair<String, String>> {
        if (!frameworkBroken) {
            ensureCompatInit()
            try {
                val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    val result = ArrayList<Pair<String, String>>()
                    db.rawQuery(sql, null).use { c ->
                        while (c.moveToNext()) {
                            result.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
                        }
                    }
                    return result
                } finally {
                    db.close()
                }
            } catch (t: Throwable) {
                frameworkUnavailable("pairs", t)
            }
        }
        if (!cliAvailable()) return emptyList()
        val (rc, out) = ShellExecutor.runWithCode(cliCmd(path, sql))
        if (rc != 0 || out == null) return emptyList()
        val result = ArrayList<Pair<String, String>>()
        for (line in out.lineSequence()) {
            val parts = line.split('|')
            if (parts.size >= 2) result.add(parts[0].trim() to parts[1].trim())
        }
        return result
    }

    private fun fallbackCli(path: String, sql: String): Boolean {
        if (!cliAvailable()) return false
        return ShellExecutor.runWithCode(cliCmd(path, sql)).first == 0
    }

    private fun fallbackCliQuery(path: String, sql: String): List<String> {
        if (!cliAvailable()) return emptyList()
        val (rc, out) = ShellExecutor.runWithCode(cliCmd(path, sql))
        if (rc != 0 || out == null) return emptyList()
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }
}

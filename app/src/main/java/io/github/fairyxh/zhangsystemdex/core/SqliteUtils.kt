package io.github.fairyxh.zhangsystemdex.core

import android.database.sqlite.SQLiteDatabase

/**
 * SQLite access without a Context. Used for MIUI powerkeeper/joyose databases
 * and OnePlus game-list databases. Falls back to the bundled sqlite3 binary.
 */
object SqliteUtils {
    fun exec(path: String, sql: String): Boolean {
        try {
            val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.execSQL(sql)
            } finally {
                db.close()
            }
            return true
        } catch (t: Throwable) {
            Logger.w("SqliteUtils", "sqlite exec failed on $path: ${t.message}")
            return fallbackCli(path, sql)
        }
    }

    fun queryFirst(path: String, sql: String): List<String> {
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
            Logger.w("SqliteUtils", "sqlite query failed on $path: ${t.message}")
            return fallbackCliQuery(path, sql)
        }
    }

    private fun fallbackCli(path: String, sql: String): Boolean {
        val cli = if (java.io.File("/data/adb/Zhang/cache/sqlite3").exists()) {
            "/data/adb/Zhang/cache/sqlite3"
        } else {
            "sqlite3"
        }
        val out = ShellExecutor.run("$cli '$path' \"$sql\"")
        return out != null
    }

    private fun fallbackCliQuery(path: String, sql: String): List<String> {
        val cli = if (java.io.File("/data/adb/Zhang/cache/sqlite3").exists()) {
            "/data/adb/Zhang/cache/sqlite3"
        } else {
            "sqlite3"
        }
        val out = ShellExecutor.run("$cli '$path' \"$sql\"") ?: return emptyList()
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }
}

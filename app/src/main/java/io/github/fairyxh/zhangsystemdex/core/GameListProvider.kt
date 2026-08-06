package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * Game list aggregation: MIUI game booster list + OnePlus/OPPO game database +
 * user-configured game_pause.conf. Shared by GamePauseModule, DozeListChange
 * and HideMyApplist config generation.
 */
object GameListProvider {
    private const val MIUI_GAME_LIST = "/data/data/com.miui.securitycenter/files/gamebooster/gblist"
    private const val OPLUS_GAME_DB = "/data/data/com.oplus.games/databases/applist.db"

    @Volatile
    private var cached: List<String>? = null

    fun refresh(readGameList: Boolean): List<String> {
        val result = LinkedHashSet<String>()
        val conf = File(if (DexContext.current != null) DexContext.current!!.config.rootDir else "/data/adb/Zhang", "game_pause.conf")
        if (conf.exists()) {
            conf.forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty() && !t.startsWith("#")) result.add(t)
            }
        }
        if (readGameList) {
            val miui = File(MIUI_GAME_LIST)
            if (miui.exists()) {
                try {
                    miui.forEachLine { line ->
                        val t = line.trim()
                        if (t.isNotEmpty()) result.add(t)
                    }
                } catch (_: Throwable) {
                }
            }
            if (File(OPLUS_GAME_DB).exists()) {
                val rows = SqliteUtils.queryFirst(OPLUS_GAME_DB, "SELECT pkg_name FROM app_list WHERE state > 0;")
                result.addAll(rows)
            }
        }
        val list = result.toList()
        cached = list
        return list
    }

    fun games(): List<String> =
        cached ?: refresh(DexContext.current?.config?.switch("read_game_list_enable") ?: false)
}

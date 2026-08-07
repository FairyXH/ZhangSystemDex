package io.github.fairyxh.zhangsystemdex.core
import android.content.Context
/**
 * Runtime context handed to every module: configuration root, logger, global
 * pause coordinator and the app_process module directory.
 */
class DexContext(val modDir: String) {
    val androidContext: Context?
        get() = ContextProvider.get()

    val config: ConfigManager = ConfigManager(modDir)
    val gamePause: GamePauseCoordinator = GamePauseCoordinator()

    fun load() {
        Logger.init(java.io.File("/data/adb/Zhang/log"))
        config.load()
        Logger.setEnabled(config.logEnabled)
    }

    companion object {
        @Volatile
        var current: DexContext? = null
    }
}

package io.github.fairyxh.zhangsystemdex.core

import java.io.File

/**
 * Shizuku 防检测：**精确白名单**形式的痕迹清理规则。
 *
 * ## 为什么需要
 *
 * Shizuku 在通过 ADB / root 启动时会往 `/data/local` 系列目录落文件，
 * 这些文件**文件名本身就带 "shizuku" 字样**，且位于任何应用都能枚举的
 * 公共位置（`/data/local/tmp` 对 shell 可读，部分检测方还会遍历）。
 * 典型检测面：
 *
 *   - `/data/local/shizuku_starter`        root 模式启动器残留
 *   - `/data/local/tmp/shizuku_starter`    旧版部署位置
 *   - `/data/local/tmp/shizuku`            服务端二进制
 *   - `/data/local/tmp/shizuku_server`     部分版本命名
 *   - `/data/local/tmp/rikka*`             Shizuku 作者包名痕迹
 *
 * 这些文件在 Shizuku 启动后会**常驻**（starter 是父进程依赖，不能无脑删；
 * 见下文「安全边界」），因此本模块只做**可控清理**，并且：
 *
 *   1. 只删**白名单内**的确切路径 / 其子路径（防越界）；
 *   2. 默认跳过正在运行的 starter（会造成 Shizuku 掉线），
 *      仅当用户显式开启 `shizuku_detect_clean_starter` 时才删；
 *   3. 单个目标失败不影响其它目标；
 *   4. 每次启动/周期执行，保证「删掉后又被 Shizuku 重建」的窗口尽量短。
 *
 * ## 安全边界（重要）
 *
 * - 本模块**绝不**做子串模糊匹配（`contains("shizuku")`），
 *   一律用精确路径比对，避免误删用户文件；
 * - 白名单未启用时即使路径匹配也不动；
 * - `/data/local/tmp` 下用户自己的文件不在白名单中，永不触碰。
 *
 * ## 与「防检测」的关系
 *
 * 严格说，Shizuku 的**进程**（`moe.shizuku.privileged.api`）无法通过删文件
 * 隐藏 —— 那需要 Zygisk/LSPosed 层面的进程名伪装。本模块的目标是消除
 * **文件系统层面的弱智检测点**（枚举 `/data/local*` 找 shizuku 关键字），
 * 这是绝大多数「检测到 Shizuku」实现实际依赖的东西。
 */
object ShizukuResidue {

    /** Shizuku 的应用包名。 */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /**
     * starter 在设备上的候选位置（按优先级）：
     *   1. `${外部存储}/Android/data/<pkg>/starter`（Shizuku 官方导出位置）
     *   2. `/data/local/tmp/shizuku_starter`
     *   3. `/data/local/shizuku_starter`
     */
    val STARTER_SOURCES: List<String> = listOf(
        "/storage/emulated/0/Android/data/$PACKAGE/starter",
        "/data/local/tmp/shizuku_starter",
        "/data/local/shizuku_starter",
    )

    /** 服务端二进制的工作位置（启动后由 starter 释放）。 */
    val SERVER_PATHS: List<String> = listOf(
        "/data/local/tmp/shizuku",
        "/data/local/tmp/shizuku_server",
    )

    /**
     * 防检测清理的**精确白名单**（仅这些路径及其子路径允许删除）。
     *
     * 分为三组：
     *  - `always`：纯残留，删掉绝对安全（Shizuku 会按需重建）；
     *  - `starter`：启动器副本，删除**可能**导致 Shizuku 无法自动重启，
     *    默认受开关保护（`shizuku_detect_clean_starter`）。
     */
    val ALWAYS_CLEAN: List<String> = listOf(
        // 旧版部署位置（现版本 Shizuku 已不使用）
        "/data/local/tmp/shizuku_starter",
        // 作者包名痕迹
        "/data/local/tmp/rikka.shizuku",
        "/data/local/tmp/rikka.shizuku_server",
    )

    /** 默认不删、需开关解锁的路径（删除会影响 Shizuku 自启）。 */
    val GUARDED_CLEAN: List<String> = listOf(
        "/data/local/shizuku_starter",
        "/data/local/tmp/shizuku",
        "/data/local/tmp/shizuku_server",
    )

    /** 全部可清理路径（未拆分时用）。 */
    val ALL: List<String> = ALWAYS_CLEAN + GUARDED_CLEAN

    /** 是否为白名单内路径（等于白名单项，或位于其下）。纯函数。 */
    fun isWhitelisted(path: String): Boolean {
        for (w in ALL) {
            if (path == w || path.startsWith("$w/")) return true
        }
        return false
    }

    /** 是否为「解锁后」才允许删除的受保护路径。纯函数。 */
    fun isGuarded(path: String): Boolean {
        for (w in GUARDED_CLEAN) {
            if (path == w || path.startsWith("$w/")) return true
        }
        return false
    }

    /**
     * 计算本轮应删除的路径集合。
     *
     * @param allowGuarded 是否允许删除受保护路径（对应开关
     *        `shizuku_detect_clean_starter`）
     * @param exists 存在性判定（注入以便单元测试，默认走真实文件系统）
     * @return 应删除的绝对路径列表（已按存在性过滤）
     */
    fun targets(
        allowGuarded: Boolean,
        exists: (String) -> Boolean = { File(it).exists() },
    ): List<String> {
        val out = ArrayList<String>()
        for (p in ALWAYS_CLEAN) if (exists(p)) out.add(p)
        if (allowGuarded) for (p in GUARDED_CLEAN) if (exists(p)) out.add(p)
        return out
    }
}

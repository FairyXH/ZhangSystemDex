package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.rubbish.OnlineRuleStore
import java.io.File

/**
 * 在线规则定时拉取模块。
 *
 * 每 [CHECK_INTERVAL_MS] 检查一次订阅源，到达各自 `intervalHours` 的源才真正拉取
 * （由 [OnlineRuleStore.fetchDue] 内部去重判定，本模块只负责「定期唤醒」）。
 *
 * 设计原则（对齐项目既有范式，见 [DaemonLoop]）：
 *  - 独立线程 + 异常隔离：一个源拉取失败绝不冒泡出 tick，更不会影响主清理流程；
 *  - 网络访问集中在 [OnlineRuleStore]（超时/大小上限/JSON 校验），本模块不做 HTTP；
 *  - 拉取失败保留旧缓存（OnlineRuleStore 语义），故此处仅记录日志与状态。
 *
 * 该模块由 `switches.conf` 的 `online_rules_enable` 控制启停；关闭时线程不创建。
 */
class OnlineRuleModule(ctx: DexContext) : DaemonLoop(ctx, CHECK_INTERVAL_MS, pauseAware = false) {

    override val name: String get() = "OnlineRules"

    private val root: File get() = File(ctx.config.rootDir)

    override fun onStart() {
        Logger.i(name, "在线规则模块启动（检查周期=${CHECK_INTERVAL_MS / 60000}min）")
        // 首次启动立即检查一次（不等待首个周期）。仅当确有到期源时才产生网络请求。
        pullIfDue()
    }

    override fun tick() {
        pullIfDue()
    }

    private fun pullIfDue() {
        try {
            val sources = OnlineRuleStore.listSources(root)
            if (sources.isEmpty()) return
            val enabled = sources.count { it.enabled }
            if (enabled == 0) return
            val (ok, fail) = OnlineRuleStore.fetchDue(root)
            if (ok > 0 || fail > 0) {
                Logger.i(name, "在线规则拉取完成: 成功=$ok 失败=$fail (启用源=$enabled)")
            }
        } catch (t: Throwable) {
            // 网络/解析异常一律吞掉：在线规则是增强能力，绝不能影响模块存活。
            Logger.w(name, "在线规则拉取异常: ${t.message}")
        }
    }

    companion object {
        /** 检查周期：5 分钟。真正的拉取频率由每个源的 intervalHours 决定。 */
        const val CHECK_INTERVAL_MS = 5 * 60 * 1000L

        /** switches.conf 开关键。 */
        const val SWITCH_KEY = "online_rules_enable"
    }
}

package io.github.fairyxh.zhangsystemdex.core

/**
 * LSPosed-style hidden API bypass. Called once at daemon startup: exempts all
 * hidden APIs for this process so framework reflection (ActivityThread system
 * context, PackageManager metadata, etc.) works from app_process Dex code.
 */
object HiddenApiBypass {
    private val enabledFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    fun enable() {
        if (enabledFlag.getAndSet(true)) return
        try {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vm.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null)
            val setExempt = vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExempt.isAccessible = true
            setExempt.invoke(runtime, arrayOf("L"))
            Logger.i("HiddenApiBypass", "已启用 Hidden API 豁免")
        } catch (t: Throwable) {
            Logger.w("HiddenApiBypass", "绕过失败: ${t.message}")
        }
    }
}

package io.github.fairyxh.zhangsystemdex.core

/**
 * Property read/write through resetprop (Magisk/KSU) with SystemProperties
 * reflection as the primary reader.
 */
object PropUtils {
    private var resetprop: String = "resetprop"

    fun detect() {
        resetprop = RootUtils.resetpropBinary()
    }

    fun get(name: String): String? {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val m = clazz.getMethod("get", String::class.java)
            val v = m.invoke(null, name) as? String
            if (!v.isNullOrEmpty()) return v
        } catch (_: Throwable) {
        }
        return ShellExecutor.run("$resetprop $name")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun set(name: String, value: String, persistent: Boolean = false) {
        val p = if (persistent) " -p" else ""
        ShellExecutor.run("$resetprop$p -n $name $value")
    }

    /** Set only when missing or different, mirroring service.sh check_reset_prop. */
    fun check(name: String, expected: String) {
        val cur = get(name)
        if (cur.isNullOrEmpty() || cur != expected) {
            set(name, expected)
            Logger.i("PropUtils", "set $name=$expected")
        }
    }

    /** Replace when current value contains the marker, mirroring contains_reset_prop. */
    fun containsReplace(name: String, contains: String, newValue: String) {
        val cur = get(name)
        if (cur != null && cur.contains(contains)) {
            set(name, newValue)
            Logger.i("PropUtils", "replace $name -> $newValue")
        }
    }

    fun delete(name: String, persistent: Boolean = false) {
        val p = if (persistent) " -p" else ""
        ShellExecutor.run("$resetprop$p -d $name")
    }

    /** Delete every property matching the given regex, mirroring the pihook sweep. */
    fun deleteMatching(regex: String) {
        val out = ShellExecutor.run("getprop") ?: return
        val names = out.lineSequence()
            .mapNotNull { line ->
                val m = Regex("^\\[([^]]+)\\]:").find(line)
                m?.groupValues?.get(1)
            }
            .filter { it.contains(Regex(regex)) }
            .toList()
        for (name in names) {
            delete(name, persistent = true)
            Logger.i("PropUtils", "deleted matching prop $name")
        }
    }
}

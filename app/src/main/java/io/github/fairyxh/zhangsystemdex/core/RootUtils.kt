package io.github.fairyxh.zhangsystemdex.core

import android.os.Process
import java.io.File

object RootUtils {
    fun isRoot(): Boolean = Process.myUid() == 0

    fun hasMagisk(): Boolean =
        File("/data/adb/magisk").exists() ||
            File("/data/adb/magisk.db").exists() ||
            File("/sbin/magisk").exists()

    fun hasKsu(): Boolean = File("/data/adb/ksu").exists()

    fun resetpropBinary(): String =
        when {
            File("/data/adb/ksu/bin/resetprop").exists() -> "/data/adb/ksu/bin/resetprop"
            File("/data/adb/magisk/resetprop").exists() -> "/data/adb/magisk/resetprop"
            File("/data/adb/modules/zygisk-magisk/resetprop").exists() ->
                "/data/adb/modules/zygisk-magisk/resetprop"
            else -> "resetprop"
        }
}

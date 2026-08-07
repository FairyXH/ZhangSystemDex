package io.github.fairyxh.zhangsystemdex.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Framework-first wrappers for operations that were originally shell commands.
 * Every method tries the Android API first and falls back to the equivalent
 * shell command on any failure, so behavior stays identical when the API path
 * is unavailable (permission, hidden-API filter, OEM restrictions).
 */
object FrameworkOps {

    private fun ctx(): Context? = SystemContext.get()

    /** Dedupe per-operation WARNs so hidden-API reflection failures do not spam the log. */
    private val warned = HashSet<String>()

    private fun warnOnce(key: String, msg: String) {
        synchronized(warned) {
            if (warned.add(key)) Logger.w("FrameworkOps", "$msg (logged once)")
        }
    }

    private fun apiFailed(key: String, action: String, t: Throwable) {
        warnOnce(key, "$action failed, fallback shell: ${t.message}")
    }

    // ---------- Package management ----------

    fun setApplicationEnabled(pkg: String, enabled: Boolean) {
        val pm = ctx()?.packageManager
        if (pm != null) {
            try {
                pm.setApplicationEnabledSetting(
                    pkg,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    0
                )
                return
            } catch (t: Throwable) {
                apiFailed("setApplicationEnabled_$pkg", "setApplicationEnabled($pkg)", t)
            }
        }
        ShellExecutor.run("pm ${if (enabled) "enable" else "disable"} $pkg")
    }

    fun setApplicationDisabledUser(pkg: String, user: Int = 0) {
        val pm = ctx()?.packageManager
        if (pm != null) {
            try {
                // setApplicationEnabledSetting has no user parameter; on a
                // multi-user device only the calling user (0) is covered here.
                pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
                return
            } catch (t: Throwable) {
                apiFailed("disable-user_$pkg", "disable-user($pkg)", t)
            }
        }
        ShellExecutor.run("pm disable-user --user $user $pkg")
    }

    fun setComponentEnabled(component: String, enabled: Boolean) {
        val cn = ComponentName.unflattenFromString(component)
        val pm = ctx()?.packageManager
        if (cn != null && pm != null) {
            try {
                pm.setComponentEnabledSetting(
                    cn,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    0
                )
                return
            } catch (t: Throwable) {
                apiFailed("setComponentEnabled_$component", "setComponentEnabled($component)", t)
            }
        }
        ShellExecutor.run("pm ${if (enabled) "enable" else "disable"} $component")
    }

    fun grantPermission(pkg: String, permission: String) {
        val pm = ctx()?.packageManager
        if (pm != null) {
            try {
                // grantRuntimePermission is @RequiresPermission(GrantRuntimePermissions);
                // reflect it so a permission-denied path falls back to shell.
                val m = PackageManager::class.java.getMethod(
                    "grantRuntimePermission",
                    String::class.java,
                    String::class.java,
                    android.os.UserHandle::class.java
                )
                m.invoke(pm, pkg, permission, android.os.Process.myUserHandle())
                return
            } catch (t: Throwable) {
                apiFailed("grant_$pkg", "grant($pkg $permission)", t)
            }
        }
        ShellExecutor.run("pm grant $pkg $permission")
    }

    /** AppOpsManager.setMode(MODE_ALLOWED) via reflection; op name -> code via strOpToOp. */
    fun appOpsSetAllow(pkg: String, op: String) {
        val c = ctx()
        if (c != null) {
            try {
                val aom = c.getSystemService(Context.APP_OPS_SERVICE)
                val uid = c.packageManager.getApplicationInfo(pkg, 0).uid
                val appOpsClass = Class.forName("android.app.AppOpsManager")
                val strToOp = appOpsClass.getMethod("strOpToOp", String::class.java)
                val code = strToOp.invoke(null, op) as? Int ?: -1
                if (code >= 0) {
                    val setMode = appOpsClass.getMethod(
                        "setMode",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    setMode.invoke(aom, code, uid, pkg, android.app.AppOpsManager.MODE_ALLOWED)
                    return
                }
            } catch (t: Throwable) {
                apiFailed("appops_$pkg", "appOps set $pkg $op", t)
            }
        }
        ShellExecutor.run("appops set $pkg $op allow")
    }

    fun forceStop(pkg: String) {
        val am = ctx()?.getSystemService(Context.ACTIVITY_SERVICE)
        if (am != null) {
            try {
                val m = Class.forName("android.app.ActivityManager")
                    .getMethod("forceStopPackage", String::class.java)
                m.invoke(am, pkg)
                return
            } catch (t: Throwable) {
                apiFailed("forceStop_$pkg", "forceStop($pkg)", t)
            }
        }
        ShellExecutor.run("am force-stop $pkg")
    }

    // ---------- Doze / device idle whitelist ----------

    fun addPowerSaveWhitelist(pkg: String) {
        val pm = ctx()?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            try {
                val m = PowerManager::class.java.getMethod("addPowerSaveWhitelistApp", String::class.java)
                m.invoke(pm, pkg)
                return
            } catch (t: Throwable) {
                apiFailed("addPowerSaveWhitelist", "addPowerSaveWhitelist($pkg)", t)
            }
        }
        ShellExecutor.run("dumpsys deviceidle whitelist +$pkg")
    }

    fun removePowerSaveWhitelist(pkg: String) {
        val pm = ctx()?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            try {
                val m = PowerManager::class.java.getMethod("removePowerSaveWhitelistApp", String::class.java)
                m.invoke(pm, pkg)
                return
            } catch (t: Throwable) {
                apiFailed("removePowerSaveWhitelist", "removePowerSaveWhitelist($pkg)", t)
            }
        }
        ShellExecutor.run("dumpsys deviceidle whitelist -$pkg")
    }

    // ---------- Radio / power hardware ----------

    fun wifiEnabled(enabled: Boolean) {
        val wm = ctx()?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm != null) {
            try {
                if (wm.setWifiEnabled(enabled)) return
            } catch (t: Throwable) {
                apiFailed("setWifiEnabled", "setWifiEnabled", t)
            }
        }
        ShellExecutor.run("svc wifi ${if (enabled) "enable" else "disable"}")
    }

    fun bluetoothEnabled(enabled: Boolean) {
        val adapter = (ctx()?.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter != null) {
            try {
                val ok = if (enabled) adapter.enable() else adapter.disable()
                if (ok) return
            } catch (t: Throwable) {
                apiFailed("bluetooth", "bluetooth ${if (enabled) "enable" else "disable"}", t)
            }
        }
        ShellExecutor.run("svc bluetooth ${if (enabled) "enable" else "disable"}")
    }

    /** PowerManager.wakeUp() replacement for `input keyevent 224`. */
    fun wakeUp() {
        val pm = ctx()?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            try {
                val m = PowerManager::class.java
                    .getMethod("wakeUp", Long::class.javaPrimitiveType, String::class.java)
                m.invoke(pm, SystemClock.uptimeMillis(), "zhangsystemdex")
                return
            } catch (t: Throwable) {
                apiFailed("wakeUp", "wakeUp", t)
            }
        }
        ShellExecutor.run("input keyevent 224")
    }

    /** AudioManager.dispatchMediaKeyEvent replacement for `input keyevent 126`. */
    fun mediaPlay() {
        val am = ctx()?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (am != null) {
            try {
                val m = android.media.AudioManager::class.java
                    .getMethod("dispatchMediaKeyEvent", KeyEvent::class.java)
                m.invoke(am, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                m.invoke(am, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                return
            } catch (t: Throwable) {
                apiFailed("mediaPlay", "mediaPlay", t)
            }
        }
        ShellExecutor.run("input keyevent 126")
    }

    // ---------- Intent / service launching ----------

    fun startService(component: String) {
        val c = ctx()
        val cn = ComponentName.unflattenFromString(component)
        if (c != null && cn != null) {
            try {
                c.startService(Intent().setComponent(cn))
                return
            } catch (t: Throwable) {
                apiFailed("startService_$component", "startService($component)", t)
            }
        }
        ShellExecutor.run("am startservice $component")
    }

    fun startActivity(component: String, extras: Map<String, Any> = emptyMap()) {
        val c = ctx()
        val cn = ComponentName.unflattenFromString(component)
        if (c != null && cn != null) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                for ((k, v) in extras) {
                    when (v) {
                        is Boolean -> intent.putExtra(k, v)
                        is Int -> intent.putExtra(k, v)
                        is String -> intent.putExtra(k, v)
                    }
                }
                c.startActivity(intent)
                return
            } catch (t: Throwable) {
                apiFailed("startActivity_$component", "startActivity($component)", t)
            }
        }
        val extraArgs = extras.map { (k, v) -> "--ez $k $v" }.joinToString(" ")
        ShellExecutor.run("am start -n $component $extraArgs".trim())
    }

    fun sendBootCompleted() {
        val c = ctx()
        if (c != null) {
            try {
                c.sendBroadcast(Intent("android.intent.action.BOOT_COMPLETED"))
                return
            } catch (t: Throwable) {
                apiFailed("sendBootCompleted", "sendBootCompleted", t)
            }
        }
        ShellExecutor.run("am broadcast -a android.intent.action.BOOT_COMPLETED")
    }

    fun homePackages(): List<String> {
        val pm = ctx()?.packageManager
        if (pm != null) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                return pm.queryIntentActivities(intent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .distinct()
            } catch (t: Throwable) {
                apiFailed("homePackages", "home resolution", t)
            }
        }
        return ShellExecutor.run(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep /"
        )?.lineSequence()?.map { it.trim().substringBefore('/') }?.filter { it.isNotEmpty() }?.toList() ?: emptyList()
    }

    // ---------- init services ----------

    /** Start/stop an init service via the ctl.* property (equivalent to `stop`/`start`). */
    fun ctlService(action: String, name: String) {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val m = clazz.getMethod("set", String::class.java, String::class.java)
            m.invoke(null, "ctl.$action", name)
            return
        } catch (t: Throwable) {
            apiFailed("ctl_${action}_$name", "ctl.$action($name)", t)
        }
        ShellExecutor.run("$action $name")
    }
}

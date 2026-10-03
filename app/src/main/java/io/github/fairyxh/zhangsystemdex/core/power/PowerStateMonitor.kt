package io.github.fairyxh.zhangsystemdex.core.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.SystemContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Event-driven power-state source. Registers framework BroadcastReceivers for
 * screen on/off and battery/charging changes so the subsystem reacts to real
 * events instead of a `while(true){sleep(1);dumpsys}` poll loop (a hard red
 * line of the long-task brief).
 *
 * Fail-open contract: if receivers cannot be registered (app_process SELinux
 * domain, no Context, OEM restrictions) the monitor degrades to the daemon's
 * own coarse tick — it never throws into the caller and never blocks startup.
 */
class PowerStateMonitor(
    private val stats: PowerStatistics,
    private val onScreenOff: () -> Unit,
    private val onScreenOn: () -> Unit,
    private val onChargingChanged: (charging: Boolean, level: Int) -> Unit,
) {
    private val registered = AtomicBoolean(false)

    @Volatile
    private var receiver: BroadcastReceiver? = null

    @Volatile
    var screenOn: Boolean = true
        private set

    @Volatile
    var charging: Boolean = false
        private set

    @Volatile
    var batteryLevel: Int = 100
        private set

    /** Last observed state change time, used to debounce flapping. */
    private var lastScreenChangeMs = 0L

    init {
        // Seed the initial state from whatever source is available now (one
        // shot at start, not a loop).
        try {
            seedInitialState()
        } catch (t: Throwable) {
            Logger.w("PowerStateMonitor", "初始状态读取失败（fail-open）: ${t.message}")
        }
    }

    private fun seedInitialState() {
        val c = SystemContext.get()
        val pm = c?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            screenOn = pm.isInteractive
        }
        // Charging state: prefer the sticky ACTION_BATTERY_CHANGED intent (this
        // is the only non-hidden, non-deprecated source). Fall back to the
        // BatteryManager capacity property for the level.
        var seededCharging = false
        try {
            val sticky = c?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                val status = sticky.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN
                )
                seededCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
            }
        } catch (_: Throwable) {
        }
        charging = seededCharging
        if (batteryLevel == 100) {
            val bm = c?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (lvl in 0..100) batteryLevel = lvl
            }
        }
    }

    /** Register the receivers. Returns true when the event path is active. */
    fun start(): Boolean {
        if (!registered.compareAndSet(false, true)) return true
        val c = SystemContext.get()
        if (c == null) {
            registered.set(false)
            Logger.w("PowerStateMonitor", "无系统 Context，事件监听不可用（fail-open，退化为周期采样）")
            return false
        }
        return try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        when (intent.action) {
                            Intent.ACTION_SCREEN_OFF -> handleScreen(false)
                            Intent.ACTION_SCREEN_ON -> handleScreen(true)
                            Intent.ACTION_BATTERY_CHANGED -> handleBattery(intent)
                            else -> {}
                        }
                    } catch (t: Throwable) {
                        stats.noteFailOpen("receiver ${intent.action}")
                        Logger.w("PowerStateMonitor", "处理广播失败: ${t.message}")
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            // Android 14+ requires the RECEIVER_EXPORTED flag for receivers that
            // receive system broadcasts; use the hidden-API-free 5-arg overload
            // when available, else the legacy one.
            registerCompat(c, r, filter)
            receiver = r
            Logger.i("PowerStateMonitor", "事件监听已注册（screen/battery）")
            true
        } catch (t: Throwable) {
            registered.set(false)
            Logger.w("PowerStateMonitor", "注册事件监听失败（fail-open）: ${t.message}")
            false
        }
    }

    private fun registerCompat(c: Context, r: BroadcastReceiver, filter: IntentFilter) {
        try {
            val m = Context::class.java.getMethod(
                "registerReceiver",
                BroadcastReceiver::class.java,
                IntentFilter::class.java,
                Int::class.javaPrimitiveType
            )
            m.invoke(c, r, filter, Context.RECEIVER_EXPORTED)
            return
        } catch (_: Throwable) {
            // Fall through to the legacy 2-arg form.
        }
        c.registerReceiver(r, filter)
    }

    fun stop() {
        if (!registered.compareAndSet(true, false)) return
        val r = receiver ?: return
        try {
            SystemContext.get()?.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
        receiver = null
        Logger.i("PowerStateMonitor", "事件监听已注销")
    }

    private fun handleScreen(on: Boolean) {
        val now = System.currentTimeMillis()
        if (screenOn == on) return
        screenOn = on
        if (now - lastScreenChangeMs < 800L) return
        lastScreenChangeMs = now
        if (on) {
            stats.noteScreenOn()
            onScreenOn()
        } else {
            stats.noteScreenOff()
            onScreenOff()
        }
    }

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else batteryLevel
        val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        batteryLevel = pct
        if (nowCharging != charging) {
            charging = nowCharging
        }
        onChargingChanged(nowCharging, pct)
    }

    /**
     * Coarse refresh used ONLY when the event path is unavailable. Reads the
     * PowerManager/battery state at the daemon cadence (never sub-second).
     */
    fun refreshCoarse() {
        if (registered.get()) return
        try {
            seedInitialState()
        } catch (_: Throwable) {
        }
    }

    fun isEventDriven(): Boolean = registered.get()
}

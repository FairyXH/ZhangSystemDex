package io.github.fairyxh.zhangsystemdex.modules

import android.provider.Settings
import android.os.Process
import io.github.fairyxh.zhangsystemdex.core.DaemonLoop
import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.ProcessUtils
import java.io.File
import java.util.concurrent.TimeUnit

/** Net Turbo's useful kernel settings without its fork-heavy shell watcher. */
class NetTurboModule(ctx: DexContext) : DaemonLoop(ctx, 30_000L, pauseAware = false) {
    override val name = "NetTurbo"
    private val originalFiles = LinkedHashMap<String, String>()
    private val ownedFiles = HashMap<String, String>()
    private val originalSettings = LinkedHashMap<String, String?>()
    private val ownedSettings = HashMap<String, String>()
    private val originalProps = LinkedHashMap<String, String>()
    private val ownedProps = HashMap<String, String>()
    private val originalPriorities = HashMap<Int, Pair<String, Int>>()
    private var previousInterfaces = ""
    private var lastRefresh = 0L
    private var hostapdConfigured = false
    private val qdiscOriginal = HashMap<String, String>()

    override fun onStart() {
        applyProperties()
        applySettings()
        refreshCore()
        lastRefresh = System.currentTimeMillis()
        Logger.i(name, "Dex 网络调优已启动；30s 检查接口，180s 校验核心参数")
    }

    override fun tick() {
        val interfaces = networkInterfaces()
        val signature = interfaces.joinToString("|") { "${it.name}:${read(File(it, "operstate"))}:${read(File(it, "carrier"))}" }
        if (signature != previousInterfaces) {
            previousInterfaces = signature
            val cpus = networkCpus()
            tuneInterfaces(interfaces, cpus)
            tuneIrqs(cpus)
            hostapdConfigured = false
            Logger.i(name, "网络接口变化，已重新应用队列和中断配置: $signature")
        }
        if (!hostapdConfigured && interfaces.any { isHotspot(it) }) {
            hostapdConfigured = tuneHostapd()
        }
        val now = System.currentTimeMillis()
        if (now - lastRefresh >= 180_000L) {
            refreshCore()
            tunePriorities()
            lastRefresh = now
        }
    }

    override fun onStop() {
        for ((path, original) in originalFiles) {
            val file = File(path)
            if (read(file) == ownedFiles[path]) writeRaw(file, original)
        }
        val resolver = ctx.androidContext?.contentResolver
        if (resolver != null) for ((key, original) in originalSettings) {
            if (Settings.Global.getString(resolver, key) == ownedSettings[key]) {
                Settings.Global.putString(resolver, key, original)
            }
        }
        for ((key, original) in originalProps) {
            if (getProp(key) == ownedProps[key]) setProp(key, original)
        }
        for ((pid, state) in originalPriorities) {
            if (ProcessUtils.procName(pid) == state.first) runCatching {
                if (Process.getThreadPriority(pid) == -12 || Process.getThreadPriority(pid) == -10) {
                    Process.setThreadPriority(pid, state.second)
                }
            }
        }
        for ((iface, kind) in qdiscOriginal) {
            if (kind in setOf("pfifo_fast", "fq", "fq_codel") && File("/sys/class/net/$iface").exists()) {
                directCommand("/system/bin/tc", "qdisc", "replace", "dev", iface, "root", kind)
            }
        }
        Logger.i(name, "网络调优已停止，已尽力恢复本次改动")
    }

    private fun refreshCore() {
        val desired = linkedMapOf(
            "/proc/sys/net/core/somaxconn" to "4096",
            "/proc/sys/net/core/netdev_max_backlog" to "30000",
            "/proc/sys/net/core/netdev_budget" to "1200",
            "/proc/sys/net/core/netdev_budget_usecs" to "8000",
            "/proc/sys/net/core/dev_weight" to "64",
            "/proc/sys/net/core/busy_poll" to "50",
            "/proc/sys/net/core/busy_read" to "50",
            "/proc/sys/net/core/rmem_max" to "33554432",
            "/proc/sys/net/core/wmem_max" to "33554432",
            "/proc/sys/net/ipv4/tcp_rmem" to "8192 2097152 33554432",
            "/proc/sys/net/ipv4/tcp_wmem" to "8192 2097152 33554432",
            "/proc/sys/net/ipv4/tcp_notsent_lowat" to "524288",
            "/proc/sys/net/ipv4/tcp_slow_start_after_idle" to "0",
            "/proc/sys/net/ipv4/tcp_mtu_probing" to "1",
            "/proc/sys/net/ipv4/tcp_fastopen" to "3",
            "/proc/sys/net/ipv4/tcp_ecn" to "1",
            "/proc/sys/net/ipv4/tcp_syncookies" to "1",
            "/proc/sys/net/ipv4/tcp_tw_reuse" to "1",
            "/proc/sys/net/ipv4/tcp_max_syn_backlog" to "8192",
            "/proc/sys/net/ipv4/udp_rmem_min" to "262144",
            "/proc/sys/net/ipv4/udp_wmem_min" to "262144",
            "/proc/sys/net/core/rps_sock_flow_entries" to "32768",
            "/proc/sys/net/core/default_qdisc" to "fq",
            "/proc/sys/net/ipv4/ip_forward" to "1",
            "/proc/sys/net/ipv6/conf/all/forwarding" to "1"
        )
        val available = read(File("/proc/sys/net/ipv4/tcp_available_congestion_control"))
        when {
            available?.split(' ')?.contains("bbr") == true -> desired["/proc/sys/net/ipv4/tcp_congestion_control"] = "bbr"
            available?.split(' ')?.contains("cubic") == true -> desired["/proc/sys/net/ipv4/tcp_congestion_control"] = "cubic"
        }
        for ((path, value) in desired) writeIfChanged(File(path), value)
        val policy0 = File("/sys/devices/system/cpu/cpufreq/policy0")
        val freqs = read(File(policy0, "scaling_available_frequencies"))
            ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.sorted().orEmpty()
        if (freqs.isNotEmpty()) writeIfChanged(File(policy0, "scaling_min_freq"), freqs[freqs.size / 2].toString())
    }

    private fun networkInterfaces(): List<File> = File("/sys/class/net").listFiles()
        ?.filter { it.name.matches(Regex("^(rmnet|ccmni|pdp_ip|wwan|vsnet|seth|wlan|ap|p2p|softap|eth|en|usb|rndis|ncm|ecm).*")) }
        ?.sortedBy { it.name }.orEmpty()

    private fun networkCpus(): List<Int> {
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.name.startsWith("policy") }
            ?.mapNotNull { pol ->
                val max = read(File(pol, "cpuinfo_max_freq"))?.toLongOrNull() ?: return@mapNotNull null
                val cpus = read(File(pol, "related_cpus"))?.split(Regex("\\s+"))
                    ?.mapNotNull { it.toIntOrNull() }.orEmpty()
                max to cpus
            }?.sortedByDescending { it.first }.orEmpty()
        return (if (policies.size > 1) policies.dropLast(1) else policies)
            .flatMap { it.second }.distinct().sorted().ifEmpty { listOf(0) }
    }

    private fun tuneInterfaces(interfaces: List<File>, cpus: List<Int>) {
        val mask = cpuMask(cpus)
        for (iface in interfaces) {
            val queues = File(iface, "queues").listFiles().orEmpty()
            for (queue in queues) {
                writeIfChanged(File(queue, "rps_cpus"), mask)
                if (!iface.name.contains("ipa")) writeIfChanged(File(queue, "xps_cpus"), mask)
                writeIfChanged(File(queue, "rps_flow_cnt"), "4096")
            }
            if (iface.name.startsWith("wlan") || iface.name.startsWith("ap")) {
                writeIfChanged(File(iface, "power/control"), "on")
                writeIfChanged(File(iface, "device/power/control"), "on")
            }
            if (read(File(iface, "operstate")) == "up" || read(File(iface, "carrier")) == "1") {
                tuneQdisc(iface.name)
            }
        }
    }

    private fun tuneIrqs(cpus: List<Int>) {
        var index = 0
        val lines = read(File("/proc/interrupts"))?.lineSequence() ?: return
        for (line in lines) {
            val irq = line.trimStart().substringBefore(':').toIntOrNull() ?: continue
            val label = line.trim().substringAfterLast(' ')
            if (!Regex("mhi|ipa|rmnet|modem|glink|wlan|wifi|pci0_wlan", RegexOption.IGNORE_CASE).containsMatchIn(label)) continue
            writeIfChanged(File("/proc/irq/$irq/smp_affinity_list"), cpus[index++ % cpus.size].toString())
        }
    }

    private fun tuneQdisc(iface: String) {
        if (iface in qdiscOriginal) return
        val root = directCommand("/system/bin/tc", "qdisc", "show", "dev", iface).second
            .lineSequence().firstOrNull().orEmpty()
        val kind = root.split(Regex("\\s+")).getOrNull(1) ?: return
        if (kind !in setOf("pfifo_fast", "fq", "fq_codel")) return
        val target = if (iface.startsWith("wlan") || iface.startsWith("ap")) "fq" else "fq_codel"
        if (kind == target || directCommand("/system/bin/tc", "qdisc", "replace", "dev", iface, "root", target).first) {
            qdiscOriginal[iface] = kind
        }
    }

    private fun isHotspot(iface: File): Boolean =
        (iface.name.startsWith("ap") || iface.name == "wlan2") &&
            (read(File(iface, "operstate")) == "up" || read(File(iface, "carrier")) == "1")

    private fun tuneHostapd(): Boolean {
        val file = File("/data/vendor/wifi/hostapd/hostapd_wlan2.conf")
        val before = runCatching { file.readText() }.getOrNull() ?: return false
        var after = before
        for ((key, value) in listOf("vht_oper_chwidth" to "1", "he_oper_chwidth" to "1", "freqlist" to "5735-5815")) {
            val pattern = Regex("(?m)^${Regex.escape(key)}=.*$")
            after = if (pattern.containsMatchIn(after)) after.replace(pattern, "$key=$value") else after.trimEnd() + "\n$key=$value\n"
        }
        if (after == before) return true
        originalFiles.putIfAbsent(file.path, before)
        if (writeRaw(file, after)) {
            ownedFiles[file.path] = after.trim()
            return true
        }
        return false
    }

    private fun tunePriorities() {
        val candidates = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }.orEmpty()
        for (dir in candidates) {
            val pid = dir.name.toIntOrNull() ?: continue
            val process = ProcessUtils.procName(pid)
            val target = when {
                process.endsWith("/hostapd") || process == "hostapd" -> -12
                process.endsWith("/wpa_supplicant") || process.endsWith("/wificond") ||
                    process.endsWith("/netd") || process == "wpa_supplicant" ||
                    process == "wificond" || process == "netd" -> -10
                else -> continue
            }
            runCatching {
                val current = Process.getThreadPriority(pid)
                if (current > target) {
                    originalPriorities.putIfAbsent(pid, process to current)
                    Process.setThreadPriority(pid, target)
                }
            }
        }
    }

    private fun applySettings() {
        val resolver = ctx.androidContext?.contentResolver ?: return
        for ((key, value) in mapOf(
            "data_saver_mode" to "0", "mobile_data_always_on" to "1",
            "wifi_scan_throttle_enabled" to "0", "soft_ap_timeout_enabled" to "0",
            "tether_offload_disabled" to "0", "soft_ap_wifi6_state" to "1"
        )) runCatching {
            val old = Settings.Global.getString(resolver, key)
            if (old != value && Settings.Global.putString(resolver, key, value)) {
                originalSettings.putIfAbsent(key, old)
                ownedSettings[key] = value
            }
        }
    }

    private fun applyProperties() {
        val values = mapOf(
            "net.tcp.buffersize.default" to "4096,87380,1048576,4096,16384,1048576",
            "net.tcp.buffersize.wifi" to "524288,1048576,2097152,262144,524288,1048576",
            "net.tcp.buffersize.umts" to "4094,87380,1048576,4096,16384,1048576",
            "net.tcp.buffersize.lte" to "524288,1048576,2097152,262144,524288,1048576",
            "net.tcp.buffersize.hspa" to "4094,87380,1048576,4096,16384,1048576",
            "net.tcp.buffersize.hsupa" to "4094,87380,1048576,4096,16384,1048576",
            "net.tcp.buffersize.cdma" to "4094,87380,1048576,4096,16384,1048576",
            "net.tcp.buffersize.evdo" to "4094,87380,1048576,4096,16384,1048576",
            "persist.vendor.wifi.power_save" to "0",
            "persist.vendor.qcacld.powersave" to "0",
            "wifi.supplicant_scan_interval" to "120"
        )
        for ((key, value) in values) {
            val old = getProp(key) ?: continue
            if (old != value && setProp(key, value)) {
                originalProps[key] = old
                ownedProps[key] = value
            }
        }
    }

    private fun getProp(key: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    private fun setProp(key: String, value: String): Boolean = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
        getProp(key) == value
    }.getOrDefault(false)

    private fun cpuMask(cpus: List<Int>): String {
        val words = IntArray((cpus.maxOrNull() ?: 0) / 32 + 1)
        for (cpu in cpus) if (cpu >= 0) words[cpu / 32] = words[cpu / 32] or (1 shl (cpu % 32))
        return words.reversed().joinToString(",") { Integer.toUnsignedString(it, 16) }
    }

    private fun read(file: File): String? = runCatching { if (file.isFile) file.readText().trim() else null }.getOrNull()

    private fun writeIfChanged(file: File, value: String): Boolean {
        val before = read(file) ?: return false
        if (before == value) return true
        if (!writeRaw(file, value)) return false
        originalFiles.putIfAbsent(file.path, before)
        ownedFiles[file.path] = value
        return true
    }

    private fun writeRaw(file: File, value: String): Boolean = runCatching {
        file.outputStream().use { it.write(value.toByteArray(Charsets.UTF_8)) }
        true
    }.getOrDefault(false)

    private fun directCommand(vararg args: String): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false to ""
        } else {
            val result = process.inputStream.bufferedReader().use { it.readText().take(4096) }
            (process.exitValue() == 0) to result
        }
    }.getOrDefault(false to "")
}

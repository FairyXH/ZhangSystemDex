package io.github.fairyxh.zhangsystemdex.modules

import io.github.fairyxh.zhangsystemdex.core.DexContext
import io.github.fairyxh.zhangsystemdex.core.FileUtils
import io.github.fairyxh.zhangsystemdex.core.FrameworkOps
import io.github.fairyxh.zhangsystemdex.core.Logger
import io.github.fairyxh.zhangsystemdex.core.PropUtils
import io.github.fairyxh.zhangsystemdex.core.ShellExecutor
import io.github.fairyxh.zhangsystemdex.core.SqliteUtils
import java.io.File

/**
 * MIUI-specific tuning: joyose/powerkeeper component disabling, database
 * quarantine and cloud-control database rewrites, plus persistent property
 * tuning. Replaces joyose_change.sh. Controlled by config.json `change_joyose`.
 */
class MiuiTuningModule(private val ctx: DexContext) {

    fun applyAll() {
        if (!ctx.config.switch("miui_tuning_enable")) {
            Logger.i("MiuiTuning", "disabled by switch, skip")
            return
        }
        try {
            disableJoyose()
            disablePowerKeeper()
            rewriteDatabases()
            applyPersistentProps()
            ShellExecutor.run("fstrim /data")
            ShellExecutor.run("dumpsys battery reset")
            try {
                File(ctx.config.logDir, "miui_tuning_last.txt")
                    .writeText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            } catch (_: Throwable) {
            }
            Logger.i("MiuiTuning", "applyAll finished")
        } catch (t: Throwable) {
            Logger.e("MiuiTuning", "applyAll failed", t)
        }
    }

    private fun disableJoyose() {
        val dbPath = "/data/data/com.xiaomi.joyose/databases/"
        FrameworkOps.setComponentEnabled("com.xiaomi.joyose/.smartop.SmartOpService", false)
        FrameworkOps.setComponentEnabled("com.xiaomi.joyose/.cloud.CloudServerReceiver", false)
        FileUtils.chattr(dbPath, "-R -i")
        FileUtils.deleteRecursive(File(dbPath))
        ShellExecutor.run("pm clear com.xiaomi.joyose")
        FileUtils.mkdirs(dbPath)
        FileUtils.chmod(dbPath, "000")
        FileUtils.chattr(dbPath, "-R +i")
    }

    private fun disablePowerKeeper() {
        FileUtils.chattr("/data/data/com.miui.powerkeeper/databases/", "-R -i")
        FrameworkOps.setComponentEnabled("com.miui.powerkeeper/com.miui.powerkeeper.cloudcontrol.CloudUpdateReceiver", false)
        FrameworkOps.setComponentEnabled("com.miui.powerkeeper/com.miui.powerkeeper.cloudcontrol.CloudUpdateJobService", false)
        FrameworkOps.setComponentEnabled("com.miui.powerkeeper/com.miui.powerkeeper.ui.CloudInfoActivity", false)
        FrameworkOps.setComponentEnabled("com.miui.powerkeeper/com.miui.powerkeeper.statemachine.PowerStateMachineService", false)
        ShellExecutor.run("pm clear com.miui.powerkeeper")
    }

    private fun rewriteDatabases() {
        val userDb = "/data/data/com.miui.powerkeeper/databases/user_configure.db"
        val cloudDb = "/data/data/com.miui.powerkeeper/databases/cloud_configure.db"
        val thermalDb = "/data/data/com.miui.powerkeeper/databases/thermal.db"
        val homoPairs = listOf(
            "key_top_names" to "com.xiaomi.homo",
            "key_priv_names" to "com.xiaomi.homo",
            "map_fps_group" to "com.xiaomi.homo",
            "full_screen_fps_group" to "com.xiaomi.homo",
            "display_fps_group" to "com.xiaomi.homo",
            "fps_top_short_video_pkg" to "com.xiaomi.homo",
            "low_fps_group" to "com.xiaomi.homo",
            "fps_exclude_pkg" to "com.xiaomi.homo",
            "fps_top_video_idle_pkg" to "com.xiaomi.homo",
            "fps_top_video_pkg" to "com.xiaomi.homo",
            "fps_smart_group" to "com.xiaomi.homo",
            "fps_group" to "com.xiaomi.homo",
            "ebook_idle_pkg" to "com.miui.homo",
            "tp_idle_pkg" to "com.miui.homo",
            "input_audio_group" to "com.miui.homo",
            "standard_video_group" to "com.miui.homo",
            "proc_cpu_time_in_state" to "{\"pkgList\":\"com.miui.home\"}",
            "clear_unactive_apps" to "{\"fucSwitch\":\"true\"}",
        )
        for ((name, value) in homoPairs) {
            SqliteUtils.exec(userDb, "INSERT OR REPLACE INTO misc (name, value) VALUES ('$name', '$value');")
        }
        SqliteUtils.exec(userDb, "INSERT OR REPLACE INTO misc (name, value) VALUES ('cloud_current_enviroment', 'http://127.0.0.1');")
        SqliteUtils.exec(userDb, "INSERT OR REPLACE INTO misc (name, value) VALUES ('is_allow_auto_cloud_sync', 'false');")
        SqliteUtils.exec(userDb, "INSERT OR REPLACE INTO misc (name, value) VALUES ('thermal_group', '{}');")
        SqliteUtils.exec(userDb, "INSERT OR REPLACE INTO misc (name, value) VALUES ('resolution_policy', 'com.miui.homo');")

        SqliteUtils.exec(cloudDb, "DELETE FROM GlobalFeatureTable WHERE configureName='dozeWhiteListApps';")
        SqliteUtils.exec(cloudDb, "INSERT OR REPLACE INTO GlobalFeatureTable (configureName, configureParam) VALUES ('levelUtimateSpecialApps', 'com.miui.homo');")
        SqliteUtils.exec(cloudDb, "INSERT OR REPLACE INTO GlobalFeatureTable (configureName, configureParam) VALUES ('FrozenNewWhiteList', 'com.miui.homo');")
        SqliteUtils.exec(cloudDb, "DELETE FROM cloudAppTable;")
        SqliteUtils.exec(cloudDb, "DELETE FROM sqlite_sequence WHERE name='cloudAppTable';")

        SqliteUtils.exec(thermalDb, "DELETE FROM thermal_duration;")
        SqliteUtils.exec(thermalDb, "DELETE FROM ThermalInfo;")
        Logger.i("MiuiTuning", "databases rewritten")
    }

    private fun applyPersistentProps() {
        val props = listOf(
            "ro.com.google.ime.theme_id" to "5",
            "ro.com.google.ime.kb_pad_port_b" to "10",
            "ro.com.google.ime.kb_pad_port_l" to "11",
            "ro.com.google.ime.kb_pad_port_r" to "11",
            "ro.com.google.ime.height_ratio" to "1.025",
            "ro.vendor.audio.game.mode" to "",
            "ro.vendor.audio.game.effect" to "",
            "ro.audio.spatializer_enabled" to "true",
            "ro.vendor.audio.spatializer_enabled" to "true",
            "ro.audio.stereo_spatialization_enabled" to "true",
            "ro.vendor.audio.stereo_spatialization_enabled" to "true",
            "persist.audio.spatializer.enable" to "true",
            "persist.vendor.audio.spatializer.enable" to "true",
            "persist.audio.spatializer.speaker_enabled" to "true",
            "persist.vendor.audio.spatializer.speaker_enabled" to "true",
            "ro.vendor.audio.spatializer.support.speaker" to "",
            "ro.vendor.audio.volume_super_index_add" to "25",
            "ro.vendor.audio.feature.spatial" to "",
            "persist.sys.precache.number" to "3",
            "persist.sys.precache2.number" to "3",
            "persist.sys.precache.appstrs1" to "com.miui.home,com.android.systemui,com.google.android.gms",
            "persist.sys.precache.appstrs2" to "com.google.android.googlequicksearchbox,com.android.vending,com.android.angle",
            "persist.sys.precache.appstrs3" to "",
            "persist.sys.precache2.appstrs1" to "",
            "persist.sys.precache2.appstrs2" to "",
            "persist.sys.precache2.appstrs3" to "",
            "persist.sys.prestart.proc" to "",
            "persist.sys.prestart.feedback.enable" to "",
            "persist.sys.launch_response_optimization.enable" to "",
            "persist.sys.stability.gcImproveEnable.808" to "",
            "persist.sys.smart_gc.enable" to "true",
            "persist.sys.smart_gc.packages" to "",
            "ro.surface_flinger.supports_background_blur" to "1",
            "ro.config.low_ram.support_miuilite_plus" to "true",
            "persist.sys.background_blur_supported" to "true",
            "persist.sys.background_blur_status_default" to "true",
            "persist.sys.mi_shadow_supported" to "true",
            "persist.sys.support_window_smoothcorner" to "true",
            "persist.sys.support_view_smoothcorner" to "true",
            "persist.sys.add_blurnoise_supported" to "true",
            "persist.sys.background_blur_mode" to "",
            "ro.miui.backdrop_sampling_enabled" to "",
            "ro.miui.support_miui_ime_bottom" to "1",
            "persist.vendor.doublentc" to "1",
            "persist.sys.stability.swapEnable" to "true",
            "persist.sys.stability.iorapEnable" to "true",
            "persist.sys.screen_anti_burn_enabled" to "true",
            "persist.berserk.mode.support" to "true",
            "ro.vendor.display.hyperos.miDualDPU_gamebox_version" to "2",
            "persist.sys.deep_sleep.enable" to "true",
            "persist.sys.render_turbo" to "true",
            "ro.vendor.mi_sf.skip_promtionfps_for_90hz" to "",
            "ro.vendor.display.skip_idle_for_90hz" to "",
            "ro.vendor.mi_sf.skip_90hz_forVrrVote" to "",
            "ro.vendor.fps.switch.thermal" to "",
            "debug.sf.hw" to "1",
            "debug.egl.hw" to "1",
            "persist.sys.force_sw_gles" to "0",
            "persist.sys.miuibooster.thermalbreaklimit" to "100000",
            "debug.angle.feature_overrides_enabled" to "preferLinearFilterForYUV:mapUnspecifiedColorSpaceToPassThrough",
            "persist.sys.ultra_hdr.support" to "true",
            "persist.sys.support_ultra_hdr" to "true",
            "ro.vendor.mi_sf.skip_promtionfps_for_60hz" to "",
            "persist.sys.perf_profile" to "4",
        )
        for ((name, value) in props) {
            PropUtils.set(name, value, persistent = true)
        }
        Logger.i("MiuiTuning", "persistent props applied (${props.size})")
    }
}

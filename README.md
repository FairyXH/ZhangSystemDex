# ZhangProtect / ZhangSystemDex

基于 Magisk 的 Root 系统管理模块。核心长期运行逻辑已使用AI重构由 Shell 迁移为 **Kotlin Dex Daemon**，
通过 `app_process` 以 root 身份运行，无需 Activity / Application 生命周期，单一 `Main.dex`。

- 入口类：`io.github.fairyxh.zhangsystemdex.Main`
- 启动方式：`/system/bin/app_process -Djava.class.path=<Main.dex> /system/bin --nice-name=zhangsystemdex io.github.fairyxh.zhangsystemdex.Main <模块目录>`
- 运行用户：UID 0（Root）

---

## 目录结构

```
ZhangProtect-Android/
├── Main.dex                  # 核心 Dex（单一 classes.dex，含 Kotlin 标准库）
├── config.conf               # 模块唯一配置：配置根目录 + 日志总开关
├── service.sh                # Magisk late_start 入口，后台启动 Dex daemon
├── 启动Dex.sh / 停止Dex.sh / 重启Dex.sh  # daemon 启停/重启快捷脚本
├── 调试运行Dex.sh            # 手动前台运行 Dex（与 service.sh 等效），用于调试
├── install.sh                # Magisk 安装脚本（APK 安装等安装期逻辑）
├── post-fs-data.sh           # 开机早期缓存清理（Magisk 生命周期）
├── uninstall.sh              # 卸载清理（含停止 Dex daemon）
├── META-INF/                 # Magisk 安装器
├── system/                   # 遮蔽挂载层（运行时由 Dex 生成/更新）
├── ZhangSetting/             # 生成配置与资源（HMA JSON、规则 XML 等）
└── *.sh                      # 保留的独立工具脚本（见"保留脚本"）
```

### 运行时数据目录 `/data/adb/Zhang/`

| 路径 | 说明 |
|---|---|
| `switches.conf` | 全部功能开关（首次运行自动生成，属性格式 + 中文注释） |
| `doze.conf` | Doze 白名单（默认内嵌原模块配置） |
| `game_pause.conf` | 游戏暂停包名列表 |
| `asguard.conf` / `asguard.paths` | 无障碍守护包名 / 已学习服务路径 |
| `notification.conf` | 通知监听服务列表 |
| `autorun.conf` | 开机自启动服务列表 |
| `app_manager/` | 停用应用列表（disable_app_list*.conf） |
| `HideMyAppList_MoreBlack.txt` | HMA 用户额外黑名单 |
| `cache/xposed_modules.json` | Xposed 模块扫描缓存 |
| `log/zhang.log` | 统一日志（1MB 滚动） |
| `CleanedRubbish/` | 垃圾文件隔离区 |
| `daemon.pid` / `Main.dex` / `daemon.log` | daemon 运行态与启动输出 |

## 配置文件

### config.conf（模块目录）

```ini
# ZhangSystemDex config
root_dir=/data/adb/Zhang   # 所有功能配置根目录
log_enabled=true           # 日志总开关：false 时完全静默（终端+文件）
```

- `root_dir` 不存在时自动创建；格式错误时记录日志并使用默认路径 `/data/adb/Zhang`。
- 首次运行自动生成 `/data/adb/Zhang/` 下全部默认配置，已存在的配置不会被覆盖。

### switches.conf（配置根目录，功能开关）

- 每个独立功能一个开关，属性格式 + 中文注释；**缺省一律默认 `false`**（12 个特殊项默认开启）。
- 关闭的功能不创建线程、不执行任何逻辑；运行中每 60s 监听变化并热启停对应线程。
- 主调优循环参数：`tuning_interval_seconds`（周期秒数，默认 600，服务器模式 300）、
  `heavy_interval_cycles`（高占用任务间隔周期数，默认 6，服务器模式 24）；留空用默认值，
  修改后需重启 daemon。旧版 switches.conf 缺少这两行时 Dex 会自动追加（不覆盖已有值）。
- 完整开关表与默认值见模块目录 `README.md` §3/§4。

## 功能模块详解

### core（基础设施）

| 类 | 职责 |
|---|---|
| `Logger` | 统一日志：时间/等级/模块/堆栈，写终端 + `log/zhang.log`（1MB 滚动），受 `log_enabled` 控制 |
| `ConfigManager` | 解析 config.conf、初始化配置根与全部默认配置、读写 config.json 开关 |
| `DexContext` | daemon 运行上下文：模块目录、配置、全局暂停协调器 |
| `RootUtils` | root/Magisk/KSU 检测、resetprop 路径选择 |
| `SystemContext` | 引导系统 Context：优先 currentActivityThread/createSystemContext，Android 15 hidden-API 过滤时走 `Looper.prepareMainLooper() + ActivityThread.systemMain()`；失败缓存 10 分钟不刷屏，自动降级 shell |
| `PropUtils` | 属性读写：resetprop（KSU/Magisk）、SystemProperties 反射、批量 check/contains/delete |
| `SettingsUtils` | Settings 三域（global/system/secure）读写：ContentResolver 优先，shell 降级 |
| `ProcessUtils` | /proc 进程枚举、pgrep、renice/chrt、cpuset/stune、前台应用与亮屏检测、内存统计 |
| `FileUtils` | 递归删除/复制/chmod/chattr 等文件操作（chattr 无 Java 等价，shell 兜底） |
| `SqliteUtils` | SQLite 访问：framework `SQLiteDatabase` 优先，Android 15 app_process 不可用时自动走内置 `sqlite_lib/` 的 sqlite3 CLI；失败/缺失只记录一次 |
| `AppListProvider` | 已安装包枚举：PackageManager 优先，`pm list packages` 降级 |
| `ServiceManagerUtils` | ServiceManager 反射 + Parcel 事务（SurfaceFlinger 私有接口） |
| `FrameworkOps` | **Framework-first 操作封装**：包管理（enable/disable/disable-user/组件/权限授予）、AppOps 反射、force-stop、Doze 白名单、WiFi/蓝牙、wakeUp/媒体按键、Intent 启动、ctl 服务控制、HOME 解析。API 优先、失败自动降级等效 shell、警告按操作去重 |
| `BinderUtils` | ServiceManager 反射辅助（asInterface 等） |
| `ShellExecutor` | 兜底 shell 执行（仅无等价实现时使用） |
| `DaemonLoop` | 守护循环基类：独立线程、周期、异常隔离、游戏暂停感知 |
| `GamePauseCoordinator` | 全局游戏暂停状态（取代各 sh 重复的 pause_on_game 函数） |
| `GameListProvider` | 游戏列表聚合：game_pause.conf + MIUI gblist + 欧加 sqlite |

### modules（功能域）

| 模块 | 来源 sh | 职责 |
|---|---|---|
| `AntiDetectionModule` | service.sh 属性段 + systemchange.sh 周期清理 | 防 Root 检测属性（boot 状态/warranty/debuggable 等约 20 条，300s 复查）；删除 pihook/pixelprops 属性、HMA 残留目录、vold 数据隔离属性 |
| `SystemTuningModule` | systemchange.sh | 主调优循环（默认 600s，服务器模式 300s，可用 `tuning_interval_seconds` 调整）：热控/性能 settings、schedtune、进程提升；每 N 周期（`heavy_interval_cycles`，默认 6/24，**仅熄屏执行**，亮屏到期下一周期立即补执行）执行高占用任务：防错误弹窗规则、电池伪装、Doze 白名单、Soter 修复、温控遮蔽、HMA 全量生成、Xposed 列表、target 列表、应用停用、Shizuku/Brevent 重启、ZhangSetting 同步 |
| `PerformanceModule` | maxcpu.sh | CPU 全核满频 + cpuset 大核/小核划分 + 高通 GPU 满频 |
| `GamePauseModule` | pause_on_game_run_monitor.sh | 前台游戏检测：游戏在前台且亮屏时全局暂停其他模块，并清理 Shizuku 残留；前台离开超时自动恢复 |
| `AccessibilityGuardModule` | AsGuard.sh | 无障碍服务守护：学习 `enabled_accessibility_services` 路径映射，被关闭时写回（10s 周期） |
| `ServiceGuardModule` | Shizukustart/brevent/KeepBluetooth/nfcfix/notification/autostart/service.d | 服务守护：Shizuku/Brevent 拉起、蓝牙守护、NFC 守护、通知监听守护、开机自启动、健康循环（pm enable 健康应用、清理 disable/Shizuku 残留、写 ZhangServiceRecent） |
| `ServerModeModule` | IsServe.sh | 服务器模式：保持 WiFi/蓝牙/常亮、performance governor、可选 frpc/音乐命令、屏幕唤醒 |
| `AppManagerModule` | disable_apps/AppOpsChange/copy_mount | 反诈快应用停用+卸载+模块层 APK 遮蔽、AppOps 白名单全允许+权限组 grant、模块 overlay 重建 |
| `PowerManagerModule` | DozeListChange/LockedAppsAdd/nightsleep | Doze 白名单维护（内置规则=原 doze.conf）、MIUI locked_apps / ColorOS 锁应用 JSON、夜间（23:00-08:00）熄屏强制 Doze |
| `MemoryModule` | MemoryClean/CleanMemory | 低内存（<5%）后台应用 force-stop（跳过前台与白名单）、300s 页面缓存清理 |
| `StorageIsolationModule` | RedirectstorageDataRM/CleanRubbishFile/storage-isolation_config | 存储隔离痕迹清理、垃圾文件隔离到 CleanedRubbish、configuration.json 生成（org.json，600s） |
| `LSPosedScannerModule` | get_xposedmodules.sh | Xposed 模块扫描：优先 LSPosed 配置 → PackageManager metaData 标记；缓存到 `cache/xposed_modules.json`，包列表签名不变直接命中缓存；**不再调用 aapt** |
| `ConfigGenModule` | HideMyApplistJsonUpdate/DoNotTryAccessibility/tricky_store/hmspush | 生成 HMA config.json（org.json 保真：configVersion 90、白/黑名单模板）、DoNotTryAccessibility 规则 XML、tricky_store / hmspush 增量目标列表 |
| `MiuiTuningModule` | joyose_change.sh | MIUI 调优：joyose/powerkeeper 组件禁用、数据库清除与 chattr 隔离、cloud 配置重写（SQLite）、约 60 条持久属性 |
| `ThermalModule` | thermaldel.sh（运行时生成） | 温控遮蔽层：遍历 /system、/vendor 的 thermal 文件，在模块 system/ 下生成 0 字节覆盖（stdltm=true） |
| `NetworkModule` | DisabledIpv6.sh | 可选 IPv6 禁用（`network_ipv6_disable=true`） |

### 线程模型

- 每个模块独立线程，独立异常捕获；**单个模块异常不会导致 daemon 退出**。
- 游戏在前台时，`GamePauseCoordinator` 暂停全部 pause-aware 模块（10s 粒度）。
- 模块间协作（如 SystemTuningModule 的 heavy 任务调用 AppManager/PowerManager/ConfigGen 等）通过对象注入完成，无共享可变全局状态。

## 日志系统

- 位置：`/data/adb/Zhang/log/zhang.log`（按 1MB 滚动为 zhang.log.1）
- 格式：`yyyy-MM-dd HH:mm:ss.SSS [INFO|WARN|ERROR] [模块名] 消息` + 异常堆栈
- 终端输出：正式 daemon 重定向到 `/data/adb/Zhang/log/daemon.log`；调试脚本直接输出到终端
- 总开关：`config.conf` → `log_enabled=false` 完全静默

## 启动与调试

### 正式启动（开机自动）

Magisk late_start 执行 `service.sh`：
1. 初始化 config.conf（不存在时）
2. 同步 `Main.dex` 到配置根
3. pid 防重复检测
4. `nohup app_process ...` 后台启动

手动启动：`sh /data/adb/modules/Zhang/service.sh` 或 `sh /data/adb/modules/Zhang/启动Dex.sh`
停止：`sh /data/adb/modules/Zhang/停止Dex.sh`；重启：`sh /data/adb/modules/Zhang/重启Dex.sh`

### 调试运行

```sh
sh /data/adb/modules/Zhang/调试运行Dex.sh          # 默认进入数字菜单
sh /data/adb/modules/Zhang/调试运行Dex.sh selftest # 自测工具（菜单 20 亦可）
```

**自测工具 `SelfTest`**：无视开关直接调用所有模块并带验证断言，输出 PASS/FAIL/WARN/SKIP 汇总；
危险操作（存储隔离清理/应用停用/AppOps 授权/CPU 满频/服务器 governor）仍按开关门控或标注 SKIP。
自测发现并修复：app_process 下 framework SQLite 不可用 → 内置 sqlite3 CLI（`sqlite_lib/`）兜底，
LSPosed 扫描恢复（55 模块/29 启用）。

与 `service.sh` 启动参数完全等效，但前台运行、终端实时输出、Ctrl+C 结束；
启动前自动停止正式 daemon 避免双实例。

## 保留脚本说明

- **生命周期**：`install.sh`、`post-fs-data.sh`、`uninstall.sh`（安装/卸载/开机早期清理）
- **独立工具**（用户指定保留原样）：格式化、删除多开、设置CPU最高频率（**依赖已删除的 maxcpu.sh，当前不可用**）、设置Dhizuku、修正Apk、一键安装、一键更新、重置系统
- **未迁移遗留**（原代码注释或孤立，无 Dex 等价物）：`killlog.sh`、`Flashrate.sh`、`Flashrate_run.sh`、`initrcChargeStart.sh`

## 卸载

Magisk 管理器移除模块时执行 `uninstall.sh`：停止 Dex daemon、清理 dalvik/package 缓存、删除 `/data/adb/Zhang`（含配置、日志、Main.dex）。

## 已知注意事项

- 模块目录仅保留 `config.conf` 一个配置文件；所有功能配置由 Dex 首次运行初始化为原模块默认值。
- `/data/adb/Zhang/` 下已存在的配置不会被自动覆盖（参数行缺失时会追加，不覆盖已有值）；升级后如需恢复默认请手动删除对应文件。
- **SystemContext**：Android 15 上 `createSystemContext()` 被 hidden-API 过滤（伪 NoSuchMethodException），
  daemon 改走 `Looper.prepareMainLooper() + ActivityThread.systemMain()`；其 ContentResolver 无法访问
  settings provider，settings 读写自动走 shell（仅首次 WARN）。
- **Shell 使用原则**：可 API 化的操作已优先 API（包管理/WiFi/蓝牙/Doze 白名单/HOME 与输入法解析/
  renice/chown/chmod/rm/mv/启动服务与广播/ctl 服务），失败自动降级等效 shell（每类一次警告）。
  保留 shell 的仅限无 Java 等价：`chrt`/`chattr`/`fstrim`/`sync`/`device_config`/`pm clear`/
  `pm uninstall`/`dumpsys deviceidle` 强制空闲/`cmd package compile`/`setenforce`/原生二进制启动。
- 部分功能依赖 OEM 环境（MIUI 数据库、ColorOS launcher 文件、高通 sysfs），在非对应机型上自动跳过并记录 WARN。
- `设置CPU最高频率.sh` 因依赖脚本已迁移删除而失效，如需使用可自行恢复 `maxcpu.sh` 或调用 Dex 的 PerformanceModule。

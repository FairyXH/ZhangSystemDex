# ZhangProtect / ZhangSystemDex

基于 Magisk 的 Root 系统管理模块。核心长期运行逻辑已由 Shell 迁移为 **Kotlin Dex Daemon**，
通过 `app_process` 以 root 身份运行，无需 Activity / Application 生命周期，单一 `Main.dex`。

- 入口类：`io.github.fairyxh.zhangsystemdex.Main`
- 启动方式：`app_process -Djava.class.path=... /system/bin --nice-name=zhangsystemdex Main <模块目录>`
- 运行身份：root（UID 0），默认无 Activity、无 Application、无多 dex

---

## 1. 架构总览

```
Magisk 模块目录                       运行时数据目录
┌──────────────────────┐              ┌────────────────────────────┐
│ Main.dex  (唯一核心)  │──同步──▶     │ /data/adb/Zhang/           │
│ config.conf (root/log)│  服务启动时  │   ├── Main.dex             │
│ service.sh (启动器)   │              │   ├── switches.conf (全部开关)│
│ 调试运行Dex.sh (调试)  │              │   ├── doze.conf / game_pause.conf ...│
│ 8 个保留工具脚本       │              │   ├── cache/  (xposed_modules.json)  │
│ system/ ZhangSetting/ │              │   └── log/    (zhang.log 滚动)       │
└──────────────────────┘              └────────────────────────────┘
```

- **模块目录只保留 `config.conf`**（指定配置根与日志开关），其余全部配置由 Dex 首次启动自动生成。
- 所有功能线程由 `Main` 按 `switches.conf` 动态加载：**关闭的功能不创建线程**。
- 运行中每 60 秒监听 `switches.conf`，开关变化自动启停对应线程，**无需重启 daemon**。

---

## 2. 模块目录文件

| 文件 | 职责 |
|---|---|
| `Main.dex` | 整个模块的核心（Kotlin Dex Daemon），所有配置由它生出 |
| `config.conf` | 仅两键：`root_dir`（配置根，默认 `/data/adb/Zhang`）、`log_enabled`（日志总开关） |
| `sqlite_lib/` | 内置 sqlite3 CLI 及其依赖库（首次启动自动同步到 `cache/sqlite_lib`） |
| `service.sh` | 正式启动器：同步 Main.dex、防重复 pid、`app_process` 后台启动 |
| `启动Dex.sh` / `停止Dex.sh` / `重启Dex.sh` | daemon 启停/重启快捷脚本（重启=停止+启动） |
| `调试运行Dex.sh` | 前台调试：默认进入数字菜单，可单次执行任意功能 |
| `install.sh` / `post-fs-data.sh` / `uninstall.sh` | 安装 / 开机触发 / 卸载（卸载会停止 daemon）。install.sh 已移除 SKIPMOUNT 逻辑，**永不创建 `skip_mount`**，防止 Magisk 跳过本模块 system/ 挂载 |
| 7 个保留工具脚本 | 格式化 / 删除多开 / 设置 Dhizuku / 修正 Apk 名 / 一键安装 / 一键更新 / 重置系统 等（**不迁移，保持原样**） |
| `aapt` / `shfmt` | 被上述保留工具依赖的二进制 |
| `system/` | Magisk overlay 内置系统应用（`system/product/app/...`），daemon 不会改动/删除 |
| `ZhangSetting/` | 配置与模块包集合，heavy 周期与 HMA/DNTA 生成时**整目录释放**到 `/data/media/0/Download/ZhangSetting`（cp -rf 语义，目录缺失允许） |

### 运行时数据目录 `/data/adb/Zhang/`

| 文件 | 说明 |
|---|---|
| `switches.conf` | **全部功能开关**，属性格式 + 中文注释（详见 §3） |
| `doze.conf` | Doze 白名单（默认内置原模块配置，`only_base_enable=false` 时读取此文件） |
| `game_pause.conf` | 游戏暂停列表（默认 6 个游戏） |
| `asguard.conf` | 无障碍守护包列表 |
| `notification.conf` / `autorun.conf` | 通知监听服务 / 开机自启动服务列表 |
| `app_manager/*.conf` | 停用与遮蔽应用列表 |
| `appops_packages.conf` | 模块目录 APK 解析出的 AppOps 历史目标包名（仅新增，原子替换） |
| `cache/xposed_modules.json` | Xposed 模块扫描缓存（按包签名增量） |
| `log/zhang.log` | 统一日志（1MB 滚动） |
| `cache/sqlite_lib/` | 内置 sqlite3 CLI（framework SQLite 不可用时数据库操作兜底） |
| `CleanedRubbish/` | 存储隔离垃圾隔离区 |

---

## 3. 功能开关 `switches.conf`

- 每个独立功能一个开关，**全部带中文注释**；每次启动都会检查，**缺省一律默认 `false`**。
- 关闭的功能：**完全不创建线程、不执行任何逻辑**。
- 以下 **13 个特殊功能默认开启**：

| 开关 | 默认 | 说明 |
|---|---|---|
| `doze_enable` | true | Doze 处理：电池优化白名单维护 + 夜间强制 Doze |
| `hma_config_enable` | true | HideMyAppList 模板列表自动写入（含 Xposed 模块扫描） |
| `game_pause_enable` | true | 游戏在前台时暂停其他功能 |
| `accessibility_guard_enable` | true | 无障碍服务守护 |
| `locked_apps_enable` | true | 多任务锁定应用处理（MIUI/ColorOS） |
| `prop_tuning_enable` | true | 系统属性优化与防检测属性 |
| `heavy_task_enable` | true | **周期高占用任务**（见 §4） |
| `target_list_enable` | true | tricky_store/hmspush 目标列表增量更新 |
| `disable_apps_enable` | true | 反诈/快应用等应用停用与遮蔽挂载 |
| `service_guard_enable` | true | 服务守护：Shizuku/Brevent/蓝牙/健康应用 |
| `read_game_list_enable` | true | 自动读取 MIUI/欧加游戏列表 |
| `miui_tuning_enable` | true | MIUI joyose/powerkeeper 数据库与属性调优 |
| `skip_mount_guard_enable` | true | 模块目录防护：自动删除 skip_mount 等残留文件（防止系统挂载被跳过） |

### 其余功能（默认 false）

| 开关 | 说明 |
|---|---|
| `system_tuning_enable` | 主调优循环：热控/调度/防错误弹窗/进程提升等每周期常规任务 |
| `extra_features_enable` | 附加功能：NFC 守护/通知监听守护/开机自启动 |
| `memory_clean_enable` | 内存清理与低内存后台杀进程 |
| `server_mode_enable` | 服务器模式：保持 WiFi/蓝牙/常亮/性能调度 |
| `thermal_mask_enable` | 温控配置文件遮蔽 |
| `appops_allow_enable` | 白名单应用 AppOps 全允许与权限组授权 |
| `module_appops_auth_enable` | 为模块挂载 App 授权 AppOps（启动扫描模块目录 APK，之后每 60 秒只读取数据库处理） |
| `dexopt_everything_enable` | 开机执行 everything 编译 |
| `selinux_disable_enable` | 关闭 SELinux |
| `powersave_enable` | 省电模式（开启后其余调优类功能全部无效） |
| `storage_isolation_enable` | 存储空间隔离配套：痕迹清理/垃圾隔离/配置生成 |
| `storage_isolate_all_enable` | 存储空间隔离作用于所有应用（false=仅第三方） |
| `storage_isolate_media_enable` | 允许隔离媒体选择器 |
| `boost_process_enable` | 进程调度提升（renice/chrt/cpuset） |
| `boost_game_enable` | 游戏进程自动加速 |
| `run_once_enable` | 高占用任务仅执行一次后退出 |
| `max_cpu_enable` | CPU/GPU 满频率与核心分配 |
| `dnt_accessibility_enable` | DoNotTryAccessibility 规则 XML 生成 |
| `network_ipv6_disable_enable` | 禁用 IPv6 |
| `only_base_enable` | Doze 白名单使用内置规则（false=读取 doze.conf） |

另有字符串配置项：`frpc_command`、`automusic_command`（服务器模式外部命令，留空跳过）。

**主调优循环参数**（留空使用默认值，修改后需重启 daemon 生效）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `tuning_interval_seconds` | 600（服务器模式 300） | 主调优循环周期（秒），最小 30 |
| `heavy_interval_cycles` | 6（服务器模式 24） | 高占用任务间隔周期数，即每 N 个主循环周期执行一次 heavy，最小 1 |
| `heavy_screen_off_only` | `false` | 高占用任务是否**仅在息屏时执行**；`false`=亮屏也允许执行，`true`=亮屏到期跳过并下一周期立即重试 |

---

## 4. 周期高占用任务（`heavy_task_enable`，默认 true）

从主调优循环中**单独抽出**的高占用任务集合，**默认开启**。由 `SystemTuningModule` 线程承载，
周期 = 主循环周期 × 间隔周期数（普通模式默认 600s×6=3600s；服务器模式默认 300s×24=7200s；
两者均可通过 `tuning_interval_seconds` / `heavy_interval_cycles` 调整）。**默认亮屏也允许执行
（`heavy_screen_off_only=false`）**；设置 `heavy_screen_off_only=true` 时仅熄屏执行，亮屏到期
输出 `高占用任务到期但未息屏（heavy_screen_off_only=true），跳过执行` 日志并**保持周期计数**，
下一个周期（屏幕熄灭后）立即补执行，不需要等满间隔周期数。启动时也会立即检查一次（首次执行，
不等待间隔）。任务清单：

0. 防错误弹窗规则（`activity_manager_constants`/device_config/冷冻进程/SurfaceFlinger 防弹窗）
1. Doze 白名单刷新（`doze_enable` 且非服务器模式）
2. MIUI 调优（`miui_tuning_enable`）→ joyose/powerkeeper 数据库与属性
3. Soter 服务重启与数据清理
4. 温控遮蔽（`thermal_mask_enable`）
5. DoNotTryAccessibility 规则生成（`dnt_accessibility_enable`）
6. 删除 hidemyapplist 残留模块目录
7. tricky_store / hmspush 目标列表增量更新（`target_list_enable`）
8. 反诈/快应用停用与遮蔽（`disable_apps_enable`）
9. Shizuku/Brevent 重启守护（`service_guard_enable`）
10. 存储隔离垃圾清理（`storage_isolation_enable`）
11. 假电池值锁定（qcom-battery fake_temp/soh/cycle）
12. 同步模块 `ZhangSetting/` 到下载目录
13. `run_once_enable=true` 时执行完首轮后退出

> 该开关只控制高占用任务本身；`system_tuning_enable`（默认 false）控制每周期常规任务
> （settings/prop/schedtune/进程提升等）。两者任一开启都会创建承载线程，互不影响。
> **修改 `tuning_interval_seconds` / `heavy_interval_cycles` 后需重启 daemon**（参数在
> 模块构造时读取，开关热加载不会重建线程）。

---

## 5. 功能模块详解

### 5.1 基础设施（core/）

| 类 | 职责 |
|---|---|
| `Logger` | 统一日志：INFO/WARN/ERROR + 异常堆栈，时间/模块/级别，文件 1MB 滚动；`log_enabled=false` 完全静默 |
| `ConfigManager` | 解析 `config.conf`、生成/读取 `switches.conf` 与全部功能默认配置、热加载检测 |
| `DexContext` | 运行上下文：模块目录、配置、全局游戏暂停协调器 |
| `RootUtils` | root 检测（UID 0） |
| `HiddenApiBypass` | LSPosed 同款 `VMRuntime.setHiddenApiExemptions`，解除进程 hidden API 限制 |
| `PropUtils` | 属性读写（resetprop 优先，失败降级 setprop） |
| `SettingsUtils` | Settings.Global/Secure/System 读写（framework 优先，shell 兜底） |
| `ShellExecutor` | 外部命令执行（仅无等价实现时使用），含 exit code 判定 |
| `FileUtils` | 文件/目录/权限/chattr/复制（`copyDirRecursive` 整目录 cp -rf 语义，FUSE 失败 shell 兜底） |
| `ProcessUtils` | 进程信息、renice、cgroup、sysfs 写入（失败自动降级 `su -c`） |
| `SqliteUtils` | SQLite 访问：framework `SQLiteDatabase` 优先，不可用时自动走内置 `sqlite_lib/` 的 sqlite3 CLI（失败/缺失只记录一次，CLI 非零退出码不当查询结果） |
| `AppListProvider` | 已安装应用列表（PackageManager 优先，`pm list` 兜底） |
| `GameListProvider` | 游戏列表（game_pause.conf + MIUI/欧加游戏数据库，`read_game_list_enable` 默认开） |
| `ServiceManagerUtils` | Binder ServiceManager 反射调用（SurfaceFlinger 等） |
| `FrameworkOps` | **Framework-first 操作封装**：包管理（enable/disable/disable-user/组件/权限授予）、AppOps 反射、force-stop、Doze 白名单、WiFi/蓝牙、wakeUp/媒体按键、Intent 启动、ctl 服务控制、HOME 解析。每项先走 Android API，失败自动降级等效 shell，失败警告按操作去重（logged once） |
| `DaemonLoop` | 通用守护线程基类：独立异常捕获、间隔循环、可被游戏暂停 |
| `GamePauseCoordinator` | 全局游戏暂停协调：游戏前台时挂起可暂停模块 |

### 5.2 功能模块（modules/）

| 模块 | 开关 | 周期 | 职责 |
|---|---|---|---|
| `AntiDetectionModule` | `prop_tuning_enable` | 300s | 防检测属性（boot/保修/调试）、pihook/pixelprops 属性清理、app_data_isolation 删除 |
| `SystemTuningModule` | `system_tuning_enable` / `heavy_task_enable` | 600s | 见 §4：常规调优 + 周期高占用任务 |
| `GamePauseModule` | `game_pause_enable` | 180s | 检测前台游戏 → 全局暂停其他功能；`boost_game_enable` 时提升游戏进程 |
| `AccessibilityGuardModule` | `accessibility_guard_enable` | 10s | 守护 asguard.conf 中应用的无障碍服务不被系统关闭 |
| `ServiceGuardModule` | `service_guard_enable` / `extra_features_enable` | 300s | Shizuku/Brevent 保活、健康应用启用、蓝牙保持；附加：NFC/通知监听/开机自启 |
| `ServerModeModule` | `server_mode_enable` | 300s | WiFi/蓝牙/常亮、性能 governor、frpc/音乐外部命令 |
| `PowerManagerModule` | `doze_enable` / `locked_apps_enable` | 60s | Doze 白名单（内置/doze.conf）、夜间 Doze、MIUI/ColorOS 多任务锁定 |
| `MemoryModule` | `memory_clean_enable` | 180s | drop_caches、低内存杀后台（白名单豁免） |
| `StorageIsolationModule` | `storage_isolation_enable` | 60s | 痕迹清理、垃圾隔离、storage-isolation 配置生成（org.json）。**高危模块**：所有执行路径（线程/主循环/调试菜单）均受 `storage_isolation_enable` 总闸门控，关闭时一律不得执行；`/data/media` 用户目录隔离与 CleanedRubbish 删除均带 WARN 日志 |
| `ConfigGenModule` | `hma_config_enable` | 600s | HMA 配置生成（含扫描）、target 列表、DoNotTryAccessibility XML |
| `LSPosedScannerModule` | 随 `hma_config_enable` | - | LSPosed 配置/数据库读取模块列表（含启用状态与 scope），按已安装应用过滤，签名缓存增量 |
| `NetworkModule` | `network_ipv6_disable_enable` | - | 禁用 IPv6 |
| `SkipMountGuardModule` | `skip_mount_guard_enable` | 10s | 模块目录防护：自动删除模块目录下的 `skip_mount` 等残留文件（防 Magisk 跳过 system/ 挂载），**不受省电模式影响** |
| `PerformanceModule` | `max_cpu_enable` | 被调用 | CPU/GPU 满频率 |
| `ThermalModule` | `thermal_mask_enable` | 被调用 | 温控配置遮蔽 |
| `MiuiTuningModule` | `miui_tuning_enable` | 被调用 | joyose/powerkeeper 调优 |
| `AppManagerModule` | `appops_allow_enable` / `disable_apps_enable` | 被调用 | AppOps 授权、应用停用与遮蔽 |

### 5.3 Xposed 模块扫描（LSPosedScannerModule）

优先级（不再使用 aapt）：
1. **LSPosed 配置/数据库**：`/data/adb/lspd/config/modules.list` → `modules_config.db`
   （`modules` 表 = 已注册模块，`modules_state.enabled` = 启用状态，`scope` = 作用域）
2. 过滤：仅保留**当前已安装**的模块（排除已卸载僵尸与 provider 组件项）
3. **`scope` 表只作作用域数据，绝不作为模块来源**（它含已停用/历史/组件条目）
4. 缓存：`cache/xposed_modules.json` 按包签名命中直接返回；仅变化时全量扫描
5. 扫描结果统一输出到配置系统（HMA 黑名单模板使用）

---

## 6. 线程与日志

- **线程模型**：每个功能独立线程 + 独立异常捕获；单个模块崩溃不影响 daemon。
- **游戏暂停**：`GamePauseCoordinator` 全局协调，游戏前台时暂停可暂停模块。
- **热加载**：每 60s 检查 `switches.conf`，开关变化自动启停线程并打日志。
- **启动日志**：`Main` 汇总输出 `已启用功能 (N 个): ...`，每个线程输出
  `功能已启动: XxxModule 开始运行 (interval=...ms)`。
- 日志路径：终端 → `daemon.log`；文件 → `/data/adb/Zhang/log/zhang.log`。

---

## 7. 调试

```sh
sh /data/adb/modules/Zhang/调试运行Dex.sh          # 默认进入数字菜单
sh /data/adb/modules/Zhang/调试运行Dex.sh normal   # 跳过菜单直接前台运行
sh /data/adb/modules/Zhang/调试运行Dex.sh selftest # 自测工具（无视开关全量自测 + 验证后退出）
```

**自测工具（`SelfTest`，调试菜单 20 或 `selftest` 参数）**：无视 Main 的开关加载逻辑，
直接实例化并调用所有模块（防检测属性/Doze 白名单/Lock 应用/无障碍守护/服务守护/内存清理/
HMA 生成/DNTA/target 列表/LSPosed 扫描/MIUI 调优/温控遮蔽），每步带验证断言并输出
`PASS/FAIL/WARN/SKIP` 汇总。危险操作（存储隔离清理、应用停用、AppOps 授权、CPU 满频、
服务器模式 governor）仍受对应开关门控或单独标注 SKIP。适合 adb 一键回归：

调试菜单（按数字单次执行后退出）：
```
0. 正常启动（全部已启用功能）     10. 存储隔离痕迹/垃圾清理
1. 防检测属性应用                 11. 温控遮蔽生成
2. HideMyAppList 配置生成         12. MIUI 调优
3. Xposed 模块扫描                13. target 列表更新
4. Doze 白名单应用                14. DoNotTryAccessibility 生成
5. 多任务 Lock 应用               15. 服务器模式动作
6. 无障碍守护检查                 16. 游戏列表刷新
7. 服务守护动作                   17. 退出
8. 内存清理                       18. LSPosed 数据库诊断
9. 存储隔离配置生成               19. SystemContext 诊断
                                  20. 自测工具（全量自测）
                                  21. 模块目录防护检查（删除 skip_mount 等残留）
```

---

## 8. 已知限制与注意事项

- **Xposed 扫描仅依赖 LSPosed 数据**：已移除 PackageManager metadata 扫描
  （API 35 上 app_process 拿不到 createSystemContext）。LSPosed 未安装时扫描结果为空。
- **SystemContext**：`createSystemContext()` 在 Android 15 被 hidden-API 过滤（伪
  NoSuchMethodException），daemon 改走 `Looper.prepareMainLooper() + ActivityThread.systemMain()`
  获取系统 Context（PackageManager 可用）；其 ContentResolver 无法访问 settings provider，
  因此 settings 读写自动走 shell（仅首次 WARN），失败尝试缓存 10 分钟，不会每周期刷屏。
- **Shell 使用原则**：能走 Android API 的操作已优先 API（包管理、WiFi/蓝牙、Doze 白名单、
  HOME/输入法解析、renice/chown/chmod/rm/mv、启动服务/广播、ctl 服务控制），API 不可用
  （hidden-API 过滤、权限、OEM 限制）时自动降级等效 shell 命令，每类失败仅记录一次。
  仍保留 shell 的仅限无 Java 等价的操作：`chrt`、`chattr`、`fstrim`/`sync`、`device_config`、
  `pm clear`/`pm uninstall`、`dumpsys deviceidle`（force-idle/enable/motion）、
  `cmd package compile`、`setenforce`、原生二进制启动（shizuku/brevent/frpc 等）。
- **SQLite 限制**：Android 15 app_process 中 framework `SQLiteDatabase` 无法访问 settings
  provider（`Unable to find app for caller`，hidden-API 过滤导致无法绕过），因此数据库操作
  自动使用模块内置 `sqlite_lib/` 的 sqlite3 CLI（首次启动同步到 `cache/sqlite_lib`，**每次
  启动自动修复执行权限**，防止模块更新覆盖权限位导致 `Permission denied`），LSPosed 扫描/
  游戏列表/MIUI 数据库均走此路径；framework 失败与 CLI 缺失只各记录一次，CLI 失败（非零
  退出码）不会把 stderr 当作查询结果。
- **ZhangSetting 同步**：HMA 配置与 DNTA 生成后立即把模块 `ZhangSetting/` 全量同步到
  `/data/media/0/Download/ZhangSetting`（heavy 周期仍会再同步一次），无需等待高占用周期。
- **周期参数体现**：daemon 启动时日志打印实际生效周期（`周期配置: tuning_interval_seconds=...
  -> 生效 Ns, heavy_interval_cycles=... -> 生效 N 周期`）；运行中修改参数会提示
  “重启 daemon 后生效”；高占用到期且 `heavy_screen_off_only=true` 未息屏时每周期输出
  `高占用任务到期但未息屏（heavy_screen_off_only=true），跳过执行，下一周期立即重试`；
  默认 `false` 时亮屏同样执行高占用。
- **周期参数**：`tuning_interval_seconds` / `heavy_interval_cycles` 仅在模块构造时读取，
  修改后需重启 daemon；旧版 switches.conf 缺少这两行时 Dex 会自动追加（不覆盖已有值）。
- **sysfs 写入**：部分 ROM 的 SELinux 禁止任何域写 `/sys`（如 CPU governor），
  Dex 会先 Java 直写、失败自动降级 `su -c`，仍失败则记录 WARN 并继续。
- **存储隔离安全性**：`StorageIsolationModule` 会移动 `/data/media/*` 下的非标准用户目录
  并清理 `CleanedRubbish`，属于高危操作；**全部执行路径（含调试菜单 9/10）都强制要求
  `storage_isolation_enable=true` 显式开启**，关闭状态下任何方法都不会执行，
  每次移动/删除都输出 WARN 日志，请勿在未确认目录归属时开启。
- **保留工具依赖**：`设置CPU最高频率.sh` 依赖已迁移删除的 `maxcpu.sh`，现已失效；
  其余保留脚本依赖 `aapt`/`shfmt`，请勿删除。
- **模块目录残留 `skip_mount` 防护**：`skip_mount_guard_enable`（默认 true）每 10s 检查模块目录，发现 `skip_mount` 等残留文件立即删除。该文件是 Magisk 安装模板残留，存在时 Magisk 跳过本模块 system/ 挂载（内置系统应用不出现）；安装脚本已移除 SKIPMOUNT 创建逻辑，若此前已残留，删除后**需重启设备**恢复挂载。监听文件列表见 `SkipMountGuardModule.WATCH_FILES`（易维护，按行添加文件名）。
- **配置不覆盖**：`/data/adb/Zhang/` 下已有配置不会被 Dex 覆盖；删除后重启才会按新默认生成。
- **卸载**：`uninstall.sh` 会停止 daemon 并删除 `/data/adb/Zhang`。

---

## 9. 版本记录

- 2026-08-07：新增 `skip_mount_guard_enable`（默认 true）模块目录防护：自动删除模块目录下 `skip_mount` 等残留文件（Magisk 安装模板残留会让 system/ 挂载被跳过）；监听列表 `WATCH_FILES` 易维护、不受省电模式影响；调试菜单新增 21、SelfTest 新增检查项。模块侧 install.sh/update-binary 已移除 SKIPMOUNT 创建逻辑（永不创建 skip_mount）。构建并部署 Main.dex（单一 classes.dex，字节验证通过）。
- 2026-08-07：修复 `hma_config_enable` 默认失效——该键在 `SPECIAL_DEFAULT_TRUE` 但缺失于 `SWITCH_DESCRIPTIONS`，导致 `SWITCH_DEFAULTS` 无此键、配置文件永不生成该行、ConfigGenModule 永不启动（HMA 模板不生成）；已补描述并支持旧配置自动追加该行。HMA 生成时除 `隐藏应用列表全隐藏.json` 外**另写 `config.json` 同名副本**到模块 `ZhangSetting/` 与 `Download/ZhangSetting/`。真机验证：ConfigGen 启用、双副本生成（白名单 117/黑名单池 66）。
- 2026-08-07：新增 `heavy_screen_off_only` 开关（默认 `false`）：控制高占用任务是否仅在息屏时执行；`false` 亮屏也执行，`true` 亮屏到期跳过并下一周期立即重试；旧配置自动追加该行。真机验证：false 亮屏直接执行 heavy、true 亮屏跳过日志。
- 2026-08-07：ZhangSetting 复制改为**整个文件夹 cp -rf 语义**（`FileUtils.copyDirRecursive`）：递归复制子目录、覆盖同名、目标不存在自动创建、模块无该目录则静默跳过；高占用维护完成日志改为**执行明细列表**（如“高占用维护完成: 防错误弹窗、假电池锁定、Doze 白名单、…、ZhangSetting 释放(22 个文件)”）。真机验证递归子目录复制与明细输出。
- 2026-08-07：修复 `copyMount()` 误删模块 `system/` 内置应用——模块根 `product` 是指向 `./system/product` 的符号链接，旧逻辑遍历根目录时 `isDirectory` 跟随链接为 true，先 `deleteRecursive(system/product)` 再复制空链接内容导致全部内置 APK 丢失；现在跳过符号链接、排除 `product/system_ext/sqlite_lib`、只做合并不删除。同时加固 `FileUtils.copyFile/syncDir`：目标父目录不存在时 shell `mkdir -p` 兜底，Java File API 在 FUSE 挂载失败时 shell `cp -f` 兜底。真机验证：heavy 执行后 system/product 51 个 APK 完整、Download/ZhangSetting 重建 22 文件、复制失败 0 条。
- 2026-08-07：日志全量中文化（157 处，技术术语保留）；高占用任务**首次启动立即检查执行**（熄屏即执行，亮屏输出“启动时高占用任务就绪但未息屏，跳过执行，下一周期立即重试”，zhang.log/daemon.log 均可见）；无障碍守护“需手动开启一次”提示改为每包只提示一次；真机验证熄屏启动自动执行 heavy。
- 2026-08-07：修复 LSPosed 扫描数量（scope 表不再作为模块来源，modules 表 + 已安装过滤；CLI 失败不再把 stderr 当查询结果）；sqlite_lib 每次启动自动修复执行权限；HMA/DNTA 生成后立即同步 ZhangSetting 到 Download；启动日志明确周期配置与亮屏跳过；SelfTest 全部结果写入 zhang.log；HMA 日志补充 blackPool 数量。真机：扫描 55 模块/29 启用（raw 84）、黑名单池 64。
- 2026-08-07：新增 `SelfTest` 自测工具（调试菜单 20 / `selftest` 参数）：无视开关调用所有模块并带验证断言；自测发现并修复 app_process 下 framework SQLite 不可用的问题——内置 sqlite3 CLI（`sqlite_lib/`）自动同步与兜底，LSPosed 扫描恢复（55 模块/29 启用）；真机自测 27 PASS / 0 FAIL。
- 2026-08-07：新增 `FrameworkOps`，批量将可 API 化的 shell 调用改为 Android API（包管理/AppOps/force-stop/Doze 白名单/WiFi/蓝牙/唤醒/媒体按键/Intent 启动/ctl 服务/HOME 与输入法解析/Os.chown·chmod·rm·mv/renice），失败自动降级 shell 且警告去重；98 处 ShellExecutor 调用降至约 74 处（含 fallback），真机验证蓝牙/WiFi API 成功、Doze 白名单与媒体按键正确 fallback。
- 2026-08-07：修复 SystemContext（Looper+systemMain fallback，失败缓存防刷屏）；防错误弹窗纳入 heavy；新增周期参数 `tuning_interval_seconds`/`heavy_interval_cycles` 并自动补写旧配置；未息屏跳过时下一周期立即补执行；新增 `启动Dex.sh`/`停止Dex.sh`/`重启Dex.sh`。
- 2026-08-06：Shell → Dex 全量迁移完成；开关体系 + 热加载；LSPosed 数据库扫描；调试菜单；heavy 任务独立开关；12 个特殊开关默认开启（doze/hma/游戏暂停/无障碍/lock/prop/heavy/target/禁用应用/服务守护/读游戏列表/MIUI 调优）。

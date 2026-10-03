# ZhangProtect 增量开发任务清单（TODO / PLAN）

> 依据《ZhangProtect_Agent_Long_Task.md》第一章要求，在完成源码分析后建立本文件。
> 本文件随任务推进持续更新：完成一项勾选一项，遇到问题在“实施日志”追加。

---

## 0. 项目现状速览（已完成分析）

### 0.1 部署形态
- Magisk/KernelSU 模块目录：`/data/adb/modules/Zhang`
- 模块内仅有：`Main.dex` + Shell 脚本（`service.sh` / `install.sh` / `uninstall.sh` / `post-fs-data.sh` / `module.prop` / `config.conf` 等）
- 注：`bt_offload_fix.sh` 已删除，其等效逻辑（蓝牙 A2DP/LE 音频 offload 循环守护）已并入 `Main.dex` 的 `BtOffloadGuardModule`，不再依赖任何 shell 脚本。
- **无 webroot 目录**（需新增 `webroot/index.html` 供 KernelSU WebUI 使用）
- 配置根：`/data/adb/Zhang`（由 `config.conf` 的 `root_dir` 决定）
- daemon 启动：`service.sh` 调用
  `app_process -Djava.class.path=... /system/bin --nice-name=zhangsystemdex io.github.fairyxh.zhangsystemdex.Main <模块目录>`

### 0.2 源码工程
- 真实工程：`/home/projects/ZhangSystemDex`（**不是 git 仓库**，`git status` → fatal: not a git repository）
- 包名：`io.github.fairyxh.zhangsystemdex`
- 源码：`app/src/main/java/io/github/fairyxh/zhangsystemdex/`
  - 顶层：`Main.kt`、`DebugMenu.kt`、`SelfTest.kt`
  - `core/`：ContextProvider、DexContext、ConfigManager、DaemonLoop、Logger、SystemContext、
    FrameworkOps、ShellExecutor、PropUtils、SettingsUtils、ProcessUtils、FileUtils、SqliteUtils、
    BinderUtils、ServiceManagerUtils、HiddenApiBypass、RootUtils、GamePauseCoordinator、
    GameListProvider、PackageManagerProvider、AppListProvider
  - `modules/`：18 个模块（AccessibilityGuard、AccelerometerRotation、AntiDetection、AppManager、
    ConfigGen、GameOomProtect、GamePause、LSPosedScanner、Memory、MiuiTuning、Network、
    Performance、PowerManager、ServerMode、ServiceGuard、SkipMountGuard、StorageIsolation、
    SystemTuning、Thermal）
- **`PowerUtils.kt` 不存在**，禁止误引用。

### 0.3 关键机制（兼容性约束）
- `ConfigManager.switch(key)`：缺失/空 → 回退 `SWITCH_DEFAULTS[key]`（默认 false，15 个特殊项默认 true）；`"true"/"1"` 为真。
- `loadSwitches()` 解析时用 `substringBefore('#')` 剥离行内中文注释。
- `ensureParamLines()`：**只追加完全缺失的键，绝不覆盖已有值** → 新增电源配置字段必须走此 migration。
- `reloadSwitchesIfChanged()`：按 `switches.conf` 的 lastModified 热加载（daemon 主循环每 60s 检查）。
- `Main.enabled(key)`：`powersave_enable` 为总闸；防护类（skip_mount_guard）与 power/accessibility_guard 绕过总闸。
- `DaemonLoop`：抽象基类，子类实现 `tick()`；异常隔离（sleep 10s 续跑）；游戏暂停感知；1s 切片 sleep 快速响应 stop。
- WebUI 与 DEX 必须共用同一份 `switches.conf`；新键通过 ensureParamLines 追加。

### 0.4 构建环境（来自 源码信息.md，已验证存在）
- JDK 21：`/opt/jdk-21.0.12.1+1`
- Gradle 9.3.1：`/opt/gradle-9.3.1`
- Android SDK：`/opt/android-sdk`
- AAPT2 桥接包装器：`/opt/aapt2-qemu/aapt2`（arm64 主机跑 x86-64 AAPT2，经 `qemu-x86_64-static`）
- 编译命令：`source /opt/zhangsys-env.sh && /opt/build.sh`
- 产物：`app/build/outputs/apk/release/app-release-unsigned.apk`

---

## 1. 开发目标

1. **KernelSU WebUI**：新增 `webroot/index.html`（+ 必要 JS/CSS），可视化开关现有全部功能；与 DEX 共用 `/data/adb/Zhang/switches.conf`。
2. **电源与后台调度优化子系统**：事件驱动、fail-open、可完全关闭、默认保守的省电子系统。

## 2. 红线（必须遵守）

- 完整保留现有功能与旧配置兼容性；**不重命名旧 key、不无故改默认值**。
- WebUI 与 DEX 共用同一份配置；配置升级用 migration（ensureParamLines），不丢弃未知项。
- 省电子系统：事件驱动（禁 `while(true)+sleep(1)+dumpsys`）；fail-open（失败→保持系统默认）；
  可完全关闭；默认保守（不永久锁频、不关核心、不禁 thermal、不假定 sysfs 节点存在、写入失败不崩 daemon）。
- DEX 已 root：优先直接读写文件，避免每次 `su -c`；避免重复写相同值。
- 决策优先级：**兼容性 > 稳定性 > 功耗收益 > 功能数量**。
- 不过度重构、不产生大 diff、不删除承担兼容功能的代码。

---

## 3. 分阶段实施计划

### 阶段 A：环境与基线验证【已完成】
- [x] 完整读取两份文档（源码信息.md、长任务书全文 674 行）
- [x] 精读全部 core 文件（21 个）与全部 modules（18 个）
- [x] 确认源码工程可编辑、构建脚本路径
- [x] 建立本 TODO/PLAN 文件
- [x] **基线构建验证**：`bash /opt/build.sh` → EXIT=0，BUILD SUCCESSFUL in 41s（39 tasks up-to-date），
      AAPT2/qemu 链路正常产出 APK
- [x] 记录基线产物：`app-release-unsigned.apk` 2320978 字节，
      SHA256=c78a9c9b4b5a35e420ba7c2bde63bfa64b291932cd7408f3590ce6b3963b9ac0；
      内含 classes.dex 2306324 字节（与工程根 Main.dex 一致）
- [x] 源码备份：`backup_src/`（java 全量 + Main.dex）

### 阶段 B：电源子系统核心（新增 core 代码）【已完成】
- [x] `core/power/PowerStatistics.kt`（98 行）：无锁计数 + 快照
- [x] `core/power/PowerStateMonitor.kt`（187 行）：事件驱动广播监听（screen/battery），Android14 兼容，fail-open
- [x] `core/power/KernelPowerManager.kt`（133 行）：保守限频 + undo 可还原 + 探针
- [x] `core/power/AppPowerManager.kt`（94 行）：后台策略（默认空列表=不动任何应用）+ 受保护集合
- [x] `core/power/PowerPolicyEngine.kt`（109 行）：纯决策层，四级模型（IDLE/SCREEN_OFF/LOW_BATTERY/CHARGING）
- [x] `core/power/PowerOptimizer.kt`（209 行）：总调度器，串联全部组件，事件驱动 + revert，注册为 DaemonLoop
- 注意：与既有 `PowerManagerModule`/`MemoryModule`/`SystemTuningModule`/`PerformanceModule`/
  `GameOomProtectModule` 协同，避免双重 forceIdle / dumpsys 冲突。

### 阶段 C：电源配置接入（migration）【已完成】
- [x] 在 `ConfigManager` 的 `SWITCH_DESCRIPTIONS` 登记 8 个新开关（含中文说明）
- [x] `power_charging_release` 加入 `SPECIAL_DEFAULT_TRUE`（默认 true）
- [x] 在 `ensureParamLines()` 中追加新键（只补不覆盖，未知项保留）
- [x] 在 `writeSwitches()` 新建“电源与后台调度优化参数”段并输出取值键
- [x] `initUserConfigs()` 增加 `power_bg_stop_list.conf` + `DEFAULT_POWER_BG_STOP_LIST`（默认空白=不限制任何应用）
- [x] 总开关名：“电源与后台调度优化”（关闭后取消延迟任务、恢复临时调度状态，不影响其他功能与 Android Doze）
- [x] 低电量阈值默认 20%，充电自动退出（已接线至引擎）
- [x] 灭屏渐进式 Level1/Level2/DeepIdle，亮屏立即撤销、延迟任务可取消

### 阶段 D：Main.kt 接入【已完成】
- [x] 将 PowerOptimizer 作为新 `ModuleEntry("power_optimize")` 加入 `entries` 表
- [x] 确认受总闸 `powersave_enable` 与自身开关 `power_optimize_enable` 双重控制
- [x] 关闭时不创建线程、不影响其他模块（`onStop()` 全量还原）
- [x] 编译验证：修复 `PowerStateMonitor` 的 `pm.isCharging` 非法引用（改为 sticky BATTERY_CHANGED 读取），BUILD SUCCESSFUL

### 阶段 E：KernelSU WebUI【已完成】
- [x] 新增 `webroot/index.html`（含内联 JS/CSS，KernelSU WebUI 单文件友好）
- [x] 读取/写入 `/data/adb/Zhang/switches.conf`（与 DEX 共用，last-wins 语义对齐 DEX）
- [x] 功能开关分组展示（现有 + 新增），含中文说明与危险项警示
- [x] 新增电源子系统专属面板（状态、等级、统计）
- [x] 保持未知配置项不被破坏（仅就地改/追加，不重排、不删除）
- 证据：`webroot/index.html` 28015 字节 SHA256=`1d651cc372d1f50452f37a793452e2dce6e5e7b7e3c567299aee386b021077f7`；
      忠实移植的 Node 校验脚本在真实 switches.conf 上 17/17 通过；已真机部署至模块 `webroot/`。

### 阶段 F：自测与回归【已完成】
- [x] 扩展 `SelfTest.kt` 覆盖新电源模块（PASS/FAIL/WARN/SKIP，破坏性操作安全门控）
      新增 `powerChecks()`（8 项，只读/纯内存，避免自测写 sysfs）：统计快照、四级决策表、
      内核初始未施加、停名单加载、事件注册/注销、快照可读、配置 8 键齐备、停名单文件存在。
- [x] DebugMenu 增加新电源动作入口（24 立即评估 / 25 状态快照 / 26 决策表；避开 0-23 与 17）
- [x] 编译通过 → 产物有效性检查（新 dex 含全部电源类 marker + 新菜单串，可加载）
- 真机自测结果：PASS=38 FAIL=0 WARN=1 SKIP=7（total 46）；8 项电源检查全部 PASS，
      其中「配置键齐备（8 项）: 8/8」直接回归本轮修复的一致性缺陷。
### 阶段 G：构建、修复、收尾
- [x] 构建产物并部署链路验证（deploy 到 /data/adb/modules/Zhang 与 /data/adb/Zhang 的 Main.dex 同步）
- [x] 按要求完成构建验证与回滚能力说明（旧 dex 备份于 `_backup_20261002-170836/`）
- [x] 第二十一章：执行 git status / git diff 自查（本工程非 git → 记录为 N/A 并人工核对改动清单）
- [x] 最终交付 12 项总结

---

## 4. 决策记录 / 风险

- R1：工程非 git 仓库 → 无法用 git 回滚；改为**改动前手动备份**到 `backup_src/`。
- R2：AAPT2 依赖 qemu 桥接 → 任何构建改动都可能暴露链路问题，阶段 A 先验证。
- R3：既有电源相关模块较多 → 新系统只做“增量、可关闭”，不改既有模块默认行为。
- R4：WebUI 单文件约束 → 尽量内联，避免额外资源路径问题。

---

## 5. 实施日志

- [T0] 完成源码全量分析（core 21 + modules 18 + 长任务书 674 行）。
- [T0] 建立 TODO_PLAN.md。
- [T1] 基线构建验证通过（BUILD SUCCESSFUL in 41s）。完成源码备份到 backup_src/。
      确认工程非 git 仓库（改进为手动备份 + 人工核对）。
- [T2] 阶段 B 完成：core/power/ 下 6 个文件全部落盘（PowerStatistics/PowerStateMonitor/
      KernelPowerManager/AppPowerManager/PowerPolicyEngine/PowerOptimizer），已逐一回读核验
      API 签名（ConfigManager/FrameworkOps/DexContext/DaemonLoop/Main/ProcessUtils/Logger）。
      下一步：阶段 C —— 在 ConfigManager 登记新开关并 migration。
- [T2.5] 阶段 C/D/E 完成并真机验证：ConfigManager 登记 8 个 power 键 + `ensureParamLines`/
      `writeSwitches` 补齐（含 3 个布尔键 power_screen_off_cpu_cap / power_low_battery_restrict_bg /
      power_screen_off_restrict_bg，默认 false）；修复 PowerStateMonitor 编译错误；WebUI 部署完成。
      真机迁移实证：daemon 启动后 switches.conf 由 60→65 行，`diff` 为纯追加，无新增重复键；
      zhang.log 记录 `switches.conf 已追加缺失参数: [power_screen_off_cpu_cap,
      power_low_battery_restrict_bg, power_screen_off_restrict_bg]`。WebUI 忠实移植 Node 校验 17/17 通过。
- [T3] 阶段 F/G 完成并真机回归：
      (1) SelfTest 新增 `powerChecks()`（8 项）；DebugMenu 新增 24/25/26 三个电源动作入口。
      (2) 构建 BUILD SUCCESSFUL；提取新 Main.dex = 2341540 字节，
          SHA256=`539401fe40028214ecb8bd617be785387b0709aeb5f9e71ec8777f7eca758fdf`，
          7 个电源类 marker 全 True；APK SHA256=`5569ba75b25a47ed1efa8a6fa678524704b1d7edd0ad0e68fdd87ec3516735b4`。
      (3) 部署前备份旧 dex（2336004 字节 / `57a38cff…602b`）至 `_backup_20261002-170836/`，
          并将新 dex 同步至 `/data/adb/modules/Zhang/Main.dex` 与 `/data/adb/Zhang/Main.dex`。
      (4) 真机 selftest：PASS=38 FAIL=0 WARN=1 SKIP=7（total 46）；8 项电源检查全 PASS，
          「配置键齐备（8 项）: 8/8」回归本轮修复缺陷；并校验新 dex 含 3 个新菜单串。
      (5) 回归：service.sh 启动新 dex daemon 稳定存活、无崩溃；switches.conf 仍 65 行 / 8 power 键 /
          无新增重复键（仅既有 heavy_screen_off_only、module_appops_auth_enable）。
      回滚方式：将 `_backup_20261002-170836/Main.dex.mod`（或 .run）覆盖回对应路径即可。

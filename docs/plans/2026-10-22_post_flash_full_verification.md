# 刷包后全功能验证报告

- 日期：2026-10-22
- 设备：OnePlus PKG110 / Android 15 / ColorOS V15.0.0
- dex：`1d60160698b02dcfc941d3ce1ced25c6`（2,820,800 字节）
- 采集时开机 121 秒，daemon pid 5054
- 安装包 md5：`a741691ebe895f1c3cafa5bffdee25a1`

## 摘要

| 项目 | 结果 |
|---|---|
| **mountify 挂载 Zhang** | ✅ **修复**（`processing Zhang`，不再 `has skip_mount`） |
| 内置 50 个 APK 可见性 | ✅ **50 / 50** |
| `skip_mount` 残留 | ✅ **0** |
| dex 一致性 | ✅ 模块 / 运行时副本 / 自报 三处一致 |
| 模块运行 | ✅ 25 个中 20 启用，**20/20 tickCount > 0** |
| OOM 自保 | ✅ `-1000`，篡改后 75 秒自愈 |
| WebUI 后端 | ✅ 4 个接口全 200，overview 116ms |
| Download 基础目录 | ✅ Files / Important / Music 均 777 |
| 系统调优 | ✅ `perf_profile=4` |
| 资源占用 | RSS 94.8MB，线程 40，CPU ≈ 单核 53% |

---

## 一、mountify 挂载（本次核心修复项）

### 判定：✅ 通过

| 证据 | 值 |
|---|---|
| `dmesg \| grep mountify` | **`[9.761021] mountify/metamount: processing Zhang`** |
| 对比修复前 | `module with name Zhang has skip_mount`（被跳过） |
| `mountify module.prop` | `modules: ViPER4Android-RE-Fork **Zhang** aml iOSThemeFontsForColorOS iOSThemeStatusIconForColorOS` |
| `/data/adb/modules/Zhang/skip_mount` | **不存在** |
| 全机 `skip_mount` 残留 | **0** |
| `mount \| grep ' /system/app '` | 1 条 overlay |

### APK 可见性逐项统计

```
模块内 system/app 包数 = 50
其中在 /system/app 可见  = 50
```

抽查两个关键包：

| 包名 | 系统可见性 |
|---|---|
| `com.github.tianma8023.xposed.smscode` | VISIBLE |
| `io.github.a13e300.fusefixer`（曾被怀疑） | VISIBLE |

> 结论：此前「删掉 fusefixer / adbstayawake 才正常」是误判。
> 真正原因是 `install.sh` 缺 `SKIPMOUNT=false`，导致每次刷包都生成
> `skip_mount`，mountify 读到即跳过。本次已修复并验证。

---

## 二、dex 一致性

### 判定：✅ 通过

| 位置 | md5 |
|---|---|
| `/data/adb/modules/Zhang/Main.dex` | `1d60160698b02dcfc941d3ce1ced25c6` |
| `/data/adb/Zhang/Main.dex`（运行时副本） | `1d60160698b02dcfc941d3ce1ced25c6` |
| `/api/overview` 自报 `dexMd5` | `1d60160698b02dcfc941d3ce1ced25c6` |
| `/api/overview` 自报 `dexSize` | 2,820,800 |

---

## 三、模块运行状态

### 判定：✅ 通过

```
total   = 25
enabled = 20
running = 20
enabled 且 tickCount > 0 = 20
enabled 但 tickCount = 0 = 0
```

按配置关闭的 5 个（属预期，非故障）：

`memory_clean`、`network_ipv6`、`online_rules`、`server_mode`、`storage_isolation`

---

## 四、守护进程健康度

### 判定：✅ 通过

| 指标 | 值 |
|---|---|
| pid | 5054 |
| `oom_score_adj` | `-1000` |
| 线程数 | 40 |
| VmRSS | 94,856 kB（≈94.8 MB） |
| PPID | 1（孤儿进程，正常） |
| `restartCount` | 1（service.sh 首次拉起计数） |
| 进程存活时长 vs 开机时长 | 117s / 121s，**无异常重启** |
| 可用内存 | 2,772,032 kB（≈2.7 GB） |

CPU 实测（90 秒静默窗口）：**481 ticks ≈ 单核 53%**。

> 对比优化前基准 83%，游戏模块空转问题已修复并保持。

---

## 五、OOM 自保自愈

### 判定：✅ 通过

```
初始           : -1000
手动篡改为     : 0
75 秒后复查    : -1000   ← 自动恢复
```

`SelfOomGuardModule` 周期性写回生效。

---

## 六、WebUI 后端

### 判定：✅ 通过

| 接口 | HTTP | 耗时 |
|---|---|---|
| `/api/overview` | 200 | 0.116s |
| `/api/paths` | 200 | — |
| `/api/oom/status` | 200 | — |
| `/api/switch/list` | 200 | — |

无超时、无挂死。

---

## 七、系统级功能

### 判定：✅ 通过

| 功能 | 证据 | 判定 |
|---|---|---|
| Download 基础目录 | `Files`/`Important`/`Music` 均 `drwxrwxrwx` | ✅ |
| 系统调优 | `getprop persist.sys.perf_profile` = `4` | ✅ |
| 多任务锁定 | `app_lock_data_file_name` 39,777 字节 | ✅ |
| 重力旋转开关 | `accelerometer_rotation` = `0` | ✅ 已读取 |
| 屏幕超时 | `screen_off_timeout` = 60000 | ✅ 已读取 |
| 输入法 | `com.sohu.inputmethod.sogouoem/.SogouIME` | ✅ 已读取 |
| 通知使用权 | 5 个已授权 | ✅ |
| 无障碍服务 | **2 个** | ⚠️ 见下 |

### ⚠️ 需要说明的一项：无障碍服务数量

当前已启用 2 个：

```
li.songe.gkd
com.remoteenv.collector
```

此前某次报告为 8 个（含 `top.bogey.touch_tool`、`smscode`、`com.omarea.vtools`、
`com.zidongdianji` 等）。经核查：

- 这 4 个应用**均已安装**（`pm list packages` 各自命中 1 条）；
- `/data/adb/Zhang/a11y_keepalive.conf` 为**空**（用户自定义名单未配置）；
- `a11y_keepalive` 模块本身 `enabled=true / running=true / tickCount=5`，工作正常。

模块语义（`KeepAliveList.kt` 注释）：

> 列表中的包，若无障碍服务掉线，守护模块会自动重新授权。**它不会主动
> 给所有应用授予无障碍权限**。

因此结论：**减少的 6 项是刷包后被系统清除的应用侧授权，不是模块故障**。
若要恢复，需在系统设置中手动重新开启这些无障碍服务，或把它们加入
`a11y_keepalive.conf` 由模块保活。

---

## 八、结论

1. **本次刷包的核心目标达成**：mountify 正常挂载 Zhang，50/50 内置应用可见，
   `skip_mount` 问题彻底修复。
2. **无回归**：dex 一致、20/20 模块运行、OOM 自保自愈、WebUI 全接口正常。
3. **唯一需人工处理项**：无障碍服务授权数量下降（应用侧状态，非模块问题）。

---

## 附：本次刷包生效的关键修复

`install.sh` 增加 `SKIPMOUNT=false`。

原因：KernelSU 内置 Magisk 兼容安装脚本有 `$SKIPMOUNT && touch $MODPATH/skip_mount`，
而 `SKIPMOUNT` **未定义时展开为空串、空命令退出码为 0**，
于是 `"" && touch ...` 会真的执行 touch，每次刷包都生成 `skip_mount`。
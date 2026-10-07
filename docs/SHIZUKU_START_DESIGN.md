# Shizuku 启动方案（官方 root 命令）

> 状态：已定稿（2026-10-06）；2026-10-07 增补 §8「进程分类二次根治」。
> 长期约束：**禁止改 Shizuku 包**，只用官方入口。

## 0. 两个独立根因速查（务必先读）

“Shizuku is not running” 在本机有**两个彼此独立**的根因，均与 Shizuku 本体无关：

| # | 根因 | 症状 | 修复 | 章节 |
|---|---|---|---|---|
| 1 | 用了**错误的 starter 入口**（adb 遗留）+ **procfs `st_size=0`** 读不到 cmdline | `serverPids` 恒空、`restartCount` 飙升 | 官方 root 命令 + `readProcText()` 流式读取 | §2、§5 |
| 2 | 用 **uid 形态**判进程角色；Shizuku 转**系统应用**后主应用 uid 为 `99910335`，被误判为服务端 | `serverPids` **恒非空**、`healthy:true`（**假健康**）、真 server 从不拉起 | 改为**按进程名** `classifyNamed()` | §8 |

> 关键区别：根因 1 是「**读不到**」，根因 2 是「**读到了但分类错**」。
> 两者叠加时会呈现「一直显示健康、实则从未有服务端」的假象。

## 1. 背景问题

模块 `ShizukuModule` 保活长期失效：`serverPids` 恒为空，`restartCount` 飙升到 130+。
先前排查误判根因（曾考虑「so 被 Deflate 压缩」「必须重打包 APK 为 STORED」），均被否决。

## 2. 真正根因（已证实）

旧实现重启用的是 **ADB 模式遗留的 starter**：
- `/data/local/tmp/shizuku_starter`（官方 start.sh 中用于 **adb/wireless 模式**的流程）
- `/data/local/shizuku_starter`（2025-11 旧版，非官方位置）

该 starter 把库路径拼成 `<apk>!/lib/arm64-v8a`，导致：
```
nativeloader: Load <apk>!/lib/arm64-v8a/librish.so using isolated ns clns-1:
              dlopen failed: library ".../base.apk!/lib/arm64-v8a/librish.so" not found
java.lang.UnsatisfiedLinkError: dlopen failed: ... not found
  at rikka.shizuku.xh.I(SourceFile:30)
  at rikka.shizuku.server.ShizukuService.<init>
```
→ 服务端启动即失败。**与 APK 压缩方式无关，问题在用了错误的 starter 入口。**

另外，服务端进程名是 **`shizuku_server`**（不含包名），而旧识别逻辑只按包名
`pidsOf("moe.shizuku.privileged.api")` 匹配进程 → **永远匹配不到服务端**。

## 3. 官方 root 启动方式（源码依据）

官方仓库 `RikkaApps/Shizuku`，文件
`manager/src/main/java/moe/shizuku/manager/starter/Starter.kt`：

```kotlin
object Starter {
    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")
    val userCommand: String = starterFile.absolutePath
    val adbCommand = "adb shell $userCommand"
    val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"
}
```

`StartRootViewHolder.kt` → `StarterActivity(EXTRA_IS_ROOT=true)` → 执行 `Starter.internalCommand`。

**即官方 root 启动命令为：**
```sh
<ApplicationInfo.nativeLibraryDir>/libshizuku.so --apk=<ApplicationInfo.sourceDir>
```

starter 本体是随 APK 分发的 native lib `libshizuku.so`，使用的是
**`nativeLibraryDir`（系统已解压的真实目录）**，因此 `librish.so` 能正常加载。

## 4. 实测验证结果

```
info: switch cgroup succeeded, cgroup in /sys/fs/cgroup
info: switching mount namespace to init...
info: starter begin
info: killing old process...
info: use apk path from argv
info: apk path is /data/app/~~xxxx==/moe.shizuku.privileged.api-...==/base.apk
info: starting server...
info: shizuku_server pid is 1821
info: shizuku_starter exit with 0
```
服务端进程：`1821  1  0  shizuku_server`（root 模式 uid=0，ppid=1 已 daemon 化）。

路径可动态获取（无需硬编码随机串）：
```
NLD = $(pm path <pkg> | sed 's/package://;s|/base.apk$||')/lib/<abi>   # ~= nativeLibraryDir
SRC = $(pm path <pkg> | sed 's/package://')                            # ~= sourceDir
```
（模块运行在 DexContext 中取不到 Android `ApplicationInfo`，故用 `pm path` + ABI 推导。）

## 5. 落地实现要点

1. `ShizukuModule.officialStartCommand()`：用 `pm path` 解析 `sourceDir`，推导
   `nativeLibraryDir/lib/<abi>`，拼接 `"<nld>/libshizuku.so" --apk="<src>"`。
   - ABI：优先 arm64-v8a → 目录 `lib/arm64`；兼容 armeabi-v7a → `lib/arm`。
   - 推导出的 `libshizuku.so` 不存在则回退旧 starter。
2. 重启优先级：**官方命令 → 旧 starter 兜底**。
3. 进程识别：服务端 = `pidsOf(PACKAGE)` ∪ `pidsOf("shizuku_server")`，
   再由 `classifyNamed()` **按进程名**划分（`arg0 == shizuku_server` 为服务端）。
   > ⚠️ 2026-10-07 修正：原「按 uid 划分（uid 0/2000/用户段≥900）」已废弃 ——
   > Shizuku 转系统应用后主应用 uid 为 `99910335`，会被误判为服务端。详见 §8。
4. `ShizukuResidue`：`/data/local/tmp/shizuku_starter` 是官方 start.sh 会重建的文件，
   默认改为「受保护（GUARDED）」，仅显式开关才清理。

## 6. 稳定性 / 通用性说明

| 维度 | 本方案 |
|---|---|
| 换设备 | 路径经 `pm path` + ABI 动态推导，无设备相关硬编码 |
| Shizuku 升级 | 使用官方契约参数 `--apk=`，升级后依旧有效 |
| 不改包 | 完全不修改 Shizuku APK |
| root 方案 | 不依赖具体 su 实现，仅需能执行 shell |
| 兜底 | 保留旧 starter 路径作为 fallback |

## 7. 端到端实测验收（2026-10-07）
部署 dex 并重启 daemon 后，逐项验证通过：

| 验证项 | 命令/观测 | 结果 |
|---|---|---|
| daemon 启动即拉起 | 重启 dex 后 dashboard | `restartCount=1`，官方命令一次成功 |
| 探测准确性 | `/api/shizuku/status` ×5（12s 间隔） | 5/5 均为 `healthy:true`，PID 稳定 |
| 失效检测 | `kill -9 <serverPid>` | 立刻 `healthy:false, serverPids:[]` |
| 自动恢复 | 等待保活周期（30s） | `serverPids:[749]`，`restartCount 1→2` |
| 无循环重启 | 连续观测 | `restartCount` 仅随真实重启递增，不再每 30s 误重启 |

关键状态样例：
```json
{"installed":true,"healthy":true,"mainPids":[22534],"serverPids":[22120],"restartCount":1}
```

### 7.1 重要澄清：`hidepid=invisible` 造成的观察者偏差
本机 `/proc` 挂载为：
```
proc /proc proc rw,relatime,gid=3009,hidepid=invisible 0 0
```
- **普通 `su` shell 上下文**（`u:r:su:s0`、`Groups` 为空）**看不到 uid=0 的其他进程**，
  `ls /proc/[0-9]*` 仅约 1000 项、其中 uid=0 命中为 0。
- 因此用 shell 手工 `grep`/`ps` 验证 `shizuku_server` 会**得到假阴性**，
  曾被误判为「模块探测失效」。
- **daemon 是 uid 0 的原生进程**，不受 `su` 上下文限制，其 `pidsOf("shizuku_server")`
  在 daemon 内部**工作正常**（已由 `healthy:true` 与 kill/恢复实验证明）。
- 结论：**验证 Shizuku 状态必须读 `/api/shizuku/status`，不要用 shell 遍历 `/proc`。**

### 7.2 排查教训
- 出现 `serverPids:[]` 时，首先确认 **daemon 是否真正加载了新 dex**；
  进程存在于旧 dex 上会导致「代码已改但行为未变」的假象。
- 修复后必须用 kill + 等待保活周期的方式做**闭环验证**，而非只看单次状态。

## 8. 进程分类二次根治（2026-10-07）

> §5.3 里那条「`classify()` 按 uid 划分」的写法是**错的**，本节说明为何并给出正解。

### 8.1 现象
用户安装含 §7 修复的母版 zip 并重启后，**仍提示 “Shizuku is not running”**，
但 `/api/shizuku/status` 却显示 `healthy:true`、`serverPids` 非空 —— **假健康**。

### 8.2 根因：系统应用化导致 uid 形态失效
本机 Shizuku 已转为**系统应用**（`/data/system/packages.list` 中 `partition=system`），
`cmd package list packages -U` 显示其 uid 为 **`10335,99910335`**（两个）。实测进程：

| 进程 | uid | 真实身份 |
|---|---|---|
| `moe.shizuku.privileged.api` | `10335` | 主应用（普通形态） |
| `moe.shizuku.privileged.api` | `99910335` | 主应用（**系统分区 uid 变体**，user 段 999） |
| `shizuku_server` | `0` | 真服务端（官方 root 命令拉起） |

旧 `classify()` 规则 `uid/100000 >= 900 → 服务端` 把 `99910335` 的**主应用**判成服务端：
- `/api/shizuku/status` 恒报 `serverPids` 非空 → `healthy:true`；
- 保活逻辑 `if (snap.healthy) return` / `if (snap.serverUsable) return` → **直接返回**；
- 结果：**真 `shizuku_server` 从未被拉起**，Shizuku 客户端自然报 “is not running”。

> 反证：手动执行官方命令 `libshizuku.so --apk=<base.apk>` **能立即起 `shizuku_server`**
> → 证明官方命令无误，问题纯在**进程识别口径**。

### 8.3 正解：按进程名，不看 uid
```kotlin
// ShizukuModule.classifyNamed(named: List<Pair<Int, String>>): Snapshot
//   arg0 == "shizuku_server"                      -> 服务端
//   arg0 == 包名 / "包名:xxx" / "*/包名"           -> 主应用
```
`snapshotStatic()` 现为：`PROC_PATTERNS`（包名 + `shizuku_server`）收 pid →
`ProcessUtils.procName(pid)` 读 arg0 → `classifyNamed()` 划分。
`classify(uid)` 仅作兜底保留（新代码勿用）。

新增 `ProcessUtils.procName(pid)`：读 `/proc/<pid>/cmdline` 的 arg0（流式，同 §5 的 `readProcText`）。

### 8.4 实测验收（dex `b9b5e895712f237d670bf23f40f34fd2`）
| 验证项 | 观测 | 结果 |
|---|---|---|
| 分类正确 | `/api/shizuku/status` | `mainPids:[uid10335, uid99910335]`、`serverPids:[uid0 shizuku_server]` ✅ |
| 失效检测 | `kill -9 <serverPid>` | 立刻 `healthy:false, serverPids:[]` ✅ |
| 自动恢复 | 等保活周期 | **5s 内**拉起新 server，`restartCount +1` ✅ |
| 无空转 | 连续采样 3×20s | `restartCount` 恒定不变 ✅ |
| 客户端连通 | logcat | `Service: send binder to user app ... in user 0/999` + `ShizukuApplication: attachApplication` ✅ |

### 8.5 通用教训
**不要用 uid 形态判断进程角色。** 系统应用化、多用户（user 999/10 等）、
uid 变体都会破坏「uid 段」假设。能用**进程名**（`cmdline` arg0）判断就别用 uid。

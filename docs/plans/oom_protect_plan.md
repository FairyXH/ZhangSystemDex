# OOM 保护名单（任意应用）— 实施计划

## 需求（用户原文）
> 新增一个功能：oom保护名单，一个应用选择器，一个手动编辑列表，列表一行一个包名。
> 像保护游戏 oom 一样，把列表中的应用的 oom=-1000，然后提高进程优先级，
> 但要保证系统稳定性，不能超过系统自身。然后处理好可能导致的内存泄露（无法释放的问题）。
> 默认内置包名 com.ai.assistance.operit

## 设计

### 数据
- 配置文件 `oom_protect.conf`（`$rootDir`），一行一个包名，`#` 注释。
- 默认内容：`com.ai.assistance.operit`（由 `ConfigManager.initUserConfigs` 创建）。

### 模块 `OomProtectModule`（DaemonLoop，5s）
- 复用 `GameOomProtectModule` 的模式：读列表 → `ProcessUtils.pidsOf(pkg)` → 写 `oom_score_adj`。
- **安全上限（保证系统稳定性）**：系统关键进程由 init 设为
  `-1000`(init) / `-900`(system_server 等)，用户应用**绝不允许低于 -900**。
  因此目标值可配（默认 `-1000` 请求），但**实际钳制到 `max(requested, SAFE_FLOOR=-900)`**，
  即最激进也只到 -900，永远不会超过（数值更小=更高优先级）系统自身核心进程。
  子进程（非主进程）用更保守的 `-700`（可配），避免抢占系统资源。
- **提高进程优先级**：对主进程 `renice(-10)`（温和提升），不做 RT/chrt 抢占，
  避免饿死系统线程。
- **内存泄露/无法释放处理**：
  1. 内部状态 `lastAdj: MutableMap<Int,Int>`（pid→上次写入值）避免重复写；
     每次 tick **只保留当前存活 pid**（`/proc/<pid>` 存在性检查），
     已退出进程的条目立即清除 → 有界、无累积。
  2. 记录模块**自己改过**的 pid 集合 `touchedPids`；当某 pid 从保护列表移除
     或模块关闭（`onStop`）时，把该 pid 的 `oom_score_adj` **还原为系统默认 0**
     （若进程仍存活），避免残留高优先级导致无法回收。
  3. `onStop` 释放全部内部结构（clear），不留悬挂引用。
- 遥测：`RuntimeRegistry.put("oom_protect", ...)` 暴露受保护数量/列表供概览页。

### API（HttpBackend）
- `GET  /api/oom/read`   → `{ok, path, content}`
- `POST /api/oom/write`  → 原子写盘 + 归一化（去空行/去重/校验包名合法性）
- `GET  /api/oom/status` → `{ok, enabled, count, protected[], known, file}`
- `GET  /api/oom/apps`   → `{ok, apps:[{pkg,label,system}]}` 应用选择器数据源

### 开关
- `oom_protect_enable`（默认关闭），登记进 `ConfigManager.SWITCH_KEYS` + `SWITCH_DESCRIPTIONS`
  + `Main.kt` ModuleEntry。

### WebUI（清理页新增卡片 / 或设置页）
- 应用选择器：从 `/api/oom/apps` 拉取，多选勾选，实时同步到 textarea。
- 手动编辑列表：`<textarea>` 一行一个包名，`#` 注释。
- 保存按钮 → `/api/oom/write`；保存后可选自动开启开关。
- 显示当前受保护进程（来自 `/api/oom/status`）。

### SelfTest
- 「OOM.保护名单读写」：写临时 conf → 读回 → 归一化一致；默认值含 operit。
- 「OOM.安全钳制」：验证 `clampOom(requested)` 在 -1000 请求下返回 -900。

## 安全边界
- 只调整**用户选择**的应用进程；永不触碰 init/system_server/zygote 等（这些不在用户列表且钳制保护）。
- 仅"提升"到不高于 -900；还原逻辑确保关闭后不留副作用。
- 所有写 `/proc/<pid>/oom_score_adj` 经 `ProcessUtils.writeFile`（File→su 兜底）。

# WebUI 电源页读数补全 + 清理页文案专业化

> 任务来源：用户反馈「WebUI 电源页面电池读数很多缺失；清理页描述不准确，
> 需专业化，去掉『实测……』等开发/测试阶段字样」。
> 状态：实现完成，静态 + 无头运行时校验均通过（详见下文）。

## 1. 问题定位

### 1.1 电源页读数缺失（根因）

- 后端 `/api/powerstatus`（`HttpBackend.apiPowerStatus`）**只返回 4 个字段**：
  `level / status / plugged / charging`，且 `readBatteryInt` 逐字段调用
  `dumpsys battery`（同一份文本被解析 3 遍）。
- 前端电源页却有 **6 个读数格**：电量 / 充电状态 / 屏幕状态 / 当前等级 /
  策略应用 / fail-open，其中 **4 格永久硬编码为 “—”**（`refreshPowerStatus`
  里直接 `set("#stScreen","—")` 等）。
- 前端判断充电用了 `status==2||5`，但真机 `plugged` 常为 `-1` 且 OEM
  `dumpsys battery` 不含 `plugged:` 行（用 `AC/USB/Wireless/Dock powered`
  布尔行代替）→ 充电状态判定同样不可靠。
- 结论：**不是数据不存在，而是后端从未把可用数据暴露出来**。
  `core/power/PowerOptimizer.snapshot()` 本已提供 屏幕状态/策略等级/最近动作/
  fail-open 计数等全部字段，但 WebUI 拿不到运行实例。

### 1.2 清理页文案不专业（根因）

- 规则说明文案集中在 `RubbishRuleSet.kt` 的 `CleanRule.note`，前端
  `renderCleanCell` 以 `textContent` 原样渲染 → 文案里的 Markdown `**` 会
  字面显示。
- 多处残留开发/测试阶段措辞：`实测 59 个…`、`实测占 1GB+`、`实测 74 个 45MB`、
  `实测约 80MB`、`（真机事故教训）`、`**运行时逐个校验…**` 等。

## 2. 实施方案

### 2.1 后端（HttpBackend.kt + PowerOptimizer.kt）

1. `PowerOptimizer` 新增 **静态活动实例注册表**（`companion object` +
   `@Volatile live`），在既有 `onStart()` 内 `attach(this)`、`onStop()` 内
   `detach(this)`，供 HTTP 后端线程读取运行中快照，不改动 DaemonLoop 语义。
2. 重写 `apiPowerStatus()`，**一次** `dumpsys battery` 解析出结构化 `BatteryFacts`
   （level/scale/status/plugged/health/present/temperature/voltage/currentNow），
   再合并 `PowerOptimizer.live()?.snapshot()`（子系统/策略/fail-open/计数…），
   额外派生稳定的人类可读字段：`statusText / pluggedText / healthText /
   policyLevelText / levelPercent`。
3. **OEM 兼容**：
   - `plugged` 缺失时由 `AC/USB/Wireless/Dock powered` 布尔行合成位掩码；
   - 温度回退 `PhoneTemp`、电流回退 `Battery current`（OPLUS 实测格式）。
4. 所有字段恒定输出（缺值给哨兵），前端渲染稳定。

### 2.2 前端（webroot/index.html）

1. 电源页拆为两块面板：**「电池与实时状态」8 格** + **「省电策略运行状态」8 格**，
   另加一行「最近动作」。
2. 重写 `refreshPowerStatus()`：按新字段渲染全部格子，统一占位处理；
   子系统未运行时策略类格子显示 “—”，电池读数仍照常显示。
3. 清理页：页脚去掉内部类名 `RubbishGuard`，`避免删文件异常` → 专业表述。

### 2.3 清理文案（RubbishRuleSet.kt）

逐条改写 `note`：去 `**`、去「实测/真机」、去易漂移的具体体积数字，
改为**面向功能与影响**的稳定描述。

## 3. 验证

| 验证 | 方法 | 结果 |
| --- | --- | --- |
| 后端编译 | `/opt/build.sh`（JDK21 + gradle 9.3.1） | BUILD SUCCESSFUL |
| 前端静态 | `node --check`（内联脚本）+ id 引用交叉校验 | 语法 OK；42 refs 全在 |
| 前端运行时（电源） | `linkedom` 执行真实 JS + mock 新 `powerstatus` | **22/22 PASS** |
| 前端运行时（清理） | 既有 `clean_harness.mjs` | **15/15 PASS** |
| 前端结构 | 既有 `harness2.mjs` | 13/13 PASS |
| 真机端到端 | 部署新 dex + 重启 daemon + `curl /api/powerstatus` | 见 AGENT_CONTEXT 记录 |

## 4. 产物

- 工程源码：`app/src/main/java/.../core/HttpBackend.kt`、`core/power/PowerOptimizer.kt`、
  `core/rubbish/RubbishRuleSet.kt`
- WebUI：母版 `webroot/index.html`（+ 模块目录同名文件），随 `Main.dex` 一起进模块包。

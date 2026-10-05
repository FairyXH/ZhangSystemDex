# 事故：清理页「扫描」虚高到 34.3GB（listOnly 规则污染可清理总量）

- 日期：2026-10-05
- 现象：WebUI 清理页点击「扫描」后，概览显示「可清理 28382 个文件 · 34.3GB」。
  实际上其中 33.1GB 来自一条**仅列出不删**的信息型规则。
- 影响：误导用户以为有 30+GB 可回收空间，实际可清理量约 1.8GB。

## 根因

`RubbishCleaner.scan()` 在汇总时把**所有**选中规则的 `files`/`bytes`
无差别累加进 `Summary.totalFiles/totalBytes`，未区分 listOnly。

规则表里存在 `listOnly = true` 的「仅列出」规则：

| 规则 | listOnly | 扫描根 | 说明 |
|---|---|---|---|
| `big_files_private` | 是 | `/data/user/<u>`（整个私有目录） | >50MB 文件**仅列出不删** |
| `big_files_list` | 是 | `/data/media/<u>/Download` | 下载目录大文件仅列出 |
| `system_junk_data` | 是 | `/data/log` 等静态日志 | 只读扫描 |

其中 `big_files_private` 递归统计整个 `/data/user/<u>` 下 >50MB 的文件，
命中的都是**正常数据**（如 `com.tencent.tmgp.sgame` 游戏资源 1031MB、
微信 `MicroMsg` 数据库 668MB 等）。实测该单条规则报 **34,955,159,800 字节**。

真机复现（修复前，默认勾选规则集合）：
`TOTAL files 28382 bytes 34924859619`；
`big_files_private 260 / 33114975640`（占了约 94%）。

## 修复（2026-10-05）

### 后端 `RubbishCleaner.kt`
1. `RuleResult` 增加 `listOnly` 字段（默认 false），所有构造点透传 `rule.listOnly`。
2. `scan()` 汇总时**跳过 listOnly 规则**：`if (!rule.listOnly) { totalFiles += r.files; totalBytes += r.bytes }`。
   （`clean()` 早先已在 listOnly 分支 `continue`，本就不累加，无需改。）
3. `summaryToJson()` 输出每条规则的 `listOnly` 标志，供前端区分展示。

### 前端 `webroot/index.html`（`app/src/main/assets/webroot/index.html`）
`doCleanScan()` 渲染每条规则大小时，listOnly 规则显示为
`仅列出 N 文件 · X（不计入可清理）`。

## 验证

- 实时回归（重启 dex，POST /api/rubbish/scan 同一规则集合）：
  - 修复前 TOTAL = 34,924,859,619 B（34.9GB）
  - 修复后 TOTAL = 1,821,617,647 B（约 1.82GB）
  - `big_files_private` 仍返回 275 文件 / 34,955,159,800 B，带 `listOnly:true`，不计入 TOTAL。
- 自检 `清理.*`：12 PASS / 0 FAIL（含「仅列出规则零删除」回归守卫、审查 17/17）。
  唯一 FAIL 为既存 AppOps `WRITE_SETTINGS`（与本改动无关）。
- 产物：`Main.dex` md5 `ae15d5380cc9e5e9cff0655d17add0bf`（2574036B）；
  `index.html` md5 `ebfef0a0c10bde7b77b35c0bc5b8ec51`（89721B）。

## 教训

**汇总口径必须区分「只读/信息型」与「可回收」两类规则。**
listOnly 规则度量的是「值得人工复核的占用」，会包含大量正常数据；
任何把它们并入「可清理」总量的逻辑都是错的。今后新增 listOnly 规则无需额外处理——
汇总已按 `CleanRule.listOnly` 统一豁免。

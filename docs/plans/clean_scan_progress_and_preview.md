# 清理扫描进度 + 清理预览（2026-10-05）

## 需求
1. 扫描时显示**进度**，不要让用户干等；
2. 显示**正在扫描的位置**（当前路径）；
3. 点击「清理」前弹**预览窗**，列出将被删除的具体文件。

## 背景问题
此前 `/api/rubbish/scan` 与 `/api/rubbish/clean` 都是**同步阻塞**调用：
请求要一直等到整轮扫描/清理结束才返回，WebUI 期间无任何反馈，
既「干等」又可能触发连接超时。

## 实现

### 后端
- 新增 `RubbishProgress`（进程内单例）：保存当前任务状态——阶段、当前规则
  （id/name）、当前路径、规则完成数/总数、分片进度、累计命中文件数与字节、
  开始时间、最近结果 JSON。扫描线程写、HTTP 线程读，原子类型 + volatile。
- `RubbishCleaner.scan()/clean()` 在每条规则开始/结束、每个分片完成时上报进度；
  `scanJunk`/`collectFilesParallel` 的 `onProgress` 顺带上报当前分片路径；
  非深度模式逐 target 上报路径。
- 新增 `RubbishCleaner.preview(ruleIds, maxSamples)`：只读遍历，尽量返回
  **实际将被删除的具体路径**（DIR_CONTENT→递归文件；GLOB→匹配项；
  EMPTY_DIR→空目录/0 字节；OLDER_THAN→超期文件；深度模式→受害者列表；
  卸载残留→残留目录）。
- HTTP 端点：
  - `POST /api/rubbish/scan`、`POST /api/rubbish/clean`：改为**启动后台任务**，
    立即返回 `{ok,running}`；后台线程执行并更新进度，结果存 `RubbishProgress`。
  - `GET /api/rubbish/progress`：轮询进度快照。
  - `GET /api/rubbish/result`：取最近一次任务结果（Summary JSON）。
  - `POST /api/rubbish/preview`：清理预览（只读）。

### 前端（webroot/index.html = assets/webroot/index.html）
- 清理页头部新增 `#cleanProg`（`#cleanProgRule` 当前规则+命中、`#cleanProgPath` 正在扫描路径）。
- `doCleanScan()`：启动扫描 → `pollCleanProgress()` 每 600ms 轮询刷新进度条/
  当前规则/路径 → 结束后取 `/api/rubbish/result` 渲染各规则大小。
- `doCleanRun()`：先调 `/api/rubbish/preview`，弹出 `#prevModal` 预览窗
  （按规则分组列出待删文件，超出前 N 个显示「… 等共 M 个」）；
  用户确认后才 `runCleanNow()` 真正清理（同样异步 + 进度）。

## 验证（真机）
- 启动扫描立即返回；轮询可见规则推进（如 8/26）、分片进度（192/434）、
  当前路径（如 `/data/user/0/com.baidu.netdisk/cache/...`）、累计命中。
- 结果 totalBytes 正确排除 listOnly 规则（2.55GB，而非 34GB 虚高）。
- 预览：`empty_dirs`/`junk_media_apps` 列出「（空目录）」项；`app_cache` 列出具体文件。
- SelfTest 清理.* 12 PASS / 0 FAIL；全局 54 PASS / 1 FAIL（既存 AppOps）/ 2 WARN / 7 SKIP。

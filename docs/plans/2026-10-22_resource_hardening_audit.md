# 全量资源隐患审计（2026-10-22，commit b435989）

## 背景
WebUI 后端挂死（ShellExecutor 闸门无限等待）修复后，做一次**同类模式全量审计**，
覆盖 80 个源文件 / 20,908 行。目标模式：

- 无限等待（`acquire()` / `await()` / `join()` 无超时）
- 无上限分配（`ByteArray(n)` / 无界集合 / 无界线程创建）
- 锁内阻塞（`synchronized` 内 sleep / 文件 IO）

## 审计结论总表

| # | 位置 | 隐患 | 修复 | 严重度 |
|---|---|---|---|---|
| 1 | `HttpBackend.handle` | `ByteArray(contentLength)` 无上限 | `MAX_BODY_BYTES=2MB`，超限 413 | **高** |
| 2 | `HttpBackend.apiReadFile` | `readText` 无上限（实际 3 倍内存放大） | `MAX_READ_FILE_BYTES=1MB` | **高** |
| 3 | `HttpBackend.startAcceptLoop` | 每连接一线程，**无并发上限** | `MAX_CONNS=16` 软限流 | **高** |
| 4 | `ParallelScanner.scan` | `latch.await()` 无超时 | `SCAN_TIMEOUT_MS=10min` | 中 |
| 5 | `ScanCache.Index.put` | `entries` 无界增长 | `MAX_ENTRIES_PER_INDEX=20万` ≈LRU | 中 |

## 已核查为安全（无需改动）

| 位置 | 结论 |
|---|---|
| `DaemonLoop.run` | 有 try/catch + sleep，模块间异常隔离 |
| `ShizukuModule` / `AccelerometerRotationModule` | 有 `lastXxxMs` 时间窗节流，不会高频重放 |
| `SystemTuningModule` 标志位 | `runOnceDone` / `appDetailUninstalled` 均为**先置位后执行** |
| `NetTurboModule.hostapdConfigured` | 失败会重试，但周期 30s 且命令轻量，风险可控 |
| `Logger` | 有 1MB 滚动上限；`synchronized` 内含文件 IO 但仅 `log_enabled=true` 时生效 |
| `FileUtils` 系列 | 先试原生 API，失败才回落 shell；shell 有 15s 默认超时 |
| 所有流操作 | 均已用 `use{}`，无句柄泄漏 |
| `Main.kt` 主循环 | 60000ms sleep + 异常兜底，健康 |

## 设计取舍（重要）

**所有限流都选择「降级为略慢」而非「功能失效」**：

- 请求体超限：明确 413，不静默丢弃
- 读文件超限：返回可读的错误提示，引导用户去终端
- 并发超限：直接关连接（不排队 → 不占内存），accept 循环不受阻
- 扫描超时：按**已收集结果**收尾，不强行 kill 线程（避免加剧内存压力）
- 缓存条目超限：只淘汰文件级增量条目 → 下次重新判定，**正确性不变**，
  分片级聚合（主要加速路径）完整保留

## 真机验证

| 项 | 结果 |
|---|---|
| clean 编译 | 0 error |
| 7 个只读 API | 全 200，overview 84ms / powerstatus 4ms |
| 写接口 `/api/switch/set` | 200 `{"ok":true}` |
| **超限请求体 3MB** | **HTTP 413，daemon 存活** |
| **30 并发压力** | 全部 200，线程数稳定 39（未膨胀） |
| 扫描 preview | 正常返回 6 个空目录 |
| 内存 RSS（40s 采样） | 142MB → 118MB → 110MB → 110MB（稳定，无增长） |

## 内存约束（后续改动务必遵守）

设备实测：`MemTotal 11.5GB / MemAvailable 仅 2.3GB`，**daemon 堆上限 512MB**。
设备内存长期吃紧（历史事故：Swap 7.5GB 用掉 6.3GB 导致 system_server Watchdog）。

因此任何新增代码都必须满足：
1. **不做无上限分配**（数组/集合/字符串拼接都要有界）；
2. **不无限等待**（`acquire`/`await`/`join` 必须带超时）；
3. **不在锁内做 IO 或 sleep**；
4. 大对象用完及时置空，长生命周期单例**不得**持有大集合。

## 仍存在的已知风险（未改，需权衡）

- `Logger` 在 `synchronized(lock)` 内做同步文件 IO：`log_enabled=true` 时
  高并发日志会成为瓶颈。若后续开启日志排障，注意此点。
- `PowerManagerModule.applyDozeList` 仍可能连发 N 条 whitelist 命令
  （已有 `added % 8 == 0` 的 60ms 限速），在 doze.conf 条目很多时会占闸门较久；
  建议后续考虑先用 `dumpsys deviceidle whitelist` 差集计算再批量下发（当前已做差集）。

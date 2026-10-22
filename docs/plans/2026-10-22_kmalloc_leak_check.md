# kmalloc 内核内存泄漏排查报告

- 日期：2026-10-22
- 设备：OnePlus PKG110 / Android 15 / ColorOS V15.0.0
- 观察窗口：开机 121s ~ 3041s（约 50 分钟）
- 方法：`/proc/slabinfo` 多轮采样 + `/proc/meminfo` 不可回收内存趋势

## 结论

**未发现 kmalloc 内核内存泄漏。**

判定依据：**不可回收内存（SUnreclaim）在 50 分钟观察期内稳定不增长，反而微降。**

## 一、关键指标趋势

| 时间点 | SUnreclaim | kmalloc-4k (active) | MemAvailable |
|---|---|---|---|
| T0 | 608,280 kB | 18,882 | — |
| T+3min | 607,268 kB（**-1012**） | 24,057（+5175） | — |
| T+6min | 607,004 kB | 24,037 | — |
| T+10min | 607,544 kB | 24,457 | 2,082,828 kB |
| T+17min | **607,048 kB（-496）** | 24,197（+131） | — |
| T+50min | **606,380 kB** | 24,153 | **2,336,692 kB** |

**核心事实**：`SUnreclaim` 全程在 606~608 MB 之间小幅波动，
50 分钟内**净减少 1.9 MB**。真正的泄漏会让这条曲线单调上升。

`MemAvailable` 从 2.08 GB 回升到 2.33 GB，`Slab` 总量从 1017 MB 降到 877 MB，
说明内核内存在**主动回收**，与泄漏特征相反。

## 二、kmalloc-4k 高占用的真实原因

初始观察中 `kmalloc-4k` 曾增长 5175 个对象（约 20 MB），一度可疑。
经交叉验证，确认为**网络 sk_buff 缓冲**，不是泄漏：

```
skbuff_head_cache  active=23912  total=24192
kmalloc-4k         active=24153  total=24183
```

两者数量**相差仅 1%**，高度吻合，说明 4k slab 的占用主要被
网络收发缓冲占据。

对应的业务背景（同一时刻采样）：

```
TIME-WAIT 连接数：1805（后续回落至 1635）
ESTAB：22 / LISTEN：16
本地接口：rmnet_data2（移动数据）
远端端口：16616~16648、443 等
```

大量短连接产生 sk_buff 分配属**正常网络行为**，
TIME-WAIT 到期后会自动释放。

## 三、排除的其他可能

| 检查项 | 结果 | 说明 |
|---|---|---|
| `drop_caches` 后是否下降 | 24067 → 24037（几乎不变） | 说明不是 page cache / 文件系统缓存 |
| dentry / inode_cache | 125079 / 86188 | 属可回收的 FS 元数据，非 kmalloc |
| 活跃/总量比（kmalloc-4k） | 24153/24183 = **99.9%** | 无大量闲置 slab，不存在碎片浪费 |
| dmesg 内存告警 | **0 条** | 无 allocation failure / OOM / slab error |
| kmemleak | 内核未编译该特性 | 商用内核常态，无法使用 |

## 四、需要区分的两种"增长"

1. **可回收增长**（正常）：sk_buff、dentry、inode 等随业务波动，
   回收后回落。本次观察到的 `kmalloc-4k` 阶段波动属此类。
2. **不可回收泄漏**（异常）：`SUnreclaim` 单调上升且不回落。

本次属于第 1 种。判定泄漏必须看 `SUnreclaim`，只看单个 kmalloc-N
的瞬时增量容易误判。

## 五、复现检查命令

```sh
# 1) 不可回收内存趋势（核心指标）
grep SUnreclaim /proc/meminfo

# 2) 具体 kmalloc 用量（按对象真实占用算 MB）
grep '^kmalloc-4k ' /proc/slabinfo | \
  awk '{printf "%s active=%s total=%s mem=%sMB\n", $1,$2,$3,($3*$4)/1048576}'

# 3) 与网络缓冲交叉验证
grep -E '^skbuff_head_cache|^kmalloc-4k' /proc/slabinfo

# 4) 连接状态
ss -s
ss -tan | awk 'NR>1{print $1}' | sort | uniq -c | sort -rn
```

**判读要点**：连续采样 10~30 分钟，
若 `SUnreclaim` 单调上涨且 `MemAvailable` 持续下降 → 疑似泄漏；
若只是某个 kmalloc-N 波动而 `SUnreclaim` 平稳 → 正常。
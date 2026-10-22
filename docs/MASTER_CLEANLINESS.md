# 母版（ZhangProtect-Android）清洁规范

- 日期：2026-10-22
- 母版路径：`/data/media/0/Download/Files/ZhangProtect-Android/`
- 状态：已按本规范清理完毕，可作发布基线

## 一、母版硬约束

1. **母版必须干净**：只保留发布所需内容，不得包含任何运行期产物。
2. **`system/` 只允许存在 `app/`**，其余子目录一律视为污染。
3. `Main.dex` 必须与最新源码构建产物和已安装模块一致。

## 二、污染是怎么产生的（根因）

`AppManagerModule.copyMount()` 会把模块根目录下除 `system`、`sqlite_lib`、
`META-INF`、`ZhangSetting`、`bin`、`adbtools` 之外的所有子目录，
**递归复制进 `system/`**。

因此在母版目录里跑过一次 dex，就会把 `tools/`、`webroot/` 复制成
`system/tools/`、`system/webroot/`，形成与根目录完全重复的副本。

更早的版本还会把 `product`、`system_ext` 一起复制进来，而这两个目录
在 `system/app` 之外的 APK 是**空文件占位**（0 字节），
看起来像 APK 实际打不开，属于典型污染残留。

> 源码里 `AppManagerModule.kt` 已有注释明确写：
> `product -> ./system/product must never be treated as a source`
> 说明这个问题在代码侧已被认知，但仍需在母版侧定期自查。

## 三、本次清理内容

| 路径 | 性质 | 处理 |
|---|---|---|
| `system/product/` | 污染（内含 3 个 0 字节 APK） | 已删除 |
| `system/system_ext/` | 污染（内含 2 个 0 字节 APK） | 已删除 |
| `system/tools/` | 污染（与根 `tools/` 逐文件 md5 相同） | 已删除 |
| `system/webroot/` | 污染（与根 `webroot/` 逐文件 md5 相同） | 已删除 |
| `system/app/` | 正常，50 个 APK | **保留** |
| `Main.dex` | 旧版 `2f764b9f...` | 已换为新版 `1d601606...` |

清理后 `system/` 只剩 `app/`，0 字节文件数为 0。

## 四、干净状态检查清单

打包或发布前，逐项确认：

```sh
M=/data/media/0/Download/Files/ZhangProtect-Android

# 1) system 下只能有 app
ls "$M/system"                     # 期望输出：只有 app

# 2) 不能有 0 字节文件
find "$M/system" -type f -size 0 | wc -l    # 期望 0

# 3) app 目录数 == APK 文件数
find "$M/system/app" -mindepth 1 -maxdepth 1 -type d | wc -l   # 期望 50
find "$M/system/app" -type f | wc -l                          # 期望 50

# 4) dex 与已安装模块一致
md5sum "$M/Main.dex" /data/adb/modules/Zhang/Main.dex

# 5) app 内容与已安装模块一致
#    逐个包对比 md5，期望 一致=50 不一致=0

# 6) 顶层必需文件齐全
#    module.prop install.sh service.sh post-fs-data.sh system.prop
#    config.conf pack.sh uninstall.sh META-INF/ tools/ webroot/
```

## 五、注意

- `system/app/ac.no.screenshot/ac.no.screenshot.Apk` 后缀是**大写 `.Apk`**，
  这是上游原始命名，已安装模块同样如此，不算污染，也不要"修正"
  （Android 不依赖扩展名识别 APK）。
- 母版不应出现 `data/`、`docs/`（运行期目录）、`Main.dex.bak.*`
  （手工备份）等条目。
- 在母版目录内**直接运行 dex 会再次产生污染**。需要测 dex 请用已安装模块目录，
  或运行后按第 4 节清单复查并清理。

## 六、顶层 `system_ext/`、`product/` 也不该出现在母版

这一点容易混淆，需要单独说明：

- **`system/system_ext/`、`system/product/`** 是 `copyMount()` 复制进 `system/`
  的运行期产物 → 母版里**不应存在**（已在第 3 节删除）。
- **顶层的 `system_ext/`、`product/`**（与 `system/` 同级）同样是
  **dex 运行过程中自动创建的**，属于运行期产物 → 母版里**不应存在**。

> 用户确认（2026-10-22）：`system_ext` 是 dex 运行过程中自动创建的，
> 不应该默认存在于母版中。

所以"干净母版"的顶层目录白名单是：

```
META-INF  system  sqlite_lib  tools  webroot  ZhangSetting  docs
```

外加若干 `.sh` / `.md` / `module.prop` / `config.conf` / `system.prop` /
`Main.dex` / `aapt` / `shfmt` / `Python.zip` 等文件。

出现 `system_ext/`、`product/`、`data/` 说明母版被 dex 跑过，需按第 3 节清理。

## 七、pack.sh 已内置清理与校验（v5）

2026-10-22 起，`pack.sh` 会在打包前自动完成两件事，**不再依赖人工检查**：

### 步骤 4/6：资源检查与清理（自动删污染）

| 清理对象 | 说明 |
|---|---|
| `system/` 下除 `app/` 以外的子目录 | `copyMount()` 复制产物（product/system_ext/tools/webroot…） |
| 顶层 `system_ext/`、`product/`、`data/` | dex 运行期自动创建 |
| `Main.dex.bak*`、`Main.dex.old` | 手工备份残留 |
| `system/` 下任何 0 字节文件 | 典型污染占位 |

发现即删除，并打印 `[-] 已清理: xxx`。全部干净时输出 `[+] 无需清理，母版已干净`。

> 设 `KEEP_DIRTY=1 sh pack.sh` 可跳过清理、仅做检查（排查用）。

### 步骤 5/6：APK 有效性校验（不合格直接中止）

由 `tools/apkcheck.py` 完成，`system/app` 下**每个包目录**必须满足：

1. 目录内恰好 1 个 `.apk` / `.Apk` 文件
2. 文件大小 > 0
3. 能作为 ZIP 打开（PK 魔数 + 中央目录完整）
4. 含 `AndroidManifest.xml`
5. 含至少一个 `.dex`
6. 逐条目 CRC 校验通过（检测截断/损坏）

任一项不合格 → 打印具体原因并 **`exit 1` 拒绝打包**。

### 实测效果

构造污染场景验证（`system/product`、`system/tools`、顶层 `system_ext/`、
`data/`、`Main.dex.bak`、0 字节 APK 各一份）：

```
[-] 清理: system/product
[-] 清理: system/tools
[-] 清理: system_ext/
[-] 清理: data/
[-] 清理: Main.dex.bak
[+] 共处理 5 项
  → system/ 只剩 app，合法目录（tools/webroot）完整保留

注入 0 字节 APK 后校验：
  [!] 以下条目不合格：
      bad.pkg      文件大小为 0（污染占位）
```

## 八、当前基线

- `Main.dex` = `1d60160698b02dcfc941d3ce1ced25c6`（2,820,800 字节）
- `system/app` = 50 个 APK，与已安装模块 100% 一致
- 母版总体积 ≈ 696M（其中 `system/app` 556M）
- 发布日期：2026-10-22

### 发布产物

| 项 | 值 |
|---|---|
| 路径 | `/data/media/0/Download/Files/ZhangProtect-Android.zip` |
| 大小 | 514,862,432 字节 |
| md5 | `a741691ebe895f1c3cafa5bffdee25a1` |
| 打包文件数 | 112 个文件（新增 `tools/apkcheck.py`） |
| SHA256 逐文件校验 | 112/112 全部一致 |
| 包内 `Main.dex` | `1d60160698b02dcfc941d3ce1ced25c6` |
| 包内 `install.sh` `SKIPMOUNT=false` | ✅ |
| 包内 `system/app` APK | 50 个，全部通过 apkcheck |
| 包内污染目录 | 0 |

### 本次打包前的完整检查记录

| 检查项 | 结果 |
|---|---|
| 母版顶层条目 | 37 |
| `system/` 子项 | 只有 `app`（1 个） |
| `system/app` 目录 / 文件 | 50 / 50 |
| 0 字节文件（全母版） | **0** |
| `system/app` 逐包异常 | **0** |
| 母版 vs 已安装 逐包 md5 | **一致 50 / 不一致 0** |
| `webroot/index.html` | `c796394b...` 与已安装一致 |
| `webroot/config.json` | `d01d5fc7...` 与已安装一致 |
| 运行期污染（system_ext/product/data/bak） | 均**不存在** |
| `Main.dex` 标记 | HttpBackend、ensureBaseDirs、SelfOomGuardModule 齐全 |

# 内置 Python 运行时（Python.zip）

本目录**不含** `Python.zip`（87 MB，见 `.gitignore`），但**打包时必需**。

## 为什么

Zhang 模块内置了 Python 运行时，用于：
- `pack.sh` 打包与完整性校验（`tools/zippack.py` / `tools/zipcheck.py`）；
- 后续可能接入的其它 Python 脚本能力。

运行时**不依赖系统 python3**：Android 系统通常没有 python3，
本设备上的 `/system/bin/python3` 实际来自独立的 `PythonforAndroid` 模块。

## 放在哪里

把运行时包放到**母版目录**（不是工程目录）：

```
/data/media/0/Download/Files/ZhangProtect-Android/Python.zip
```

`pack.sh` 会把母版目录整个打成 zip，因此该文件会随模块分发。

## 从哪里来

**必须使用「内层运行时包」**，而不是 `Python_for_Android-*.zip` 模块包本身
（后者是模块外壳，里面嵌套着真正的 `Python.zip`）：

| 来源 | 说明 |
|---|---|
| `/data/adb/modules/PythonforAndroid/Python.zip` | ✅ 正确（22 项顶层为 `Python/...`） |
| `Python_for_Android-3.13.5.zip` 解压后的 `Python.zip` | ✅ 正确（同上） |
| `Python_for_Android-3.13.5.zip` 本身 | ❌ 错误（是模块包，解压会得到 module.prop/META-INF 等） |

**快速判别**：`unzip -l Python.zip | grep 'bin/python3.13'` 有输出即为正确包。

当前使用版本：**Python 3.13.5**（md5 `921bc3c63b36919de907f80d374c9a7c`，
8751 个文件 / 解压约 270 MB）。

## 运行时如何释放

`service.sh` 在开机时检查 `/data/Python`，缺失则：

```sh
unzip -o "$MODDIR/Python.zip" -d /data     # → /data/Python
chmod -R 755 /data/Python
```

统一入口为 `tools/python3`（设置 `PYTHONHOME` / `LD_LIBRARY_PATH` 后 exec），
**不要直接调用** `/data/Python/bin/python3.13`——它依赖 `libpython3.13.so`，
不设 `LD_LIBRARY_PATH` 会报 `CANNOT LINK EXECUTABLE`。

## 与独立 Python 模块的关系

两者释放位置相同（`/data/Python`）、内容同源，因此可以共存：

- 若 `PythonforAndroid` 模块已安装并解压，`service.sh` 检测到
  `/data/Python/bin/python3.13` 存在即跳过，零开销；
- 若未安装，Zhang 模块的 `service.sh` 会自行解压，功能完整。

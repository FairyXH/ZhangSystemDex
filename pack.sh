#!/system/bin/sh
# =============================================================
# pack.sh —— ZhangProtect-Android 打包 + SHA256 完整性自检脚本
#
# 用法:  sh pack.sh
#        （可在 MT 管理器中直接运行；无需电脑、无需安装 zip）
#
# 设计目标（v3，2026-10-03 改造）：
#   * **模块自包含**：打包/校验所需的工具全部放在模块目录 tools/ 中，
#     与 aapt / shfmt 采用同样的「随模块分发」策略；
#   * **零系统依赖**：tools/ 内为纯 Python 实现（zippack.py / zipcheck.py），
#     不依赖 Android 上罕见的 `zip` 命令，也不依赖 `unzip` 二进制；
#   * 只要求系统提供 python3（Android 10+ 默认内置 /system/bin/python3）。
#     若系统确实没有 python3，可从 https://github.com/termux 或
#     模块仓库补入 tools/python3 后再运行。
#   * 保留 v1 全部安全行为：前置检查、最多 3 次重试、SHA256 逐文件比对。
#
# 行为:
#   0. 【前置检查】必须存在 webroot/index.html + webroot/config.json + Main.dex，
#      且 Main.dex 必须包含 HttpBackend 标记（否则说明是旧 dex，拒绝打包）；
#   1. 打包「脚本所在目录的所有文件」，**保留 Unix 权限位**（755/644 原样写入）；
#   2. 产物输出到 上层目录/ZhangProtect-Android.zip；
#   3. 解压回工作区，按 SHA256 逐文件比对，校验完全一致；
#   4. 校验不通过自动重跑，最多 3 次。
#
# 退出码: 0=成功  1=前置检查失败或三次校验均失败  2=缺少 python3 运行时
# =============================================================

# ---- 统一以脚本所在目录为基准（不受调用位置影响）----
SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$SRC/.." && pwd)/ZhangProtect-Android.zip"
WORK="$(mktemp -d 2>/dev/null || echo /data/local/tmp/packwork.$$)"
mkdir -p "$WORK"
MAX_RETRY=3

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM HUP

echo "== 源码目录(SRC): $SRC"
echo "== 输出产物(OUT): $OUT"
echo "== 工作区(WORK): $WORK"
echo

# -------------------------------------------------------------
# 探测 python3：优先模块自带 tools/，其次 PATH，最后系统常见路径。
# （与模块内 aapt 一样，先把依赖在模块目录里找一遍。）
# -------------------------------------------------------------
find_python() {
    # 1) 模块自带 tools/python3（若将来补入独立二进制）
    if [ -x "$SRC/tools/python3" ]; then
        echo "$SRC/tools/python3"; return 0
    fi
    # 2) tool 脚本自带 shebang 指向的解释器（zippack.py 用 /system/bin/python3）
    if [ -x /system/bin/python3 ]; then
        echo /system/bin/python3; return 0
    fi
    # 3) PATH
    p="$(command -v python3 2>/dev/null)"
    if [ -n "$p" ] && [ -x "$p" ]; then echo "$p"; return 0; fi
    # 4) 其它常见位置
    for cand in /system/xbin/python3 /data/Python/bin/python3 /data/debian/usr/bin/python3; do
        if [ -x "$cand" ]; then echo "$cand"; return 0; fi
    done
    return 1
}

PY="$(find_python)"
if [ -z "$PY" ]; then
    echo "[!] 未找到 python3 —— 打包与校验都依赖它。"
    echo "    Android 10+ 通常自带 /system/bin/python3；"
    echo "    若确实没有，可把独立 python3 放入 $SRC/tools/python3 后重试。"
    exit 2
fi
echo "[+] 运行时: python3 = $PY"

# -------------------------------------------------------------
# 自带工具定位（tools/ 随模块分发；缺失则视为损坏，直接报错）
# -------------------------------------------------------------
ZIPPACK="$SRC/tools/zippack.py"
ZIPCHECK="$SRC/tools/zipcheck.py"

if [ ! -f "$ZIPPACK" ]; then
    echo "[!] 缺少模块自带工具: tools/zippack.py"
    exit 1
fi
if [ ! -f "$ZIPCHECK" ]; then
    echo "[!] 缺少模块自带工具: tools/zipcheck.py"
    exit 1
fi
echo "[+] 自带工具: zippack.py / zipcheck.py"
echo

# -------------------------------------------------------------
# 前置检查(0): 防止再次打出「无 WebUI / 旧 dex」的坏包
# -------------------------------------------------------------
precheck() {
    rc=0
    if [ ! -f "$SRC/webroot/index.html" ]; then
        echo "[!] 前置检查失败: 缺少 webroot/index.html（WebUI 入口，KernelSU 无处渲染）"
        rc=1
    fi
    if [ ! -f "$SRC/webroot/config.json" ]; then
        echo "[!] 前置检查失败: 缺少 webroot/config.json（WebUI X 宿主清单）"
        rc=1
    fi
    if [ ! -f "$SRC/Main.dex" ]; then
        echo "[!] 前置检查失败: 缺少 Main.dex"
        rc=1
    elif ! grep -aq 'HttpBackend' "$SRC/Main.dex"; then
        echo "[!] 前置检查失败: Main.dex 不含 HttpBackend 标记（疑似旧 dex），WebUI 将无数据后端"
        rc=1
    fi
    if [ "$rc" -ne 0 ]; then
        return 1
    fi
    echo "[+] 前置检查通过: webroot/index.html + webroot/config.json + 含 HttpBackend 的 Main.dex 均就绪"
    return 0
}

precheck || exit 1
echo

# -------------------------------------------------------------
# 主流程：最多重试 MAX_RETRY 次
# -------------------------------------------------------------
retry=0
while [ "$retry" -lt "$MAX_RETRY" ]; do
    retry=$((retry + 1))
    echo "############ 第 $retry 次尝试 / 共 $MAX_RETRY 次 ############"

    rm -f "$OUT"

    # 1) 打包到临时文件（tools/zippack.py，保留权限位）
    TMPZIP="$WORK/ZhangProtect-Android.zip"
    rm -f "$TMPZIP"
    if ! "$PY" "$ZIPPACK" "$SRC" "$TMPZIP"; then
        echo "[!] 打包失败，重试..."
        continue
    fi
    if [ ! -f "$TMPZIP" ]; then
        echo "[!] 未生成 zip，重试..."
        continue
    fi

    # 2) 移动到目标位置（成功后才落盘，避免半成品）
    if ! mv -f "$TMPZIP" "$OUT"; then
        echo "[!] 产物移动到 $OUT 失败，重试..."
        continue
    fi
    echo "[+] 打包完成: $OUT ($(wc -c < "$OUT") 字节)"

    # 3) 解压 + SHA256 逐文件校验（tools/zipcheck.py）
    if "$PY" "$ZIPCHECK" "$OUT" "$SRC" "$WORK/unzip"; then
        echo "############ 成功 ############"
        exit 0
    fi
    echo "[!] 将在清理后重试..."
done

echo "############ 失败: $MAX_RETRY 次校验均未通过 ############"
exit 1
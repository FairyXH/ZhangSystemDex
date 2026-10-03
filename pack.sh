#!/system/bin/sh
# =============================================================
# pack.sh —— ZhangProtect-Android 打包 + SHA256 完整性自检脚本
#
# 用法:  sh pack.sh
#        （可在 MT 管理器中直接运行；无需电脑、无需安装 zip）
#
# 设计目标（v4，2026-10-03 改造）：
#   * **模块自包含**：打包/校验所需的工具全部放在模块目录 tools/ 中，
#     与 aapt / shfmt 采用同样的「随模块分发」策略；
#   * **零系统依赖**：tools/ 内为纯 Python 实现（zippack.py / zipcheck.py），
#     不依赖 Android 上罕见的 `zip` 命令，也不依赖 `unzip` 二进制；
#   * **全程进度可见**：打包/解压/SHA256 校验每个阶段都持续打印进度
#     （阶段标题 + 百分比 + 文件数 + 字节数 + 耗时），并在每条 echo 后
#     立即输出，用户不会「干等结果」。Python 以 -u 无缓冲运行。
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

# 统一的分隔线/步骤函数，保证输出节奏清晰、实时。
LINE="============================================================"
step() { echo; echo "$LINE"; echo "== $*"; echo "$LINE"; }

start_ts=$(date +%s 2>/dev/null || echo 0)
elapsed() {
    now=$(date +%s 2>/dev/null || echo 0)
    if [ "$start_ts" -gt 0 ] 2>/dev/null && [ "$now" -ge "$start_ts" ] 2>/dev/null; then
        echo "$((now - start_ts))s"
    else
        echo "?"
    fi
}

step "ZhangProtect-Android 打包 (pack.sh v4)"
echo "== 源码目录(SRC): $SRC"
echo "== 输出产物(OUT): $OUT"
echo "== 工作区(WORK): $WORK"
echo "== 开始时间: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)"

# -------------------------------------------------------------
# 探测 python3：本模块已内置 Python 运行时（Python.zip → /data/Python），
# 统一通过 tools/python3 入口调用（它会设置 PYTHONHOME / LD_LIBRARY_PATH，
# 这一步是必须的：/data/Python/bin/python3.13 依赖 libpython3.13.so，
# 不设 LD_LIBRARY_PATH 会报 "CANNOT LINK EXECUTABLE"）。
# -------------------------------------------------------------
find_python() {
    # 1) 模块自带 tools/python3（统一入口，首选）
    #    注意：模块位于 /data/media（FUSE 挂载），文件可能没有执行位，
    #    因此这里用 `-f` 判断存在性，再用 `sh` 显式调用。
    if [ -f "$SRC/tools/python3" ]; then
        echo "sh:$SRC/tools/python3"; return 0
    fi
    # 2) 模块内置运行时的**包装**（动态生成一个，保证环境变量正确）
    if [ -f /data/Python/bin/python3.13 ]; then
        WRAP="$WORK/python3"
        {
            echo '#!/system/bin/sh'
            echo 'PREFIX=/data/Python'
            echo 'export PYTHONHOME=$PREFIX'
            echo 'export LD_LIBRARY_PATH=$PREFIX/lib:$LD_LIBRARY_PATH'
            echo 'exec $PREFIX/bin/python3.13 "$@"'
        } >"$WRAP"
        chmod 755 "$WRAP"
        echo "$WRAP"; return 0
    fi
    # 3) 独立 Python 模块（PythonforAndroid）提供的系统包装
    if [ -f /system/bin/python3 ]; then
        echo /system/bin/python3; return 0
    fi
    # 4) PATH
    p="$(command -v python3 2>/dev/null)"
    if [ -n "$p" ] && [ -f "$p" ]; then echo "$p"; return 0; fi
    # 5) 其它常见位置
    for cand in /system/xbin/python3 /data/debian/usr/bin/python3; do
        if [ -f "$cand" ]; then echo "$cand"; return 0; fi
    done
    return 1
}

step "步骤 1/5：探测 Python 运行时"
PY_SPEC="$(find_python)"
if [ -z "$PY_SPEC" ]; then
    echo "[!] 未找到 python3 —— 打包与校验都依赖它。"
    echo "    本模块内置了 Python 运行时（Python.zip → /data/Python），"
    echo "    请确认模块已通过 service.sh 完成首次解压，或手动运行："
    echo "      sh \"$SRC/tools/python3\" -c 'print(1)'"
    exit 2
fi

# 支持 "sh:<脚本>" 形式（FUSE 下无执行位的模块自带脚本）
case "$PY_SPEC" in
sh:*)
    PY_RUN="sh"
    PY_ARG="${PY_SPEC#sh:}"
    ;;
*) PY_RUN="$PY_SPEC"; PY_ARG="" ;;
esac

echo "[+] 运行时: python3 = ${PY_ARG:-$PY_RUN}"

# 运行自检：确保 zipfile / hashlib 可用（链接正常）
echo "[+] 自检 python3（import zipfile/hashlib）..."
if ! $PY_RUN $PY_ARG -c 'import sys, zipfile, hashlib' >/dev/null 2>&1; then
    echo "[!] python3 运行时不可用（缺少 zipfile/hashlib 或链接失败）"
    exit 2
fi
echo "[+] python3 运行时自检通过"

# -------------------------------------------------------------
# 自带工具定位（tools/ 随模块分发；缺失则视为损坏，直接报错）
# -------------------------------------------------------------
step "步骤 2/5：定位模块自带工具"
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

# -------------------------------------------------------------
# 前置检查(0): 防止再次打出「无 WebUI / 旧 dex」的坏包
# -------------------------------------------------------------
step "步骤 3/5：前置检查"
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

# -------------------------------------------------------------
# 主流程：最多重试 MAX_RETRY 次
# -------------------------------------------------------------
retry=0
while [ "$retry" -lt "$MAX_RETRY" ]; do
    retry=$((retry + 1))
    step "步骤 4/5：打包（第 $retry 次尝试 / 共 $MAX_RETRY 次）"

    rm -f "$OUT"

    # 1) 打包到临时文件（tools/zippack.py，保留权限位；内部打印进度）
    TMPZIP="$WORK/ZhangProtect-Android.zip"
    rm -f "$TMPZIP"
    echo "[>] 调用 zippack.py 开始打包（下方为其实时进度）..."
    if ! $PY_RUN $PY_ARG -u "$ZIPPACK" "$SRC" "$TMPZIP"; then
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
    echo "[+] 打包完成: $OUT ($(wc -c < "$OUT") 字节，累计 $(elapsed))"

    # 3) 解压 + SHA256 逐文件校验（tools/zipcheck.py；内部打印进度）
    step "步骤 5/5：解压 + SHA256 逐文件校验（第 $retry 次尝试）"
    echo "[>] 调用 zipcheck.py 开始校验（下方为其实时进度）..."
    if $PY_RUN $PY_ARG -u "$ZIPCHECK" "$OUT" "$SRC" "$WORK/unzip"; then
        step "成功 ✅"
        echo "[+] 产物: $OUT"
        echo "[+] 大小: $(wc -c < "$OUT") 字节"
        echo "[+] 耗时: $(elapsed)"
        exit 0
    fi
    echo "[!] 校验未通过，将在清理后重试..."
done

step "失败 ❌"
echo "[!] $MAX_RETRY 次校验均未通过，请检查上方差异明细。"
echo "[!] 耗时: $(elapsed)"
exit 1
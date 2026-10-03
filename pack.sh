#!/bin/sh
# =============================================================
# pack.sh —— ZhangProtect-Android 打包 + SHA256 完整性自检脚本
#
# 用法:  sh pack.sh
#
# 行为:
#   0. 【前置检查】必须存在 webroot/index.html + webroot/config.json + Main.dex，
#      且 Main.dex 必须包含 HttpBackend 标记（否则说明是旧 dex，拒绝打包）；
#   1. 打包「当前目录(脚本所在目录)的所有文件」，无任何忽略；
#   2. 产物输出到 上层目录/ZhangProtect-Android.zip；
#   3. 按 SHA256 逐文件比对 zip 内容物与源目录，校验完全一致；
#   4. 校验不通过自动重跑，最多 3 次重试。
#
# 退出码: 0=成功  1=前置检查失败或三次校验均失败
# =============================================================

# 统一以脚本所在目录为基准，避免受调用位置影响
SRC="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$SRC/.." && pwd)/ZhangProtect-Android.zip"
WORK="$(mktemp -d)"
MAX_RETRY=3

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

echo "== 源码目录(SRC): $SRC"
echo "== 输出产物(OUT): $OUT"
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
        echo "[!] 请先运行 构建WebUI.bat 生成 webroot/，并运行 构建MainDex.bat 生成最新 Main.dex 后再打包"
        return 1
    fi
    echo "[+] 前置检查通过: webroot/index.html + webroot/config.json + 含 HttpBackend 的 Main.dex 均就绪"
    return 0
}

precheck || exit 1
echo

# -------------------------------------------------------------
# 生成源目录清单: 相对路径 + SHA256 + 文件大小(字节)
# 只统计普通文件(type f)，符号链接按链接本身处理(这里无链接)
# -------------------------------------------------------------
src_manifest() {
    cd "$SRC" || return 1
    # 用 sha256sum 计算每个文件；路径统一为相对路径
    find . -type f -print0 \
        | sort -z \
        | xargs -0 sha256sum \
        | sed 's|  \./|  |' \
        | sort
}

# -------------------------------------------------------------
# 从已解压的 zip 目录生成清单
# -------------------------------------------------------------
zip_manifest() {
    cd "$WORK/unzip" || return 1
    find . -type f -print0 \
        | sort -z \
        | xargs -0 sha256sum \
        | sed 's|  \./|  |' \
        | sort
}

retry=0
while [ "$retry" -lt "$MAX_RETRY" ]; do
    retry=$((retry + 1))
    echo "############ 第 $retry 次尝试 / 共 $MAX_RETRY 次 ############"

    # ---- 1) 清理旧产物与工作区 ----
    rm -f "$OUT"
    rm -rf "$WORK/unzip"
    mkdir -p "$WORK/unzip"

    # ---- 2) 打包全部文件(无忽略) ----
    # 在源码目录内以 "." 为根打包，保证 zip 内无二级包装文件夹。
    # 先写临时 zip, 成功后再移动到目标位置, 避免半成品残留。
    TMPZIP="$WORK/ZhangProtect-Android.zip"
    rm -f "$TMPZIP"
    ( cd "$SRC" && zip -r -y -q "$TMPZIP" . )
    zip_rc=$?
    if [ "$zip_rc" -ne 0 ]; then
        echo "[!] zip 打包失败 (rc=$zip_rc)，重试..."
        continue
    fi

    # ---- 3) 移动到上层目录 ----
    if ! mv -f "$TMPZIP" "$OUT"; then
        echo "[!] 产物移动到 $OUT 失败，重试..."
        continue
    fi
    echo "[+] 打包完成: $OUT ($(wc -c < "$OUT") 字节)"

    # ---- 4) 解压到工作区 ----
    if ! unzip -q "$OUT" -d "$WORK/unzip"; then
        echo "[!] 解压产物失败，重试..."
        continue
    fi

    # ---- 5) SHA256 完整性比对 ----
    src_manifest > "$WORK/src.sha256"
    zip_manifest > "$WORK/zip.sha256"

    if diff -u "$WORK/src.sha256" "$WORK/zip.sha256" > "$WORK/diff.txt"; then
        n_src=$(wc -l < "$WORK/src.sha256")
        n_zip=$(wc -l < "$WORK/zip.sha256")
        echo "[+] 完整性校验通过: 共 $n_src 个文件, SHA256 全部一致"
        echo "############ 成功 ############"
        exit 0
    else
        echo "[!] 完整性校验未通过! 差异如下:"
        head -50 "$WORK/diff.txt"
        echo "[!] 将在清理后重试..."
    fi
done

echo "############ 失败: $MAX_RETRY 次校验均未通过 ############"
exit 1
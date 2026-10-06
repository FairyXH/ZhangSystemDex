#!/system/bin/sh
#
# 为 system/app 与 system/priv-app 下挂载的应用补齐 native lib 目录。
#
# ## 为什么需要
#
# ZhangProtect 会把一批应用「挂载为系统应用」（放在模块的 system/app/<pkg>/）。
# 系统扫描到这种「系统应用」时，会把 `nativeLibraryDir` 解析为
#   /system/app/<pkg>/lib
# （与 /data/app/.../lib 不同，系统应用**不会**自动解压 APK 内的 lib/）。
#
# 如果只放了 APK、没放 lib/，那么该应用的任何进程在加载自己的 .so 时
# 都会失败：
#   dlopen failed: library ".../lib/arm64-v8a/xxx.so" not found
#   java.lang.UnsatisfiedLinkError
#
# 实测案例：Shizuku 被挂载后，其服务端进程加载 librish.so 失败 → 服务端
# 永远起不来 → Shizuku 显示「服务未运行」。同类问题会影响全部 25+ 个被
# 挂载的应用（GKD、Scene、HMA、单次授权、ShortX、VirtualEnv 等）。
#
# ## 本脚本做什么
#
# 遍历 <目标>/system/app/* 与 system/priv-app/*，对每个含 .apk 的目录：
#   1. 若已存在 lib/ 目录 → 跳过；
#   2. 否则从 APK 中解压 lib/<abi>/*.so 到 <dir>/lib/<abi>/；
#   3. 只处理 arm64-v8a 与 armeabi-v7a（其它 ABI 设备用不到，省空间）。
#
# 幂等：可反复执行；不会覆盖已有 lib。
#
# 用法：
#   sh fix_system_app_libs.sh [目标根目录]
# 默认目标为脚本自身所在目录（母版或模块目录）。

set -u

# 脚本自身所在目录作为默认目标（母版 / 模块目录都含 system/app）
SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TARGET="${1:-$SELF_DIR}"

log() { echo "[fix-libs] $*"; }

if ! command -v unzip >/dev/null 2>&1; then
    log "错误：找不到 unzip，无法解压 APK"
    exit 1
fi

fixed=0
skipped=0

for base in "$TARGET/system/app" "$TARGET/system/priv-app"; do
    [ -d "$base" ] || continue
    for appdir in "$base"/*/; do
        [ -d "$appdir" ] || continue
        apk=$(ls "$appdir"*.apk 2>/dev/null | head -1)
        [ -n "$apk" ] || continue

        # 该 APK 里是否有 native lib？
        haslib=$(unzip -l "$apk" 2>/dev/null | grep -c 'lib/')
        [ "$haslib" -gt 0 ] || continue

        # 已有 lib 目录则跳过（幂等）
        if [ -d "$appdir/lib" ]; then
            skipped=$((skipped + 1))
            continue
        fi

        name=$(basename "$appdir")
        tmp=$(mktemp -d 2>/dev/null) || continue
        ok=1
        for abi in arm64-v8a armeabi-v7a; do
            if unzip -o -j "$apk" "lib/$abi/*" -d "$tmp/$abi" >/dev/null 2>&1; then
                # 仅当确实解出了文件才算成功
                if ls "$tmp/$abi"/*.so >/dev/null 2>&1; then
                    mkdir -p "$appdir/lib/$abi"
                    cp -f "$tmp/$abi"/*.so "$appdir/lib/$abi/" 2>/dev/null || ok=0
                fi
            fi
        done
        rm -rf "$tmp"

        if [ "$ok" = "1" ] && [ -d "$appdir/lib" ]; then
            n=$(find "$appdir/lib" -name '*.so' 2>/dev/null | wc -l)
            log "已补齐 $name ($n 个 .so)"
            fixed=$((fixed + 1))
        else
            log "跳过 $name（无可提取的 lib）"
        fi
    done
done

log "完成：补齐 $fixed 个，跳过 $skipped 个（已有 lib）"

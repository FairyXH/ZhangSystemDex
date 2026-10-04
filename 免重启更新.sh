#!/system/bin/sh
# ZhangSystemDex 免重启在线更新
# 从 GitHub main 分支下载最新 Main.dex 与 WebUI，写入「已存在的模块目录」并重启 daemon。
# 无需刷入 zip、无需重启设备。
MODDIR=$(cd "$(dirname "$0")" && pwd)
REPO="https://raw.githubusercontent.com/FairyXH/ZhangSystemDex/main"
DEX_URL="${REPO}/Main.dex"
UI_URL="${REPO}/app/src/main/assets/webroot/index.html"
CONF="/data/adb/Zhang"
DEX="${MODDIR}/Main.dex"
UI="${MODDIR}/webroot/index.html"

CURL="/system/bin/curl"
[ -x "$CURL" ] || CURL="$(command -v curl)"
if [ -z "$CURL" ]; then echo "错误：未找到 curl"; exit 1; fi

echo "== ZhangSystemDex 免重启更新 =="
mkdir -p "${MODDIR}/webroot" "${CONF}"

# 备份
BK="${CONF}/_backup_ota_shell_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BK"
[ -f "$DEX" ] && cp -f "$DEX" "$BK/Main.dex"
[ -f "$UI" ] && cp -f "$UI" "$BK/index.html"
echo "已备份到 $BK"

# 下载 dex
echo "下载 Main.dex ..."
if ! "$CURL" -fL --connect-timeout 10 --max-time 120 --retry 2 -s -o "${DEX}.tmp" "$DEX_URL"; then
	echo "下载 Main.dex 失败"; rm -f "${DEX}.tmp"; exit 1
fi
# 校验 dex 魔数
MAGIC=$(head -c 4 "${DEX}.tmp" 2>/dev/null | od -An -tx1 | tr -d ' \n')
case "$MAGIC" in
	6465780a) : ;;
	*) echo "下载内容不是合法 dex（魔数=$MAGIC）"; rm -f "${DEX}.tmp"; exit 1 ;;
esac
mv -f "${DEX}.tmp" "$DEX"
chmod 755 "$DEX"
echo "Main.dex 更新完成 ($(wc -c < "$DEX") 字节)"

# 下载 UI
echo "下载 WebUI ..."
if "$CURL" -fL --connect-timeout 10 --max-time 120 --retry 2 -s -o "${UI}.tmp" "$UI_URL"; then
	mv -f "${UI}.tmp" "$UI"
	echo "WebUI 更新完成 ($(wc -c < "$UI") 字节)"
else
	echo "WebUI 下载失败（保留原界面）"; rm -f "${UI}.tmp"
fi

# 同步 dex 到配置根（与 service.sh 一致）
cp -f "$DEX" "${CONF}/Main.dex"
chmod 755 "${CONF}/Main.dex"

# 重启 daemon（免重启设备）
echo "重启 daemon ..."
[ -f "${MODDIR}/停止Dex.sh" ] && sh "${MODDIR}/停止Dex.sh" >/dev/null 2>&1
sleep 1
sh "${MODDIR}/service.sh" >/dev/null 2>&1
sleep 2
if [ -f "${CONF}/daemon.pid" ] && [ -d "/proc/$(cat "${CONF}/daemon.pid")" ]; then
	echo "更新完成，daemon 已重启 (pid $(cat "${CONF}/daemon.pid"))"
else
	echo "更新已写入，但 daemon 未确认启动，请查看 ${CONF}/log/daemon.log"
fi

#!/system/bin/sh
# 部署母版到已安装位置（本地升级，离线）
# 把母版（本脚本所在目录，默认 /sdcard/Download/Files/ZhangProtect-Android/）
# 全部内容覆盖到已安装模块目录 /data/adb/modules/Zhang/，然后重启 dex。
# 无需刷 zip、无需重启设备即可看到修改效果。
MODDIR="/data/adb/modules/Zhang"
CONF="/data/adb/Zhang"
SRC=$(cd "$(dirname "$0")" && pwd)

if [ ! -d "$MODDIR" ]; then echo "错误：模块未安装（$MODDIR 不存在）"; exit 1; fi
if [ ! -f "${SRC}/Main.dex" ]; then echo "错误：母版缺少 Main.dex（$SRC）"; exit 1; fi

echo "== 部署母版 -> 已安装 =="
echo "源: $SRC"
echo "目标: $MODDIR"

# 备份当前 dex/UI
BK="${CONF}/_backup_predeploy_$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BK"
[ -f "$MODDIR/Main.dex" ] && cp -f "$MODDIR/Main.dex" "$BK/"
[ -f "$MODDIR/webroot/index.html" ] && cp -f "$MODDIR/webroot/index.html" "$BK/"
echo "已备份到 $BK"

# 全量覆盖母版内容（不删除已安装位置特有文件，如 APK payload）
cd "$SRC" && tar cf - . | (cd "$MODDIR" && tar xf - )
if [ $? -ne 0 ]; then echo '错误：覆盖失败'; exit 1; fi
chmod 755 "${MODDIR}/Main.dex" 2>/dev/null
echo "母版内容已覆盖"

# 同步 dex 到配置根
cp -f "${MODDIR}/Main.dex" "${CONF}/Main.dex"
chmod 755 "${CONF}/Main.dex"
echo "Main.dex 已同步到 ${CONF}"

# 重启 dex（无需重启设备）
echo "重启 dex ..."
[ -f "${MODDIR}/停止Dex.sh" ] && sh "${MODDIR}/停止Dex.sh" >/dev/null 2>&1
sleep 2
sh "${MODDIR}/service.sh" >/dev/null 2>&1
sleep 2
if [ -f "${CONF}/daemon.pid" ] && [ -d "/proc/$(cat "${CONF}/daemon.pid")" ]; then
	echo "部署完成，dex 已重启 (pid $(cat "${CONF}/daemon.pid"))"
	echo "Main.dex md5: $(md5sum "${MODDIR}/Main.dex" 2>/dev/null | cut -d' ' -f1)"
else
	echo "部署已写入，但 dex 未确认启动，请查看 ${CONF}/log/daemon.log"
fi

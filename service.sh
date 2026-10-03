MODDIR=$(cd "$(dirname "$0")" && pwd)
zhang_config="/data/adb/Zhang"
DEX_SRC="${MODDIR}/Main.dex"
DEX_DST="${zhang_config}/Main.dex"
LOGDIR="${zhang_config}/log"
PIDFILE="${zhang_config}/daemon.pid"
NICE_NAME="zhangsystemdex"
MAIN_CLASS="io.github.fairyxh.zhangsystemdex.Main"

mkdir -p "${zhang_config}"
mkdir -p "${LOGDIR}"

# =============================================================
# 内置 Python 运行时（整合自 Python_for_Android-3.13.5 模块）
#
#   - 运行时包：${MODDIR}/Python.zip（解压后约 261MB）
#   - 释放位置：/data/Python（与独立 Python 模块一致，便于复用与排障）
#   - 入口：/data/Python/bin/python3.13（tools/python3 包装脚本会设好
#     PYTHONHOME / LD_LIBRARY_PATH 后 exec 它）
#
#   仅在 /data/Python 缺失时解压（首次开机），之后不再重复消耗 IO。
# =============================================================
PYTHON_DIR="/data/Python"
PYTHON_ZIP="${MODDIR}/Python.zip"

ensure_python() {
	[ -x "${PYTHON_DIR}/bin/python3.13" ] && return 0

	if [ ! -f "${PYTHON_ZIP}" ]; then
		echo "ZhangSystemDex: Python.zip not found (${PYTHON_ZIP}), skip Python setup"
		return 1
	fi

	# 等待 /data 就绪
	i=0
	while [ ! -d /data ] && [ "$i" -lt 30 ]; do
		sleep 1
		i=$((i + 1))
	done

	echo "ZhangSystemDex: extracting Python runtime -> ${PYTHON_DIR} ..."
	mkdir -p /data
	if unzip -o "${PYTHON_ZIP}" -d /data >/dev/null 2>&1; then
		chmod -R 755 "${PYTHON_DIR}" 2>/dev/null
		echo "ZhangSystemDex: Python runtime ready (${PYTHON_DIR})"
	else
		echo "! ZhangSystemDex: failed to extract ${PYTHON_ZIP}"
		return 1
	fi
	return 0
}

ensure_python

# 初始化 config.conf（配置根 + 日志总开关，Dex 首次运行也会兜底生成）
if [ ! -f "${MODDIR}/config.conf" ]; then
	echo "root_dir=/data/adb/Zhang" >"${MODDIR}/config.conf"
	echo "log_enabled=true" >>"${MODDIR}/config.conf"
	echo "ZhangSystemDex: config.conf initialized"
fi

# 引入 Dex：模块 Main.dex -> 配置根
if [ ! -f "${DEX_SRC}" ]; then
	echo "! Main.dex not found in module directory: ${DEX_SRC}"
	exit 1
fi
cp -f "${DEX_SRC}" "${DEX_DST}"
chmod 755 "${DEX_DST}"
echo "ZhangSystemDex: Main.dex synced to ${DEX_DST}"

# 防重复启动
if [ -f "${PIDFILE}" ]; then
	oldpid=$(cat "${PIDFILE}" 2>/dev/null)
	if [ -n "${oldpid}" ] && [ -d "/proc/${oldpid}" ]; then
		oldcmd=$(tr '\0' ' ' </proc/${oldpid}/cmdline 2>/dev/null)
		case "${oldcmd}" in
		*"${NICE_NAME}"* | *"${MAIN_CLASS}"*)
			echo "ZhangSystemDex already running (pid ${oldpid})"
			exit 0
			;;
		esac
		kill "${oldpid}" 2>/dev/null
		sleep 1
	fi
	rm -f "${PIDFILE}"
fi

# 启动 Dex daemon（终端输出 -> daemon.log，内部 Logger 同时写 zhang.log）
nohup /system/bin/app_process \
	-Djava.class.path="${DEX_DST}" \
	/system/bin \
	--nice-name="${NICE_NAME}" \
	"${MAIN_CLASS}" \
	"${MODDIR}" \
	>"${LOGDIR}/daemon.log" 2>&1 &
echo $! >"${PIDFILE}"

sleep 1
if [ -d "/proc/$(cat ${PIDFILE})" ]; then
	echo "ZhangSystemDex started, pid $(cat ${PIDFILE})"
else
	echo "! ZhangSystemDex failed to start, see ${LOGDIR}/daemon.log"
fi

[ -x "${MODDIR}/bt_offload_fix.sh" ] && "${MODDIR}/bt_offload_fix.sh" &
# 内置应用守护「可独立开关」+ OOM「可选/强制」改造

日期：2026-10-06  状态：实施中

## 需求（用户 2026-10-06 14:40）
1. 把「模块内置守护」改为**可独立开关**（不再无条件强制）。
2. **OOM 做成可选**：
   - **有「无障碍功能」的内置应用**（声明 AccessibilityService）→ **强制 OOM**。
   - **有「通知使用权」的内置应用**（声明 NotificationListenerService）→ **强制 OOM**。
   - **其余内置应用** → OOM **可选**（复选框，勾选才保护，默认不勾）。
3. 先做完 → 覆盖到已安装模块 → **复原 oom** → **免重启更新**。

## 设计
新增配置 `builtin_guard.conf`（配置根 /data/adb/Zhang），一行一个内置应用：
```
# pkg=guard,oom
com.omarea.vtools=1,1
li.songe.gkd=1,0
```
- guard：参与「内置守护」（Doze+多任务Lock+通知/无障碍保活），默认 1。
- oom：参与 OOM 保护（用户勾选），默认 0；若声明了 a11y/通知组件则**强制 true**（忽略配置）。
- 未出现的应用视为 guard=1, oom=0。

OOM 内置生效集合：
```
oomBuiltin(pkg) = hasNotifOrA11yComponent(pkg) ? true : conf.oom(pkg)
```

## 涉及文件
- 新增 core/BuiltinConfig.kt；KeepAliveList.builtinPackages→只返回 guard=1；
  OomProtectList 内置并集→BuiltinConfig.oomPackages；PowerManagerModule→guardPackages；
  OomProtectModule 关闭时→builtinOomPackages；HttpBackend 新增/扩展 builtin API；WebUI 复选框。

## 验证
bash /opt/build.sh；SelfTest；功能改动观察 Doze/ColorOS/OOM /proc；母版免重启更新。

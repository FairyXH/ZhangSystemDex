@echo off
setlocal
cd /d "%~dp0"

REM ============================================================
REM  ZhangSystemDex: 构建并提取 KernelSU/Magisk WebUI
REM  从 release APK 中取出 assets/webroot/index.html 与 config.json，
REM  释放到 webroot\（与 Main.dex 同级交付）。
REM  部署：把 webroot\ 整个目录拷贝到模块目录
REM        /data/adb/modules/Zhang/webroot/
REM  （KernelSU/WebUI X 从模块目录读取 webroot/index.html + config.json
REM    渲染模块 WebUI；缺任一文件 WebUI 都不会出现）
REM ============================================================

set "BUILD_LOG=build.log"
echo === ZhangSystemDex: building release APK ===
call gradlew.bat :app:assembleRelease --console=plain --no-daemon > "%BUILD_LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] build failed
    powershell -NoProfile -Command "Get-Content -LiteralPath '%BUILD_LOG%' -Tail 100"
    exit /b 1
)

set "APK=app\build\outputs\apk\release\app-release-unsigned.apk"
if not exist "%APK%" set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
    echo [ERROR] release APK not found
    exit /b 1
)

echo === extracting assets/webroot/index.html + config.json and verifying ===
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $apk='%APK%'; Add-Type -AssemblyName System.IO.Compression.FileSystem; $zip=[System.IO.Compression.ZipFile]::OpenRead($apk); $outDir=Join-Path (Get-Location) 'webroot'; if(-not (Test-Path $outDir)){ New-Item -ItemType Directory -Path $outDir | Out-Null }; function Extract($name){ $entry=$zip.Entries | Where-Object { $_.FullName -eq $name } | Select-Object -First 1; if(-not $entry){ $zip.Dispose(); Write-Error ($name + ' not found in APK'); exit 1 }; $leaf=Split-Path $name -Leaf; $dest=Join-Path $outDir $leaf; $out=[System.IO.File]::Create($dest); $in=$entry.Open(); $in.CopyTo($out); $in.Dispose(); $out.Dispose(); return $dest }; $html=Extract 'assets/webroot/index.html'; $json=Extract 'assets/webroot/config.json'; $zip.Dispose(); $hlen=(Get-Item $html).Length; $jlen=(Get-Item $json).Length; if($hlen -lt 1000){ Write-Error ('index.html too small: ' + $hlen); exit 1 }; if($jlen -lt 50){ Write-Error ('config.json too small: ' + $jlen); exit 1 }; $txt=[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($html)); if(-not $txt.TrimEnd().EndsWith('</html>')){ Write-Error 'index.html not properly closed (missing </html>)'; exit 1 }; if(-not $txt.Contains('KSU')){ Write-Error 'index.html missing KernelSU bridge marker (KSU)'; exit 1 }; $jtxt=[System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($json)); if(-not $jtxt.Contains('title')){ Write-Error 'config.json missing title field (WebUI X manifest)'; exit 1 }; Write-Host ('OK: ' + $html + ' (' + $hlen + ' bytes)'); Write-Host ('OK: ' + $json + ' (' + $jlen + ' bytes)')"
if errorlevel 1 (
    echo [ERROR] webui extraction/verification failed
    exit /b 1
)

echo === done: webroot\index.html + webroot\config.json generated at %~dp0webroot ===
echo deploy: copy the whole webroot\ directory to /data/adb/modules/Zhang/webroot/
endlocal

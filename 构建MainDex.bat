@echo off
setlocal
cd /d "%~dp0"

echo === ZhangSystemDex: building release APK ===
call gradlew.bat :app:assembleRelease --console=plain
if errorlevel 1 (
    echo [ERROR] build failed
    exit /b 1
)

set "APK=app\build\outputs\apk\release\app-release-unsigned.apk"
if not exist "%APK%" set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
    echo [ERROR] release APK not found
    exit /b 1
)

echo === extracting classes.dex and verifying ===
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $apk='%APK%'; Add-Type -AssemblyName System.IO.Compression.FileSystem; $zip=[System.IO.Compression.ZipFile]::OpenRead($apk); $dexes=@($zip.Entries | Where-Object { $_.Name -like '*.dex' }); if($dexes.Count -ne 1 -or $dexes[0].FullName -ne 'classes.dex'){ $zip.Dispose(); Write-Error ('bad dex set: ' + (($dexes | ForEach-Object { $_.FullName }) -join ',')); exit 1 }; $dest=Join-Path (Get-Location) 'Main.dex'; $out=[System.IO.File]::Create($dest); $in=$dexes[0].Open(); $in.CopyTo($out); $in.Dispose(); $out.Dispose(); $zip.Dispose(); $len=(Get-Item $dest).Length; if($len -lt 1000000){ Write-Error ('Main.dex too small: ' + $len); exit 1 }; $txt=[System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($dest)); if(-not $txt.Contains('SkipMountGuardModule')){ Write-Error 'dex missing SkipMountGuardModule marker, stale build?'; exit 1 }; Write-Host ('OK: ' + $dest + ' (' + $len + ' bytes)')"
if errorlevel 1 (
    echo [ERROR] dex extraction/verification failed
    exit /b 1
)

echo === done: Main.dex generated at %~dp0 ===
echo push: git add Main.dex ^&^& git push (or run push.bat)
endlocal

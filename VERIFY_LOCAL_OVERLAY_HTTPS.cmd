@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

set "PS1=%~dp0tools\overlay-https\Setup-LocalOverlayHttps.ps1"
if not exist "%PS1%" (
  echo [ERROR] Script not found:
  echo   %PS1%
  pause
  exit /b 1
)

echo ============================================
echo   Verify local HTTPS overlay
echo ============================================
echo.
echo Без UAC и без пересоздания сертификатов.
echo Основной URL: https://localhost:8766/overlay/tiktok
echo customHostsRequired=false
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -VerifyInstalled -NoPause
set ERR=%ERRORLEVEL%

echo.
echo ---- Java-side Windows-MY SSLContext ----
call "%~dp0gradlew.bat" verifyWindowsMySsl --quiet
set JAVAERR=%ERRORLEVEL%
if %JAVAERR% NEQ 0 (
  echo Java verify: FAILED
  echo sslContext=false
  if %ERR% EQU 0 set ERR=1
) else (
  echo Java verify: SUCCESS
  echo sslContext=true
)

echo.
if %ERR% EQU 0 (
  echo VERIFY: SUCCESS
  echo certificateSource=WINDOWS_MY
  echo primaryHostname=localhost
  echo customHostsRequired=false
  echo.
  echo После ручного запуска Minecraft проверьте:
  echo   Test-NetConnection localhost -Port 8766
  echo   Invoke-WebRequest -UseBasicParsing https://localhost:8766/arena/health
) else (
  echo VERIFY: FAILED
  echo При необходимости запустите SETUP_LOCAL_OVERLAY_HTTPS.cmd
  echo Нужен SAN: localhost + arena-overlay.test + 127.0.0.1 в CurrentUser\My.
)
echo.
pause
exit /b %ERR%

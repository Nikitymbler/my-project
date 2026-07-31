@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "OVERLAY_URL=https://localhost:8766/overlay/tiktok?background=chroma&preview=1"
set "HEALTH_URL=https://localhost:8766/arena/health"

echo Диагностика preview overlay...
powershell -NoProfile -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $r = Invoke-WebRequest -Uri '%HEALTH_URL%' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -ne 200) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 1 (
  echo.
  echo Локальный HTTPS overlay не отвечает.
  echo Проверьте, что Minecraft запущен и мир открыт.
  echo Если HTTPS ещё не настроен — SETUP_LOCAL_OVERLAY_HTTPS.cmd
  echo.
  pause
  exit /b 1
)

echo Открываю preview: %OVERLAY_URL%
echo Chroma key: #FF00FF
echo Для захвата окна используйте OPEN_OVERLAY_WINDOW.cmd
start "" "%OVERLAY_URL%"
exit /b 0

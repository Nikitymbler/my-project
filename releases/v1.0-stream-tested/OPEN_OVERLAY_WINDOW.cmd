@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "OVERLAY_URL=https://localhost:8766/overlay/tiktok?background=chroma"
set "HEALTH_URL=https://localhost:8766/arena/health"

echo Проверка локального HTTPS overlay...
powershell -NoProfile -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $r = Invoke-WebRequest -Uri '%HEALTH_URL%' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -ne 200) { exit 2 }; exit 0 } catch { exit 1 }"
if errorlevel 1 (
  echo.
  echo Overlay недоступен. Сначала запустите Arena of Nations и откройте мир.
  echo.
  pause
  exit /b 1
)

set "BROWSER="
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" set "BROWSER=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" set "BROWSER=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
if not defined BROWSER if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "BROWSER=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not defined BROWSER if exist "%LocalAppData%\Google\Chrome\Application\chrome.exe" set "BROWSER=%LocalAppData%\Google\Chrome\Application\chrome.exe"

if not defined BROWSER (
  echo.
  echo Не найден Microsoft Edge или Google Chrome.
  echo Установите Edge/Chrome или откройте вручную:
  echo   %OVERLAY_URL%
  echo.
  pause
  exit /b 1
)

echo Открываю окно захвата: %OVERLAY_URL%
echo Chroma key: #FF00FF
echo Браузер: %BROWSER%
start "" "%BROWSER%" --app="%OVERLAY_URL%" --window-size=1080,1920 --force-device-scale-factor=1 --high-dpi-support=1
exit /b 0

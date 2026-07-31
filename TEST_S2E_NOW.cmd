@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================
echo   Тест Arena S2E gift (без StreamToEarn)
echo ============================================
echo.

set "PROP=run\config\arena_of_nations.properties"
if not exist "%PROP%" (
  echo [FAIL] Нет файла %PROP%
  echo Зайди в мир Minecraft хотя бы раз, чтобы конфиг создался.
  goto :end
)

set "TOKEN="
for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /c:"s2e_http_token=" "%PROP%"`) do set "TOKEN=%%B"
if "%TOKEN%"=="" (
  echo [FAIL] s2e_http_token пустой в конфиге.
  goto :end
)

echo Проверка health...
curl.exe -s -m 3 http://127.0.0.1:8765/arena/health
echo.
if errorlevel 1 (
  echo [FAIL] Мост не отвечает. Открой Minecraft и зайди в мир.
  goto :end
)

echo.
echo POST gift coins=1 ...
curl.exe -s -m 5 -w "\nHTTP_CODE:%%{http_code}\n" -X POST "http://127.0.0.1:8765/arena/streamtoearn/gift" -H "Content-Type: application/json; charset=utf-8" --data "{\"token\":\"%TOKEN%\",\"viewerId\":\"testUniqueId\",\"coins\":\"1\"}"
echo.
echo.
echo Если HTTP_CODE=200 или 202 — Minecraft OK, проблема в настройке StreamToEarn Play.
echo Если HTTP_CODE=400/401 — в игре напиши /arena_s2e_status и пришли last HTTP body=
echo Если мост не отвечает — мир не открыт или мод старый (нужен git pull + build).
echo.

:end
pause

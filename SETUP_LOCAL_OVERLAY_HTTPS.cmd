@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"

set "PS1=%~dp0tools\overlay-https\Setup-LocalOverlayHttps.ps1"
set "LOG=%LOCALAPPDATA%\ArenaOfNations\overlay-https\setup.log"
set "RESULT=%LOCALAPPDATA%\ArenaOfNations\overlay-https\setup-result.json"

if not exist "%PS1%" (
  echo [ERROR] Не найден скрипт:
  echo   %PS1%
  echo Запускайте SETUP_LOCAL_OVERLAY_HTTPS.cmd из корня проекта ArenaOfNations.
  pause
  exit /b 1
)

echo ============================================
echo   Настройка локального HTTPS overlay
echo ============================================
echo.
echo Скрипт: %PS1%
echo Лог:    %LOG%
echo.
echo Сейчас может появиться запрос UAC.
echo Дождитесь второго окна и нажмите Enter в нём после итога.
echo Итог берётся из setup-result.json текущего запуска, а не только из ExitCode.
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set ERR=%ERRORLEVEL%
echo.

if %ERR% EQU 0 (
  echo ========================================
  echo Установка локального HTTPS overlay завершена успешно.
  echo ========================================
  echo Лог: %LOG%
  echo Результат: %RESULT%
  echo Адрес: https://localhost:8766/overlay/tiktok
  echo Legacy: https://arena-overlay.test:8766/overlay/tiktok
  echo.
  pause
  exit /b 0
)

echo ========================================
echo Установка не завершена.
echo ========================================
echo Код процесса: %ERR%
echo Проверьте лог и setup-result.json:
echo %LOG%
echo %RESULT%
echo.
echo Если админ-окно показало SUCCESS, но здесь FAILED — сообщите об этом.
echo Можно проверить состояние: VERIFY_LOCAL_OVERLAY_HTTPS.cmd
echo.
pause
exit /b %ERR%

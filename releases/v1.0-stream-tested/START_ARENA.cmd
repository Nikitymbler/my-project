@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================
echo   Arena of Nations — запуск
echo ============================================
echo.

where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Java не найдена в PATH.
  echo Установите Java 21 и повторите.
  goto :fail
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
  set "JAVA_VER=%%~v"
  goto :havejava
)
:havejava
echo Java: %JAVA_VER%
echo %JAVA_VER% | findstr /r "\"21\." >nul
if errorlevel 1 (
  echo [WARN] Ожидается Java 21. Найдено: %JAVA_VER%
)

set "PS1=%~dp0tools\overlay-https\Setup-LocalOverlayHttps.ps1"
set "RUNTIME=%LOCALAPPDATA%\ArenaOfNations\overlay-https"

echo.
echo Проверка локального HTTPS overlay...
if not exist "%PS1%" goto :need_setup
if not exist "%RUNTIME%\setup-result.json" goto :need_setup

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -VerifyInstalled -NoPause
if errorlevel 1 goto :need_setup

echo OK: VerifyInstalled SUCCESS
echo Overlay window capture:
echo   OPEN_OVERLAY_WINDOW.cmd
echo URL chroma:
echo   https://localhost:8766/overlay/tiktok?background=chroma
echo Chroma key:
echo   #FF00FF
echo Preview:
echo   OPEN_OVERLAY.cmd
echo Проверка: VERIFY_LOCAL_OVERLAY_HTTPS.cmd
echo Инструкция: OVERLAY_README_RU.txt
echo.

echo Запуск Minecraft ^(gradlew runClient^)...
echo.
call gradlew.bat runClient
set ERR=%ERRORLEVEL%
if %ERR% NEQ 0 goto :fail
goto :eof

:need_setup
echo.
echo HTTPS overlay ещё не настроен или проверка не прошла.
echo.
echo Сделайте ОДИН РАЗ:
echo   1. Двойной клик по SETUP_LOCAL_OVERLAY_HTTPS.cmd
echo   2. Подтвердите UAC, дождитесь SUCCESS в админ-окне
echo   3. Нажмите Enter и закройте окна
echo   4. При сомнении: VERIFY_LOCAL_OVERLAY_HTTPS.cmd
echo   5. Снова START_ARENA.cmd
echo.
echo Cloudflare / Firebase / туннели не нужны.
echo.
pause
exit /b 1

:fail
echo.
echo Запуск завершился с ошибкой. Окно останется открытым.
pause
exit /b 1

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

echo.
echo Overlay — бесплатно, без домена и Cloudflare:
echo   http://127.0.0.1:8766/overlay/tiktok
echo Скопировать URL: tools\overlay-setup\CopyLocalOverlayUrl.cmd
echo.

echo Запуск Minecraft ^(gradlew runClient^)...
echo.
call gradlew.bat runClient
set ERR=%ERRORLEVEL%
if %ERR% NEQ 0 goto :fail
goto :eof

:fail
echo.
echo Запуск завершился с ошибкой. Окно останется открытым.
pause
exit /b 1

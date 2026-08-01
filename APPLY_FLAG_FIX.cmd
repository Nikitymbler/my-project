@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================
echo   Обновление флагов бойцов + США
echo ============================================
echo.
echo Закрой Minecraft полностью, потом жми любую клавишу...
pause >nul

echo.
echo git fetch + hard reset на origin/main ...
git fetch origin
if errorlevel 1 goto :fail
git reset --hard origin/main
if errorlevel 1 goto :fail
git clean -fd
if errorlevel 1 goto :fail

echo.
echo git log -1:
git log -1 --oneline

echo.
echo Сборка...
call gradlew.bat build
if errorlevel 1 goto :fail

echo.
echo BUILD OK.
echo 1. Открой Minecraft заново
echo 2. Зайди в мир
echo 3. F3+T в игре (перезагрузка текстур)
echo.
echo Ожидай: у бойца синий угол СЛЕВА как у базы, у США видны звёзды.
echo.
pause
exit /b 0

:fail
echo.
echo FAIL. Пришли скрин этого окна.
pause
exit /b 1

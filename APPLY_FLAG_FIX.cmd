@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

echo ============================================
echo   Обновление флагов бойцов + США (локально)
echo ============================================
echo.
echo Этот скрипт НЕ делает git pull/reset.
echo Собирает мод из текущего исходника на диске.
echo.
echo Закрой Minecraft полностью, потом жми любую клавишу...
pause >nul

echo.
echo Сборка gradlew.bat build ...
call gradlew.bat build
if errorlevel 1 goto :fail

set "JAR=%~dp0build\libs\arena_of_nations-1.0.0.jar"
if not exist "%JAR%" (
  echo FAIL: не найден %JAR%
  goto :fail
)

echo.
echo JAR готов: %JAR%

if exist "%~dp0run\mods" (
  echo Копирую в run\mods ...
  del /q "%~dp0run\mods\arena_of_nations*.jar" 2>nul
  copy /y "%JAR%" "%~dp0run\mods\arena_of_nations-1.0.0.jar" >nul
)

echo.
echo BUILD OK. START_ARENA.cmd = runClient (сборка достаточна).
echo 1. Открой Minecraft заново ^(START_ARENA.cmd^)
echo 2. Зайди в мир
echo 3. F3+T в игре ^(перезагрузка текстур^)
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

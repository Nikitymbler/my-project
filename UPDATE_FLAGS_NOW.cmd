@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

echo ============================================
echo   ФЛАГИ: скачать фикс + собрать (без git)
echo ============================================
echo.
echo Закрой Minecraft полностью.
pause

set "BASE=https://raw.githubusercontent.com/Nikitymbler/my-project/main"
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

call :patch_dir "%ROOT%"
if errorlevel 1 goto :fail

if exist "C:\Users\pavel\Desktop\ArenaOfNations\gradlew.bat" (
  echo.
  echo Найден второй проект: ArenaOfNations — патчу и его...
  call :patch_dir "C:\Users\pavel\Desktop\ArenaOfNations"
  if errorlevel 1 goto :fail
)

if exist "C:\Users\pavel\Desktop\my-project\gradlew.bat" (
  if /I not "%ROOT%"=="C:\Users\pavel\Desktop\my-project" (
    echo.
    echo Патчу C:\Users\pavel\Desktop\my-project ...
    call :patch_dir "C:\Users\pavel\Desktop\my-project"
    if errorlevel 1 goto :fail
  )
)

echo.
echo ============================================
echo   ГОТОВО. Запусти Minecraft через START_ARENA.cmd
echo   из той папки, откуда обычно играешь, зайди в мир, F3+T
echo ============================================
pause
exit /b 0

:fail
echo FAIL
pause
exit /b 1

:patch_dir
set "D=%~1"
echo.
echo --- Папка: %D%
if not exist "%D%\gradlew.bat" (
  echo skip: нет gradlew.bat
  exit /b 0
)

mkdir "%D%\src\client\java\com\nikita\arenaofnations\client" 2>nul
mkdir "%D%\src\main\resources\assets\arena_of_nations\overlay\flags" 2>nul
mkdir "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags" 2>nul
mkdir "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags_hd" 2>nul
mkdir "%D%\src\main\resources\assets\arena_of_nations\overlay\tiktok\flags" 2>nul

call :dl "%BASE%/src/client/java/com/nikita/arenaofnations/client/ArenaFighterOverheadRenderer.java" "%D%\src\client\java\com\nikita\arenaofnations\client\ArenaFighterOverheadRenderer.java"
if errorlevel 1 exit /b 1
call :dl "%BASE%/src/client/java/com/nikita/arenaofnations/client/ArenaFighterFlagVisuals.java" "%D%\src\client\java\com\nikita\arenaofnations\client\ArenaFighterFlagVisuals.java"
if errorlevel 1 exit /b 1
call :dl "%BASE%/src/main/resources/assets/arena_of_nations/overlay/flags/us.svg" "%D%\src\main\resources\assets\arena_of_nations\overlay\flags\us.svg"
if errorlevel 1 exit /b 1
call :dl "%BASE%/src/main/resources/assets/arena_of_nations/textures/gui/flags/us.png" "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png"
if errorlevel 1 exit /b 1
call :dl "%BASE%/src/main/resources/assets/arena_of_nations/textures/gui/flags_hd/us.png" "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags_hd\us.png"
if errorlevel 1 exit /b 1
call :dl "%BASE%/src/main/resources/assets/arena_of_nations/overlay/tiktok/flags/us.png" "%D%\src\main\resources\assets\arena_of_nations\overlay\tiktok\flags\us.png"
if errorlevel 1 exit /b 1

echo blur=false для США...
> "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" echo {
>> "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" echo   "texture": {
>> "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" echo     "blur": false,
>> "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" echo     "clamp": true
>> "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" echo   }
>> "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" echo }
copy /y "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags\us.png.mcmeta" "%D%\src\main\resources\assets\arena_of_nations\textures\gui\flags_hd\us.png.mcmeta" >nul

echo Собираю...
pushd "%D%"
call gradlew.bat build --quiet
set ERR=!ERRORLEVEL!
popd
if not "!ERR!"=="0" (
  echo BUILD FAIL в %D%
  exit /b 1
)
echo BUILD OK: %D%
exit /b 0

:dl
echo   get %~nx2
curl.exe -fsSL "%~1" -o "%~2"
if errorlevel 1 (
  echo DOWNLOAD FAIL: %~1
  exit /b 1
)
exit /b 0

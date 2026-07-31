@echo off
REM Apply S2E gift-400 fix branch, rebuild mod, remind to rejoin world.
cd /d "%~dp0"
echo === Fetch + checkout fix branch ===
git fetch origin cursor/s2e-gift-400-fix-ce83
if errorlevel 1 (
  echo FAIL: git fetch
  pause
  exit /b 1
)
git checkout cursor/s2e-gift-400-fix-ce83
if errorlevel 1 (
  echo FAIL: git checkout
  pause
  exit /b 1
)
git pull origin cursor/s2e-gift-400-fix-ce83
echo === Build ===
call gradlew.bat build
if errorlevel 1 (
  echo FAIL: build
  pause
  exit /b 1
)
echo.
echo BUILD OK. Now FULLY leave the Minecraft world and join again.
echo Then in StreamToEarn Gift Play use coins "1" for offline test.
pause

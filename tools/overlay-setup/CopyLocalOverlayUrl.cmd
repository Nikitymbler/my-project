@echo off
setlocal EnableExtensions
set "URL=https://localhost:8766/overlay/tiktok"
echo ============================================
echo   Arena Overlay — TikTok / OBS Browser Source
echo ============================================
echo.
echo Постоянный HTTPS URL:
echo.
echo   %URL%
echo.
echo Legacy alias:
echo   https://arena-overlay.test:8766/overlay/tiktok
echo.
echo Один раз: SETUP_LOCAL_OVERLAY_HTTPS.cmd
echo Размер источника: 1080 x 1920
echo.
powershell -NoProfile -Command "Set-Clipboard -Value '%URL%'"
if errorlevel 1 (
  echo [WARN] Не удалось скопировать в буфер — скопируйте URL вручную.
) else (
  echo URL скопирован в буфер обмена.
)
echo.
pause

@echo off
setlocal EnableExtensions
set "URL=http://127.0.0.1:8766/overlay/tiktok"
echo ============================================
echo   Arena Overlay — OBS / TikTok Browser Source
echo ============================================
echo.
echo 1. Сначала запустите Minecraft с модом.
echo 2. В OBS: Источники -^> Браузер
echo 3. URL:
echo.
echo   %URL%
echo.
echo 4. Ширина 1080   Высота 1920
echo 5. FPS 30
echo 6. Галочка "Завершать при отсутствии" — ВЫКЛ
echo 7. ПКМ по источнику -^> Обновить
echo.
echo Если пусто: откройте тот же URL в Chrome.
echo Если в Chrome есть, а в OBS нет — перезапустите OBS
echo НЕ от имени администратора (если Minecraft обычный).
echo.
powershell -NoProfile -Command "Set-Clipboard -Value '%URL%'"
if errorlevel 1 (
  echo [WARN] Не удалось скопировать в буфер — скопируйте URL вручную.
) else (
  echo URL скопирован в буфер обмена.
)
echo.
pause

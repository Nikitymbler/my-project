@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Tank Rush LIVE - StreamToEarn bridge v2 (8080)

echo ================================================================
echo Tank Rush LIVE - StreamToEarn v2 (server-side binding)
echo ================================================================
echo Status: http://127.0.0.1:8080/
echo Health: http://127.0.0.1:8080/health
echo.
echo CHAT (GET):
echo http://127.0.0.1:8080/chat?viewerId={uniqueid}^&viewerName={nickname}^&message={comment}^&eventId={eventid}
echo.
echo GIFT (GET):
echo http://127.0.0.1:8080/gift?viewerId={uniqueid}^&viewerName={nickname}^&coins={coins}^&giftcount={giftcount}^&giftName={giftname}^&eventId={eventid}
echo ================================================================
echo.

if not exist "ste_server.py" (
    echo ERROR: ste_server.py not found.
    pause
    exit /b 1
)

where py >nul 2>nul
if not errorlevel 1 (
    start "Tank Rush StreamToEarn Bridge" cmd /k "cd /d ""%~dp0"" ^& py -3 -u ste_server.py"
    timeout /t 2 /nobreak >nul
    start "" "http://127.0.0.1:8080/game"
    exit /b 0
)

where python >nul 2>nul
if not errorlevel 1 (
    start "Tank Rush StreamToEarn Bridge" cmd /k "cd /d ""%~dp0"" ^& python -u ste_server.py"
    timeout /t 2 /nobreak >nul
    start "" "http://127.0.0.1:8080/game"
    exit /b 0
)

echo ERROR: Python 3 is not installed or not added to PATH.
pause
exit /b 1

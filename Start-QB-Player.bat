@echo off
setlocal EnableExtensions
title QB Player by Naqeeb - Launcher
cd /d "%~dp0"

echo ====================================================
echo      Starting QB Player by Naqeeb (Ad-Free)
echo ====================================================
echo.

where python >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo Starting optimized local streaming server with Range support...
    echo (Keep this window open while playing videos)
    echo.
    python server.py
) else (
    echo Python not found in PATH, launching index.html directly in default browser...
    start "" "%~dp0index.html"
)

echo.
pause

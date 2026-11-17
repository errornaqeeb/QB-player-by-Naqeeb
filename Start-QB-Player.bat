@echo off
title QB Player by Naqeeb - Launcher
echo ====================================================
echo      Starting QB Player by Naqeeb (Ad-Free)
echo ====================================================

where python >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo Starting optimized local streaming server with Range support...
    python server.py
) else (
    echo Python not found in PATH, launching index.html directly in default browser...
    start "" "%~dp0index.html"
)
pause
